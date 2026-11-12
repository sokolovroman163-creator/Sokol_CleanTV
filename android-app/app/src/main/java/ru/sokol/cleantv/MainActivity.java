package ru.sokol.cleantv;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import org.json.*;

public final class MainActivity extends Activity {
  private static final int INK = 0xFF15223A, MUTED = 0xFF52627C, BLUE = 0xFF0755DC;
  private SokolApp app;
  private LinearLayout page;
  private TextView progress;
  private ProgressBar spinner;
  private EditText address;
  private int tab = 0;
  private boolean home = false, dpi = false, showProtected = false;
  private final Set<String> selected = new TreeSet<>();
  private final List<File> wallpapers = new ArrayList<>();
  private String endpoint = "";
  private Discovery discovery;
  private final List<View> actions = new ArrayList<>();
  private final Runnable update = () -> updateProgress();

  private int d(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    app = (SokolApp) getApplication();
    endpoint = getPreferences(0).getString("endpoint", "");
    if (saved != null) {
      tab = saved.getInt("tab");
      home = saved.getBoolean("home");
      dpi = saved.getBoolean("dpi");
      endpoint = saved.getString("endpoint", endpoint);
      selected.addAll(saved.getStringArrayList("selected"));
      for (String p : saved.getStringArrayList("walls"))
        if (new File(p).isFile()) wallpapers.add(new File(p));
    }
    app.listener = update;
    render();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    if (address != null) endpoint = address.getText().toString();
    b.putInt("tab", tab);
    b.putBoolean("home", home);
    b.putBoolean("dpi", dpi);
    b.putString("endpoint", endpoint);
    b.putStringArrayList("selected", new ArrayList<>(selected));
    ArrayList<String> files = new ArrayList<>();
    for (File f : wallpapers) files.add(f.getPath());
    b.putStringArrayList("walls", files);
  }

  @Override
  protected void onDestroy() {
    if (app.listener == update) app.listener = null;
    if (discovery != null) discovery.stop();
    super.onDestroy();
  }

