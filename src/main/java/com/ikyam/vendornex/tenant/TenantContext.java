package com.ikyam.vendornex.tenant;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The Postgres schema of the company the current thread is working for. {@code null} means no
 * company (Super Admin, login, startup): only the {@code ik_vendor} schema is visible then, so a
 * query against a tenant table fails loudly instead of reading another company's data.
 */
public final class TenantContext {

    public static final String GLOBAL_SCHEMA = "ik_vendor";

    private static final Pattern SAFE = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final ThreadLocal<String> SCHEMA = new ThreadLocal<>();

    private TenantContext() {}

    public static String get() {
        return SCHEMA.get();
    }

    public static void set(String schema) {
        if (schema == null) {
            SCHEMA.remove();
            return;
        }
        if (!SAFE.matcher(schema).matches()) throw new IllegalArgumentException("Invalid tenant schema name: " + schema);
        SCHEMA.set(schema);
    }

    public static void clear() {
        SCHEMA.remove();
    }

    /** Runs {@code work} for the given schema, then restores whatever was set before. */
    public static <T> T call(String schema, Supplier<T> work) {
        String previous = SCHEMA.get();
        set(schema);
        try {
            return work.get();
        } finally {
            set(previous);
        }
    }

    public static void run(String schema, Runnable work) {
        call(schema, () -> {
            work.run();
            return null;
        });
    }

    /** The search_path a connection must use for the current thread. */
    static String searchPath() {
        String schema = SCHEMA.get();
        return schema == null ? "\"" + GLOBAL_SCHEMA + "\"" : "\"" + schema + "\", \"" + GLOBAL_SCHEMA + "\"";
    }
}
