package ru.sokol.cleantv;

import android.content.Context;
import io.github.muntashirakon.adb.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class AdbClient extends AbsAdbConnectionManager implements Transport {
  private final PrivateKey key;
  private final Certificate cert;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

  AdbClient(Context context) throws Exception {
    File dir = new File(context.getNoBackupFilesDir(), "connection");
    if (!dir.isDirectory() && !dir.mkdirs())
      throw new IOException("Не удалось сохранить ключ подключения.");
    File k = new File(dir, "key.pk8"), c = new File(dir, "certificate.der");
    if (k.exists() && c.exists()) {
      key =
          KeyFactory.getInstance("RSA")
              .generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(k.toPath())));
      cert =
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(c.toPath())));
    } else {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      java.security.KeyPair pair = g.generateKeyPair();
      key = pair.getPrivate();
      X500Name name = new X500Name("CN=Sokol CleanTV");
      long now = System.currentTimeMillis();
      var builder =
          new JcaX509v3CertificateBuilder(
              name,
              new BigInteger(96, new SecureRandom()),
              new Date(now - 86400000L),
              new Date(now + 10L * 365 * 86400000L),
              name,
              pair.getPublic());
      var provider = new BouncyCastleProvider();
      cert =
          new JcaX509CertificateConverter()
              .setProvider(provider)
              .getCertificate(
                  builder.build(
                      new JcaContentSignerBuilder("SHA256withRSA")
                          .setProvider(provider)
                          .build(key)));
      Files.write(k.toPath(), key.getEncoded());
      Files.write(c.toPath(), cert.getEncoded());
    }
    setApi(30);
    setTimeout(35, TimeUnit.SECONDS);
    setThrowOnUnauthorised(false);
  }

  @Override
  protected PrivateKey getPrivateKey() {
    return key;
  }

  @Override
  protected Certificate getCertificate() {
    return cert;
  }

  @Override
  protected String getDeviceName() {
    return "Sokol_CleanTV";
  }

  void connectTv(String endpoint) throws Exception {
    String[] e = Rules.endpoint(endpoint);
    disconnect();
    try (java.net.Socket probe = new java.net.Socket()) {
      probe.connect(new java.net.InetSocketAddress(e[0], Integer.parseInt(e[1])), 5000);
    } catch (IOException unavailable) {
      throw new IOException(
          "Телевизор не ответил на этом порту. Проверьте IP, порт, отладку и общую сеть Wi-Fi.",
          unavailable);
    }
    if (!connect(e[0], Integer.parseInt(e[1])))
      throw new IOException(
          "Телевизор не подтвердил подключение. Разрешите отладку с пульта и повторите.");
  }

  void pairTv(String endpoint, String code) throws Exception {
    String[] e = Rules.endpoint(endpoint);
    if (!code.matches("[0-9]{6}"))
      throw new IllegalArgumentException("Введите 6 цифр с экрана телевизора.");
    timed(
        () -> {
          try (var pairing =
              new io.github.muntashirakon.adb.SokolPairingConnection(
                  e[0],
                  Integer.parseInt(e[1]),
                  code.getBytes(StandardCharsets.UTF_8),
                  key,
                  cert,
                  getDeviceName())) {
            pairing.start();
          }
          return null;
        });
  }

  private interface Action<T> {
    T run() throws Exception;
  }

  private <T> T timed(Action<T> action) throws Exception {
    Thread thread = Thread.currentThread();
    ScheduledFuture<?> alarm = timer.schedule(thread::interrupt, 90, TimeUnit.SECONDS);
    try {
      return action.run();
    } finally {
      alarm.cancel(false);
      Thread.interrupted();
    }
  }

  @Override
  public String shell(String command) throws Exception {
    return timed(
        () -> {
          String marker = "SOKOL_" + UUID.randomUUID().toString().replace("-", "");
          String text;
          try (AdbStream stream =
              openStream("shell:" + command + "; printf '\\n" + marker + ":%s\\n' $?")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            while (true) {
              try {
                int n = stream.read(b, 0, b.length);
                if (n < 0) break;
                out.write(b, 0, n);
                if (Rules.shellDone(out.toString("UTF-8").replace("\r", ""), marker)) break;
                if (out.size() > 8 * 1024 * 1024)
                  throw new IOException("Ответ телевизора слишком большой.");
              } catch (IOException e) {
                if (e.getCause() instanceof InterruptedException)
                  throw new IOException(
                      "Время ожидания истекло. Проверьте сеть и повторно подключите ТВ.", e);
                if (!"Stream closed.".equals(e.getMessage())) throw e;
                break;
              }
            }
            text = out.toString("UTF-8").replace("\r", "");
          }
          int pos = text.lastIndexOf("\n" + marker + ":");
          if (pos < 0)
            throw new IOException(
                "Связь прервалась до подтверждения команды. Проверьте журнал и копию.");
          String status = text.substring(pos + marker.length() + 2).trim();
          String output = text.substring(0, pos).trim();
          if (!status.equals("0"))
            throw new IOException(
                "Телевизор отклонил действие. "
                    + output.substring(Math.max(0, output.length() - 600)));
          return output;
        });
  }

  static byte[] header(String id, int length) {
    return ByteBuffer.allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put(id.getBytes(StandardCharsets.US_ASCII))
        .putInt(length)
        .array();
  }

  static void write(AdbStream s, byte[] bytes) throws Exception {
    for (int p = 0; p < bytes.length; p += 4096) {
      int n = Math.min(4096, bytes.length - p);
      s.write(bytes, p, n);
      s.flush();
    }
  }

  static byte[] exact(AdbStream s, int n) throws IOException {
    if (n < 0 || n > 20 * 1024 * 1024) throw new IOException("Недопустимый размер ответа.");
    byte[] b = new byte[n];
    int p = 0;
    while (p < n) {
      int k = s.read(b, p, n - p);
      if (k < 1) throw new EOFException();
      p += k;
    }
    return b;
  }

  @Override
  public void push(byte[] data, String remote) throws Exception {
    timed(
        () -> {
          try (AdbStream s = openStream("sync:")) {
            byte[] path = (remote + ",33204").getBytes(StandardCharsets.UTF_8);
            write(s, header("SEND", path.length));
            write(s, path);
            for (int i = 0; i < data.length; i += 32768) {
              int n = Math.min(32768, data.length - i);
              write(s, header("DATA", n));
              write(s, Arrays.copyOfRange(data, i, i + n));
            }
            write(s, header("DONE", (int) (System.currentTimeMillis() / 1000)));
            byte[] h = exact(s, 8);
            String id = new String(h, 0, 4, StandardCharsets.US_ASCII);
            int len = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (!id.equals("OKAY"))
              throw new IOException(
                  "Не удалось передать файл: " + new String(exact(s, len), StandardCharsets.UTF_8));
          }
          return null;
        });
    String digest = shell("sha256sum " + Rules.quote(remote));
    if (!digest.startsWith(Rules.hash(data) + " "))
      throw new IOException("Контрольная сумма файла на ТВ не совпала.");
  }

  @Override
  public byte[] pull(String remote, int limit) throws Exception {
    return timed(
        () -> {
          try (AdbStream s = openStream("sync:")) {
            byte[] path = remote.getBytes(StandardCharsets.UTF_8);
            write(s, header("RECV", path.length));
            write(s, path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (true) {
              byte[] h = exact(s, 8);
              String id = new String(h, 0, 4, StandardCharsets.US_ASCII);
              int len = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
              if (id.equals("DONE")) return out.toByteArray();
              if (id.equals("FAIL"))
                throw new IOException(new String(exact(s, len), StandardCharsets.UTF_8));
              if (!id.equals("DATA") || len < 0 || out.size() + len > limit)
                throw new IOException("Файл слишком большой или повреждён.");
              out.write(exact(s, len));
            }
          }
        });
  }
}
