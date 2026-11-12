package ru.sokol.cleantv;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import org.json.*;

/** All device mutations run on SokolApp's single worker, behind identity and backup checks. */
final class Workshop {
  static final String RESOLVE =
      "cmd package resolve-activity --brief -a android.intent.action.MAIN -c"
          + " android.intent.category.HOME";
  static final String THEME = "/sdcard/Android/data/" + Rules.HOME + "/files/";
  final Transport adb;
  final Context context;
  final File backups;
  final Consumer<String> log;
  Device device;
  File latest;
  String endpoint = "";

  static final class App {
    String name, packageName, blocked;
    int state;

    App(String p, int s, String b) {
      packageName = p;
      name = Rules.NAMES.getOrDefault(p, p);
      state = s;
      blocked = b;
    }
  }

  static final class Device {
    String identity, model, brand, fingerprint, home, abi, android;
    int sdk;
    boolean tv;
    Set<String> packages;
    final List<App> apps = new ArrayList<>();
  }

  Workshop(Context c, Transport t, Consumer<String> l) {
    this(c, new File(c.getNoBackupFilesDir(), "backups"), t, l);
  }

  Workshop(Context c, File root, Transport t, Consumer<String> l) {
    context = c;
    adb = t;
    log = l;
    backups = root;
  }

  String shell(String command) throws Exception {
    return adb.shell(command);
  }

  Device inspect(String address) throws Exception {
    device = null;
    endpoint = address;
    log.accept("Читаю модель и возможности телевизора…");
    if (!shell("am get-current-user").trim().equals("0"))
      throw new IOException(
          "Выберите основной профиль телевизора. Настройка других пользователей пока не"
              + " поддерживается.");
    Map<String, String> p = Rules.props(shell("getprop"));
    Device d = new Device();
    d.sdk = Integer.parseInt(p.getOrDefault("ro.build.version.sdk", "0"));
    if (d.sdk < 1) throw new IOException("Устройство не определено как Android.");
    d.identity = Rules.identity(p, shell("settings get secure android_id"));
    d.fingerprint = p.getOrDefault("ro.build.fingerprint", "");
    if (d.fingerprint.isEmpty()) throw new IOException("Телевизор не сообщил версию прошивки.");
    d.brand = p.getOrDefault("ro.product.manufacturer", "ТВ");
    d.model = p.getOrDefault("ro.product.model", "Неизвестная модель");
    d.abi = p.getOrDefault("ro.product.cpu.abi", "");
    d.android = p.getOrDefault("ro.build.version.release", "");
    String features = shell("pm list features");
    d.tv =
        features.contains("android.software.leanback")
            || features.contains("android.hardware.type.television");
    d.packages = Rules.packages(shell("pm list packages --user 0"));
    if (d.packages.isEmpty()) throw new IOException("Не удалось получить список приложений.");
    d.home = Rules.component(shell(RESOLVE));
    for (String name : d.packages) {
      String block = Rules.protect(name);
      int state = -1;
      if (block.isEmpty())
        try {
          String dump = shell("dumpsys package " + name);
          state = Rules.state(dump);
          Rules.safeDump(dump);
        } catch (Exception e) {
          block = "Роль приложения не подтверждена или связана с системой";
        }
      d.apps.add(new App(name, state, block));
    }
    d.apps.sort(
        Comparator.comparing((App a) -> !a.blocked.isEmpty())
            .thenComparing(a -> a.name.toLowerCase(Locale.ROOT)));
    device = d;
    latest = null;
    File[] saved = backups.listFiles(File::isDirectory);
    if (saved != null) {
      Arrays.sort(saved, Comparator.comparing(File::getName).reversed());
      for (File folder : saved)
        try {
          JSONObject m = read(new File(folder, "backup.json"));
          if (m.optBoolean("complete")
              && d.identity.equals(m.optString("identity"))
              && d.fingerprint.equals(m.optString("fingerprint"))) {
            latest = folder;
            break;
          }
        } catch (Exception ignored) {
        }
    }
    return d;
  }

  void identity() throws Exception {
    identity(device.identity, device.fingerprint);
  }

