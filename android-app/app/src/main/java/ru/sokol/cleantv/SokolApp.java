package ru.sokol.cleantv;

import android.app.Application;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

public final class SokolApp extends Application {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler ui = new Handler(Looper.getMainLooper());
  final List<String> log = new ArrayList<>();
  volatile boolean busy = false;
  volatile String progress = "";
  Runnable listener;
  AdbClient client;
  Workshop workshop;

  synchronized void log(String text) {
    log.add(text);
    if (log.size() > 250) log.remove(0);
    progress = text;
    ui.post(
        () -> {
          if (listener != null) listener.run();
        });
  }

  void ready() throws Exception {
    if (client == null) {
      client = new AdbClient(this);
      workshop = new Workshop(this, client, this::log);
    }
  }

  interface Job {
    String run() throws Exception;
  }

  void run(Job job, java.util.function.Consumer<String> done) {
    if (busy) return;
    busy = true;
    ui.post(
        () -> {
          if (listener != null) listener.run();
        });
    worker.execute(
        () -> {
          PowerManager.WakeLock wake =
              ((PowerManager) getSystemService(POWER_SERVICE))
                  .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SokolCleanTV:operation");
          wake.acquire(15 * 60 * 1000L);
          String result;
          try {
            ready();
            result = job.run();
            log(result);
          } catch (Exception e) {
            if (client != null && !client.isConnected() && workshop != null) workshop.device = null;
            result =
                "Не завершено. "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log(result);
          } finally {
            if (wake.isHeld()) wake.release();
            busy = false;
          }
          String message = result;
          ui.post(
              () -> {
                if (listener != null) listener.run();
                done.accept(message);
              });
        });
  }
}
