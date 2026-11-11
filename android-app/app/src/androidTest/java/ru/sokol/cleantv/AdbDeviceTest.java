package ru.sokol.cleantv;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Opt-in integration test. Target must be an owned test emulator, never a customer's TV. */
@RunWith(AndroidJUnit4.class)
public class AdbDeviceTest {
  @Test
  public void pairingStopsWhenEndpointDoesNotReply() throws Exception {
    String endpoint = InstrumentationRegistry.getArguments().getString("testEndpoint");
    Assume.assumeTrue(endpoint != null);
    String host = Rules.endpoint(endpoint)[0];
    java.net.ServerSocket server = new java.net.ServerSocket(0);
    java.util.concurrent.atomic.AtomicReference<java.net.Socket> accepted =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread peer =
        new Thread(
            () -> {
              try {
                accepted.set(server.accept());
                Thread.sleep(30000);
              } catch (Exception ignored) {
              }
            });
    peer.setDaemon(true);
    peer.start();
    AdbClient client =
        new AdbClient(InstrumentationRegistry.getInstrumentation().getTargetContext());
    long started = System.currentTimeMillis();
    try {
      assertThrows(
          Exception.class, () -> client.pairTv(host + ":" + server.getLocalPort(), "123456"));
      assertTrue(
          "A nonresponsive pairing endpoint must time out",
          System.currentTimeMillis() - started < 25000);
    } finally {
      server.close();
      if (accepted.get() != null) accepted.get().close();
      peer.interrupt();
      client.disconnect();
    }
  }

  @Test
  public void authenticatesExecutesShellAndRoundTripsSyncFile() throws Exception {
    String endpoint = InstrumentationRegistry.getArguments().getString("testEndpoint");
    Assume.assumeTrue("Provide testEndpoint for an owned test emulator", endpoint != null);
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AdbClient client = new AdbClient(context);
    try {
      client.connectTv(endpoint);
      assertEquals("hello-cleantv", client.shell("printf hello-cleantv"));
      assertThrows(java.io.IOException.class, () -> client.shell("false"));
      byte[] bytes = new byte[100_003];
      new Random(73).nextBytes(bytes);
      String path = "/data/local/tmp/sokol-integration-" + UUID.randomUUID();
      try {
        client.push(bytes, path);
        assertArrayEquals(bytes, client.pull(path, 110_000));
      } finally {
        client.shell("rm -f " + path);
      }
      Workshop w = new Workshop(context, client, s -> {});
      Workshop.Device device = w.inspect(endpoint);
      assertFalse(
          "This integration scenario requires a phone emulator, never a real TV", device.tv);
      assertThrows(java.io.IOException.class, () -> w.apply(Set.of(), true, false, List.of()));
      assertNull(w.latest);
    } finally {
      client.disconnect();
    }
  }
}
