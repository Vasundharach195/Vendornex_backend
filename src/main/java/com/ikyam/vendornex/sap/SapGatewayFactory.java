package com.ikyam.vendornex.sap;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.sap.mock.MockServiceLayer;
import com.ikyam.vendornex.security.Crypto;

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

    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static volatile MockServiceLayer mock;

    private SapGatewayFactory() {}

    public static void useMock(MockServiceLayer m) {
        mock = m;
    }

    /** Connection parameters (decrypted). */
    public record Conn(String mode, String baseUrl, String companyDb, String user, String password, boolean verifyTls) {}

    public static SapB1Gateway forCompany(UUID companyId) {
        Row c = Db.one("SELECT * FROM companies WHERE id = ?", companyId);
        if (c == null) throw ApiException.notFound("Company");
        Conn conn = new Conn(c.str("integrationMode"), c.str("serviceLayerBaseUrl"), c.str("sapCompanyDb"), c.str("slUsername"),
                c.str("slPasswordEnc") == null ? null : Crypto.decrypt(c.str("slPasswordEnc")), c.bool("slVerifyTls"));
        String fp = Objects.hash(conn.mode(), conn.baseUrl(), conn.companyDb(), conn.user(), conn.password(), conn.verifyTls()) + "";
        Entry e = CACHE.get(companyId);
        if (e != null && e.fingerprint().equals(fp)) return e.gateway();
        SapB1Gateway g = create(conn, companyId.toString());
        CACHE.put(companyId, new Entry(fp, g));
        return g;
    }

    /** Builds an uncached gateway, e.g. to test connection details before saving them. */
    public static SapB1Gateway create(Conn c, String tenantKey) {
        if ("MOCK".equals(c.mode())) {
            if (mock == null) throw ApiException.badRequest("The Service Layer simulator is not running on this server");
            String db = c.companyDb() == null || c.companyDb().isBlank() ? "MOCK_" + tenantKey.substring(0, 8) : c.companyDb();
            return new ServiceLayerGateway("http://127.0.0.1:" + mock.port(), db, MockServiceLayer.USER, MockServiceLayer.PASSWORD, true);
        }
        return new ServiceLayerGateway(c.baseUrl(), c.companyDb(), c.user(), c.password(), c.verifyTls());
    }

    public static void evict(UUID companyId) {
        CACHE.remove(companyId);
    }
}
