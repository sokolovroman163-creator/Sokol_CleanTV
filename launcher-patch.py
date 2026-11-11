"""Apply Sokol's two isolated launcher integrations to a pristine local fork."""
from pathlib import Path

root = Path(__file__).resolve().parent / 'launcher/app/src/main/java/com/gathin/tvhome'
main = root / 'MainActivity.java'
source = main.read_text(encoding='utf-8')
old = '''    private void openVpnPill() {
        Intent i = vpnAppIntent();'''
new = '''    private void openVpnPill() {
        if ("io.github.romanvht.byedpi".equals(vpnApp)) {
            Intent toggle = new Intent();
            toggle.setComponent(new ComponentName(vpnApp,
                    "io.github.romanvht.byedpi.activities.ToggleActivity"));
            if (getPackageManager().resolveActivity(toggle, 0) != null) {
                open(toggle);
                return;
            }
        }
        Intent i = vpnAppIntent();'''
if old not in source:
    raise SystemExit('Unrecognized launcher source; no patch applied')
main.write_text(source.replace(old, new, 1), encoding='utf-8')
provider = root / 'ThemeProvider.java'
source = provider.read_text(encoding='utf-8')
marker = '    @Override public boolean onCreate() { return true; }'
addition = '''
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

'''
if marker not in source:
    raise SystemExit('Unrecognized ThemeProvider source')
provider.write_text(source.replace(marker, addition + marker), encoding='utf-8')