  void identity(String id, String fingerprint) throws Exception {
    if (!shell("am get-current-user").trim().equals("0"))
      throw new IOException(
          "Выберите основной профиль телевизора. Настройка других пользователей пока не"
              + " поддерживается.");
    Map<String, String> p = Rules.props(shell("getprop"));
    String actual = Rules.identity(p, shell("settings get secure android_id"));
    if (!id.equals(actual) || !fingerprint.equals(p.get("ro.build.fingerprint")))
      throw new IOException(
          "Подключён другой телевизор или изменилась прошивка. Выполните проверку заново.");
  }

  static void atomic(File file, byte[] bytes) throws Exception {
    File parent = file.getParentFile();
    if (!parent.isDirectory() && !parent.mkdirs())
      throw new IOException("Не удалось создать папку копии.");
    File temp = new File(parent, file.getName() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(bytes);
      out.getFD().sync();
    }
    Files.move(
        temp.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE);
  }

  static void json(File file, JSONObject data) throws Exception {
    atomic(file, data.toString(2).getBytes(StandardCharsets.UTF_8));
  }

  static JSONObject read(File file) throws Exception {
    return new JSONObject(IO.text(file));
  }

  static void put(File dir, String path, byte[] bytes, JSONObject files) throws Exception {
    File f = new File(dir, path);
    atomic(f, bytes);
    files.put(path, Rules.hash(bytes));
  }

  static boolean themePath(String name) {
    return name.equals("config.json")
        || name.equals("theme_revision")
        || name.equals("wallpaper.jpg")
        || name.equals("wallpapers/captions.txt")
        || name.matches("wallpapers/[A-Za-z0-9_-][A-Za-z0-9_.-]*\\.(?i:jpg|jpeg|png|webp)");
  }

