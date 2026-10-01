package com.ikyam.vendornex.web;

/** Query-parameter helpers mirroring Req.query/queryInt exactly. */
public final class QueryParams {
    private QueryParams() {}

    /** Blank-to-null, trimmed — mirrors {@code Req.query(name)}. */
    public static String orNull(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** Mirrors {@code Req.query(name, def)}. */
    public static String orDefault(String v, String def) {
        String r = orNull(v);
        return r == null ? def : r;
    }

    /**
     * Mirrors {@code Req.queryInt(name, def, max)}: parses, clamps a successfully-parsed
     * value to [0, max]; on a missing/blank/unparsable value, silently returns {@code def}
     * un-clamped (no error).
     */
    public static int clampInt(String raw, int def, int max) {
        if (raw == null || raw.isBlank()) return def;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < 0) return 0;
            return Math.min(v, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
