package ru.sokol.cleantv;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

final class IO {
  static byte[] bytes(InputStream in, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[16384];
    int n;
    while ((n = in.read(b)) != -1) {
      if (out.size() + n > limit) throw new IOException("Файл превышает допустимый размер.");
      out.write(b, 0, n);
    }
    return out.toByteArray();
  }

  static String text(File f) throws IOException {
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
  }

  static List<String> keys(JSONObject o) {
    List<String> keys = new ArrayList<>();
    Iterator<String> it = o.keys();
    while (it.hasNext()) keys.add(it.next());
    return keys;
  }
}
