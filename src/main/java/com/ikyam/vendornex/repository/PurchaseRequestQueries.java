package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.db.SqlBind;
import com.ikyam.vendornex.service.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Purchase-request SQL that stays on JdbcTemplate instead of the JPA entities: the list/detail
 * read-model (json_agg of lines + approval summary, returned to the client as-is), cross-table
 * reads, SAP master-data checks, and the sourcing-status UPDATE (correlated subquery).
 */
@Repository
@RequiredArgsConstructor
public class PurchaseRequestQueries {

    private static final String LIST_SQL = "SELECT p.id, p.pr_no, p.source, p.sap_doc_num, p.requester_name, p.department, p.required_date, " +
            "p.justification, p.status, p.rejection_reason, p.sap_push_status, p.created_at, " +
            "(SELECT json_agg(json_build_object('id', l.id, 'itemCode', l.item_code, 'itemName', l.item_name, 'uom', l.uom, " +
            "'quantity', l.quantity, 'sourcedQty', l.sourced_qty, 'warehouseCode', l.warehouse_code) ORDER BY l.line_num) " +
            " FROM purchase_request_lines l WHERE l.purchase_request_id = p.id) AS lines, " +
            ApprovalService.summarySql("PURCHASE_REQUEST", "p.id") + " AS approvals " +
            "FROM purchase_requests p ";

    private final JdbcTemplate jdbc;

    // ------------------------------------------------------------------ PR read-model

    /** @param onlyRequester when set, only PRs raised by that user (requester view). */
    public List<Row> list(UUID companyId, UUID onlyRequester, String status, String source) {
        String sql = LIST_SQL + "WHERE p.company_id = ? AND (?::uuid IS NULL OR p.requester_user_id = ?) " +
                "AND (?::text IS NULL OR p.status = ?) AND (?::text IS NULL OR p.source = ?) ORDER BY p.created_at DESC LIMIT 500";
        return query(sql, companyId, onlyRequester, onlyRequester, status, status, source, source);
    }

    public Row findRow(UUID id, UUID companyId) {
        return one(LIST_SQL + "WHERE p.id = ? AND p.company_id = ?", id, companyId);
    }

    /** RFQs and POs the PR's lines were pulled into. */
    public List<Row> sourcedInto(UUID prId) {
        return query("""
                SELECT DISTINCT 'RFQ' AS kind, r.id, r.rfq_no AS number, r.status FROM rfq_line_sources s
                  JOIN rfq_lines rl ON rl.id = s.rfq_line_id JOIN rfqs r ON r.id = rl.rfq_id
                  JOIN purchase_request_lines l ON l.id = s.pr_line_id WHERE l.purchase_request_id = ?
                UNION
                SELECT DISTINCT 'PO', po.id, COALESCE(po.sap_doc_num::text, 'pending'), po.status FROM purchase_order_lines pl
                  JOIN purchase_orders po ON po.id = pl.purchase_order_id
                  JOIN purchase_request_lines l ON l.id = pl.base_pr_line_id WHERE l.purchase_request_id = ?""", prId, prId);
    }

    /** Open (unsourced) quantity on lines of approved PRs. */
    public List<Row> sourceableLines(UUID companyId, UUID[] prIds) {
        return query("""
                SELECT l.id AS pr_line_id, p.id AS pr_id, p.pr_no, l.item_code, l.item_name, l.uom, l.warehouse_code, l.required_date,
                       l.quantity - l.sourced_qty AS open_qty, i.avg_price
                  FROM purchase_request_lines l JOIN purchase_requests p ON p.id = l.purchase_request_id
                  LEFT JOIN sap_items i ON i.company_id = p.company_id AND i.item_code = l.item_code
                 WHERE p.company_id = ? AND p.id = ANY(?) AND p.status IN ('APPROVED','PARTIALLY_SOURCED') AND l.quantity > l.sourced_qty
                 ORDER BY p.pr_no, l.line_num""", companyId, prIds);
    }

    // ------------------------------------------------------------------ SAP master-data checks

    /** {itemName, purchaseUom} of an active SAP purchase item, or null. */
    public Row findActiveItem(UUID companyId, String itemCode) {
        return one("SELECT item_name, purchase_uom FROM sap_items WHERE company_id = ? AND item_code = ? AND is_active", companyId, itemCode);
    }

    public boolean activeWarehouseExists(UUID companyId, String warehouseCode) {
        return one("SELECT 1 AS found FROM sap_warehouses WHERE company_id = ? AND warehouse_code = ? AND is_active", companyId, warehouseCode) != null;
    }

    // ------------------------------------------------------------------ writes

    /** PR status from its lines' sourced quantity: SOURCED when all lines are covered, PARTIALLY_SOURCED when some are. */
    public void recomputeSourcing(UUID prId) {
        jdbc.update("""
                UPDATE purchase_requests p SET status = CASE WHEN q.all_done THEN 'SOURCED' WHEN q.any THEN 'PARTIALLY_SOURCED' ELSE p.status END,
                       updated_at = now()
                  FROM (SELECT bool_and(sourced_qty >= quantity) AS all_done, bool_or(sourced_qty > 0) AS any
                          FROM purchase_request_lines WHERE purchase_request_id = ?) q
                 WHERE p.id = ? AND p.status IN ('APPROVED','PARTIALLY_SOURCED')""",
                ps -> SqlBind.bind(ps.getConnection(), ps, new Object[]{prId, prId}));
    }

    // ------------------------------------------------------------------ helpers

    private List<Row> query(String sql, Object... params) {
        return jdbc.query(sql, ps -> SqlBind.bind(ps.getConnection(), ps, params), GenericRowMapper.INSTANCE);
    }

    private Row one(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
