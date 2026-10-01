package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.service.ApprovalService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-model queries that join {@code users} against tables not yet converted to entities in this
 * migration stage ({@code sap_employees}, {@code login_activity}), or that splice
 * {@link ApprovalService#summarySql} — a runtime-built SQL fragment, never a static {@code @Query}
 * string — so they stay on {@code NamedParameterJdbcTemplate} until those domains convert too.
 */
@Repository
public class UserQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public UserQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> loginActivity(UUID companyId) {
        return jdbc.query("""
                SELECT l.occurred_at, l.event, l.email, l.ip_address, u.name, u.role FROM login_activity l
                  LEFT JOIN users u ON u.id = l.user_id
                 WHERE l.company_id = :company ORDER BY l.occurred_at DESC LIMIT 200""",
                new MapSqlParameterSource("company", companyId), GenericRowMapper.INSTANCE);
    }

    public List<Row> listRequesters(UUID companyId) {
        String sql = "SELECT u.id, u.name, u.email, u.department, u.status, u.sap_employee_id, u.rejection_reason, u.last_login_at, u.invite_token, " +
                ApprovalService.summarySql("REQUESTER", "u.id") + " AS approvals " +
                "FROM users u WHERE u.company_id = :company AND u.role = 'REQUESTER' ORDER BY u.name";
        return jdbc.query(sql, new MapSqlParameterSource("company", companyId), GenericRowMapper.INSTANCE);
    }

    public Row requesterDetail(UUID id, UUID companyId) {
        List<Row> rows = jdbc.query("""
                SELECT u.id, u.name, u.email, u.department, u.status, u.sap_employee_id, u.rejection_reason, u.created_at, u.invite_token,
                       e.full_name AS sap_employee_name
                  FROM users u LEFT JOIN sap_employees e ON e.company_id = u.company_id AND e.employee_id = u.sap_employee_id
                 WHERE u.id = :id AND u.company_id = :company AND u.role = 'REQUESTER'""",
                new MapSqlParameterSource("id", id).addValue("company", companyId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean employeeExists(UUID companyId, Integer employeeId) {
        Boolean r = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sap_employees WHERE company_id = :company AND employee_id = :emp)",
                new MapSqlParameterSource("company", companyId).addValue("emp", employeeId), Boolean.class);
        return Boolean.TRUE.equals(r);
    }
}
