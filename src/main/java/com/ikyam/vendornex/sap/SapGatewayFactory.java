package com.ikyam.vendornex.sap;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.sap.mock.MockServiceLayer;
import com.ikyam.vendornex.security.Crypto;
import com.ikyam.vendornex.tenant.Tenants;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out one Service Layer gateway per company (so each company keeps its own B1 session).
 * A cached gateway is replaced automatically when the company's connection settings change.
 * Companies in MOCK mode are pointed at the embedded Service Layer simulator.
 */
public final class SapGatewayFactory {

    private record Entry(String fingerprint, SapB1Gateway gateway) {}

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile MockServiceLayer mock;

    private SapGatewayFactory() {}

    public static void useMock(MockServiceLayer m) {
        mock = m;
    }

    /** Connection parameters (decrypted). */
    public record Conn(String mode, String baseUrl, String companyDb, String user, String password, boolean verifyTls) {}

    /** The company's default SAP DB (else its first active one). */
    public static SapB1Gateway forCompany(UUID companyId) {
        return forConnection(companyId, null);
    }

    /** Ids of the company's active SAP DBs, default first — what a master-data sync loops over. */
    public static List<UUID> activeConnectionIds(UUID companyId) {
        return Tenants.call(companyId, () -> Db.query("""
                SELECT connection_id FROM ik_sap_connection
                 WHERE is_active ORDER BY is_default DESC, sequence_no NULLS LAST, connection_name""")
                .stream().map(r -> r.uuid("connectionId")).toList());
    }

    /** Gateway for one SAP DB of the company; {@code connectionId == null} means the default one. */
    public static SapB1Gateway forConnection(UUID companyId, UUID connectionId) {
        Row c = Db.one("SELECT integration_mode, sl_verify_tls, schema_id FROM ik_onboarding_company WHERE onboard_company_id = ?", companyId);
        if (c == null) throw ApiException.notFound("Company");
        Row sap = Tenants.call(companyId, () -> Db.one("""
                SELECT connection_id, base_url, company_db_code, sap_username, sap_password FROM ik_sap_connection
                 WHERE is_active AND (?::uuid IS NULL OR connection_id = ?::uuid)
                 ORDER BY is_default DESC, sequence_no NULLS LAST, connection_name LIMIT 1""", connectionId, connectionId));
        if (sap == null && !"MOCK".equals(c.str("integrationMode")))
            throw ApiException.badRequest("This company has no active SAP connection configured");
        Conn conn = sap == null
                ? new Conn(c.str("integrationMode"), null, null, null, null, c.bool("slVerifyTls"))
                : new Conn(c.str("integrationMode"), sap.str("baseUrl"), sap.str("companyDbCode"), sap.str("sapUsername"),
                        Crypto.decrypt(sap.str("sapPassword")), c.bool("slVerifyTls"));
        String key = companyId + ":" + (sap == null ? "-" : sap.str("connectionId"));
        String fp = Objects.hash(conn.mode(), conn.baseUrl(), conn.companyDb(), conn.user(), conn.password(), conn.verifyTls()) + "";
        Entry e = CACHE.get(key);
        if (e != null && e.fingerprint().equals(fp)) return e.gateway();
        SapB1Gateway g = create(conn, companyId.toString());
        if (g instanceof ServiceLayerGateway sl) sl.trackSession(companyId, c.str("schemaId"));
        CACHE.put(key, new Entry(fp, g));
        return g;
    }

    /** Builds an uncached gateway, e.g. to test connection details before saving them. */
    public static SapB1Gateway create(Conn c, String tenantKey) {
        if ("MOCK".equals(c.mode())) {
            if (mock == null) throw ApiException.badRequest("The Service Layer simulator is not running on this server");
            String db = c.companyDb() == null || c.companyDb().isBlank() ? "MOCK_" + tenantKey.substring(0, 8) : c.companyDb();
            // The simulator only serves /b1s/v1, whatever version real companies default to.
            return new ServiceLayerGateway("http://127.0.0.1:" + mock.port() + "/b1s/v1", db, MockServiceLayer.USER, MockServiceLayer.PASSWORD, true);
        }
        System.out.println("-------inside SapB1Gateway create--------");
        return new ServiceLayerGateway(c.baseUrl(), c.companyDb(), c.user(), c.password(), c.verifyTls());
    }

    public static void evict(UUID companyId) {
        CACHE.keySet().removeIf(k -> k.startsWith(companyId + ":"));
    }
}