  File backup(Set<String> tracked) throws Exception {
    if (device == null) throw new IOException("Сначала подключите и проверьте телевизор.");
    identity();
    File dir =
        new File(
            backups,
            System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6));
    JSONObject m =
        new JSONObject()
            .put("schema", 1)
            .put("complete", false)
            .put("created", System.currentTimeMillis())
            .put("identity", device.identity)
            .put("fingerprint", device.fingerprint)
            .put("model", device.model)
            .put("home", Rules.component(shell(RESOLVE)))
            .put("states", new JSONObject())
            .put("files", new JSONObject());
    json(new File(dir, "backup.json"), m);
    log.accept("Сохраняю резервную копию до изменений…");
    JSONObject files = m.getJSONObject("files"), states = m.getJSONObject("states");
    String[][] queries = {
      {"packages.txt", "pm list packages -f --user 0"},
      {"disabled.txt", "pm list packages -d --user 0"},
      {"getprop.txt", "getprop"},
      {"system.txt", "settings list system"},
      {"secure.txt", "settings list secure"},
      {"global.txt", "settings list global"}
    };
    for (String[] q : queries) {
      String output = shell(q[1]);
      if (output.isEmpty() && !q[0].equals("disabled.txt"))
        throw new IOException("Не удалось сохранить " + q[0]);
      put(dir, q[0], output.getBytes(StandardCharsets.UTF_8), files);
    }
    Set<String> installed = Rules.packages(shell("pm list packages --user 0"));
    Set<String> recorded = new TreeSet<>(tracked);
    recorded.add(m.getString("home").split("/")[0]);
    for (String name : recorded) {
      if (!Rules.pkg(name)) throw new IOException("Недопустимое имя приложения.");
      states.put(
          name,
          installed.contains(name)
              ? Rules.state(shell("dumpsys package " + name))
              : JSONObject.NULL);
    }
    if (installed.contains(Rules.HOME)) {
      String snapshot = snapshot();
      put(dir, "preferences.b64", snapshot.getBytes(StandardCharsets.US_ASCII), files);
      String listing =
          shell(
              "if [ -d "
                  + THEME
                  + " ]; then find "
                  + THEME
                  + " -type f; else echo SOKOL_NO_THEME; fi");
      for (String line : listing.split("\\r?\\n"))
        if (!line.equals("SOKOL_NO_THEME") && !line.trim().isEmpty()) {
          if (!line.startsWith(THEME))
            throw new IOException("Не удалось проверить файлы оформления.");
          String path = line.substring(THEME.length());
          if (themePath(path)) put(dir, "theme/" + path, adb.pull(line, 20 * 1024 * 1024), files);
        }
    }
    identity();
    m.put("complete", true);
    json(new File(dir, "backup.json"), m);
    atomic(
        new File(dir, "backup.sha256"),
        Rules.hash(Files.readAllBytes(new File(dir, "backup.json").toPath()))
            .getBytes(StandardCharsets.US_ASCII));
    json(
        new File(dir, "journal.json"),
        new JSONObject().put("status", "saved").put("actions", new JSONArray()));
    latest = dir;
    return dir;
  }

  String snapshot() throws Exception {
    Matcher match =
        Pattern.compile("snapshot=([A-Za-z0-9+/=]+)")
            .matcher(
                shell(
                    "content call --uri content://"
                        + Rules.HOME
                        + ".theme --method sokol_snapshot"));
    if (!match.find())
      throw new IOException(
          "Этот вариант главного экрана не умеет сохранять свои настройки. Изменения остановлены."
              + " Нужна версия Sokol CleanTV Home с резервным копированием.");
    new JSONObject(new String(Base64.getDecoder().decode(match.group(1)), StandardCharsets.UTF_8));
    return match.group(1);
  }

  JSONObject validate(File dir) throws Exception {
    File root = dir.getCanonicalFile();
    if (!root.getParentFile().equals(backups.getCanonicalFile()))
      throw new IOException("Неподдерживаемая папка копии.");
    File meta = new File(root, "backup.json");
    String expected = IO.text(new File(root, "backup.sha256")).trim();
    if (!Rules.hash(Files.readAllBytes(meta.toPath())).equals(expected))
      throw new IOException("Описание копии повреждено.");
    JSONObject m = read(meta);
    if (m.getInt("schema") != 1 || !m.getBoolean("complete"))
      throw new IOException("Копия не завершена.");
    JSONObject files = m.getJSONObject("files");
    for (String path : IO.keys(files)) {
      File f = new File(root, path).getCanonicalFile();
      if (!f.toPath().startsWith(root.toPath())
          || !Rules.hash(Files.readAllBytes(f.toPath())).equals(files.getString(path)))
        throw new IOException("Файл копии повреждён: " + path);
    }
    return m;
  }

  void journal(File dir, String status, String action) throws Exception {
    JSONObject j = read(new File(dir, "journal.json"));
    j.put("status", status);
    j.getJSONArray("actions")
        .put(new JSONObject().put("time", System.currentTimeMillis()).put("text", action));
    json(new File(dir, "journal.json"), j);
    log.accept(action);
  }

  byte[] asset(String name) throws Exception {
    try (InputStream in = context.getAssets().open("payloads/" + name)) {
      return IO.bytes(in, 20 * 1024 * 1024);
    }
  }

  void install(File dir, String name, String packageName) throws Exception {
    if (Rules.packages(shell("pm list packages --user 0")).contains(packageName)) {
      int state = Rules.state(shell("dumpsys package " + packageName));
      if (state >= 2) {
        journal(
            dir,
            "running",
            "Включаю ранее отключённое приложение: "
                + Rules.NAMES.getOrDefault(packageName, packageName));
        setState(packageName, 1);
      }
      log.accept("Уже установлено: " + Rules.NAMES.getOrDefault(packageName, packageName));
      return;
    }
    JSONObject manifest =
        new JSONObject(new String(asset("manifest.json"), StandardCharsets.UTF_8));
    byte[] bytes = asset(name);
    if (!Rules.hash(bytes).equals(manifest.getString(name)))
      throw new IOException("Установочный файл повреждён: " + name);
    journal(
        dir, "running", "Устанавливаю " + Rules.NAMES.getOrDefault(packageName, packageName) + "…");
    String remote = "/data/local/tmp/sokol-" + UUID.randomUUID() + ".apk";
    try {
      adb.push(bytes, remote);
      String result = shell("pm install -r --user 0 " + remote);
      if (!result.contains("Success"))
        throw new IOException("Установка не подтверждена: " + result);
    } finally {
      shell("rm -f " + remote);
    }
    if (!Rules.packages(shell("pm list packages --user 0")).contains(packageName))
      throw new IOException("Приложение не появилось на ТВ.");
  }

  void setState(String name, int state) throws Exception {
    shell("pm " + Rules.stateAction(state) + " --user 0 " + name);
    if (Rules.state(shell("dumpsys package " + name)) != state)
      throw new IOException("Не подтверждено состояние приложения: " + name);
  }

  String currentHome() throws Exception {
    return Rules.component(shell(RESOLVE));
  }

  void verifyHome(String component) throws Exception {
    new HomeHandover(adb, text -> {}).verify(component);
  }

  void newHome(File dir) throws Exception {
    String original = currentHome();
    String old = original.split("/")[0];
    if (!old.equals(Rules.HOME) && !Rules.STOCK.contains(old))
      throw new IOException(
          "Для этого главного экрана автоматическая замена пока не проверена. Очистка и ByeByeDPI"
              + " доступны отдельно.");
    JSONObject backup = validate(dir);
    if (!Rules.fullComponent(original).equals(Rules.fullComponent(backup.getString("home"))))
      throw new IOException("Главный экран изменился после создания копии. Подключитесь заново.");
    install(dir, "tvhome.apk", Rules.HOME);
    if (old.equals(Rules.HOME)) return;
    JSONObject states = backup.getJSONObject("states");
    int before = states.isNull(Rules.HOME) ? 3 : states.getInt(Rules.HOME);
    boolean keepStock = device.packages.stream().anyMatch(p -> p.startsWith("com.cvte."));
    new HomeHandover(adb, text -> journal(dir, "running", text))
        .change(original, Rules.HOME + "/.MainActivity", keepStock, states.getInt(old), before);
  }

  String apply(Set<String> names, boolean home, boolean dpi, List<File> wallpapers)
      throws Exception {
    if (device == null || !device.tv || device.sdk < 26)
      throw new IOException("Настройка доступна для Android TV и Google TV 8.0 и новее.");
    identity();
    if (names.isEmpty() && !home && !dpi && wallpapers.isEmpty())
      throw new IOException("Выберите хотя бы одно действие.");
    if (home
        && !device.home.startsWith(Rules.HOME + "/")
        && !Rules.STOCK.contains(device.home.split("/")[0]))
      throw new IOException(
          "Замена этого главного экрана пока не поддерживается. Снимите галочку «Новый главный"
              + " экран».");
    if (!wallpapers.isEmpty() && !home && !device.packages.contains(Rules.HOME))
      throw new IOException("Для обоев нужен главный экран Sokol CleanTV Home.");
    for (String p : names) {
      if (!Rules.pkg(p) || !device.packages.contains(p) || !Rules.protect(p).isEmpty())
        throw new IOException("Выбрано защищённое или отсутствующее приложение: " + p);
      Rules.safeDump(shell("dumpsys package " + p));
    }
    for (File f : wallpapers)
      if (!f.isFile() || f.length() > 20 * 1024 * 1024 || f.length() < 1)
        throw new IOException("Обои отсутствуют или превышают 20 МБ.");
    String abi = device.abi;
    if (dpi
        && !device.packages.contains(Rules.DPI)
        && !Set.of("armeabi-v7a", "arm64-v8a").contains(abi))
      throw new IOException("В этой сборке нет ByeByeDPI для " + abi);
    Set<String> tracked = new TreeSet<>(names);
    if (home) {
      tracked.add(Rules.HOME);
      for (String p : Rules.STOCK) if (device.packages.contains(p)) tracked.add(p);
    }
    if (dpi) tracked.add(Rules.DPI);
    File dir = backup(tracked);
    JSONObject plan = read(new File(dir, "journal.json"));
    plan.put("disabled", new JSONArray(names));
    plan.put("newHome", home);
    json(new File(dir, "journal.json"), plan);
    journal(dir, "running", "Начинаю настройку. Резервная копия сохранена.");
    try {
      identity();
      if (home) newHome(dir);
      if (dpi) install(dir, "byedpi-" + abi + ".apk", Rules.DPI);
      for (String p : names) {
        identity();
        journal(dir, "running", "Отключаю " + Rules.NAMES.getOrDefault(p, p));
        Rules.safeDump(shell("dumpsys package " + p));
        setState(p, 3);
      }
      if ((home || dpi || !wallpapers.isEmpty())
          && Rules.packages(shell("pm list packages --user 0")).contains(Rules.HOME))
        theme(dir, wallpapers, dpi || device.packages.contains(Rules.DPI));
      identity();
      shell("sync");
      journal(
          dir, "applied", "Настройка завершена. Проверьте пульт, HDMI и воспроизведение на ТВ.");
      return "Готово. Копия сохранена во вкладке «Копии».";
    } catch (Exception e) {
      journal(
          dir,
          "failed",
          "Операция остановлена: " + e.getMessage() + ". Копия доступна для восстановления.");
      throw e;
    }
  }

  void writeTheme(String path, byte[] data) throws Exception {
    if (!themePath(path)) throw new IOException("Недопустимое имя файла оформления.");
    String tmp = "/data/local/tmp/sokol-" + UUID.randomUUID();
    try {
      adb.push(data, tmp);
      String uri = "content://" + Rules.HOME + ".theme/" + path;
      // Android 8/9 have no content write. Their shell can copy into external app files.
      shell(
          "if content write --uri "
              + uri
              + " < "
              + tmp
              + " >/dev/null 2>&1 && [ \"$(content read --uri "
              + uri
              + " 2>/dev/null | wc -c)\" -eq "
              + data.length
              + " ]; then true; else mkdir -p "
              + Rules.quote(
                  THEME + (new File(path).getParent() == null ? "" : new File(path).getParent()))
              + "; cp "
              + tmp
              + " "
              + Rules.quote(THEME + path)
              + "; fi");
      String verify = shell("content read --uri " + uri + " 2>/dev/null | sha256sum");
      if (!verify.startsWith(Rules.hash(data) + " ")) {
        String external = shell("sha256sum " + Rules.quote(THEME + path));
        if (!external.startsWith(Rules.hash(data) + " "))
          throw new IOException("Не подтверждена передача оформления.");
      }
    } finally {
      shell("rm -f " + tmp);
    }
  }

  void restorePreferences(String encoded, boolean keepThemeSignature) throws Exception {
    JSONObject saved =
        new JSONObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
    if (keepThemeSignature) {
      JSONObject current =
          new JSONObject(
              new String(Base64.getDecoder().decode(snapshot()), StandardCharsets.UTF_8));
      JSONObject prefs = current.optJSONObject("launcher_settings");
      if (prefs != null && prefs.has("theme_signature")) {
        if (!saved.has("launcher_settings")) saved.put("launcher_settings", new JSONObject());
        saved
            .getJSONObject("launcher_settings")
            .put("theme_signature", prefs.get("theme_signature"));
      }
    }
    byte[] bytes = Base64.getEncoder().encode(saved.toString().getBytes(StandardCharsets.UTF_8));
    String tmp = "/data/local/tmp/sokol-preferences";
    adb.push(bytes, tmp);
    try {
      String result =
          shell(
              "content call --uri content://"
                  + Rules.HOME
                  + ".theme --method sokol_restore --arg \"$(cat "
                  + tmp
                  + ")\"");
      if (!result.contains("restored=true"))
        throw new IOException("Настройки главного экрана не восстановлены.");
    } finally {
      shell("rm -f " + tmp);
    }
  }

  void theme(File dir, List<File> wallpapers, boolean dpi) throws Exception {
    journal(dir, "running", "Передаю оформление и обои…");
    File old = new File(dir, "theme/config.json");
    JSONObject config = old.isFile() ? read(old) : new JSONObject().put("wallpaper", false);
    config
        .put("app_library", true)
        .put("app_library_selection", true)
        .put("library_button", true)
        .put("vpn_app", dpi ? Rules.DPI : "")
        .put("vpn_pill", dpi);
    if (!old.isFile())
      config
          .put(
              "dock_apps",
              new JSONArray(
                  List.of(
                      "com.google.android.youtube.tv",
                      "ru.kinopoisk.tv",
                      "ru.rutube.app",
                      "com.vk.tv",
                      "mobi.zona")))
          .put("fill_dock", false);
    for (File f : wallpapers) {
      byte[] b = Files.readAllBytes(f.toPath());
      String ext = f.getName().substring(f.getName().lastIndexOf('.'));
      writeTheme("wallpapers/wallpaper-" + Rules.hash(b).substring(0, 12) + ext, b);
    }
    writeTheme("config.json", config.toString().getBytes(StandardCharsets.UTF_8));
    writeTheme("theme_revision", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    shell("am force-stop " + Rules.HOME);
    shell("input keyevent KEYCODE_HOME");
    Thread.sleep(700);
    File prefs = new File(dir, "preferences.b64");
    if (prefs.isFile()) {
      restorePreferences(IO.text(prefs), true);
      shell("am force-stop " + Rules.HOME);
      shell("input keyevent KEYCODE_HOME");
    }
  }

  String restore(File dir) throws Exception {
    JSONObject m = validate(dir);
    identity(m.getString("identity"), m.getString("fingerprint"));
    String original = Rules.component(m.getString("home"));
    String old = original.split("/")[0];
    Set<String> current = Rules.packages(shell("pm list packages --user 0"));
    if (!current.contains(old))
      throw new IOException("Исходный главный экран отсутствует. Нужен его установочный файл.");
    JSONObject states = m.getJSONObject("states");
    for (String p : IO.keys(states)) {
      if (!Rules.pkg(p) || (!states.isNull(p) && (states.getInt(p) < 0 || states.getInt(p) > 4)))
        throw new IOException("Недопустимое состояние в копии.");
      if (!states.isNull(p) && !current.contains(p))
        throw new IOException("Приложение из копии удалено: " + p);
    }
    journal(dir, "restoring", "Возвращаю прежний главный экран и состояния приложений…");
    shell("pm enable --user 0 " + old);
    for (String p : IO.keys(states)) {
      identity(m.getString("identity"), m.getString("fingerprint"));
      if (current.contains(p)) setState(p, states.isNull(p) ? 3 : states.getInt(p));
    }
    if (new File(dir, "preferences.b64").isFile()) {
      shell("pm enable --user 0 " + Rules.HOME);
      shell("am force-stop " + Rules.HOME);
      shell(
          "content delete --uri content://"
              + Rules.HOME
              + ".theme/wallpapers; rm -f "
              + THEME
              + "wallpapers/*.[jJ][pP][gG] "
              + THEME
              + "wallpapers/*.[pP][nN][gG] "
              + THEME
              + "wallpapers/*.[wW][eE][bB][pP]");
      JSONObject files = m.getJSONObject("files");
      for (String path : IO.keys(files))
        if (path.startsWith("theme/"))
          writeTheme(path.substring(6), Files.readAllBytes(new File(dir, path).toPath()));
      if (!new File(dir, "theme/config.json").isFile())
        shell(
            "content delete --uri content://"
                + Rules.HOME
                + ".theme/config.json; rm -f "
                + THEME
                + "config.json");
      restorePreferences(IO.text(new File(dir, "preferences.b64")), false);
      if (states.has(Rules.HOME) && !states.isNull(Rules.HOME))
        setState(Rules.HOME, states.getInt(Rules.HOME));
    }
    shell("cmd package set-home-activity --user 0 " + original);
    verifyHome(original);
    shell("sync");
    journal(
        dir, "restored", "Восстановление завершено. Новые приложения остаются на ТВ отключёнными.");
    return "Восстановлены главный экран, его оформление и изменённые состояния приложений.";
  }

  String verify() throws Exception {
    identity();
    if (!shell("getprop sys.boot_completed").trim().equals("1"))
      throw new IOException("Телевизор ещё загружается.");
    String expectedHome = currentHome();
    if (latest != null) {
      JSONObject m = validate(latest);
      identity(m.getString("identity"), m.getString("fingerprint"));
      JSONObject j = read(new File(latest, "journal.json"));
      if (j.getString("status").equals("applied")) {
        JSONArray disabled = j.optJSONArray("disabled");
        if (disabled == null) throw new IOException("В журнале нет списка проверяемых изменений.");
        for (int n = 0; n < disabled.length(); n++) {
          String p = disabled.getString(n);
          if (!Rules.pkg(p)) throw new IOException("Журнал проверки повреждён.");
          if (Rules.state(shell("dumpsys package " + p)) != 3)
            throw new IOException("После перезагрузки приложение включилось: " + p);
        }
        expectedHome =
            j.optBoolean("newHome") ? Rules.HOME + "/.MainActivity" : m.getString("home");
      } else if (j.getString("status").equals("restored")) {
        expectedHome = m.getString("home");
      }
    }
    verifyHome(expectedHome);
    return "Главный экран доступен. Проверьте кнопки пульта, HDMI, Wi-Fi и видео на ТВ.";
  }
}
