package com.parsv2r.app;

import com.parsv2r.core.Json;

import java.util.Map;

/** One entry in the user's list: either a share link or a whole JSON config. */
final class SavedConfig {

    static final String KIND_LINK = "link";
    static final String KIND_JSON = "json";

    final String id;
    final String kind;
    final String label;
    final String subtitle;
    /** The text exactly as the user gave it, so it can always be shown back and re-parsed. */
    final String raw;

    SavedConfig(String id, String kind, String label, String subtitle, String raw) {
        this.id = id;
        this.kind = kind;
        this.label = label;
        this.subtitle = subtitle;
        this.raw = raw;
    }

    boolean isJson() {
        return KIND_JSON.equals(kind);
    }

    Map<String, Object> toJson() {
        Map<String, Object> o = Json.newObject();
        o.put("id", id);
        o.put("kind", kind);
        o.put("label", label);
        o.put("subtitle", subtitle);
        o.put("raw", raw);
        return o;
    }

    static SavedConfig fromJson(Map<String, Object> o) {
        if (o == null) return null;
        String id = Json.str(o.get("id"));
        String raw = Json.str(o.get("raw"));
        if (id == null || raw == null) return null;
        String kind = Json.str(o.get("kind"));
        String label = Json.str(o.get("label"));
        String subtitle = Json.str(o.get("subtitle"));
        return new SavedConfig(id, kind == null ? KIND_LINK : kind,
                label == null ? "" : label, subtitle == null ? "" : subtitle, raw);
    }
}
