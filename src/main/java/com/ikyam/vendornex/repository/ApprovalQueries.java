package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.db.SqlBind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-model queries for the approval engine that join across polymorphic entity types
 * (vendors/users/purchase_requests) — none of which is a single mappable association — and stay
 * on {@code JdbcTemplate} with the exact original SQL text.
 */
@Repository
public class ApprovalQueries {

    private final JdbcTemplate jdbc;

    public ApprovalQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> queue(UUID companyId, String[] stages) {
        String sql = """
                SELECT s.id, s.entity_type, s.entity_id, s.stage, s.seq, s.round,
                       COALESCE(v.legal_name, ru.name, pr.pr_no) AS title,
                       CASE s.entity_type WHEN 'VENDOR' THEN COALESCE(v.vendor_group_name, '') || CASE WHEN v.link_type = 'EXISTING' THEN ' · links B1 BP ' || v.sap_card_code ELSE ' · new B1 BP on approval' END
                                          WHEN 'REQUESTER' THEN COALESCE(ru.department, '') || ' · ' || ru.email
                                          ELSE pr.requester_name || ' · ' || COALESCE(pr.department, '') END AS subtitle,
                       COALESCE(v.created_at, ru.created_at, pr.created_at) AS submitted_at,
                       (SELECT count(*) FROM approval_steps x WHERE x.entity_type = s.entity_type AND x.entity_id = s.entity_id AND x.round = s.round) AS total_stages
                  FROM approval_steps s
                  LEFT JOIN vendors v ON s.entity_type = 'VENDOR' AND v.id = s.entity_id
                  LEFT JOIN users ru ON s.entity_type = 'REQUESTER' AND ru.id = s.entity_id
                  LEFT JOIN purchase_requests pr ON s.entity_type = 'PURCHASE_REQUEST' AND pr.id = s.entity_id
                 WHERE s.company_id = ? AND s.status = 'PENDING' AND s.stage = ANY(?)
                 ORDER BY submitted_at""";
        return jdbc.query(sql, ps -> SqlBind.bind(ps.getConnection(), ps, new Object[]{companyId, stages}), GenericRowMapper.INSTANCE);
    }

    public List<Row> history(UUID companyId, UUID userId) {
        String sql = """
                SELECT s.id, s.entity_type, s.entity_id, s.stage, s.status, s.acted_at, s.remarks,
                       COALESCE(v.legal_name, ru.name, pr.pr_no) AS title
                  FROM approval_steps s
                  LEFT JOIN vendors v ON s.entity_type = 'VENDOR' AND v.id = s.entity_id
                  LEFT JOIN users ru ON s.entity_type = 'REQUESTER' AND ru.id = s.entity_id
                  LEFT JOIN purchase_requests pr ON s.entity_type = 'PURCHASE_REQUEST' AND pr.id = s.entity_id
                 WHERE s.company_id = ? AND s.acted_by_user_id = ?
                 ORDER BY s.acted_at DESC LIMIT 100""";
        return jdbc.query(sql, ps -> SqlBind.bind(ps.getConnection(), ps, new Object[]{companyId, userId}), GenericRowMapper.INSTANCE);
    }

    public List<Row> steps(String entityType, UUID entityId) {
        String sql = """
                SELECT a.id, a.stage, a.seq, a.status, a.round, a.acted_at, a.remarks, u.name AS acted_by
                  FROM approval_steps a LEFT JOIN users u ON u.id = a.acted_by_user_id
                 WHERE a.entity_type = ? AND a.entity_id = ?
                   AND a.round = (SELECT max(round) FROM approval_steps WHERE entity_type = ? AND entity_id = ?)
                 ORDER BY a.seq""";
        return jdbc.query(sql, ps -> SqlBind.bind(ps.getConnection(), ps, new Object[]{entityType, entityId, entityType, entityId}), GenericRowMapper.INSTANCE);
    }
}
