package com.ikyam.vendornex.db;

import com.ikyam.vendornex.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;
import java.util.List;

/**
 * Minimal Flyway-compatible-style migration runner (V{n}__name.sql under db/migration).
 * Runs over a DIRECT PostgreSQL connection, never through pgbouncer, because it needs a
 * session-level advisory lock to stop two instances migrating at the same time.
 */
public final class Migrator {

    private static final Logger log = LoggerFactory.getLogger(Migrator.class);

    /** Add new migration files here in order. */
    private static final List<String> MIGRATIONS = List.of(
            "V1__init.sql"
    );

    private Migrator() {}

    public static void migrate(AppConfig cfg) throws Exception {
        try (Connection c = DriverManager.getConnection(cfg.migrationDbUrl, cfg.dbUser, cfg.dbPassword)) {
            try (Statement st = c.createStatement()) {
                st.execute("SELECT pg_advisory_lock(8675309)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migrations (
                            version     VARCHAR(100) PRIMARY KEY,
                            checksum    VARCHAR(64) NOT NULL,
                            applied_at  TIMESTAMPTZ NOT NULL DEFAULT now())""");
            }
            try {
                for (String file : MIGRATIONS) {
                    String sql = load(file);
                    String sum = sha256(sql);
                    String existing = null;
                    try (PreparedStatement ps = c.prepareStatement("SELECT checksum FROM schema_migrations WHERE version = ?")) {
                        ps.setString(1, file);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) existing = rs.getString(1);
                        }
                    }
                    if (existing != null) {
                        if (!existing.equals(sum)) {
                            throw new IllegalStateException("Migration " + file + " was modified after it was applied");
                        }
                        continue;
                    }
                    log.info("Applying migration {}", file);
                    c.setAutoCommit(false);
                    try (Statement st = c.createStatement()) {
                        st.execute(sql);
                        try (PreparedStatement ps = c.prepareStatement("INSERT INTO schema_migrations(version, checksum) VALUES (?, ?)")) {
                            ps.setString(1, file);
                            ps.setString(2, sum);
                            ps.executeUpdate();
                        }
                        c.commit();
                    } catch (SQLException e) {
                        c.rollback();
                        throw e;
                    } finally {
                        c.setAutoCommit(true);
                    }
                }
            } finally {
                try (Statement st = c.createStatement()) {
                    st.execute("SELECT pg_advisory_unlock(8675309)");
                }
            }
        }
    }

    private static String load(String file) throws IOException {
        try (InputStream in = Migrator.class.getResourceAsStream("/db/migration/" + file)) {
            if (in == null) throw new IOException("Missing migration resource " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
