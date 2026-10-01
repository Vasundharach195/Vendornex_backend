package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-model queries for the Super Admin company list/detail screens — a multi-table LATERAL join
 * plus correlated count/max subqueries, none of which map onto a single entity, so per the
 * migration's query-routing rule this stays on {@code NamedParameterJdbcTemplate} with the exact
 * SQL text {@code CompanyController} used before conversion.
 */
@Repository
public class CompanyQueries {

    private static final String SELECT = """
            SELECT c.id, c.name, c.industry, c.integration_mode, c.service_layer_base_url, c.sap_company_db, c.sl_username,
                   c.sl_verify_tls, c.connection_status, c.connection_message, c.sap_b1_version, c.last_tested_at, c.is_active,
                   c.onboarded_on, (c.sl_password_enc IS NOT NULL) AS has_password,
                   a.id AS admin_id, a.name AS admin_name, a.email AS admin_email, a.status AS admin_status, a.invite_token AS admin_invite_token,
                   (SELECT count(*) FROM vendors v WHERE v.company_id = c.id AND v.status = 'ACTIVE') AS active_vendors,
                   (SELECT count(*) FROM users u WHERE u.company_id = c.id) AS user_count,
                   (SELECT max(finished_at) FROM sync_runs r WHERE r.company_id = c.id AND r.status = 'SUCCESS') AS last_sync_at
              FROM companies c
              LEFT JOIN LATERAL (SELECT * FROM users u WHERE u.company_id = c.id AND u.role = 'ADMIN' ORDER BY u.created_at LIMIT 1) a ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CompanyQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> list() {
        return jdbc.query(SELECT + " ORDER BY c.name", GenericRowMapper.INSTANCE);
    }

    public Row findById(UUID id) {
        List<Row> rows = jdbc.query(SELECT + " WHERE c.id = :id", new MapSqlParameterSource("id", id), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Row> syncRuns(UUID companyId) {
        return jdbc.query(
                "SELECT entity, status, records, error_detail, started_at, finished_at FROM sync_runs WHERE company_id = :id ORDER BY started_at DESC LIMIT 20",
                new MapSqlParameterSource("id", companyId), GenericRowMapper.INSTANCE);
    }
}
