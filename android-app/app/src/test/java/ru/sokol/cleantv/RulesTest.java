package ru.sokol.cleantv;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RulesTest {
  @Test
  public void acceptsOnlyPrivateIPv4AndValidPorts() {
    assertArrayEquals(new String[] {"192.168.1.90", "5555"}, Rules.endpoint("192.168.1.90"));
    assertEquals("37000", Rules.endpoint("10.0.0.1:37000")[1]);
    for (String s :
        List.of(
            "8.8.8.8",
            "127.0.0.1",
            "192.168.1.2:0",
            "192.168.1.2:65536",
            "192.168.1.2;reboot",
            "192.168.1.300",
            "[::1]",
            "192.168.01.2"))
      assertThrows(s, IllegalArgumentException.class, () -> Rules.endpoint(s));
  }

  @Test
  public void preservesStoreNetworkAndHardware() {
    for (String p :
        List.of(
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.tungsten.setupwraith",
            "com.cltv.mal",
            "com.mediatek.tis",
            "com.mediatek.tv.service",
            "com.mediatek.tv.agent",
            "com.android.bluetooth",
            Rules.HOME)) assertFalse(p, Rules.protect(p).isEmpty());
    assertEquals("", Rules.protect("com.netflix.ninja"));
  }

  @Test
  public void detectsRolesEvenForUnknownPackage() {
    for (String role :
        List.of(
            "TV_INPUT_HARDWARE",
            "android.media.tv.TvInputService",
            "android.intent.category.HOME",
            "android.net.VpnService",
            "GlobalKeyReceiver",
            "VIEW_INPUTS"))
      assertThrows(
          IllegalArgumentException.class,
          () -> Rules.safeDump("User 0: installed=true enabled=0\n" + role));
    assertThrows(IllegalArgumentException.class, () -> Rules.safeDump("Permission denied"));
  }

  @Test
  public void recordsAllFivePackageStates() {
    for (int i = 0; i < 5; i++)
      assertEquals(
          i,
          Rules.state(
              "User 0: ceDataInode=0 installed=true hidden=false enabled=" + i + " instant=false"));
    assertEquals("default-state", Rules.stateAction(0));
    assertThrows(
        IllegalArgumentException.class, () -> Rules.state("User 10: installed=true enabled=0"));
  }

  @Test
  public void identityUsesStableSerialAndRejectsMissingId() throws Exception {
    Map<String, String> p = new HashMap<>();
    p.put("ro.serialno", "abc");
    p.put("ro.product.device", "tv");
    assertEquals(Rules.identity(p, "id1"), Rules.identity(p, "id2"));
    p.remove("ro.serialno");
    assertThrows(IllegalArgumentException.class, () -> Rules.identity(p, "null"));
  }

  @Test
  public void shellArgumentsAreQuoted() {
    assertEquals("'a'\\''b'", Rules.quote("a'b"));
    assertFalse(Rules.pkg("com.app;reboot"));
  }

  @Test
  public void detectsTerminalMarkerWithoutWaitingForStreamEof() {
    assertTrue(Rules.shellDone("response\nSOKOL_x:0\n", "SOKOL_x"));
    assertTrue(Rules.shellDone("response\nSOKOL_x:127\n", "SOKOL_x"));
    assertFalse(Rules.shellDone("response\nSOKOL_x:0", "SOKOL_x"));
    assertFalse(Rules.shellDone("response\nSOKOL_other:0\n", "SOKOL_x"));
  }
}
