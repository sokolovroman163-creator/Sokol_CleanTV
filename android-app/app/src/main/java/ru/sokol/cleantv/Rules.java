package ru.sokol.cleantv;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

final class Rules {
  static final String HOME = "com.gathin.tvhome", DPI = "io.github.romanvht.byedpi";
  static final Set<String> STOCK =
      Set.of(
          "com.google.android.tvlauncher",
          "com.google.android.apps.tv.launcherx",
          "com.google.android.leanbacklauncher",
          "com.mitv.tvhome");
  static final Set<String> OPTIONAL =
      Set.of(
          "com.netflix.ninja",
          "com.amazon.amazonvideo.livingroom",
          "com.google.android.youtube.tv",
          "com.google.android.youtube.tvmusic",
          "com.google.android.play.games",
          "com.google.android.music",
          "com.google.android.videos",
          "com.google.android.feedback",
          "com.google.android.tvrecommendations",
          "ru.haier.evo",
          "com.haier.apps.evoSmart",
          "com.haier.apps.shahid",
          "tv.anoki.acr.controller");
  static final Map<String, String> NAMES =
      Map.ofEntries(
          Map.entry("com.google.android.youtube.tv", "YouTube"),
          Map.entry("com.vk.tv", "VK Видео"),
          Map.entry("ru.rutube.app", "Rutube"),
          Map.entry("ru.kinopoisk.tv", "Кинопоиск"),
          Map.entry("mobi.zona", "Зона"),
          Map.entry("com.android.vending", "Google Play"),
          Map.entry(HOME, "Sokol CleanTV Home"),
          Map.entry(DPI, "ByeByeDPI"),
          Map.entry("com.netflix.ninja", "Netflix"),
          Map.entry("com.amazon.amazonvideo.livingroom", "Prime Video"),
          Map.entry("com.google.android.youtube.tvmusic", "YouTube Music"));

  static boolean pkg(String s) {
    return s != null && s.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
  }

  static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  static String[] endpoint(String text) {
    String[] parts = text.trim().split(":", -1);
    if (parts.length > 2)
      throw new IllegalArgumentException(
          "Введите IPv4-адрес телевизора, например 192.168.1.50:5555.");
    String[] octets = parts[0].split("\\.", -1);
    if (octets.length != 4)
      throw new IllegalArgumentException("IP-адрес должен содержать четыре числа через точку.");
    int[] a = new int[4];
    try {
      for (int i = 0; i < 4; i++) {
        if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new NumberFormatException();
        a[i] = Integer.parseInt(octets[i]);
        if (a[i] > 255) throw new NumberFormatException();
      }
      int port = parts.length == 2 ? Integer.parseInt(parts[1]) : 5555;
      boolean local =
          a[0] == 10
              || a[0] == 172 && a[1] >= 16 && a[1] <= 31
              || a[0] == 192 && a[1] == 168
              || a[0] == 169 && a[1] == 254;
      if (!local || port < 1 || port > 65535) throw new NumberFormatException();
      return new String[] {parts[0], Integer.toString(port)};
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Нужен локальный IP-адрес и порт от 1 до 65535.");
    }
  }

  static String protect(String p) {
    if (STOCK.contains(p)
        || Set.of(
                HOME,
                DPI,
                "android",
                "com.android.vending",
                "com.google.android.gms",
                "com.google.android.gsf",
                "com.google.android.katniss",
                "com.google.android.tts",
                "com.google.android.apps.mediashell",
                "com.google.android.tv.remote.service",
                "com.google.android.tungsten.setupwraith",
                "com.cltv.mal")
            .contains(p)) return "Главный экран или важная функция ТВ";
    for (String prefix :
        new String[] {
          "com.android.",
          "android.",
          "com.mediatek.",
          "com.cvte.",
          "com.mstar.",
          "com.droidlogic.",
          "com.mitv.",
          "mitv.",
          "com.xiaomi.mitv."
        }) if (p.startsWith(prefix)) return "Системный компонент телевизора";
    if (p.startsWith("com.google.") && !OPTIONAL.contains(p)) return "Служба Google или Android";
    for (String word :
        new String[] {
          "factory", "setup", "bluetooth", "keyboard", "inputmethod", "settings", "ota", "updat"
        })
      if (p.toLowerCase(Locale.ROOT).contains(word))
        return "Оборудование, настройки или обновления";
    return "";
  }

  static void safeDump(String dump) {
    state(dump);
    if (Pattern.compile(
            "TV_INPUT_HARDWARE|android\\.media\\.tv\\.TvInputService|android\\.intent\\.category\\.HOME|android\\.net\\.VpnService|GlobalKeyReceiver|VIEW_INPUTS|android\\.settings\\.")
        .matcher(dump)
        .find())
      throw new IllegalArgumentException(
          "Это приложение обслуживает главный экран, пульт, ТВ-входы или VPN.");
  }

  static int state(String dump) {
    Matcher m =
        Pattern.compile("User 0:[^\\n]*installed=true[^\\n]*enabled=([0-4])(?:\\s|$)")
            .matcher(dump);
    if (!m.find())
      throw new IllegalArgumentException(
          "Не удалось проверить состояние приложения для пользователя 0.");
    return Integer.parseInt(m.group(1));
  }

  static Map<String, String> props(String text) {
    Map<String, String> p = new HashMap<>();
    Matcher m =
        Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$", Pattern.MULTILINE)
            .matcher(text.replace("\r", ""));
    while (m.find()) p.put(m.group(1), m.group(2));
    return p;
  }

  static Set<String> packages(String text) {
    Set<String> set = new TreeSet<>();
    for (String line : text.split("\\r?\\n"))
      if (line.startsWith("package:")) {
        String p =
            line.substring(line.lastIndexOf('=') >= 0 ? line.lastIndexOf('=') + 1 : 8).trim();
        if (pkg(p)) set.add(p);
      }
    return set;
  }

  static String component(String text) {
    String result = "";
    for (String line : text.split("\\r?\\n"))
      if (line.trim().matches("[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+")) result = line.trim();
    if (result.isEmpty() || result.contains("ResolverActivity"))
      throw new IllegalArgumentException(
          "Не удалось определить основной главный экран телевизора.");
    return result;
  }

  static String identity(Map<String, String> p, String id) throws Exception {
    String serial = p.getOrDefault("ro.serialno", "");
    if (serial.isEmpty()) serial = p.getOrDefault("ro.boot.serialno", "");
    if (serial.isEmpty()) serial = id.trim();
    if (Set.of("", "null", "unknown", "0123456789ABCDEF").contains(serial))
      throw new IllegalArgumentException(
          "Телевизор не сообщил устойчивый идентификатор для восстановления.");
    return hash(
        (p.getOrDefault("ro.product.manufacturer", "")
                + "|"
                + p.getOrDefault("ro.product.device", "")
                + "|"
                + serial)
            .getBytes(StandardCharsets.UTF_8));
  }

  static String hash(byte[] bytes) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder b = new StringBuilder();
    for (byte v : d) b.append(String.format(Locale.ROOT, "%02x", v));
    return b.toString();
  }

  static String stateAction(int s) {
    return new String[] {"default-state", "enable", "disable", "disable-user", "disable-until-used"}
        [s];
  }

  static String fullComponent(String s) {
    String[] p = s.split("/", 2);
    return p[0] + "/" + (p[1].startsWith(".") ? p[0] + p[1] : p[1]);
  }

  static boolean shellDone(String text, String marker) {
    int pos = text.lastIndexOf("\n" + marker + ":");
    return pos >= 0 && text.substring(pos + marker.length() + 2).matches("[0-9]+\\n");
  }
}
