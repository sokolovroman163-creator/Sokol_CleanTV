package ru.sokol.cleantv;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.*;
import org.junit.Test;

public class HomeHandoverTest {
  static final String OLD = "com.google.android.tvlauncher",
      ORIGINAL = OLD + "/.Home",
      TARGET = Rules.HOME + "/.MainActivity";

  static final class Tv implements Transport {
    Map<String, Integer> states = new HashMap<>(Map.of(OLD, 0, Rules.HOME, 0));
    List<String> commands = new ArrayList<>();
    String owner = ORIGINAL, visible = OLD;
    boolean refuse = false,
        crash = false,
        allowWithStock = false,
        failedRecovery = false,
        windowOnly = false;
    int reads = 0, delay = 0;

    public String shell(String command) throws Exception {
      commands.add(command);
      if (command.equals(Workshop.RESOLVE)) return owner;
      if (command.startsWith("dumpsys package "))
        return "User 0: installed=true enabled="
            + states.get(command.substring(16))
            + " hidden=false";
      if (command.startsWith("pm ")) {
        String[] p = command.split(" ");
        String pkg = p[p.length - 1];
        if (failedRecovery && p[1].equals("default-state") && pkg.equals(OLD))
          throw new IOException("lost connection restoring stock");
        for (int s = 0; s < 5; s++) if (p[1].equals(Rules.stateAction(s))) states.put(pkg, s);
        return "done";
      }
      if (command.startsWith("am start ")) {
        visible = crash ? OLD : Rules.HOME;
        return "Status: ok\nActivity: " + TARGET;
      }
      if (command.startsWith("cmd package set-home-activity")) {
        String target = command.substring(command.lastIndexOf(' ') + 1);
        if (target.equals(ORIGINAL) || !refuse && (allowWithStock || states.get(OLD) == 3))
          owner = target;
        return "Success";
      }
      if (command.equals("input keyevent KEYCODE_HOME")) {
        visible = owner.split("/")[0];
        return "";
      }
      if (command.equals("dumpsys activity activities")) {
        reads++;
        String active = reads <= delay ? OLD : visible;
        return windowOnly
            ? "history: " + TARGET
            : "mResumedActivity: ActivityRecord{a1 u0 " + active + "/.Main t1}";
      }
      if (command.equals("dumpsys window windows"))
        return "mCurrentFocus=Window{a1 u0 " + visible + "/.Main}";
      throw new IOException("Unexpected command: " + command);
    }

    public void push(byte[] b, String p) {
      throw new UnsupportedOperationException();
    }

    public byte[] pull(String p, int n) {
      throw new UnsupportedOperationException();
    }

    HomeHandover flow() {
      return new HomeHandover(this, s -> {}, ms -> {}, 4);
    }
  }

  @Test
  public void switchesWhenFirmwareKeepsStockUntilDisabled() throws Exception {
    Tv tv = new Tv();
    tv.flow().change(ORIGINAL, TARGET, false, 0, 3);
    int disable = tv.commands.indexOf("pm disable-user --user 0 " + OLD);
    assertTrue(disable > tv.commands.indexOf("dumpsys activity activities"));
    assertTrue(disable < tv.commands.indexOf("cmd package set-home-activity --user 0 " + TARGET));
    assertTrue(disable < tv.commands.indexOf("input keyevent KEYCODE_HOME"));
    assertEquals(TARGET, tv.owner);
    assertEquals(Rules.HOME, tv.visible);
    assertEquals(3, (int) tv.states.get(OLD));
    assertTrue(
        tv.commands.stream()
            .anyMatch(
                c ->
                    c.startsWith(
                        "am start -W -a android.intent.action.MAIN -c"
                            + " android.intent.category.HOME")));
  }

  @Test
  public void doesNotDisableStockWhenNewHomeCrashes() {
    Tv tv = new Tv();
    tv.crash = true;
    assertThrows(IOException.class, () -> tv.flow().change(ORIGINAL, TARGET, false, 0, 3));
    assertFalse(tv.commands.contains("pm disable-user --user 0 " + OLD));
    assertEquals(0, (int) tv.states.get(OLD));
    assertEquals(ORIGINAL, tv.owner);
  }

  @Test
  public void restoresExactStatesWhenFirmwareRefusesHandover() {
    Tv tv = new Tv();
    tv.refuse = true;
    IOException error =
        assertThrows(IOException.class, () -> tv.flow().change(ORIGINAL, TARGET, false, 0, 3));
    assertEquals(0, (int) tv.states.get(OLD));
    assertEquals(3, (int) tv.states.get(Rules.HOME));
    assertEquals(OLD, tv.visible);
    assertTrue(error.getMessage().contains("возвращён и проверен"));
  }

  @Test
  public void doesNotDisableStockForCvte() throws Exception {
    Tv tv = new Tv();
    tv.allowWithStock = true;
    tv.flow().change(ORIGINAL, TARGET, true, 0, 0);
    assertEquals(0, (int) tv.states.get(OLD));
    assertEquals(TARGET, tv.owner);
  }

  @Test
  public void cvteRefusalLeavesStockEnabled() {
    Tv tv = new Tv();
    assertThrows(IOException.class, () -> tv.flow().change(ORIGINAL, TARGET, true, 0, 0));
    assertFalse(tv.commands.contains("pm disable-user --user 0 " + OLD));
    assertEquals(0, (int) tv.states.get(OLD));
  }

  @Test
  public void waitsForSlowFirstFrame() throws Exception {
    Tv tv = new Tv();
    tv.delay = 2;
    tv.flow().change(ORIGINAL, TARGET, false, 0, 3);
    assertTrue(tv.reads >= 3);
    assertEquals(TARGET, tv.owner);
  }

  @Test
  public void usesWindowFocusWhenActivityFieldIsAbsent() throws Exception {
    Tv tv = new Tv();
    tv.windowOnly = true;
    tv.flow().change(ORIGINAL, TARGET, false, 0, 3);
    assertEquals(TARGET, tv.owner);
  }

  @Test
  public void doesNotMistakeHistoryOrPackagePrefixForForeground() {
    assertEquals(
        OLD,
        HomeHandover.focusedPackage(
            "mResumedActivity: ActivityRecord{x u0 " + ORIGINAL + " t1}\nHistory #0: " + TARGET,
            false));
    assertEquals(
        Rules.HOME + ".other",
        HomeHandover.focusedPackage(
            "mResumedActivity: ActivityRecord{x u0 " + Rules.HOME + ".other/.Main t1}", false));
    assertEquals("", HomeHandover.focusedPackage("History #0: " + TARGET, false));
  }

  @Test
  public void reportsUnconfirmedRecoveryWithoutMaskingOriginalFailure() {
    Tv tv = new Tv();
    tv.refuse = true;
    tv.failedRecovery = true;
    IOException error =
        assertThrows(IOException.class, () -> tv.flow().change(ORIGINAL, TARGET, false, 0, 3));
    assertTrue(error.getMessage().contains("полностью не подтверждён"));
    assertTrue(error.getMessage().contains("Home="));
    assertNotNull(error.getCause());
  }

  @Test
  public void blocksUnknownHomeBeforeMutations() {
    Tv tv = new Tv();
    assertThrows(
        IOException.class, () -> tv.flow().change("com.unknown/.Home", TARGET, false, 0, 3));
    assertTrue(tv.commands.isEmpty());
  }
}
