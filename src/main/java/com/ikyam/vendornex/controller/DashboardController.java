package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.ApprovalService;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class DashboardController {

    @GetMapping("/api/dashboard")
    @Roles(Role.ADMIN)
    public Object admin(CurrentUser u) {
        UUID c = u.company();
        Row k = Db.one("""
                SELECT (SELECT count(*) FROM vendors WHERE company_id = ? AND status = 'ACTIVE') AS active_vendors,
                       (SELECT count(*) FROM vendors WHERE company_id = ? AND status = 'PENDING_APPROVAL') AS vendors_in_approval,
                       (SELECT count(*) FROM vendors WHERE company_id = ? AND status IN ('SAP_SYNC_PENDING','SAP_SYNC_FAILED')) AS vendors_b1_pending,
                       (SELECT count(*) FROM vendors WHERE company_id = ? AND status = 'DRAFT') AS vendor_drafts,
                       (SELECT count(*) FROM approval_steps WHERE company_id = ? AND status = 'PENDING' AND stage = 'ADMIN') AS my_approvals,
                       (SELECT count(*) FROM purchase_requests WHERE company_id = ? AND status = 'PENDING_APPROVAL') AS prs_pending,
                       (SELECT count(*) FROM purchase_requests WHERE company_id = ? AND status IN ('APPROVED','PARTIALLY_SOURCED')) AS prs_to_source,
                       (SELECT count(*) FROM rfqs WHERE company_id = ? AND status = 'OPEN') AS open_rfqs,
                       (SELECT count(*) FROM rfqs WHERE company_id = ? AND status IN ('CLOSED','PARTIALLY_AWARDED')) AS rfqs_to_award,
                       (SELECT count(*) FROM purchase_orders WHERE company_id = ? AND status = 'PENDING_ACK') AS pos_pending_ack,
                       (SELECT count(*) FROM purchase_orders WHERE company_id = ? AND status IN ('PARTIALLY_SHIPPED','SHIPPED','PARTIALLY_RECEIVED')) AS pos_in_transit,
                       (SELECT count(*) FROM grpos WHERE company_id = ? AND status IN ('DRAFT_CREATED','DRAFT_FAILED')) AS grpos_to_confirm,
                       (SELECT count(*) FROM sync_transactions WHERE company_id = ? AND status = 'FAILED') AS sap_failures,
                       (SELECT COALESCE(sum(doc_total), 0) FROM purchase_orders WHERE company_id = ? AND status NOT IN ('SAP_PENDING','SAP_FAILED','CANCELLED')
                           AND doc_date >= date_trunc('month', CURRENT_DATE)) AS spend_this_month
                """, c, c, c, c, c, c, c, c, c, c, c, c, c, c);
        k.put("recentPurchaseOrders", Db.query("""
                SELECT po.id, po.sap_doc_num, po.status, po.doc_total, po.doc_date, v.legal_name AS vendor_name FROM purchase_orders po
                  JOIN vendors v ON v.id = po.vendor_id WHERE po.company_id = ? ORDER BY po.created_at DESC LIMIT 6""", c));
        k.put("onboardingPipeline", Db.query("SELECT v.id, v.legal_name, v.status, v.link_type, v.sap_card_code, " +
                ApprovalService.summarySql("VENDOR", "v.id") + " AS approvals FROM vendors v " +
                "WHERE v.company_id = ? AND v.status IN ('PENDING_APPROVAL','SAP_SYNC_PENDING','SAP_SYNC_FAILED','REJECTED') ORDER BY v.updated_at DESC LIMIT 8", c));
        k.put("connection", Db.one("SELECT integration_mode, connection_status, sap_b1_version, sap_company_db, last_tested_at FROM companies WHERE id = ?", c));
        k.put("lastSyncAt", Db.scalar("SELECT max(finished_at)::text FROM sync_runs WHERE company_id = ? AND status = 'SUCCESS'", String.class, c));
        return k;
    }

    @GetMapping("/api/vendor/dashboard")
    @Roles(Role.VENDOR)
    public Object vendor(CurrentUser u) {
        Row k = Db.one("""
                SELECT (SELECT count(*) FROM rfq_vendors rv JOIN rfqs r ON r.id = rv.rfq_id
                         WHERE rv.vendor_id = ? AND r.status = 'OPEN' AND r.due_date >= CURRENT_DATE
                           AND NOT EXISTS (SELECT 1 FROM quotations q WHERE q.rfq_id = r.id AND q.vendor_id = rv.vendor_id AND q.status <> 'WITHDRAWN')) AS rfqs_to_quote,
                       (SELECT count(*) FROM purchase_orders WHERE vendor_id = ? AND status = 'PENDING_ACK') AS pos_pending_ack,
                       (SELECT count(*) FROM purchase_orders WHERE vendor_id = ? AND status IN ('ACKNOWLEDGED','PARTIALLY_SHIPPED','PARTIALLY_RECEIVED')) AS pos_to_ship,
                       (SELECT COALESCE(sum(doc_total), 0) FROM purchase_orders WHERE vendor_id = ? AND status NOT IN ('SAP_PENDING','SAP_FAILED','CANCELLED','DECLINED')) AS total_order_value
                """, u.vendor(), u.vendor(), u.vendor(), u.vendor());
        k.put("recentPurchaseOrders", Db.query("""
                SELECT id, sap_doc_num, status, doc_total, doc_date, doc_due_date FROM purchase_orders
                 WHERE vendor_id = ? AND company_id = ? AND status NOT IN ('SAP_PENDING','SAP_FAILED') ORDER BY created_at DESC LIMIT 6""", u.vendor(), u.company()));
        List<Row> s = ScorecardController.scores(u.company(), u.vendor());
        if (!s.isEmpty()) {
            Row sc = s.get(0);
            k.put("scorecard", new Row().with("otif", sc.get("otif")).with("quality", sc.get("quality")).with("priceVariance", sc.get("priceVariance")));
        }
        return k;
    }
}