  private TextView text(String s, float size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setTypeface(
        Typeface.create(
            bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    t.setLineSpacing(d(3), 1);
    return t;
  }

  private GradientDrawable shape(int color, float radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(d(radius));
    return g;
  }

  private GradientDrawable glass() {
    GradientDrawable g =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xEFFFFFFF, 0xCFEAF0FF});
    g.setCornerRadius(d(22));
    g.setStroke(d(1), 0xFFFFFFFF);
    return g;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private void space(LinearLayout parent, int height) {
    View v = new View(this);
    parent.addView(v, new LinearLayout.LayoutParams(1, d(height)));
  }

  private void add(LinearLayout parent, View child) {
    parent.addView(child, new LinearLayout.LayoutParams(-1, -2));
  }

  private LinearLayout card() {
    LinearLayout c = column();
    c.setPadding(d(18), d(18), d(18), d(18));
    c.setBackground(glass());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = d(14);
    page.addView(c, lp);
    return c;
  }

  private TextView button(String label, boolean primary, Runnable click) {
    TextView t = text(label, 16, primary ? 0xFFFFFFFF : BLUE, true);
    t.setGravity(Gravity.CENTER);
    t.setPadding(d(14), d(13), d(14), d(13));
    t.setMinHeight(d(50));
    GradientDrawable bg =
        primary
            ? new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xFF0873DA, 0xFF2453FA, 0xFF086BE9})
            : shape(0x88FFFFFF, 15);
    bg.setCornerRadius(d(15));
    if (!primary) bg.setStroke(d(1), 0xFFACC7FA);
    t.setBackground(
        new RippleDrawable(android.content.res.ColorStateList.valueOf(0x30356DFF), bg, null));
    t.setOnClickListener(
        v -> {
          if (!app.busy) click.run();
        });
    t.setFocusable(true);
    actions.add(t);
    return t;
  }

  private void title(String title, String subtitle) {
    add(page, text(title, 29, INK, true));
    space(page, 4);
    add(page, text(subtitle, 14, MUTED, false));
    space(page, 18);
  }

  private Workshop.Device device() {
    return app.workshop == null ? null : app.workshop.device;
  }

  private void render() {
    actions.clear();
    address = null;
    FrameLayout root = new FrameLayout(this);
    root.addView(new Art(this, true), new FrameLayout.LayoutParams(-1, -1));
    LinearLayout shell = column();
    root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets.consumeSystemWindowInsets();
        });
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout header = row();
    header.setPadding(d(22), d(18), d(18), d(15));
    TextView logo = text("S", 25, 0xFFFFFFFF, true);
    logo.setGravity(Gravity.CENTER);
    GradientDrawable lg =
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {0xFF24D8F1, 0xFF1544FF});
    lg.setCornerRadius(d(13));
    logo.setBackground(lg);
    header.addView(logo, new LinearLayout.LayoutParams(d(43), d(43)));
    LinearLayout brand = column();
    brand.setPadding(d(11), 0, 0, 0);
    add(brand, text("Sokol_CleanTV", 21, INK, true));
    add(brand, text("НАСТРОЙКА ТЕЛЕВИЗОРА", 10, MUTED, true));
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    TextView help = button("?", false, this::help);
    header.addView(help, new LinearLayout.LayoutParams(d(46), d(46)));
    add(shell, header);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    page = column();
    page.setPadding(d(20), d(8), d(20), d(22));
    scroll.addView(page);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (tab == 0) devicePage();
    else if (tab == 1) setupPage();
    else backupPage();
    LinearLayout status = column();
    status.setPadding(d(22), d(8), d(22), d(8));
    progress = text("", 12, MUTED, false);
    progress.setMaxLines(3);
    spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    spinner.setIndeterminate(true);
    spinner.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
    add(status, progress);
    status.addView(spinner, new LinearLayout.LayoutParams(-1, d(3)));
    add(shell, status);
    LinearLayout nav = row();
    nav.setPadding(d(12), d(8), d(12), d(10));
    nav.setBackground(shape(0xBFFFFFFF, 0));
    String[] labels = {"Телевизор", "Настройка", "Копии"};
    String[] icons = {"tv", "layers", "history"};
    for (int i = 0; i < 3; i++) {
      final int target = i;
      LinearLayout item = column();
      item.setGravity(Gravity.CENTER);
      item.setPadding(d(5), d(8), d(5), d(8));
      if (i == tab) item.setBackground(shape(0xC9D8E7FF, 18));
      Icon icon = new Icon(this, icons[i], i == tab ? BLUE : MUTED);
      item.addView(icon, new LinearLayout.LayoutParams(d(25), d(25)));
      TextView label = text(labels[i], 12, i == tab ? BLUE : MUTED, i == tab);
      label.setGravity(Gravity.CENTER);
      add(item, label);
      item.setContentDescription(labels[i]);
      item.setFocusable(true);
      item.setOnClickListener(
          v -> {
            if (address != null) endpoint = address.getText().toString();
            tab = target;
            render();
          });
      nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }
    add(shell, nav);
    setContentView(root);
    updateProgress();
  }

  private void updateProgress() {
    if (progress == null) return;
    progress.setText(
        app.busy
            ? app.progress + "\nОставьте приложение открытым до завершения."
            : app.progress.isEmpty() ? "Выберите телевизор для настройки" : app.progress);
    spinner.setVisibility(app.busy ? View.VISIBLE : View.GONE);
    for (View v : actions) {
      v.setEnabled(!app.busy);
      v.setAlpha(app.busy ? .5f : 1f);
    }
    if (app.busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private void job(SokolApp.Job work) {
    app.run(
        work,
        message -> {
          if (!isDestroyed() && !isFinishing()) {
            render();
            message("Результат", message);
          }
        });
  }

  private void message(String title, String body) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton("Понятно", null)
        .show();
  }

  private void devicePage() {
    Workshop.Device dev = device();
    LinearLayout heading = row();
    heading.addView(text("Ваш телевизор", 27, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
    TextView chip =
        text(
            dev == null ? "Не подключён" : "Подключён", 12, dev == null ? MUTED : 0xFF12632F, true);
    chip.setPadding(d(10), d(7), d(10), d(7));
    chip.setBackground(shape(dev == null ? 0xD9FFFFFF : 0xC9CDFADE, 18));
    heading.addView(chip);
    add(page, heading);
    space(page, 16);
    LinearLayout tv = card();
    tv.setPadding(d(12), d(6), d(12), d(16));
    Art art = new Art(this, false);
    tv.addView(art, new LinearLayout.LayoutParams(-1, d(176)));
    LinearLayout info = column();
    info.setPadding(d(6), 0, d(6), 0);
    add(info, text(dev == null ? "Подключим ваш ТВ" : dev.model, 22, INK, true));
    space(info, 3);
    add(
        info,
        text(
            dev == null
                ? "Телефон и ТВ должны быть в одной сети Wi-Fi"
                : dev.brand + " · Android " + dev.android + " · " + dev.abi,
            13,
            MUTED,
            false));
    space(info, 14);
    View line = new View(this);
    line.setBackgroundColor(0xFFD3DDED);
    info.addView(line, new LinearLayout.LayoutParams(-1, d(1)));
    space(info, 12);
    add(
        info,
        text(
            dev == null
                ? "Android TV и Google TV · от Android 8.0"
                : dev.tv && dev.sdk >= 26
                    ? "Проверка пройдена · Можно перейти к настройке"
                    : "Это устройство не поддерживает настройку ТВ",
            13,
            dev == null ? MUTED : dev.tv && dev.sdk >= 26 ? 0xFF146A39 : 0xFF8E4511,
            true));
    add(tv, info);
    if (dev == null) {
      LinearLayout connect = card();
      add(connect, text("Подключение по Wi-Fi", 18, INK, true));
      space(connect, 8);
      add(
          connect,
          text(
              "Включите отладку в настройках телевизора и разрешите подключение с пульта.",
              14,
              MUTED,
              false));
      space(connect, 14);
      add(connect, text("IP-адрес телевизора и порт", 12, MUTED, true));
      address = input("192.168.1.50:5555", InputType.TYPE_CLASS_TEXT);
      address.setText(endpoint);
      add(connect, address);
      space(connect, 12);
      add(connect, button("Найти телевизор в сети", true, this::discover));
      space(connect, 10);
      add(connect, button("Подключиться по IP-адресу", false, this::connect));
      space(connect, 8);
      add(connect, button("Подключить по коду с экрана ТВ", false, this::pair));
    } else {
      add(
          page,
          button(
              "Перейти к настройке",
              true,
              () -> {
                tab = 1;
                render();
              }));
      space(page, 12);
      LinearLayout check = card();
      add(check, text("Всё под контролем", 17, INK, true));
      space(check, 8);
      add(
          check,
          text(
              "Проверены модель, система, главный экран и список приложений. Изменения запустятся"
                  + " только после вашего выбора.",
              14,
              MUTED,
              false));
      space(check, 12);
      add(
          check,
          button("Проверить после перезагрузки", false, () -> job(() -> app.workshop.verify())));
      space(check, 8);
      add(check, button("Завершить и закрыть отладку", false, this::finishDebug));
      space(check, 8);
      add(
          check,
          button(
              "Отключиться",
              false,
              () ->
                  job(
                      () -> {
                        app.client.disconnect();
                        app.workshop.device = null;
                        return "Соединение отключено. Отладку на ТВ выключите с пульта.";
                      })));
    }
    LinearLayout links = card();
    add(links, button("Как включить отладку на телевизоре", false, this::help));
    space(links, 8);
    add(links, button("Каталог телевизоров", false, this::catalog));
  }

  private EditText input(String hint, int type) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextColor(INK);
    e.setHintTextColor(0xFF6A7C98);
    e.setTextSize(16);
    e.setInputType(
        type == InputType.TYPE_CLASS_TEXT
            ? type | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            : type);
    e.setHint(hint);
    e.setPadding(d(12), d(12), d(12), d(12));
    GradientDrawable g = shape(0xCFFFFFFF, 12);
    g.setStroke(d(1), 0xFFB8CAE8);
    e.setBackground(g);
    e.setMinHeight(d(50));
    return e;
  }

  private void connect() {
    endpoint = address.getText().toString().trim();
    try {
      Rules.endpoint(endpoint);
    } catch (Exception e) {
      address.setError(e.getMessage());
      return;
    }
    getPreferences(0).edit().putString("endpoint", endpoint).apply();
    final String host = endpoint;
    selected.clear();
    job(
        () -> {
          app.log("Подключаюсь. Подтвердите запрос на телевизоре…");
          app.client.connectTv(host);
          app.workshop.inspect(host);
          return "Телевизор проверен. Можно выбрать действия во вкладке «Настройка».";
        });
  }

  private void discover() {
    if (discovery != null) discovery.stop();
    discovery = new Discovery(this);
    app.progress = "Ищу объявления отладки в вашей сети…";
    updateProgress();
    List<String> results = new ArrayList<>();
    discovery.start(
        found -> {
          results.clear();
          results.addAll(found);
        });
    new Handler(Looper.getMainLooper())
        .postDelayed(
            () -> {
              if (isDestroyed()) return;
              app.progress =
                  results.isEmpty()
                      ? "Объявлений отладки не найдено. Введите IP-адрес из настроек сети ТВ."
                      : "Найдено устройств: " + results.size();
              updateProgress();
              if (!results.isEmpty())
                new AlertDialog.Builder(this)
                    .setTitle("Выберите адрес телевизора")
                    .setItems(
                        results.toArray(new String[0]),
                        (dlg, index) -> {
                          endpoint = results.get(index);
                          if (address != null) address.setText(endpoint);
                        })
                    .setNegativeButton("Закрыть", null)
                    .show();
            },
            9200);
  }

  private void pair() {
    LinearLayout form = column();
    form.setPadding(d(24), d(10), d(24), 0);
    add(
        form,
        text(
            "На ТВ: «Беспроводная отладка → Подключить устройство по коду». Порт в этом окне"
                + " отличается от порта подключения.",
            14,
            MUTED,
            false));
    space(form, 12);
    add(form, text("IP-адрес и порт из окна с кодом", 13, INK, true));
    EditText host = input("192.168.1.50:37123", InputType.TYPE_CLASS_TEXT);
    add(form, host);
    space(form, 10);
    add(form, text("Код с экрана телевизора", 13, INK, true));
    EditText code = input("6 цифр", InputType.TYPE_CLASS_NUMBER);
    add(form, code);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Подключение по коду")
            .setView(form)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Связать", null)
            .create();
    dialog.setOnShowListener(
        x ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    v -> {
                      try {
                        Rules.endpoint(host.getText().toString());
                        if (!code.getText().toString().matches("[0-9]{6}"))
                          throw new IllegalArgumentException("Введите 6 цифр.");
                      } catch (Exception e) {
                        code.setError(e.getMessage());
                        return;
                      }
                      String h = host.getText().toString(), c = code.getText().toString();
                      dialog.dismiss();
                      job(
                          () -> {
                            app.client.pairTv(h, c);
                            return "Сопряжение готово. Теперь введите IP и порт с основного экрана"
                                + " беспроводной отладки и нажмите «Подключиться по"
                                + " IP-адресу».";
                          });
                    }));
    dialog.show();
  }

  private CheckBox choice(
      String title, String detail, boolean checked, java.util.function.Consumer<Boolean> change) {
    CheckBox c = new CheckBox(this);
    c.setText(title + "\n" + detail);
    c.setTextColor(INK);
    c.setTextSize(15);
    c.setPadding(0, d(7), 0, d(7));
    c.setMinHeight(d(60));
    c.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
    c.setChecked(checked);
    c.setOnCheckedChangeListener((b, state) -> change.accept(state));
    actions.add(c);
    return c;
  }

  private void setupPage() {
    title("Настройте под себя", "Выберите, что изменить. Перед запуском сохраним копию.");
    Workshop.Device dev = device();
    if (dev == null || !dev.tv || dev.sdk < 26) {
      LinearLayout c = card();
      add(c, text("Сначала проверим телевизор", 20, INK, true));
      space(c, 8);
      add(
          c,
          text(
              "Для настройки нужен Android TV или Google TV 8.0 и новее с разрешённой отладкой.",
              14,
              MUTED,
              false));
      space(c, 16);
      add(
          c,
          button(
              "К подключению",
              true,
              () -> {
                tab = 0;
                render();
              }));
      return;
    }
    LinearLayout main = card();
    add(main, text("Главный экран и приложения", 18, INK, true));
    add(
        main,
        choice(
            "Новый главный экран",
            "Крупные плитки, библиотека и выбор обоев",
            home,
            x -> home = x));
    add(
        main,
        choice(
            "Установить ByeByeDPI",
            "По желанию · Первый запуск подтвердите на ТВ",
            dpi,
            x -> dpi = x));
    add(
        main,
        text(
            "Google Play, службы Google и важные функции телевизора защищены. Приложения"
                + " отключаются, чтобы их можно было вернуть.",
            13,
            MUTED,
            false));
    LinearLayout wall = card();
    add(wall, text("Ваши обои", 18, INK, true));
    space(wall, 6);
    add(
        wall,
        text(
            "Добавьте фото с телефона. На ТВ фон выбирается в настройках главного экрана."
                + " Стандартный фон останется.",
            14,
            MUTED,
            false));
    space(wall, 12);
    for (File f : new ArrayList<>(wallpapers)) {
      LinearLayout item = row();
      ImageView thumb = new ImageView(this);
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(f.getPath(), o);
      o.inSampleSize = 1;
      while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 384) o.inSampleSize *= 2;
      o.inJustDecodeBounds = false;
      thumb.setImageBitmap(BitmapFactory.decodeFile(f.getPath(), o));
      thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
      item.addView(thumb, new LinearLayout.LayoutParams(d(70), d(44)));
      TextView name = text("Обои " + (wallpapers.indexOf(f) + 1), 14, INK, false);
      name.setPadding(d(12), 0, 0, 0);
      item.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      TextView remove =
          button(
              "Убрать",
              false,
              () -> {
                wallpapers.remove(f);
                render();
              });
      item.addView(remove);
      add(wall, item);
      space(wall, 8);
    }
    add(
        wall,
        button(
            "Выбрать обои с телефона",
            false,
            () -> {
              Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
              i.setType("image/*");
              i.addCategory(Intent.CATEGORY_OPENABLE);
              i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
              startActivityForResult(i, 10);
            }));
    LinearLayout apps = card();
    add(apps, text("Какие приложения отключить", 18, INK, true));
    space(apps, 6);
    add(
        apps,
        text(
            "Отметьте только ненужные. Без галочки приложение останется на телевизоре.",
            14,
            MUTED,
            false));
    for (Workshop.App a : dev.apps)
      if (a.blocked.isEmpty()) {
        if (a.state == 3) {
          space(apps, 10);
          add(apps, text(a.name + " · Уже отключено", 14, MUTED, false));
        } else
          add(
              apps,
              choice(
                  a.name,
                  a.packageName,
                  selected.contains(a.packageName),
                  checked -> {
                    if (checked) selected.add(a.packageName);
                    else selected.remove(a.packageName);
                  }));
      }
    TextView keep =
        button(
            showProtected ? "Скрыть защищённые приложения" : "Показать защищённые приложения",
            false,
            () -> {
              showProtected = !showProtected;
              render();
            });
    add(apps, keep);
    if (showProtected)
      for (Workshop.App a : dev.apps)
        if (!a.blocked.isEmpty()) {
          space(apps, 12);
          add(apps, text(a.name, 14, INK, true));
          add(apps, text(a.blocked, 12, MUTED, false));
        }
    add(page, button("Проверить и применить", true, this::confirmApply));
    space(page, 10);
    add(
        page,
        text(
            "Это настройка Android TV. Разделы прошивки и личные данные приложений не"
                + " перезаписываются.",
            12,
            MUTED,
            false));
  }

  private void confirmApply() {
    Set<String> names = new TreeSet<>(selected);
    List<File> walls = new ArrayList<>(wallpapers);
    boolean newHome = home, bye = dpi;
    if (names.isEmpty() && !newHome && !bye && walls.isEmpty()) {
      message(
          "Выберите действия",
          "Отметьте ненужные приложения или выберите главный экран, ByeByeDPI либо обои.");
      return;
    }
    StringBuilder s =
        new StringBuilder("Перед изменениями автоматически сохранится резервная копия.\n\n");
    if (newHome) s.append("• Новый главный экран\n");
    if (bye) s.append("• ByeByeDPI\n");
    if (!walls.isEmpty()) s.append("• Добавить обои: ").append(walls.size()).append("\n");
    if (!names.isEmpty()) {
      s.append("\nОтключить приложения:\n");
      for (String p : names) s.append("• ").append(Rules.NAMES.getOrDefault(p, p)).append("\n");
    }
    new AlertDialog.Builder(this)
        .setTitle("Применить выбранные изменения?")
        .setMessage(s.toString())
        .setNegativeButton("Назад", null)
        .setPositiveButton(
            "Применить",
            (a, b) ->
                job(
                    () -> {
                      String result = app.workshop.apply(names, newHome, bye, walls);
                      selected.clear();
                      home = false;
                      dpi = false;
                      try {
                        app.workshop.inspect(app.workshop.endpoint);
                      } catch (Exception refresh) {
                        app.log(
                            "Настройка завершена. Подключитесь заново для обновления списка"
                                + " приложений.");
                      }
                      return result;
                    }))
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 10 && result == RESULT_OK && data != null) {
      List<Uri> uris = new ArrayList<>();
      if (data.getClipData() != null)
        for (int n = 0; n < data.getClipData().getItemCount(); n++)
          uris.add(data.getClipData().getItemAt(n).getUri());
      else if (data.getData() != null) uris.add(data.getData());
      job(
          () -> {
            File dir = new File(getNoBackupFilesDir(), "wallpapers");
            if (!dir.isDirectory() && !dir.mkdirs())
              throw new IOException("Не удалось сохранить обои.");
            if (wallpapers.size() + uris.size() > 12)
              throw new IOException("Добавьте не больше 12 обоев за один раз.");
            for (Uri uri : uris) {
              byte[] bytes;
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Фото недоступно.");
                bytes = IO.bytes(in, 20 * 1024 * 1024);
              }
              if (bytes.length > 20 * 1024 * 1024)
                throw new IOException("Размер фото должен быть до 20 МБ.");
              BitmapFactory.Options o = new BitmapFactory.Options();
              o.inJustDecodeBounds = true;
              BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
              if (o.outWidth < 1
                  || o.outHeight < 1
                  || (long) o.outWidth * o.outHeight > 80_000_000L
                  || !Set.of("image/jpeg", "image/png", "image/webp").contains(o.outMimeType))
                throw new IOException("Выберите JPG, PNG или WebP до 80 мегапикселей.");
              String ext =
                  o.outMimeType.equals("image/png")
                      ? ".png"
                      : o.outMimeType.equals("image/webp") ? ".webp" : ".jpg";
              File f = new File(dir, Rules.hash(bytes) + ext);
              Workshop.atomic(f, bytes);
              if (!wallpapers.contains(f)) wallpapers.add(f);
            }
            return "Обои добавлены. Нажмите «Проверить и применить», чтобы отправить их на ТВ.";
          });
    }
  }

  private void backupPage() {
    title("Резервные копии", "Верните главный экран и состояния приложений.");
    LinearLayout info = card();
    add(info, text("Что сохраняется", 18, INK, true));
    space(info, 7);
    add(
        info,
        text(
            "Список приложений, их состояния, главный экран и его оформление. Личные данные,"
                + " аккаунты и сама прошивка в копию не входят. Копии хранятся на этом телефоне и"
                + " удалятся вместе с приложением.",
            14,
            MUTED,
            false));
    space(info, 12);
    add(
        info,
        button(
            "Сохранить копию сейчас",
            true,
            () ->
                job(
                    () -> {
                      if (device() == null) throw new IOException("Сначала подключите телевизор.");
                      Set<String> packages = new TreeSet<>();
                      for (Workshop.App a : device().apps)
                        if (a.blocked.isEmpty()) packages.add(a.packageName);
                      for (String p : Rules.STOCK)
                        if (device().packages.contains(p)) packages.add(p);
                      if (device().packages.contains(Rules.HOME)) packages.add(Rules.HOME);
                      app.workshop.backup(packages);
                      return "Резервная копия сохранена на телефоне.";
                    })));
    File root = new File(getNoBackupFilesDir(), "backups");
    File[] dirs = root.listFiles(File::isDirectory);
    if (dirs == null || dirs.length == 0)
      add(page, text("Здесь появятся ваши сохранённые копии.", 14, MUTED, false));
    else {
      Arrays.sort(dirs, Comparator.comparing(File::getName).reversed());
      for (File dir : dirs)
        try {
          JSONObject m = Workshop.read(new File(dir, "backup.json"));
          LinearLayout c = card();
          add(c, text(m.getString("model"), 18, INK, true));
          space(c, 5);
          add(
              c,
              text(
                  DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                      .format(new Date(m.getLong("created"))),
                  13,
                  MUTED,
                  false));
          String status = "Копия не завершена";
          if (m.optBoolean("complete")) {
            JSONObject j = Workshop.read(new File(dir, "journal.json"));
            status =
                switch (j.optString("status")) {
                  case "applied" -> "Настройка завершена";
                  case "restored" -> "Уже восстановлено";
                  case "running", "failed", "restoring" -> "Изменения могли примениться частично";
                  default -> "Копия сохранена";
                };
          }
          space(c, 6);
          add(c, text(status, 13, MUTED, true));
          space(c, 12);
          add(
              c,
              button(
                  "Восстановить из этой копии",
                  false,
                  () ->
                      new AlertDialog.Builder(this)
                          .setTitle("Вернуть прежние настройки?")
                          .setMessage(
                              "Телевизор будет сверен с копией. Вернутся главный экран и состояния"
                                  + " записанных приложений. Новые APK останутся установленными, но"
                                  + " будут отключены. Аккаунты и данные приложений не"
                                  + " восстанавливаются.")
                          .setNegativeButton("Отмена", null)
                          .setPositiveButton(
                              "Восстановить",
                              (x, y) ->
                                  job(
                                      () -> {
                                        if (device() == null)
                                          throw new IOException("Сначала подключите телевизор.");
                                        return app.workshop.restore(dir);
                                      }))
                          .show()));
        } catch (Exception ignored) {
          add(page, text("Незавершённая копия: " + dir.getName(), 13, MUTED, false));
        }
    }
    space(page, 12);
    add(page, button("Открыть журнал работы", false, this::showLog));
  }

  private void finishDebug() {
    new AlertDialog.Builder(this)
        .setTitle("Завершить настройку?")
        .setMessage(
            "После отключения отладки подключение прервётся. Если порт останется открыт, выключите"
                + " беспроводную отладку на телевизоре с пульта и отзовите разрешения.")
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "Завершить",
            (a, b) ->
                job(
                    () -> {
                      String endpoint = app.workshop.endpoint;
                      try {
                        app.workshop.shell("settings put global adb_enabled 0");
                      } catch (Exception ignored) {
                      }
                      app.client.disconnect();
                      app.workshop.device = null;
                      String[] e = Rules.endpoint(endpoint);
                      try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(
                            new java.net.InetSocketAddress(e[0], Integer.parseInt(e[1])), 2500);
                        return "Порт отладки ещё открыт. Выключите беспроводную отладку на ТВ и"
                            + " отзовите разрешения.";
                      } catch (java.net.ConnectException refused) {
                        if (refused.getMessage() != null
                            && refused.getMessage().contains("ECONNREFUSED"))
                          return "Порт отладки закрыт. Отзовите разрешения в настройках ТВ.";
                        return "Связь отключена. Закрытие порта не подтверждено — проверьте отладку"
                            + " на ТВ.";
                      } catch (IOException unavailable) {
                        return "ТВ не ответил. Закрытие порта не подтверждено. Выключите отладку и"
                            + " отзовите разрешения на ТВ.";
                      }
                    }))
        .show();
  }

  private void help() {
    new AlertDialog.Builder(this)
        .setTitle("Как подключить телевизор")
        .setMessage(
            "1. Подключите телефон и ТВ к одной домашней сети Wi-Fi. Гостевая сеть может"
                + " блокировать связь.\n\n"
                + "2. На ТВ откройте «Настройки → Об устройстве». Нажмите «Сборка» 7 раз.\n\n"
                + "3. В разделе «Для разработчиков» включите USB-отладку или беспроводную отладку,"
                + " если она есть. Для подключения по коду используйте отдельную кнопку в"
                + " приложении.\n\n"
                + "4. Введите IP-адрес из настроек сети телевизора. Для обычной сетевой отладки"
                + " порт обычно 5555. Если он недоступен, одной USB-отладки недостаточно — смотрите"
                + " меню производителя.\n\n"
                + "5. Разрешите подключение на экране ТВ с пульта.\n\n"
                + "После работы выключите отладку и отзовите разрешения.\n\n"
                + "Sokol_CleanTV 0.1.1 · Android-версия")
        .setPositiveButton("Понятно", null)
        .setNeutralButton("Журнал", (a, b) -> showLog())
        .show();
  }

  private void showLog() {
    String logs;
    synchronized (app) {
      logs = String.join("\n\n", app.log);
    }
    message("Журнал работы", logs.isEmpty() ? "Действий пока не было." : logs);
  }

  private void catalog() {
    try {
      JSONArray list;
      try (InputStream in = getAssets().open("catalog.json")) {
        list =
            new JSONArray(
                new String(IO.bytes(in, 2 * 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
      }
      Map<String, Integer> counts = new TreeMap<>();
      for (int i = 0; i < list.length(); i++) {
        String brand = list.getJSONObject(i).getString("brand");
        counts.put(brand, counts.getOrDefault(brand, 0) + 1);
      }
      String[] brands = counts.keySet().toArray(new String[0]);
      String[] labels =
          Arrays.stream(brands).map(b -> b + " · отчётов: " + counts.get(b)).toArray(String[]::new);
      new AlertDialog.Builder(this)
          .setTitle("Каталог · " + list.length() + " отчётов")
          .setMessage(
              "Список брендов — справочник. Возможности вашего телевизора приложение проверяет"
                  + " отдельно.")
          .setPositiveButton(
              "Открыть бренды",
              (a, b) ->
                  new AlertDialog.Builder(this)
                      .setTitle("Выберите бренд")
                      .setItems(
                          labels,
                          (x, index) -> {
                            StringBuilder text = new StringBuilder();
                            for (int i = 0; i < list.length(); i++)
                              try {
                                JSONObject item = list.getJSONObject(i);
                                if (item.getString("brand").equals(brands[index]))
                                  text.append(item.getString("tv"))
                                      .append("\n")
                                      .append(item.optString("os"))
                                      .append("\n\n");
                              } catch (Exception ignored) {
                              }
                            message(brands[index], text.toString());
                          })
                      .setNegativeButton("Закрыть", null)
                      .show())
          .setNegativeButton("Закрыть", null)
          .show();
    } catch (Exception e) {
      message("Каталог недоступен", e.getMessage());
    }
  }

  private final class Icon extends View {
    final String kind;
    final Paint p = new Paint(3);

    Icon(Context c, String k, int color) {
      super(c);
      kind = k;
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.8f);
      p.setStrokeCap(Paint.Cap.ROUND);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      float s = getWidth() / 24f;
      canvas.save();
      canvas.scale(s, s);
      if (kind.equals("tv")) {
        canvas.drawRoundRect(2, 3, 22, 17, 2, 2, p);
        canvas.drawLine(12, 18, 12, 21, p);
        canvas.drawLine(7, 22, 17, 22, p);
      } else if (kind.equals("layers")) {
        Path path = new Path();
        path.moveTo(12, 2);
        path.lineTo(22, 8);
        path.lineTo(12, 14);
        path.lineTo(2, 8);
        path.close();
        canvas.drawPath(path, p);
        for (int y : new int[] {12, 16}) {
          canvas.drawLine(2, y, 12, y + 6, p);
          canvas.drawLine(12, y + 6, 22, y, p);
        }
      } else {
        canvas.drawArc(3, 3, 22, 22, -130, 300, false, p);
        canvas.drawLine(2, 3, 2, 9, p);
        canvas.drawLine(2, 9, 8, 9, p);
        canvas.drawLine(12, 7, 12, 13, p);
        canvas.drawLine(12, 13, 16, 16, p);
      }
      canvas.restore();
    }
  }
}
