package com.ikyam.vendornex.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.config.AppConfig;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.tenant.TenantRoutingDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Thin JDBC layer. The application pool (HikariCP) connects to pgbouncer, which runs in
 * TRANSACTION pooling mode, so:
 * <ul>
 *   <li>no session state is used (no SET, no advisory locks, no LISTEN/NOTIFY, no temp tables);</li>
 *   <li>server-side prepared statements are disabled with {@code prepareThreshold=0} in the JDBC URL;</li>
 *   <li>everything that must be atomic runs inside {@link #tx(TxWork)} on one connection.</li>
 * </ul>
 * A transaction is bound to the current thread, so repository calls made inside {@code tx}
 * automatically join it.
 */
public final class Db {

    private static HikariDataSource pool;
    /** {@link #pool} wrapped so every statement runs in the current company's schema. */
    private static DataSource ds;
    private static final ThreadLocal<Connection> TX = new ThreadLocal<>();
    /** Spring-managed DataSource (JPA/JdbcTemplate). Set at startup; see {@link #bindSpringDataSource}. */
    private static DataSource springDs;

    private Db() {}

    /**
     * Lets static {@code Db} calls made from inside a Spring {@code @Transactional} service
     * (e.g. {@code DocNumbers}, {@code Settings}, {@code SyncService.enqueue}) run on that
     * transaction's connection instead of a separate pool connection — so they see the service's
     * uncommitted rows and commit/roll back together with it.
     */
    public static void bindSpringDataSource(DataSource dataSource) {
        springDs = dataSource;
    }

    private static boolean inSpringTx() {
        return springDs != null && TransactionSynchronizationManager.isActualTransactionActive();
    }

    public static void init(AppConfig cfg) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.dbUrl);
        hc.setUsername(cfg.dbUser);
        hc.setPassword(cfg.dbPassword);
        hc.setMaximumPoolSize(cfg.dbPoolSize);
        hc.setMinimumIdle(Math.min(4, cfg.dbPoolSize));
        hc.setPoolName("vendornex-pgbouncer");
        hc.setConnectionTimeout(10_000);
        // pgbouncer closes idle server connections itself; keep client-side lifetime shorter than
        // pgbouncer's client_idle_timeout so Hikari never hands out a dead socket.
        hc.setMaxLifetime(25 * 60_000L);
        hc.setIdleTimeout(5 * 60_000L);
        hc.addDataSourceProperty("prepareThreshold", "0");
        hc.addDataSourceProperty("ApplicationName", "vendornex-api");
        pool = new HikariDataSource(hc);
        ds = new TenantRoutingDataSource(pool);
    }

    public static void close() {
        if (pool != null) pool.close();
    }

    // ------------------------------------------------------------------ transactions

    @FunctionalInterface
    public interface TxWork<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface TxVoid {
        void run() throws Exception;
    }

    public static <T> T tx(TxWork<T> work) {
        if (TX.get() != null || inSpringTx()) { // already inside a transaction (Db or Spring) — join it
            try {
                return work.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new DbException(e);
            }
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            TX.set(c);
            try {
                T result = work.run();
                c.commit();
                return result;
            } catch (Throwable t) {
                try { c.rollback(); } catch (SQLException ignored) {}
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error err) throw err;
                throw new DbException(t);
            } finally {
                TX.remove();
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public static void txv(TxVoid work) {
        tx(() -> { work.run(); return null; });
    }

    // ------------------------------------------------------------------ queries

    public static List<Row> query(String sql, Object... params) {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(c, ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return mapAll(rs);
                }
            }
        });
    }

    public static Row one(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static <T> T scalar(String sql, Class<T> type, Object... params) {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(c, ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return rs.getObject(1, type);
                }
            }
        });
    }

    public static int exec(String sql, Object... params) {
        return withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(c, ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /** Runs a multi-statement SQL script (DDL) with no parameters. */
    public static void script(String sql) {
        withConn(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    /** Runs a statement once per parameter set in a JDBC batch. */
    public static void batch(String sql, List<Object[]> paramSets) {
        if (paramSets.isEmpty()) return;
        withConn(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int n = 0;
                for (Object[] p : paramSets) {
                    bind(c, ps, p);
                    ps.addBatch();
                    if (++n % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ internals

    @FunctionalInterface
    private interface ConnWork<T> {
        T run(Connection c) throws SQLException;
    }

    private static <T> T withConn(ConnWork<T> work) {
        Connection txc = TX.get();
        try {
            if (txc != null) return work.run(txc);
            if (inSpringTx()) {
                Connection sc = DataSourceUtils.getConnection(springDs);
                try {
                    return work.run(sc);
                } finally {
                    DataSourceUtils.releaseConnection(sc, springDs);
                }
            }
            try (Connection c = ds.getConnection()) {
                return work.run(c);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    private static void bind(Connection c, PreparedStatement ps, Object[] params) throws SQLException {
        SqlBind.bind(c, ps, params);
    }

    private static List<Row> mapAll(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) keys[i] = camel(md.getColumnLabel(i + 1));
        List<Row> out = new ArrayList<>();
        while (rs.next()) {
            Row r = new Row();
            for (int i = 0; i < n; i++) r.put(keys[i], convert(rs.getObject(i + 1)));
            out.add(r);
        }
        return out;
    }

    /**
     * Normalizes a raw JDBC value into the same JSON-friendly shape used throughout the app
     * (UUID/date/timestamp as strings, trimmed BigDecimal, jsonb as a parsed tree, SQL arrays
     * as lists). Public so {@link GenericRowMapper} can reuse it for the new JdbcTemplate-based
     * read-model queries introduced during the JPA migration, guaranteeing byte-identical
     * response shapes whether a row came from this class or from Spring's JdbcTemplate.
     */
    public static Object convert(Object v) throws SQLException {
        if (v == null) return null;
        if (v instanceof UUID u) return u.toString();
        if (v instanceof Timestamp ts) return ts.toInstant().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof BigDecimal b) return trimDecimal(b);
        if (v instanceof Array a) {
            Object arr = a.getArray();
            if (arr instanceof Object[] oa) {
                List<Object> l = new ArrayList<>(Arrays.asList(oa));
                l.replaceAll(o -> o instanceof UUID u ? u.toString() : o);
                return l;
            }
            return arr;
        }
        if (v instanceof PGobject pg) {
            if ("jsonb".equals(pg.getType()) || "json".equals(pg.getType())) {
                return pg.getValue() == null ? null : Json.parse(pg.getValue());
            }
            return pg.getValue();
        }
        return v;
    }

    /**
     * Same trailing-zero stripping every numeric column value has always gone through on its way
     * into a JSON response. When a JPA-mapped entity field feeds a response directly (bypassing
     * {@link #convert}/{@link GenericRowMapper} entirely), it must still be run through this —
     * Postgres NUMERIC(p,s) values round-trip through JDBC/Hibernate at their declared scale (e.g.
     * "12.0000"), which without this step would leak trailing zeros the original Db-based responses
     * never had.
     */
    public static BigDecimal trimDecimal(BigDecimal b) {
        return b == null ? null : (b.scale() > 0 ? b.stripTrailingZeros() : b);
    }

    public static String camel(String col) {
        StringBuilder sb = new StringBuilder(col.length());
        boolean up = false;
        for (char ch : col.toCharArray()) {
            if (ch == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = false;
        }
        return sb.toString();
    }

    public static final class DbException extends RuntimeException {
        public DbException(Throwable cause) { super(cause.getMessage(), cause); }

        public String sqlState() {
            return getCause() instanceof SQLException se ? se.getSQLState() : null;
        }
    }
}
