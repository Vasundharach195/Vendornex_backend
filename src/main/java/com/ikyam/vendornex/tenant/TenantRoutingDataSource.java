package com.ikyam.vendornex.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * Makes every statement run with {@code search_path = "<tenant schema>", ik_vendor} for the
 * company in {@link TenantContext}, so unqualified table names resolve to that company's tables.
 *
 * <p>pgbouncer runs in TRANSACTION pooling mode, where a session-level {@code SET} would leak to
 * whichever client gets the server connection next. So the path is set with {@code SET LOCAL},
 * which only lasts for one transaction, and the physical connection is always kept in a
 * transaction: callers that believe they are in auto-commit mode get the same behaviour (each
 * update is committed at once), but through an explicit commit this class issues for them.
 */
public class TenantRoutingDataSource extends DelegatingDataSource {

    public TenantRoutingDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(obtainTargetDataSource().getConnection(username, password));
    }

    private static Connection wrap(Connection real) throws SQLException {
        real.setAutoCommit(false);
        ConnectionHandler handler = new ConnectionHandler(real);
        Connection proxy = (Connection) Proxy.newProxyInstance(
                TenantRoutingDataSource.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
        handler.proxy = proxy;
        return proxy;
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection real;
        private Connection proxy;
        /** What the caller asked for; the physical connection itself is never in auto-commit mode. */
        private boolean autoCommit = true;
        /** search_path set in the transaction currently open on {@code real}; null once it ends. */
        private String appliedPath;

        ConnectionHandler(Connection real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals":
                    return p == args[0];
                case "hashCode":
                    return System.identityHashCode(p);
                case "toString":
                    return "TenantConnection[" + real + "]";
                case "getAutoCommit":
                    return autoCommit;
                case "setAutoCommit": {
                    boolean wanted = (Boolean) args[0];
                    if (wanted != autoCommit) {
                        endTransaction(true); // JDBC: changing the mode commits the open transaction
                        autoCommit = wanted;
                    }
                    return null;
                }
                case "commit":
                    endTransaction(true);
                    return null;
                case "rollback":
                    if (args == null) {
                        endTransaction(false);
                        return null;
                    }
                    appliedPath = null; // a savepoint rollback may have undone the SET LOCAL
                    return TenantRoutingDataSource.invoke(real, method, args);
                case "close":
                    if (real.isClosed()) return null;
                    try {
                        if (autoCommit) endTransaction(true);
                    } finally {
                        real.close();
                    }
                    return null;
                case "createStatement":
                case "prepareStatement":
                case "prepareCall":
                    applyPath();
                    return wrapStatement((Statement) TenantRoutingDataSource.invoke(real, method, args));
                default:
                    return TenantRoutingDataSource.invoke(real, method, args);
            }
        }

        private void endTransaction(boolean commit) throws SQLException {
            appliedPath = null;
            if (commit) real.commit();
            else real.rollback();
        }

        /** Issues SET LOCAL when a new transaction starts or the thread switched company. */
        void applyPath() throws SQLException {
            String wanted = TenantContext.searchPath();
            if (wanted.equals(appliedPath)) return;
            try (Statement st = real.createStatement()) {
                st.execute("SET LOCAL search_path TO " + wanted);
            }
            appliedPath = wanted;
        }

        private Statement wrapStatement(Statement st) {
            Class<?> type = st instanceof CallableStatement ? CallableStatement.class
                    : st instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return (Statement) Proxy.newProxyInstance(TenantRoutingDataSource.class.getClassLoader(),
                    new Class<?>[]{type}, new StatementHandler(this, st));
        }
    }

    private static final class StatementHandler implements InvocationHandler {

        private static final Set<String> UPDATES = Set.of("executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch");

        private final ConnectionHandler conn;
        private final Statement real;

        StatementHandler(ConnectionHandler conn, Statement real) {
            this.conn = conn;
            this.real = real;
        }

        @Override
        public Object invoke(Object p, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) return conn.proxy;
            if (name.equals("equals")) return p == args[0];
            if (name.equals("hashCode")) return System.identityHashCode(p);
            if (!name.startsWith("execute")) return TenantRoutingDataSource.invoke(real, method, args);

            conn.applyPath();
            Object result;
            try {
                result = TenantRoutingDataSource.invoke(real, method, args);
            } catch (SQLException e) {
                // Auto-commit semantics: a failed statement must not poison later ones on this connection.
                if (conn.autoCommit) conn.endTransaction(false);
                throw e;
            }
            // Auto-commit semantics: an update is durable as soon as it returns. Queries stay open
            // until the connection is closed, so their result sets remain readable.
            if (conn.autoCommit && (UPDATES.contains(name) || Boolean.FALSE.equals(result))) conn.endTransaction(true);
            return result;
        }
    }
}
