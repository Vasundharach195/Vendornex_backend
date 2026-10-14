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
            SELECT c.onboard_company_id AS id, c.company_name AS name, c.industry_type AS industry, c.integration_mode,
                   c.sl_verify_tls, c.connection_status, c.connection_message, c.sap_b1_version, c.last_tested_at, c.is_active,
                   c.onboarded_on, c.schema_id,
                            c.company_phone_no, c.company_street, c.company_city, c.company_state, c.company_zipcode, c.company_website,
                   c.headoffice_location, c.company_gst, c.registration_number, c.user_name, c.email, c.user_phone_no,
                   c.is_approved, c.email_id, c.smtp_server, c.smtp_port, c.company_logo,
                   (c.email_pwd IS NOT NULL) AS has_email_pwd,
                   a.id AS admin_id, a.name AS admin_name, a.email AS admin_email, a.status AS admin_status, a.invite_token AS admin_invite_token,
                   (SELECT count(*) FROM ik_global_users u WHERE u.company_id = c.onboard_company_id) AS user_count
              FROM ik_onboarding_company c
              LEFT JOIN LATERAL (SELECT * FROM ik_global_users u WHERE u.company_id = c.onboard_company_id AND u.role = 'ADMIN' ORDER BY u.created_at LIMIT 1) a ON TRUE
            """;

    private static final String TENANT_STATS = """
            SELECT (SELECT count(*) FROM ik_vendors v WHERE v.company_id = :id AND v.status = 'ACTIVE') AS active_vendors,
                   (SELECT max(last_sync_at) FROM ik_success) AS last_sync_at""";

    private final NamedParameterJdbcTemplate jdbc;

    public CompanyQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> list() {
        List<Row> rows = jdbc.query(SELECT + " ORDER BY c.company_name", GenericRowMapper.INSTANCE);
        rows.forEach(this::addTenantStats);
        return rows;
    }

    public Row findById(UUID id) {
        List<Row> rows = jdbc.query(SELECT + " WHERE c.onboard_company_id = :id", new MapSqlParameterSource("id", id), GenericRowMapper.INSTANCE);
        if (rows.isEmpty()) return null;
        addTenantStats(rows.get(0));
        return rows.get(0);
    }

    public List<Row> syncRuns(UUID companyId, String schema) {
        if (schema == null) return List.of();
        return TenantContext.call(schema, () -> jdbc.query(
                "SELECT entity, status, records, error_detail, started_at, finished_at FROM ik_sync_runs WHERE company_id = :id ORDER BY started_at DESC LIMIT 20",
                new MapSqlParameterSource("id", companyId), GenericRowMapper.INSTANCE));
    }

    /** Every SAP DB of the company, default first (never the password) — for the Super Admin company screen. */
    public List<Row> sapConnections(String schema) {
        if (schema == null) return List.of();
        return TenantContext.call(schema, () -> jdbc.query("""
                SELECT connection_id, connection_name, company_db_code, base_url, sap_username, is_default, is_active,
                       sequence_no, last_health_check_at, last_health_status
                  FROM ik_sap_connection ORDER BY is_default DESC, sequence_no NULLS LAST, connection_name""",
                GenericRowMapper.INSTANCE));
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
