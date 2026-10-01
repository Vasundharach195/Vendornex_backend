package com.ikyam.vendornex.config;

/**
 * All runtime configuration comes from environment variables (12-factor).
 * Defaults are for local development only.
 */
public final class AppConfig {

    /** JDBC URL used by the application — points at pgbouncer (transaction pooling). */
    public final String dbUrl;
    /** JDBC URL used only by the migration runner — points straight at PostgreSQL (needs session features). */
    public final String migrationDbUrl;
    public final String dbUser;
    public final String dbPassword;
    public final int dbPoolSize;

    public final int httpPort;
    public final String jwtSecret;
    public final int jwtTtlMinutes;
    /** Base64 AES-256 key used to encrypt Service Layer passwords at rest. */
    public final String secretKeyB64;
    public final String documentStoreDir;
    public final String publicAppUrl;
    public final boolean seedDemo;
    public final boolean schedulerEnabled;
    public final String corsOrigin;

    private AppConfig() {
        dbUrl = env("DB_URL", "jdbc:postgresql://localhost:6432/vendornex?prepareThreshold=0");
        migrationDbUrl = env("MIGRATION_DB_URL", "jdbc:postgresql://localhost:5432/vendornex");
        dbUser = env("DB_USER", "vendornex");
        dbPassword = env("DB_PASSWORD", "vendornex");
        dbPoolSize = Integer.parseInt(env("DB_POOL_SIZE", "20"));
        httpPort = Integer.parseInt(env("HTTP_PORT", "8080"));
        jwtSecret = env("JWT_SECRET", "dev-only-change-me-dev-only-change-me-0123456789");
        jwtTtlMinutes = Integer.parseInt(env("JWT_TTL_MINUTES", "480"));
        secretKeyB64 = env("APP_SECRET_KEY", "");
        documentStoreDir = env("DOCUMENT_STORE_DIR", "./data/documents");
        publicAppUrl = env("PUBLIC_APP_URL", "http://localhost:5173");
        seedDemo = Boolean.parseBoolean(env("SEED_DEMO", "true"));
        schedulerEnabled = Boolean.parseBoolean(env("SCHEDULER_ENABLED", "true"));
        corsOrigin = env("CORS_ORIGIN", "*");
    }

    private static final AppConfig INSTANCE = new AppConfig();

    public static AppConfig get() { return INSTANCE; }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }
}
