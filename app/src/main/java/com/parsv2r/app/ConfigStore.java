package com.parsv2r.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.parsv2r.core.Json;
import com.parsv2r.core.JsonImport;
import com.parsv2r.core.ProxyConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The saved list of servers, kept in shared preferences as a JSON array.
 *
 * <p>Each entry keeps the original text. Re-parsing on demand costs nothing and means an entry
 * saved by an older build is never stranded by a change to how configs are read.
 */
final class ConfigStore {

    private static final String PREFS = "parsv2r";
    private static final String KEY_LIST = "configs";
    private static final String KEY_ACTIVE = "active";

    private final SharedPreferences prefs;

    ConfigStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<SavedConfig> all() {
        List<SavedConfig> out = new ArrayList<>();
        String raw = prefs.getString(KEY_LIST, "");
        if (raw.isEmpty()) return out;
        try {
            List<Object> array = Json.arr(Json.parse(raw));
            if (array == null) return out;
            for (Object o : array) {
                SavedConfig c = SavedConfig.fromJson(Json.obj(o));
                if (c != null) out.add(c);
            }
        } catch (RuntimeException ignored) {
            // A corrupt store should cost the list, never the app.
        }
        return out;
    }

    private void save(List<SavedConfig> list) {
        List<Object> array = Json.newArray();
        for (SavedConfig c : list) array.add(c.toJson());
        prefs.edit().putString(KEY_LIST, Json.write(array)).apply();
    }

    SavedConfig active() {
        String id = prefs.getString(KEY_ACTIVE, "");
        if (id.isEmpty()) return null;
        for (SavedConfig c : all()) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    void setActive(String id) {
        prefs.edit().putString(KEY_ACTIVE, id == null ? "" : id).apply();
    }

    void remove(String id) {
        List<SavedConfig> list = all();
        list.removeIf(c -> c.id.equals(id));
        save(list);
        SavedConfig current = active();
        if (current == null && !list.isEmpty()) setActive(list.get(0).id);
    }

    void clear() {
        prefs.edit().remove(KEY_LIST).remove(KEY_ACTIVE).apply();
    }

    /** How many entries a piece of pasted text added. Duplicates already stored are skipped. */
    int add(String text) throws JsonImport.InvalidConfigException {
        if (text == null || text.trim().isEmpty()) {
            throw new JsonImport.InvalidConfigException("empty");
        }
        List<SavedConfig> list = all();
        Set<String> existing = new LinkedHashSet<>();
        for (SavedConfig c : list) existing.add(c.raw.trim());

        int added = 0;
        if (JsonImport.looksLikeJson(text)) {
            // Validate before saving, so a config that can never run is refused at the door
            // rather than at connect time.
            JsonImport.Result result = JsonImport.normalise(text, "warning");
            if (existing.add(text.trim())) {
                list.add(new SavedConfig(UUID.randomUUID().toString(), SavedConfig.KIND_JSON,
                        result.label, "JSON", text.trim()));
                added++;
            }
        } else {
            List<ProxyConfig> parsed = ProxyConfig.parseDocument(text);
            if (parsed.isEmpty()) {
                throw new JsonImport.InvalidConfigException("no configs found");
            }
            for (ProxyConfig p : parsed) {
                String link = originalLine(text, p);
                if (!existing.add(link.trim())) continue;
                list.add(new SavedConfig(UUID.randomUUID().toString(), SavedConfig.KIND_LINK,
                        p.displayName(), p.protocol + " · " + p.host + ":" + p.port, link.trim()));
                added++;
            }
        }
        if (added > 0) {
            save(list);
            if (active() == null && !list.isEmpty()) setActive(list.get(list.size() - added).id);
        }
        return added;
    }

    /**
     * Finds the line the parsed config came from, so what is stored is what the user pasted.
     * Falls back to re-serialising nothing at all -- if the line cannot be found, the whole
     * document is kept, which still re-parses to the same server.
     */
    private String originalLine(String document, ProxyConfig target) {
        for (String line : document.split("\\r?\\n")) {
            ProxyConfig c = ProxyConfig.parse(line);
            if (c != null && c.identity().equals(target.identity())) return line.trim();
        }
        // The document may have been a base64 subscription; keep something that re-parses.
        return document.trim();
    }

    /** Builds the Xray config for an entry. Throws if it cannot be used. */
    String buildXrayConfig(SavedConfig entry, String logLevel, boolean bypassLan)
            throws JsonImport.InvalidConfigException {
        if (entry == null) throw new JsonImport.InvalidConfigException("nothing selected");
        if (entry.isJson()) {
            return JsonImport.normalise(entry.raw, logLevel).json;
        }
        ProxyConfig c = ProxyConfig.parse(entry.raw);
        if (c == null) {
            List<ProxyConfig> parsed = ProxyConfig.parseDocument(entry.raw);
            if (parsed.isEmpty()) throw new JsonImport.InvalidConfigException("this entry no longer parses");
            c = parsed.get(0);
        }
        if (!com.parsv2r.core.XrayConfig.supports(c)) {
            throw new JsonImport.InvalidConfigException("unsupported protocol: " + c.protocol);
        }
        return com.parsv2r.core.XrayConfig.build(c, logLevel, bypassLan);
    }

    Map<String, Object> debugSnapshot() {
        Map<String, Object> o = Json.newObject();
        o.put("count", all().size());
        SavedConfig a = active();
        o.put("active", a == null ? null : a.label);
        return o;
    }
}
