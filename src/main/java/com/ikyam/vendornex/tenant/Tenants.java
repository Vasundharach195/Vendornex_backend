package com.ikyam.vendornex.tenant;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Company → Postgres schema lookup, schema provisioning at onboarding, and helpers to run code
 * for one company (or for every company) outside a logged-in request: Super Admin screens,
 * schedulers, background jobs.
 */
public final class Tenants {

    private static final Logger log = LoggerFactory.getLogger(Tenants.class);
    private static final String TENANT_DDL = "/db/tenant/T1__init.sql";
    /** A company's schema never changes once set, so the lookup is cached for the process lifetime. */
    private static final Map<UUID, String> SCHEMAS = new ConcurrentHashMap<>();

    private Tenants() {}

    public static String schemaOf(UUID companyId) {
        String cached = SCHEMAS.get(companyId);
        if (cached != null) return cached;
        String schema = Db.scalar("SELECT schema_id FROM companies WHERE id = ?", String.class, companyId);
        if (schema == null) throw new IllegalStateException("Company " + companyId + " has no tenant schema");
        SCHEMAS.put(companyId, schema);
        return schema;
    }

    public static <T> T call(UUID companyId, Supplier<T> work) {
        return TenantContext.call(schemaOf(companyId), work);
    }

    public static void run(UUID companyId, Runnable work) {
        TenantContext.run(schemaOf(companyId), work);
    }

    /** Runs {@code work} once per onboarded company, each inside that company's schema. One failing company never stops the rest. */
    public static void forEach(Consumer<UUID> work) {
        List<Row> companies = TenantContext.call(null,
                () -> Db.query("SELECT id, schema_id FROM companies WHERE schema_id IS NOT NULL ORDER BY created_at"));
        for (Row c : companies) {
            UUID id = c.uuid("id");
            try {
                TenantContext.run(c.str("schemaId"), () -> work.accept(id));
            } catch (Exception e) {
                log.warn("Tenant job failed for company {} ({}): {}", id, c.str("schemaId"), e.getMessage());
            }
        }
    }

    /**
     * Creates the next tenant schema (vnx_c00001, vnx_c00002, ...) with all tenant tables and
     * returns its name. Runs in the caller's transaction, so a failed onboarding leaves no
     * half-built schema behind. The caller stores the name in companies.schema_id.
     */
    public static String createSchema() {
        return Db.tx(() -> {
            long n = Db.scalar("SELECT nextval('ik_vendor.tenant_schema_seq')", Long.class);
            String schema = String.format("vnx_c%05d", n);
            Db.script("CREATE SCHEMA \"" + schema + "\"");
            String ddl = loadTenantDdl();
            TenantContext.run(schema, () -> Db.script(ddl));
            log.info("Created tenant schema {}", schema);
            return schema;
        });
    }

    private static String loadTenantDdl() throws IOException {
        try (InputStream in = Tenants.class.getResourceAsStream(TENANT_DDL)) {
            if (in == null) throw new IOException("Missing tenant schema script " + TENANT_DDL);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
