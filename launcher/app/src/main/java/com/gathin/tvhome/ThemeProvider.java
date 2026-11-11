package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets tools/theme.sh write the theme files into {@link Config#dir} on firmware where the adb shell is
 * locked out of Android/data (TCL G07, Android 11). Only the files {@link ThemeFiles} allows, and only for
 * callers holding WRITE_SECURE_SETTINGS, which the adb shell has and ordinary apps cannot get.
 *
 *   content write --uri content://com.gathin.tvhome.theme/config.json < /data/local/tmp/config.json
 *   content write --uri content://com.gathin.tvhome.theme/wallpapers/dusk.jpg < /data/local/tmp/dusk.jpg
 *   content delete --uri content://com.gathin.tvhome.theme/config.json
 *   content delete --uri content://com.gathin.tvhome.theme/wallpapers      (every image in the collection)
 */
public class ThemeProvider extends ContentProvider {

    private File file(Uri uri) throws FileNotFoundException {
        String path = ThemeFiles.path(uri.getPathSegments());
        File dir = Config.dir(getContext());
        if (dir == null || path == null) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(dir, path);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (mode.contains("w")) {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File dir = Config.dir(getContext());
        if (dir != null && ThemeFiles.isCollection(uri.getPathSegments())) {
            File[] images = new File(dir, ThemeFiles.WALLPAPERS).listFiles((d, name) -> ThemeFiles.collectionImage(name));
            int n = 0;
            if (images != null) for (File f : images) if (f.delete()) n++;
            return n;
        }
        try {
            return file(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }


    // Sokol desktop backups. Permission is checked explicitly because ContentProvider.call
    // does not automatically enforce the provider's read/write permission.
    @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
        if (!"sokol_snapshot".equals(method) && !"sokol_restore".equals(method)) {
            return super.call(method, arg, extras);
        }
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS, "Sokol backup requires shell access");
        android.os.Bundle result = new android.os.Bundle();
        try {
            if ("sokol_snapshot".equals(method)) {
                org.json.JSONObject all = new org.json.JSONObject();
                File directory = new File(getContext().getApplicationInfo().dataDir, "shared_prefs");
                File[] files = directory.listFiles((dir, name) -> name.endsWith(".xml"));
                if (files != null) for (File file : files) {
                    String name = file.getName().substring(0, file.getName().length() - 4);
                    android.content.SharedPreferences preferences = getContext().getSharedPreferences(name, 0);
                    org.json.JSONObject values = new org.json.JSONObject();
                    for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                        Object value = entry.getValue();
                        org.json.JSONObject item = new org.json.JSONObject();
                        item.put("type", value instanceof java.util.Set ? "set" : value.getClass().getSimpleName());
                        item.put("value", value instanceof java.util.Set
                                ? new org.json.JSONArray((java.util.Collection<?>) value) : value);
                        values.put(entry.getKey(), item);
                    }
                    all.put(name, values);
                }
                byte[] data = all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (data.length > 100000) throw new IllegalArgumentException("Preferences exceed backup limit");
                result.putString("snapshot", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
            } else {
                if (arg == null || arg.length() > 150000) throw new IllegalArgumentException("Invalid backup size");
                org.json.JSONObject all = new org.json.JSONObject(new String(
                        android.util.Base64.decode(arg, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8));
                java.util.Iterator<String> names = all.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    if (!name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid preferences name");
                    org.json.JSONObject values = all.getJSONObject(name);
                    android.content.SharedPreferences.Editor editor = getContext().getSharedPreferences(name, 0).edit().clear();
                    java.util.Iterator<String> keys = values.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        org.json.JSONObject item = values.getJSONObject(key);
                        String type = item.getString("type");
                        switch (type) {
                            case "String": editor.putString(key, item.getString("value")); break;
                            case "Boolean": editor.putBoolean(key, item.getBoolean("value")); break;
                            case "Integer": editor.putInt(key, item.getInt("value")); break;
                            case "Long": editor.putLong(key, item.getLong("value")); break;
                            case "Float": editor.putFloat(key, (float) item.getDouble("value")); break;
                            case "set":
                                org.json.JSONArray array = item.getJSONArray("value");
                                java.util.Set<String> set = new java.util.HashSet<>();
                                for (int n = 0; n < array.length(); n++) set.add(array.getString(n));
                                editor.putStringSet(key, set);
                                break;
                            default: throw new IllegalArgumentException("Unsupported preferences type");
                        }
                    }
                    if (!editor.commit()) throw new IllegalStateException("Preferences write failed");
                }
                result.putBoolean("restored", true);
            }
        } catch (Exception error) {
            result.putString("error", error.getMessage());
        }
        return result;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
