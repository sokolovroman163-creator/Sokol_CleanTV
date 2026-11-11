package ru.sokol.cleantv;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.util.*;
import java.util.function.Consumer;

final class Discovery {
  private final NsdManager nsd;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
  private final Set<String> found = new LinkedHashSet<>();
  private final Queue<NsdServiceInfo> queue = new ArrayDeque<>();
  private boolean resolving = false, closed = false;
  private WifiManager.MulticastLock lock;
  private Consumer<List<String>> callback;

  Discovery(Context context) {
    nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    WifiManager wifi =
        (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    if (wifi != null) lock = wifi.createMulticastLock("SokolCleanTVDiscovery");
  }

  void start(Consumer<List<String>> callback) {
    this.callback = callback;
    if (lock != null && !lock.isHeld()) lock.acquire();
    for (String type : new String[] {"_adb._tcp.", "_adb-tls-connect._tcp."}) {
      NsdManager.DiscoveryListener l =
          new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String s) {}

            public void onDiscoveryStopped(String s) {}

            public void onStartDiscoveryFailed(String s, int e) {}

            public void onStopDiscoveryFailed(String s, int e) {}

            public void onServiceLost(NsdServiceInfo s) {}

            public void onServiceFound(NsdServiceInfo s) {
              handler.post(
                  () -> {
                    if (!closed) {
                      queue.add(s);
                      resolve();
                    }
                  });
            }
          };
      listeners.add(l);
      try {
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
      } catch (Exception ignored) {
      }
    }
    handler.postDelayed(
        () -> {
          stop();
          callback.accept(new ArrayList<>(found));
        },
        9000);
  }

  @SuppressWarnings("deprecation")
  private void resolve() {
    if (closed || resolving || queue.isEmpty()) return;
    resolving = true;
    nsd.resolveService(
        queue.remove(),
        new NsdManager.ResolveListener() {
          public void onResolveFailed(NsdServiceInfo s, int error) {
            handler.post(
                () -> {
                  resolving = false;
                  resolve();
                });
          }

          public void onServiceResolved(NsdServiceInfo s) {
            handler.post(
                () -> {
                  resolving = false;
                  if (!closed && s.getHost() != null) {
                    String e = s.getHost().getHostAddress() + ":" + s.getPort();
                    try {
                      Rules.endpoint(e);
                      found.add(e);
                      callback.accept(new ArrayList<>(found));
                    } catch (Exception ignored) {
                    }
                  }
                  resolve();
                });
          }
        });
  }

  void stop() {
    if (closed) return;
    closed = true;
    handler.removeCallbacksAndMessages(null);
    for (var l : listeners)
      try {
        nsd.stopServiceDiscovery(l);
      } catch (Exception ignored) {
      }
    if (lock != null && lock.isHeld()) lock.release();
  }
}
