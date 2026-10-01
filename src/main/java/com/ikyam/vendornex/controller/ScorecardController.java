package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Vendor scorecard, derived from portal + B1 receipt data:
 * <ul>
 *   <li><b>OTIF</b> — % of due PO lines received in full on or before the line delivery date;</li>
 *   <li><b>Quality</b> — accepted ÷ (accepted + rejected) quantity on posted GRPOs
 *       (the natural hook for InspectPro inward-inspection results);</li>
 *   <li><b>Price variance</b> — average % of PO unit price over the item's B1 average cost (negative = cheaper).</li>
 * </ul>
 * Trend uses monthly snapshots (vendor_score_snapshots) plus the live current figure.
 */
@RestController
public class ScorecardController {

    @GetMapping("/api/scorecard")
    @Roles(Role.ADMIN)
    public Object scorecard(CurrentUser u) {
        return scores(u.company(), null);
    }

    private static final String SQL = """
            WITH lines AS (
                SELECT po.vendor_id, pl.id, pl.quantity, pl.received_qty, pl.unit_price, COALESCE(pl.ship_date, po.doc_due_date) AS due,
                       i.avg_price,
                       (SELECT max(g.posting_date) FROM grpo_lines gl JOIN grpos g ON g.id = gl.grpo_id
                         WHERE gl.po_line_id = pl.id AND g.status = 'POSTED') AS last_receipt
                  FROM purchase_orders po JOIN purchase_order_lines pl ON pl.purchase_order_id = po.id
                  LEFT JOIN sap_items i ON i.company_id = po.company_id AND i.item_code = pl.item_code
                 WHERE po.company_id = ? AND po.status NOT IN ('SAP_PENDING','SAP_FAILED','CANCELLED','DECLINED')
            ), q AS (
                SELECT po.vendor_id, sum(gl.received_qty) AS acc, sum(gl.rejected_qty) AS rej
                  FROM grpo_lines gl JOIN grpos g ON g.id = gl.grpo_id JOIN purchase_orders po ON po.id = g.purchase_order_id
                 WHERE po.company_id = ? AND g.status = 'POSTED' GROUP BY po.vendor_id
            ), agg AS (
                SELECT vendor_id,
                       count(*) FILTER (WHERE due <= CURRENT_DATE OR received_qty >= quantity) AS evaluable,
                       count(*) FILTER (WHERE received_qty >= quantity AND (last_receipt IS NULL OR last_receipt <= due)) AS otif_lines,
                       avg((unit_price - avg_price) * 100 / NULLIF(avg_price, 0)) AS price_var,
                       sum(quantity * unit_price) AS spend
                  FROM lines GROUP BY vendor_id
            )
            SELECT v.id AS vendor_id, v.legal_name, v.sap_card_code, v.vendor_group_name,
                   (SELECT count(*) FROM purchase_orders p WHERE p.vendor_id = v.id AND p.status NOT IN ('SAP_PENDING','SAP_FAILED','CANCELLED')) AS po_count,
                   COALESCE(a.spend, 0) AS spend,
                   CASE WHEN a.evaluable > 0 THEN round(a.otif_lines * 100.0 / a.evaluable, 1) END AS otif_live,
                   CASE WHEN COALESCE(q.acc, 0) + COALESCE(q.rej, 0) > 0 THEN round(q.acc * 100.0 / (q.acc + q.rej), 1) END AS quality_live,
                   round(a.price_var, 2) AS price_variance_live,
                   (SELECT json_agg(json_build_object('period', s.period, 'otif', s.otif_pct, 'quality', s.quality_pct, 'priceVariance', s.price_variance_pct)
                                    ORDER BY s.period)
                      FROM (SELECT * FROM vendor_score_snapshots s WHERE s.vendor_id = v.id ORDER BY period DESC LIMIT 6) s) AS history
              FROM vendors v LEFT JOIN agg a ON a.vendor_id = v.id LEFT JOIN q ON q.vendor_id = v.id
             WHERE v.company_id = ? AND v.status IN ('ACTIVE','INACTIVE') AND (?::uuid IS NULL OR v.id = ?)
             ORDER BY v.legal_name
            """;

    public static List<Row> scores(UUID c, UUID vendorId) {
        List<Row> rows = Db.query(SQL, c, c, c, vendorId, vendorId);
        for (Row r : rows) {
            // Live figure when there is data for it, otherwise the latest monthly snapshot.
            com.fasterxml.jackson.databind.JsonNode hist = (com.fasterxml.jackson.databind.JsonNode) r.get("history");
            com.fasterxml.jackson.databind.JsonNode last = hist != null && hist.size() > 0 ? hist.get(hist.size() - 1) : null;
            r.put("otif", r.get("otifLive") != null ? r.get("otifLive") : last == null || last.get("otif").isNull() ? null : last.get("otif").decimalValue());
            r.put("quality", r.get("qualityLive") != null ? r.get("qualityLive") : last == null || last.get("quality").isNull() ? null : last.get("quality").decimalValue());
            r.put("priceVariance", r.get("priceVarianceLive") != null ? r.get("priceVarianceLive")
                    : last == null || last.get("priceVariance").isNull() ? null : last.get("priceVariance").decimalValue());
        }
        return rows;
    }

    @GetMapping("/api/vendor/scorecard")
    @Roles(Role.VENDOR)
    public Object mine(CurrentUser u) {
        List<Row> rows = scores(u.company(), u.vendor());
        if (rows.isEmpty()) throw ApiException.notFound("Scorecard");
        Row r = rows.get(0);
        r.remove("spend"); // vendors see their performance, not the buyer's spend analytics
        return r;
    }
}
