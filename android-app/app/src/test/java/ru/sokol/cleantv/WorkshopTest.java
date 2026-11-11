package ru.sokol.cleantv;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class WorkshopTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  static final String STOCK = "com.google.android.tvlauncher",
      APP = "com.netflix.ninja",
      SECOND = "com.amazon.amazonvideo.livingroom";
  Fake tv;
  Workshop w;

  @Before
  public void start() throws Exception {
    tv = new Fake();
    w = new Workshop(null, temporary.newFolder("backups"), tv, s -> {});
    w.inspect("192.168.1.2:5555");
  }

  static final class Fake implements Transport {
    final Map<String, Integer> states =
        new TreeMap<>(Map.of(STOCK, 0, APP, 0, SECOND, 4, "com.android.vending", 0));
    String serial = "tv-one", fingerprint = "firmware-one", home = STOCK + "/.Home", fail = "";
    int mutations = 0;
    String user = "0";
    Runnable beforeMutation = () -> {};

    public String shell(String cmd) throws Exception {
      if (cmd.equals("am get-current-user")) return user;
      if (cmd.equals("getprop"))
        return "[ro.build.version.sdk]: [30]\n[ro.build.version.release]: [11]\n[ro.serialno]: ["
            + serial
            + "]\n"
            + "[ro.product.manufacturer]: [Haier]\n"
            + "[ro.product.model]: [Test TV]\n"
            + "[ro.product.device]: [tv]\n"
            + "[ro.product.cpu.abi]: [arm64-v8a]\n"
            + "[ro.build.fingerprint]: ["
            + fingerprint
            + "]";
      if (cmd.equals("settings get secure android_id")) return "id-one";
      if (cmd.equals("pm list features")) return "feature:android.software.leanback";
      if (cmd.startsWith("pm list packages")) {
        StringBuilder b = new StringBuilder();
        for (String p : states.keySet())
          if (!cmd.contains(" -d ") || states.get(p) == 3)
            b.append("package:").append(p).append("\n");
        return b.toString();
      }
      if (cmd.startsWith("dumpsys package ")) {
        String p = cmd.substring(16);
        return states.containsKey(p)
            ? "User 0: installed=true enabled=" + states.get(p) + " hidden=false"
            : "not found";
      }
      if (cmd.equals(Workshop.RESOLVE)) return home;
      if (cmd.startsWith("settings list")) return "key=value";
      if (cmd.startsWith("pm ")) {
        String[] a = cmd.split(" ");
        String p = a[a.length - 1];
        beforeMutation.run();
        mutations++;
        if (p.equals(fail)) throw new IOException("connection lost");
        for (int i = 0; i < 5; i++) if (a[1].equals(Rules.stateAction(i))) states.put(p, i);
        return "Package " + p + " new state";
      }
      if (cmd.startsWith("cmd package set-home-activity")) {
        beforeMutation.run();
        mutations++;
        home = cmd.substring(cmd.lastIndexOf(' ') + 1);
        return "Success";
      }
      if (cmd.equals("dumpsys activity activities")) return "mResumedActivity: " + home;
      if (cmd.equals("getprop sys.boot_completed")) return "1";
      return "";
    }

    public void push(byte[] data, String remote) {
      throw new UnsupportedOperationException();
    }

    public byte[] pull(String remote, int limit) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  public void writesCompleteBackupBeforeFirstMutation() throws Exception {
    tv.beforeMutation =
        () -> {
          try {
            assertNotNull(w.latest);
            assertTrue(w.validate(w.latest).getBoolean("complete"));
            assertEquals(
                "running", Workshop.read(new File(w.latest, "journal.json")).getString("status"));
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        };
    w.apply(Set.of(APP), false, false, List.of());
    assertEquals(3, (int) tv.states.get(APP));
    assertEquals(0, w.validate(w.latest).getJSONObject("states").getInt(APP));
  }

  @Test
  public void restoresDefaultAndPreviouslyDisabledStatesExactly() throws Exception {
    w.apply(Set.of(APP, SECOND), false, false, List.of());
    w.restore(w.latest);
    assertEquals(0, (int) tv.states.get(APP));
    assertEquals(4, (int) tv.states.get(SECOND));
    assertEquals(STOCK + "/.Home", tv.home);
    assertEquals(0, (int) tv.states.get(STOCK));
  }

  @Test
  public void reloadsLatestBackupAfterReconnect() throws Exception {
    w.apply(Set.of(APP), false, false, List.of());
    File copy = w.latest;
    w.latest = null;
    w.inspect("192.168.1.2:5555");
    assertEquals(copy, w.latest);
    w.verify();
  }

  @Test
  public void catchesPackageReenabledAfterReboot() throws Exception {
    w.apply(Set.of(APP), false, false, List.of());
    tv.states.put(APP, 0);
    w.inspect("192.168.1.2:5555");
    assertThrows(IOException.class, () -> w.verify());
  }

  @Test
  public void catchesHomeChangedAfterReboot() throws Exception {
    w.apply(Set.of(APP), false, false, List.of());
    tv.home = "com.other.home/.Main";
    w.inspect("192.168.1.2:5555");
    assertThrows(IOException.class, () -> w.verify());
  }

  @Test
  public void blocksSwitchToSecondaryUser() {
    tv.user = "10";
    assertThrows(IOException.class, () -> w.apply(Set.of(APP), false, false, List.of()));
    assertEquals(0, tv.mutations);
  }

  @Test
  public void rejectsDifferentDeviceBeforeAnyMutation() throws Exception {
    w.backup(Set.of(APP));
    tv.serial = "friend-tv";
    assertThrows(IOException.class, () -> w.restore(w.latest));
    assertEquals(0, tv.mutations);
  }

  @Test
  public void rejectsChangedFirmware() throws Exception {
    w.backup(Set.of(APP));
    tv.fingerprint = "other";
    assertThrows(IOException.class, () -> w.restore(w.latest));
    assertEquals(0, tv.mutations);
  }

  @Test
  public void rejectsCorruptedBackup() throws Exception {
    w.backup(Set.of(APP));
    Files.write(
        new File(w.latest, "getprop.txt").toPath(),
        "corrupt".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> w.restore(w.latest));
    assertEquals(0, tv.mutations);
  }

  @Test
  public void preservesRecoveryAfterPartialFailure() throws Exception {
    tv.fail = APP;
    assertThrows(IOException.class, () -> w.apply(Set.of(APP, SECOND), false, false, List.of()));
    assertNotNull(w.latest);
    assertEquals("failed", Workshop.read(new File(w.latest, "journal.json")).getString("status"));
    tv.fail = "";
    w.restore(w.latest);
    assertEquals(4, (int) tv.states.get(SECOND));
    assertEquals(0, (int) tv.states.get(APP));
  }

  @Test
  public void protectsGooglePlayBeforeBackupOrChanges() {
    assertThrows(
        IOException.class, () -> w.apply(Set.of("com.android.vending"), false, false, List.of()));
    assertNull(w.latest);
    assertEquals(0, tv.mutations);
  }

  @Test
  public void rejectsUnknownHomeWithoutStartingInstall() {
    w.device.home = "com.unknown.tv/.Main";
    assertThrows(IOException.class, () -> w.apply(Set.of(), true, false, List.of()));
    assertNull(w.latest);
    assertEquals(0, tv.mutations);
  }

  @Test
  public void blocksPhoneAndOldAndroid() {
    w.device.tv = false;
    assertThrows(IOException.class, () -> w.apply(Set.of(APP), false, false, List.of()));
    w.device.tv = true;
    w.device.sdk = 25;
    assertThrows(IOException.class, () -> w.apply(Set.of(APP), false, false, List.of()));
    assertEquals(0, tv.mutations);
  }

  @Test
  public void wallpaperPathsAreConstrained() {
    assertTrue(Workshop.themePath("wallpapers/wallpaper-123.jpg"));
    for (String path :
        List.of(
            "../config.json",
            "wallpapers/../config.json",
            "wallpapers/bad;rm.jpg",
            "other/file.jpg")) assertFalse(path, Workshop.themePath(path));
  }
}
