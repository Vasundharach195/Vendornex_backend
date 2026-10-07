package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.tenant.TenantContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-model queries for the Super Admin company list/detail screens. Company and admin-user
 * columns come from the {@code ik_vendor} schema; the vendor count and last sync time are read from
 * each company's own schema.
 */
@Repository
public class CompanyQueries {

    private static final String SELECT = """
            SELECT c.id, c.name, c.industry, c.integration_mode, c.service_layer_base_url, c.sap_company_db, c.sl_username,
                   c.sl_verify_tls, c.connection_status, c.connection_message, c.sap_b1_version, c.last_tested_at, c.is_active,
                   c.onboarded_on, (c.sl_password_enc IS NOT NULL) AS has_password, c.schema_id,
                   a.id AS admin_id, a.name AS admin_name, a.email AS admin_email, a.status AS admin_status, a.invite_token AS admin_invite_token,
                   (SELECT count(*) FROM global_users u WHERE u.company_id = c.id) AS user_count
              FROM companies c
              LEFT JOIN LATERAL (SELECT * FROM global_users u WHERE u.company_id = c.id AND u.role = 'ADMIN' ORDER BY u.created_at LIMIT 1) a ON TRUE
            """;

    private static final String TENANT_STATS = """
            SELECT (SELECT count(*) FROM vendors v WHERE v.company_id = :id AND v.status = 'ACTIVE') AS active_vendors,
                   (SELECT max(finished_at) FROM sync_runs r WHERE r.company_id = :id AND r.status = 'SUCCESS') AS last_sync_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public CompanyQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> list() {
        List<Row> rows = jdbc.query(SELECT + " ORDER BY c.name", GenericRowMapper.INSTANCE);
        rows.forEach(this::addTenantStats);
        return rows;
    }

    public Row findById(UUID id) {
        List<Row> rows = jdbc.query(SELECT + " WHERE c.id = :id", new MapSqlParameterSource("id", id), GenericRowMapper.INSTANCE);
        if (rows.isEmpty()) return null;
        addTenantStats(rows.get(0));
        return rows.get(0);
    }

    public List<Row> syncRuns(UUID companyId, String schema) {
        if (schema == null) return List.of();
        return TenantContext.call(schema, () -> jdbc.query(
                "SELECT entity, status, records, error_detail, started_at, finished_at FROM sync_runs WHERE company_id = :id ORDER BY started_at DESC LIMIT 20",
                new MapSqlParameterSource("id", companyId), GenericRowMapper.INSTANCE));
    }

    private void addTenantStats(Row c) {
        String schema = c.str("schemaId");
        Object userCount = c.remove("userCount");
        Row stats = schema == null ? new Row() : TenantContext.call(schema, () -> jdbc.query(TENANT_STATS,
                new MapSqlParameterSource("id", c.uuid("id")), GenericRowMapper.INSTANCE).get(0));
        c.put("activeVendors", stats.getOrDefault("activeVendors", 0L));
        c.put("userCount", userCount);
        c.put("lastSyncAt", stats.get("lastSyncAt"));
    }
}
