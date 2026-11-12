package ru.sokol.cleantv;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Start and observe first, then switch the known stock Home, then verify or recover. */
final class HomeHandover {
  interface Note {
    void write(String text) throws Exception;
  }

  interface Pause {
    void sleep(long millis) throws Exception;
  }

  private final Transport adb;
  private final Note note;
  private final Pause pause;
  private final int attempts;
  private String diagnostic = "";

  HomeHandover(Transport adb, Note note) {
    this(adb, note, Thread::sleep, 30);
  }

  HomeHandover(Transport adb, Note note, Pause pause, int attempts) {
    this.adb = adb;
    this.note = note;
    this.pause = pause;
    this.attempts = attempts;
  }

  private String shell(String command) throws Exception {
    return adb.shell(command);
  }

  private String owner() throws Exception {
    return Rules.component(shell(Workshop.RESOLVE));
  }

  static String focusedPackage(String dump, boolean window) {
    String[] fields =
        window
            ? new String[] {"mCurrentFocus", "mFocusedApp"}
            : new String[] {
              "topResumedActivity", "mResumedActivity", "ResumedActivity", "mFocusedActivity"
            };
    for (String field : fields) {
      Matcher lines = Pattern.compile("(?m)^\\s*" + field + "\\s*[:=]([^\\r\\n]*)").matcher(dump);
      while (lines.find()) {
        Matcher component =
            Pattern.compile("(?:^|[\\s{])([A-Za-z_][A-Za-z0-9_.]*)/[A-Za-z0-9_.$]+")
                .matcher(lines.group(1));
        if (component.find()) return component.group(1);
      }
    }
    return "";
  }

  private void waitFor(String target, boolean requireOwner) throws Exception {
    String pkg = target.split("/")[0];
    for (int i = 0; i < attempts; i++) {
      String current = requireOwner ? owner() : "не меняли";
      String visible = focusedPackage(shell("dumpsys activity activities"), false);
      if (visible.isEmpty()) visible = focusedPackage(shell("dumpsys window windows"), true);
      diagnostic =
          "Home=" + current + "; на экране=" + (visible.isEmpty() ? "не определено" : visible);
      boolean selected =
          !requireOwner || Rules.fullComponent(current).equals(Rules.fullComponent(target));
      if (selected && visible.equals(pkg)) return;
      if (i + 1 < attempts) pause.sleep(500);
    }
    throw new IOException("Телевизор не подтвердил главный экран. " + diagnostic);
  }

  void verify(String component) throws Exception {
    shell("input keyevent KEYCODE_HOME");
    waitFor(component, true);
  }

  private void state(String pkg, int value) throws Exception {
    shell("pm " + Rules.stateAction(value) + " --user 0 " + pkg);
    if (Rules.state(shell("dumpsys package " + pkg)) != value)
      throw new IOException("Не подтверждено состояние приложения: " + pkg);
  }

  void change(String original, String target, boolean keepStock, int oldState, int targetBefore)
      throws Exception {
    String old = original.split("/")[0], next = target.split("/")[0];
    if (!Rules.STOCK.contains(old) || !next.equals(Rules.HOME) || oldState > 1)
      throw new IOException("Автоматическая замена этого главного экрана не поддерживается.");
    String stage = "запуск нового экрана";
    try {
      note.write("Запускаю новый главный экран. Прежний пока включён…");
      state(next, 1);
      String start =
          shell(
              "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "
                  + target);
      if (!Pattern.compile("(?m)^\\s*Status:\\s*ok\\s*$").matcher(start).find()
          || Pattern.compile("(?m)^\\s*(?:Error|Exception)").matcher(start).find())
        throw new IOException("Не подтверждён запуск: " + start);
      // A persistent OEM preference can still resolve to stock here. Only foreground is required.
      waitFor(target, false);
      stage = "переключение главного экрана";
      if (!keepStock) {
        note.write("Новый экран запущен и проверен. Переключаю кнопку Home…");
        state(old, 3);
      }
      shell("cmd package set-home-activity --user 0 " + target);
      stage = "проверка кнопки Home";
      verify(target);
      note.write("Новый главный экран назначен и проверен.");
    } catch (Exception failure) {
      List<String> recoveryErrors = new ArrayList<>();
      try {
        state(old, oldState);
      } catch (Exception e) {
        recoveryErrors.add("прежнее приложение: " + e.getMessage());
      }
      try {
        state(next, targetBefore);
      } catch (Exception e) {
        recoveryErrors.add("новое приложение: " + e.getMessage());
      }
      try {
        shell("cmd package set-home-activity --user 0 " + original);
      } catch (Exception e) {
        recoveryErrors.add("выбор Home: " + e.getMessage());
      }
      try {
        verify(original);
      } catch (Exception e) {
        recoveryErrors.add("проверка возврата: " + e.getMessage());
      }
      String recovery =
          recoveryErrors.isEmpty()
              ? "Прежний главный экран возвращён и проверен."
              : "Возврат прежнего экрана полностью не подтверждён. Используйте сохранённую копию"
                    + " после восстановления связи. "
                  + String.join("; ", recoveryErrors);
      String message = "Этап «" + stage + "»: " + failure.getMessage() + " " + recovery;
      try {
        note.write(message);
      } catch (Exception logError) {
        failure.addSuppressed(logError);
      }
      throw new IOException(message, failure);
    }
  }
}
