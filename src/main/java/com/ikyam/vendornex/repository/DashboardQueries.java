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
 * Read-model queries for Admin and Vendor dashboards: aggregate KPI counts, recent PO lists,
 * vendor onboarding pipeline, connection status, and latest sync timestamp.
 */
@Repository
public class DashboardQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Row adminKpis(UUID companyId) {
        String sql = """
                SELECT (SELECT count(*) FROM vendors WHERE company_id = :c AND status = 'ACTIVE') AS active_vendors,
                       (SELECT count(*) FROM vendors WHERE company_id = :c AND status = 'PENDING_APPROVAL') AS vendors_in_approval,
                       (SELECT count(*) FROM vendors WHERE company_id = :c AND status IN ('SAP_SYNC_PENDING','SAP_SYNC_FAILED')) AS vendors_b1_pending,
                       (SELECT count(*) FROM vendors WHERE company_id = :c AND status = 'DRAFT') AS vendor_drafts,
                       (SELECT count(*) FROM approval_steps WHERE company_id = :c AND status = 'PENDING' AND stage = 'ADMIN') AS my_approvals,
                       (SELECT count(*) FROM purchase_requests WHERE company_id = :c AND status = 'PENDING_APPROVAL') AS prs_pending,
                       (SELECT count(*) FROM purchase_requests WHERE company_id = :c AND status IN ('APPROVED','PARTIALLY_SOURCED')) AS prs_to_source,
                       (SELECT count(*) FROM rfqs WHERE company_id = :c AND status = 'OPEN') AS open_rfqs,
                       (SELECT count(*) FROM rfqs WHERE company_id = :c AND status IN ('CLOSED','PARTIALLY_AWARDED')) AS rfqs_to_award,
                       (SELECT count(*) FROM purchase_orders WHERE company_id = :c AND status = 'PENDING_ACK') AS pos_pending_ack,
                       (SELECT count(*) FROM purchase_orders WHERE company_id = :c AND status IN ('PARTIALLY_SHIPPED','SHIPPED','PARTIALLY_RECEIVED')) AS pos_in_transit,
                       (SELECT count(*) FROM grpos WHERE company_id = :c AND status IN ('DRAFT_CREATED','DRAFT_FAILED')) AS grpos_to_confirm,
                       (SELECT count(*) FROM sync_transactions WHERE company_id = :c AND status = 'FAILED') AS sap_failures,
                       (SELECT COALESCE(sum(doc_total), 0) FROM purchase_orders WHERE company_id = :c AND status NOT IN ('SAP_PENDING','SAP_FAILED','CANCELLED')
                           AND doc_date >= date_trunc('month', CURRENT_DATE)) AS spend_this_month
                """;
        List<Row> rows = jdbc.query(sql, new MapSqlParameterSource("c", companyId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? new Row() : rows.get(0);
    }

    public List<Row> recentPurchaseOrders(UUID companyId, int limit) {
        String sql = """
                SELECT po.id, po.sap_doc_num, po.status, po.doc_total, po.doc_date, v.legal_name AS vendor_name
                  FROM purchase_orders po
                  JOIN vendors v ON v.id = po.vendor_id
                 WHERE po.company_id = :companyId
                 ORDER BY po.created_at DESC
                 LIMIT :limit""";
        return jdbc.query(sql, new MapSqlParameterSource("companyId", companyId).addValue("limit", limit), GenericRowMapper.INSTANCE);
    }

    public List<Row> onboardingPipeline(UUID companyId, int limit) {
        String sql = "SELECT v.id, v.legal_name, v.status, v.link_type, v.sap_card_code, " +
                ApprovalService.summarySql("VENDOR", "v.id") + " AS approvals FROM vendors v " +
                "WHERE v.company_id = :companyId AND v.status IN ('PENDING_APPROVAL','SAP_SYNC_PENDING','SAP_SYNC_FAILED','REJECTED') " +
                "ORDER BY v.updated_at DESC LIMIT :limit";
        return jdbc.query(sql, new MapSqlParameterSource("companyId", companyId).addValue("limit", limit), GenericRowMapper.INSTANCE);
    }

    public Row companyConnection(UUID companyId) {
        String sql = "SELECT integration_mode, connection_status, sap_b1_version, sap_company_db, last_tested_at FROM companies WHERE id = :companyId";
        List<Row> rows = jdbc.query(sql, new MapSqlParameterSource("companyId", companyId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String lastSyncAt(UUID companyId) {
        String sql = "SELECT max(finished_at)::text FROM sync_runs WHERE company_id = :companyId AND status = 'SUCCESS'";
        List<String> list = jdbc.query(sql, new MapSqlParameterSource("companyId", companyId), (rs, rowNum) -> rs.getString(1));
        return list.isEmpty() ? null : list.get(0);
    }

    public Row vendorKpis(UUID vendorId) {
        String sql = """
                SELECT (SELECT count(*) FROM rfq_vendors rv JOIN rfqs r ON r.id = rv.rfq_id
                         WHERE rv.vendor_id = :vendorId AND r.status = 'OPEN' AND r.due_date >= CURRENT_DATE
                           AND NOT EXISTS (SELECT 1 FROM quotations q WHERE q.rfq_id = r.id AND q.vendor_id = rv.vendor_id AND q.status <> 'WITHDRAWN')) AS rfqs_to_quote,
                       (SELECT count(*) FROM purchase_orders WHERE vendor_id = :vendorId AND status = 'PENDING_ACK') AS pos_pending_ack,
                       (SELECT count(*) FROM purchase_orders WHERE vendor_id = :vendorId AND status IN ('ACKNOWLEDGED','PARTIALLY_SHIPPED','PARTIALLY_RECEIVED')) AS pos_to_ship,
                       (SELECT COALESCE(sum(doc_total), 0) FROM purchase_orders WHERE vendor_id = :vendorId AND status NOT IN ('SAP_PENDING','SAP_FAILED','CANCELLED','DECLINED')) AS total_order_value
                """;
        List<Row> rows = jdbc.query(sql, new MapSqlParameterSource("vendorId", vendorId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? new Row() : rows.get(0);
    }

    public List<Row> vendorRecentPurchaseOrders(UUID vendorId, UUID companyId, int limit) {
        String sql = """
                SELECT id, sap_doc_num, status, doc_total, doc_date, doc_due_date
                  FROM purchase_orders
                 WHERE vendor_id = :vendorId AND company_id = :companyId AND status NOT IN ('SAP_PENDING','SAP_FAILED')
                 ORDER BY created_at DESC
                 LIMIT :limit""";
        return jdbc.query(sql, new MapSqlParameterSource("vendorId", vendorId)
                .addValue("companyId", companyId)
                .addValue("limit", limit), GenericRowMapper.INSTANCE);
    }
}
