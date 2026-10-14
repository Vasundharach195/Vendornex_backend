package com.ikyam.vendornex.tenant;

import java.util.UUID;

/**
 * The SAP DB (ik_sap_connection.connection_id) the current request works on: the one the user picked after login and
 * sent in the X-SAP-Connection-Id header. Null = the company's default DB. Like {@link TenantContext} it lives only for
 * one request thread; {@code TenantRoutingDataSource} hands it to Postgres as app.connection_id for every statement,
 * which is what ik_default_connection() reads.
 */
public final class ConnectionContext {

    private static final ThreadLocal<UUID> CONNECTION = new ThreadLocal<>();

    private ConnectionContext() {}

    public static UUID get() {
        return CONNECTION.get();
    }

    public static void set(UUID connectionId) {
        if (connectionId == null) CONNECTION.remove();
        else CONNECTION.set(connectionId);
    }

    public static void clear() {
        CONNECTION.remove();
    }
}
