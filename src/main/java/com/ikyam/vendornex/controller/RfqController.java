package com.ikyam.vendornex.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.common.DocNumbers;
import com.ikyam.vendornex.common.Settings;
import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.PurchaseRequestService;
import com.ikyam.vendornex.service.SyncService;
import com.ikyam.vendornex.tenant.Tenants;
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.QueryParams;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * RFQ lifecycle (app-owned — B1 has no RFQ step in this flow): create (optionally merging approved PR
 * lines), invite vendors, collect / revise quotations, compare, and award per line. An award creates
 * one Purchase Order per winning vendor in SAP B1 through the sync outbox.
 */
@RestController
public class RfqController {

    private final PurchaseRequestService purchaseRequestService;

    public RfqController(PurchaseRequestService purchaseRequestService) {
        this.purchaseRequestService = purchaseRequestService;
    }

    // =================================================================== admin

    @GetMapping("/api/rfqs")
    @Roles(Role.ADMIN)
    public Object list(@RequestParam(required = false) String status, CurrentUser u) {
        String st = QueryParams.orNull(status);
        return Db.query("""
                SELECT r.id, r.rfq_no, r.title, r.due_date, r.status, r.created_at,
                       (SELECT count(*) FROM rfq_lines l WHERE l.rfq_id = r.id) AS line_count,
                       (SELECT count(*) FROM rfq_vendors v WHERE v.rfq_id = r.id) AS invited_count,
                       (SELECT count(*) FROM quotations q WHERE q.rfq_id = r.id AND q.status <> 'WITHDRAWN') AS quote_count,
                       (SELECT string_agg(DISTINCT v.legal_name, ', ') FROM rfq_awards a JOIN vendors v ON v.id = a.vendor_id WHERE a.rfq_id = r.id) AS awarded_to
                  FROM rfqs r WHERE r.company_id = ? AND (?::text IS NULL OR r.status = ?)
                 ORDER BY r.created_at DESC LIMIT 500""", u.company(), st, st);
    }

    @GetMapping("/api/rfqs/{id}")
    @Roles(Role.ADMIN)
    public Object detailEndpoint(@PathVariable String id, CurrentUser u) {
        return detail(u.company(), Ids.uuid(id));
    }

    public static Row detail(UUID c, UUID id) {
        Row r = Db.one("SELECT r.*, u.name AS created_by FROM rfqs r LEFT JOIN users u ON u.id = r.created_by_user_id WHERE r.id = ? AND r.company_id = ?", id, c);
        if (r == null) throw ApiException.notFound("RFQ");
        r.put("lines", Db.query("""
                SELECT l.*, w.warehouse_name,
                       (SELECT json_agg(json_build_object('prNo', p.pr_no, 'prId', p.id, 'quantity', s.quantity))
                          FROM rfq_line_sources s JOIN purchase_request_lines pl ON pl.id = s.pr_line_id
                          JOIN purchase_requests p ON p.id = pl.purchase_request_id WHERE s.rfq_line_id = l.id) AS sources
                  FROM rfq_lines l LEFT JOIN sap_warehouses w ON w.company_id = ? AND w.warehouse_code = l.warehouse_code
                 WHERE l.rfq_id = ? ORDER BY l.line_num""", c, id));
        r.put("vendors", Db.query("""
                SELECT v.id, v.legal_name, v.sap_card_code, v.sap_bp_status, rv.invited_at, rv.viewed_at,
                       q.id AS quotation_id, q.status AS quote_status, q.submitted_at, q.revision
                  FROM rfq_vendors rv JOIN vendors v ON v.id = rv.vendor_id
                  LEFT JOIN quotations q ON q.rfq_id = rv.rfq_id AND q.vendor_id = rv.vendor_id
                 WHERE rv.rfq_id = ? ORDER BY v.legal_name""", id));
        List<Row> quotes = Db.query("""
                SELECT q.*, v.legal_name AS vendor_name, v.sap_bp_status,
                       (SELECT json_agg(json_build_object('rfqLineId', ql.rfq_line_id, 'unitPrice', ql.unit_price, 'fulfilQty', ql.fulfil_qty,
                                'taxPercent', ql.tax_percent)) FROM quotation_lines ql WHERE ql.quotation_id = q.id) AS lines,
                       (SELECT sum(ql.unit_price * ql.fulfil_qty) FROM quotation_lines ql WHERE ql.quotation_id = q.id) AS total
                  FROM quotations q JOIN vendors v ON v.id = q.vendor_id WHERE q.rfq_id = ? ORDER BY total""", id);
        r.put("quotations", quotes);
        r.put("awards", Db.query("""
                SELECT a.id, a.rfq_line_id, a.quotation_id, a.vendor_id, v.legal_name AS vendor_name, a.awarded_qty, a.unit_price, a.awarded_at,
                       a.purchase_order_id, po.sap_doc_num, po.status AS po_status
                  FROM rfq_awards a JOIN vendors v ON v.id = a.vendor_id LEFT JOIN purchase_orders po ON po.id = a.purchase_order_id
                 WHERE a.rfq_id = ? ORDER BY a.awarded_at""", id));
        return r;
    }

    /** Per line: every quote ranked by price, with lowest / fastest / full-quantity flags. */
    @GetMapping("/api/rfqs/{id}/comparison")
    @Roles(Role.ADMIN)
    public Object comparison(@PathVariable String id, CurrentUser u) {
        UUID c = u.company();
        UUID rfqId = Ids.uuid(id);
        Row r = detail(c, rfqId);
        List<Row> out = new ArrayList<>();
        for (Row line : Db.query("SELECT * FROM rfq_lines WHERE rfq_id = ? ORDER BY line_num", rfqId)) {
            List<Row> quotes = Db.query("""
                    SELECT q.id AS quotation_id, v.id AS vendor_id, v.legal_name AS vendor_name, v.sap_bp_status, ql.unit_price, ql.fulfil_qty,
                           ql.tax_percent, q.delivery_date, q.revision, ql.unit_price * ql.fulfil_qty AS line_total
                      FROM quotation_lines ql JOIN quotations q ON q.id = ql.quotation_id JOIN vendors v ON v.id = q.vendor_id
                     WHERE ql.rfq_line_id = ? AND q.status <> 'WITHDRAWN' AND ql.fulfil_qty > 0
                     ORDER BY ql.unit_price, q.delivery_date""", line.uuid("id"));
            BigDecimal lowest = quotes.stream().map(q -> q.dec("unitPrice")).min(BigDecimal::compareTo).orElse(null);
            String fastest = quotes.stream().map(q -> q.str("deliveryDate")).min(String::compareTo).orElse(null);
            for (Row q : quotes) {
                q.put("lowestPrice", lowest != null && q.dec("unitPrice").compareTo(lowest) == 0);
                q.put("fastestDelivery", fastest != null && fastest.equals(q.str("deliveryDate")));
                q.put("fullQuantity", q.dec("fulfilQty").compareTo(line.dec("quantity")) >= 0);
                BigDecimal ref = Db.scalar("SELECT avg_price FROM sap_items WHERE company_id = ? AND item_code = ?", BigDecimal.class, c, line.str("itemCode"));
                if (ref != null && ref.signum() > 0) {
                    q.put("variancePct", q.dec("unitPrice").subtract(ref).multiply(BigDecimal.valueOf(100)).divide(ref, 2, java.math.RoundingMode.HALF_UP));
                }
            }
            line.put("quotes", quotes);
            line.put("remainingQty", line.dec("quantity").subtract(line.dec("awardedQty")));
            out.add(line);
        }
        return new Row().with("rfq", r).with("lines", out);
    }

    @PostMapping("/api/rfqs")
    @Roles(Role.ADMIN)
    public Object create(@RequestBody JsonNode b, CurrentUser u) {
        UUID c = u.company();
        String title = Json.reqText(b, "title", 200);
        LocalDate due = Json.reqDate(b, "dueDate");
        if (due.isBefore(LocalDate.now())) throw ApiException.badRequest("Due date cannot be in the past");
        List<UUID> vendorIds = new ArrayList<>();
        for (String v : Json.textList(b, "vendorIds")) vendorIds.add(UUID.fromString(v));
        if (vendorIds.isEmpty()) throw ApiException.badRequest("Invite at least one vendor");
        List<JsonNode> lines = Json.reqArray(b, "lines");
        Row settings = Settings.of(c);
        Set<UUID> touchedPrs = new HashSet<>();
        UUID id = Db.tx(() -> {
            UUID rfqId = Db.scalar("INSERT INTO rfqs(company_id, rfq_no, title, due_date, notes, created_by_user_id) VALUES (?,?,?,?,?,?) RETURNING id",
                    UUID.class, c, DocNumbers.next(c, "RFQ"), title, due, Json.optText(b, "notes"), u.userId());
            int n = 0;
            for (JsonNode l : lines) {
                String item = Json.reqText(l, "itemCode");
                BigDecimal qty = Json.reqPositive(l, "quantity");
                String wh = Optional.ofNullable(Json.optText(l, "warehouseCode")).orElse(settings.str("defaultWarehouseCode"));
                Row it = Db.one("SELECT item_name, purchase_uom FROM sap_items WHERE company_id = ? AND item_code = ? AND is_active", c, item);
                if (it == null) throw ApiException.badRequest("Line " + (n + 1) + ": item " + item + " is not an active purchase item in SAP B1");
                if (wh == null || Db.scalar("SELECT 1 FROM sap_warehouses WHERE company_id = ? AND warehouse_code = ?", Integer.class, c, wh) == null) {
                    throw ApiException.badRequest("Line " + (n + 1) + ": choose a valid SAP B1 warehouse");
                }
                UUID lineId = Db.scalar("""
                        INSERT INTO rfq_lines(rfq_id, line_num, item_code, item_name, uom, quantity, warehouse_code, required_date)
                        VALUES (?,?,?,?,?,?,?,?) RETURNING id""", UUID.class, rfqId, n++, item, it.str("itemName"), it.str("purchaseUom"), qty, wh,
                        Json.optDate(l, "requiredDate"));
                BigDecimal fromPrs = BigDecimal.ZERO;
                for (JsonNode s : Json.optArray(l, "sources")) {
                    UUID prLine = Json.reqUuid(s, "prLineId");
                    BigDecimal sq = Json.reqPositive(s, "quantity");
                    touchedPrs.add(consumePrLine(c, prLine, item, sq));
                    Db.exec("INSERT INTO rfq_line_sources(rfq_line_id, pr_line_id, quantity) VALUES (?,?,?)", lineId, prLine, sq);
                    fromPrs = fromPrs.add(sq);
                }
                if (fromPrs.compareTo(qty) > 0) throw ApiException.badRequest("Line " + n + ": quantity is less than the PR quantity pulled in");
            }
            inviteVendors(c, rfqId, vendorIds);
            return rfqId;
        });
        touchedPrs.forEach(purchaseRequestService::recomputeSourcing);
        return detail(c, id);
    }

    /** Reserves quantity on an approved PR line; returns the PR id. */
    public static UUID consumePrLine(UUID c, UUID prLineId, String itemCode, BigDecimal qty) {
        Row pl = Db.one("""
                SELECT l.id, l.item_code, l.quantity, l.sourced_qty, p.id AS pr_id, p.pr_no, p.status FROM purchase_request_lines l
                  JOIN purchase_requests p ON p.id = l.purchase_request_id WHERE l.id = ? AND p.company_id = ? FOR UPDATE OF l""", prLineId, c);
        if (pl == null) throw ApiException.badRequest("PR line not found");
        if (!Set.of("APPROVED", "PARTIALLY_SOURCED").contains(pl.str("status"))) throw ApiException.conflict(pl.str("prNo") + " is not approved for sourcing");
        if (itemCode != null && !itemCode.equals(pl.str("itemCode"))) throw ApiException.badRequest(pl.str("prNo") + ": item mismatch on merged line");
        if (pl.dec("sourcedQty").add(qty).compareTo(pl.dec("quantity")) > 0) {
            throw ApiException.conflict(pl.str("prNo") + " " + pl.str("itemCode") + ": only " + pl.dec("quantity").subtract(pl.dec("sourcedQty")).toPlainString() + " left to source");
        }
        Db.exec("UPDATE purchase_request_lines SET sourced_qty = sourced_qty + ? WHERE id = ?", qty, prLineId);
        return pl.uuid("prId");
    }

    private static void inviteVendors(UUID c, UUID rfqId, List<UUID> vendorIds) {
        for (UUID v : vendorIds) {
            Row vr = Db.one("SELECT legal_name, status FROM vendors WHERE id = ? AND company_id = ?", v, c);
            if (vr == null) throw ApiException.badRequest("Vendor not found");
            if (!"ACTIVE".equals(vr.str("status"))) throw ApiException.badRequest(vr.str("legalName") + " is not an active vendor");
            Db.exec("INSERT INTO rfq_vendors(rfq_id, vendor_id) VALUES (?,?) ON CONFLICT DO NOTHING", rfqId, v);
        }
    }

    private static Row open(UUID c, UUID id) {
        Row r = Db.one("SELECT * FROM rfqs WHERE id = ? AND company_id = ? FOR UPDATE", id, c);
        if (r == null) throw ApiException.notFound("RFQ");
        return r;
    }

    @PostMapping("/api/rfqs/{id}/vendors")
    @Roles(Role.ADMIN)
    public Object inviteMore(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        UUID c = u.company();
        UUID rfqId = Ids.uuid(id);
        List<UUID> ids = new ArrayList<>();
        for (String v : Json.textList(b, "vendorIds")) ids.add(UUID.fromString(v));
        Db.txv(() -> {
            if (!"OPEN".equals(open(c, rfqId).str("status"))) throw ApiException.conflict("Vendors can only be added while the RFQ is open");
            inviteVendors(c, rfqId, ids);
        });
        return detail(c, rfqId);
    }

    @PostMapping("/api/rfqs/{id}/extend")
    @Roles(Role.ADMIN)
    public Object extend(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        UUID c = u.company();
        UUID rfqId = Ids.uuid(id);
        LocalDate due = Json.reqDate(b, "dueDate");
        if (due.isBefore(LocalDate.now())) throw ApiException.badRequest("New due date cannot be in the past");
        Db.txv(() -> {
            String s = open(c, rfqId).str("status");
            if (!Set.of("OPEN", "CLOSED").contains(s)) throw ApiException.conflict("RFQ is " + s.toLowerCase());
            Db.exec("UPDATE rfqs SET due_date = ?, status = 'OPEN', updated_at = now() WHERE id = ?", due, rfqId);
        });
        return detail(c, rfqId);
    }

    @PostMapping("/api/rfqs/{id}/close")
    @Roles(Role.ADMIN)
    public Object close(@PathVariable String id, CurrentUser u) {
        UUID c = u.company();
        UUID rfqId = Ids.uuid(id);
        Db.txv(() -> {
            if (!"OPEN".equals(open(c, rfqId).str("status"))) throw ApiException.conflict("RFQ is not open");
            Db.exec("UPDATE rfqs SET status = 'CLOSED', updated_at = now() WHERE id = ?", rfqId);
        });
        return detail(c, rfqId);
    }

    @PostMapping("/api/rfqs/{id}/cancel")
    @Roles(Role.ADMIN)
    public Object cancel(@PathVariable String id, CurrentUser u) {
        UUID c = u.company();
        UUID rfqId = Ids.uuid(id);
        Set<UUID> prs = new HashSet<>();
        Db.txv(() -> {
            Row r = open(c, rfqId);
            if (!Set.of("OPEN", "CLOSED").contains(r.str("status"))) throw ApiException.conflict("Awarded RFQs cannot be cancelled");
            // Give the PR quantity back so it can be sourced again.
            for (Row s : Db.query("""
                    SELECT s.pr_line_id, s.quantity, pl.purchase_request_id FROM rfq_line_sources s JOIN rfq_lines l ON l.id = s.rfq_line_id
                      JOIN purchase_request_lines pl ON pl.id = s.pr_line_id WHERE l.rfq_id = ?""", rfqId)) {
                Db.exec("UPDATE purchase_request_lines SET sourced_qty = GREATEST(0, sourced_qty - ?) WHERE id = ?", s.dec("quantity"), s.uuid("prLineId"));
                prs.add(s.uuid("purchaseRequestId"));
            }
            Db.exec("UPDATE rfqs SET status = 'CANCELLED', updated_at = now() WHERE id = ?", rfqId);
            for (UUID pr : prs) {
                Db.exec("""
                        UPDATE purchase_requests p SET status = CASE WHEN EXISTS (SELECT 1 FROM purchase_request_lines l WHERE l.purchase_request_id = p.id AND l.sourced_qty > 0)
                               THEN 'PARTIALLY_SOURCED' ELSE 'APPROVED' END, updated_at = now()
                         WHERE p.id = ? AND p.status IN ('SOURCED','PARTIALLY_SOURCED')""", pr);
            }
        });
        return detail(c, rfqId);
    }

    /**
     * Line-level award. Body: {docDueDate?, taxCode?, awards:[{rfqLineId, quotationId, quantity}]}.
     * Creates one PO per vendor, queued to SAP B1; the response carries each PO's B1 outcome.
     */
    @PostMapping("/api/rfqs/{id}/award")
    @Roles(Role.ADMIN)
    public Object award(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        UUID c = u.company();
        UUID rfqId = Ids.uuid(id);
        List<JsonNode> awards = Json.reqArray(b, "awards");
        LocalDate dueOverride = Json.optDate(b, "docDueDate");
        Row settings = Settings.of(c);
        String taxCode = Optional.ofNullable(Json.optText(b, "taxCode")).orElse(settings.str("defaultTaxCode"));
        if (taxCode != null && Db.scalar("SELECT 1 FROM sap_tax_codes WHERE company_id = ? AND tax_code = ?", Integer.class, c, taxCode) == null) {
            throw ApiException.badRequest("Tax code " + taxCode + " not found in SAP B1");
        }
        List<UUID> txs = new ArrayList<>();
        List<UUID> poIds = new ArrayList<>();
        Db.txv(() -> {
            Row rfq = open(c, rfqId);
            if (!Set.of("OPEN", "CLOSED", "PARTIALLY_AWARDED").contains(rfq.str("status"))) throw ApiException.conflict("RFQ is " + rfq.str("status").toLowerCase());
            Map<UUID, List<Row>> byVendor = new LinkedHashMap<>();
            Map<UUID, LocalDate> vendorDelivery = new HashMap<>();
            for (JsonNode a : awards) {
                UUID lineId = Json.reqUuid(a, "rfqLineId");
                UUID quoteId = Json.reqUuid(a, "quotationId");
                BigDecimal qty = Json.reqPositive(a, "quantity");
                Row line = Db.one("SELECT * FROM rfq_lines WHERE id = ? AND rfq_id = ? FOR UPDATE", lineId, rfqId);
                if (line == null) throw ApiException.badRequest("Line does not belong to this RFQ");
                Row q = Db.one("""
                        SELECT q.id, q.vendor_id, q.delivery_date, q.status, ql.unit_price, ql.fulfil_qty, v.legal_name, v.status AS vendor_status,
                               v.sap_card_code, v.sap_bp_status
                          FROM quotations q JOIN quotation_lines ql ON ql.quotation_id = q.id AND ql.rfq_line_id = ? JOIN vendors v ON v.id = q.vendor_id
                         WHERE q.id = ? AND q.rfq_id = ?""", lineId, quoteId, rfqId);
                if (q == null) throw ApiException.badRequest("Quotation has no price for line " + line.str("itemCode"));
                if ("WITHDRAWN".equals(q.str("status"))) throw ApiException.conflict(q.str("legalName") + " withdrew the quotation");
                if (!"ACTIVE".equals(q.str("vendorStatus"))) throw ApiException.conflict(q.str("legalName") + " is no longer an active vendor");
                if ("INACTIVE".equals(q.str("sapBpStatus"))) {
                    throw ApiException.conflict(q.str("legalName") + " (" + q.str("sapCardCode") + ") is Inactive in SAP B1 — activate the Business Partner in B1 first");
                }
                if (qty.compareTo(q.dec("fulfilQty")) > 0) throw ApiException.badRequest(q.str("legalName") + " quoted only " + q.dec("fulfilQty").toPlainString() + " for " + line.str("itemCode"));
                BigDecimal remaining = line.dec("quantity").subtract(line.dec("awardedQty"));
                if (qty.compareTo(remaining) > 0) throw ApiException.badRequest(line.str("itemCode") + ": only " + remaining.toPlainString() + " left to award");
                Db.exec("UPDATE rfq_lines SET awarded_qty = awarded_qty + ? WHERE id = ?", qty, lineId);
                Row awardRow = new Row().with("line", line).with("quote", q).with("qty", qty);
                byVendor.computeIfAbsent(q.uuid("vendorId"), k -> new ArrayList<>()).add(awardRow);
                vendorDelivery.merge(q.uuid("vendorId"), q.date("deliveryDate"), (x, y) -> x.isAfter(y) ? x : y);
            }
            for (Map.Entry<UUID, List<Row>> e : byVendor.entrySet()) {
                UUID vendorId = e.getKey();
                Row firstQuote = (Row) e.getValue().get(0).get("quote");
                LocalDate due = dueOverride != null ? dueOverride : vendorDelivery.get(vendorId);
                BigDecimal total = BigDecimal.ZERO;
                UUID poId = Db.scalar("""
                        INSERT INTO purchase_orders(company_id, vendor_id, card_code, source, rfq_id, doc_due_date, currency, created_by_user_id, status, remarks)
                        VALUES (?,?,?, 'RFQ', ?,?,?,?, 'SAP_PENDING', ?) RETURNING id""", UUID.class, c, vendorId, firstQuote.str("sapCardCode"),
                        rfqId, due, settings.str("currency"), u.userId(), "Awarded from " + rfq.str("rfqNo"));
                int n = 0;
                for (Row a : e.getValue()) {
                    Row line = (Row) a.get("line");
                    Row q = (Row) a.get("quote");
                    BigDecimal qty = (BigDecimal) a.get("qty");
                    UUID basePr = Db.scalar("SELECT pr_line_id FROM rfq_line_sources WHERE rfq_line_id = ? LIMIT 1", UUID.class, line.uuid("id"));
                    Integer sources = Db.scalar("SELECT count(*)::int FROM rfq_line_sources WHERE rfq_line_id = ?", Integer.class, line.uuid("id"));
                    Db.exec("""
                            INSERT INTO purchase_order_lines(purchase_order_id, line_num, item_code, item_name, uom, quantity, unit_price, warehouse_code,
                                   tax_code, ship_date, base_pr_line_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                            poId, n++, line.str("itemCode"), line.str("itemName"), line.str("uom"), qty, q.dec("unitPrice"), line.str("warehouseCode"),
                            taxCode, q.date("deliveryDate"), sources != null && sources == 1 ? basePr : null);
                    Db.exec("""
                            INSERT INTO rfq_awards(rfq_id, rfq_line_id, quotation_id, vendor_id, awarded_qty, unit_price, purchase_order_id, awarded_by_user_id)
                            VALUES (?,?,?,?,?,?,?,?)""", rfqId, line.uuid("id"), q.uuid("id"), vendorId, qty, q.dec("unitPrice"), poId, u.userId());
                    total = total.add(qty.multiply(q.dec("unitPrice")));
                }
                Db.exec("UPDATE purchase_orders SET doc_total = ? WHERE id = ?", total, poId);
                poIds.add(poId);
                txs.add(SyncService.enqueue(c, "CREATE_PURCHASE_ORDER", poId, u.userId()));
            }
            Boolean complete = Db.scalar("SELECT bool_and(awarded_qty >= quantity) FROM rfq_lines WHERE rfq_id = ?", Boolean.class, rfqId);
            Db.exec("UPDATE rfqs SET status = ?, updated_at = now() WHERE id = ?", Boolean.TRUE.equals(complete) ? "AWARDED" : "PARTIALLY_AWARDED", rfqId);
            Db.exec("""
                    UPDATE quotations q SET status = CASE
                        WHEN NOT EXISTS (SELECT 1 FROM rfq_awards a WHERE a.quotation_id = q.id) THEN CASE WHEN ? THEN 'NOT_AWARDED' ELSE q.status END
                        WHEN EXISTS (SELECT 1 FROM quotation_lines ql LEFT JOIN (SELECT rfq_line_id, sum(awarded_qty) s FROM rfq_awards WHERE quotation_id = q.id GROUP BY rfq_line_id) aw
                                       ON aw.rfq_line_id = ql.rfq_line_id WHERE ql.quotation_id = q.id AND ql.fulfil_qty > COALESCE(aw.s, 0)) THEN 'PARTIALLY_AWARDED'
                        ELSE 'AWARDED' END, updated_at = now()
                     WHERE q.rfq_id = ? AND q.status <> 'WITHDRAWN'""", Boolean.TRUE.equals(complete), rfqId);
        });
        List<Row> results = new ArrayList<>();
        for (int i = 0; i < txs.size(); i++) {
            Row sync = SyncService.processNow(txs.get(i));
            results.add(Db.one("""
                    SELECT po.id, po.sap_doc_num, po.status, po.doc_total, v.legal_name AS vendor_name FROM purchase_orders po
                      JOIN vendors v ON v.id = po.vendor_id WHERE po.id = ?""", poIds.get(i)).with("sapSync", sync));
        }
        return new Row().with("purchaseOrders", results).with("rfq", detail(c, rfqId));
    }

    /** Scheduler: OPEN RFQs past their due date stop accepting quotes. */
    public static int closeOverdue() {
        // rfqs is a per-company table: close overdue RFQs in each company's schema.
        int[] total = {0};
        Tenants.forEach(companyId -> total[0] +=
                Db.exec("UPDATE rfqs SET status = 'CLOSED', updated_at = now() WHERE status = 'OPEN' AND due_date < CURRENT_DATE"));
        return total[0];
    }

    // =================================================================== vendor side

    @GetMapping("/api/vendor/rfqs")
    @Roles(Role.VENDOR)
    public Object vendorList(CurrentUser u) {
        return Db.query("""
                SELECT r.id, r.rfq_no, r.title, r.due_date, r.status, rv.invited_at, rv.viewed_at,
                       (SELECT count(*) FROM rfq_lines l WHERE l.rfq_id = r.id) AS line_count,
                       q.status AS quote_status, q.submitted_at, q.revision,
                       (SELECT COALESCE(sum(a.awarded_qty * a.unit_price), 0) FROM rfq_awards a WHERE a.rfq_id = r.id AND a.vendor_id = rv.vendor_id) AS awarded_value
                  FROM rfq_vendors rv JOIN rfqs r ON r.id = rv.rfq_id
                  LEFT JOIN quotations q ON q.rfq_id = r.id AND q.vendor_id = rv.vendor_id
                 WHERE rv.vendor_id = ? AND r.company_id = ? AND r.status <> 'CANCELLED'
                 ORDER BY r.due_date DESC""", u.vendor(), u.company());
    }

    private static Row invitedRfq(CurrentUser u, UUID rfqId) {
        Row r = Db.one("""
                SELECT r.id, r.rfq_no, r.title, r.due_date, r.status, r.notes FROM rfqs r
                  JOIN rfq_vendors rv ON rv.rfq_id = r.id AND rv.vendor_id = ? WHERE r.id = ? AND r.company_id = ?""", u.vendor(), rfqId, u.company());
        if (r == null) throw ApiException.notFound("RFQ");
        return r;
    }

    @GetMapping("/api/vendor/rfqs/{id}")
    @Roles(Role.VENDOR)
    public Object vendorDetailEndpoint(@PathVariable String id, CurrentUser u) {
        return vendorDetail(Ids.uuid(id), u);
    }

    private static Row vendorDetail(UUID id, CurrentUser u) {
        Row r = invitedRfq(u, id);
        Db.exec("UPDATE rfq_vendors SET viewed_at = COALESCE(viewed_at, now()) WHERE rfq_id = ? AND vendor_id = ?", id, u.vendor());
        r.put("lines", Db.query("SELECT id, line_num, item_code, item_name, uom, quantity, required_date FROM rfq_lines WHERE rfq_id = ? ORDER BY line_num", id));
        Row q = Db.one("SELECT * FROM quotations WHERE rfq_id = ? AND vendor_id = ?", id, u.vendor());
        if (q != null) q.put("lines", Db.query("SELECT rfq_line_id, unit_price, fulfil_qty, tax_percent FROM quotation_lines WHERE quotation_id = ?", q.uuid("id")));
        r.put("myQuotation", q);
        r.put("myAwards", Db.query("""
                SELECT a.rfq_line_id, a.awarded_qty, a.unit_price, po.sap_doc_num, po.id AS purchase_order_id FROM rfq_awards a
                  LEFT JOIN purchase_orders po ON po.id = a.purchase_order_id WHERE a.rfq_id = ? AND a.vendor_id = ?""", id, u.vendor()));
        r.put("canQuote", "OPEN".equals(r.str("status")) && !r.date("dueDate").isBefore(LocalDate.now()));
        return r;
    }

    @PostMapping("/api/vendor/rfqs/{id}/quote")
    @Roles(Role.VENDOR)
    public Object submitQuote(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        UUID rfqId = Ids.uuid(id);
        LocalDate delivery = Json.reqDate(b, "deliveryDate");
        if (delivery.isBefore(LocalDate.now())) throw ApiException.badRequest("Delivery date cannot be in the past");
        List<JsonNode> lines = Json.reqArray(b, "lines");
        Db.txv(() -> {
            Row r = invitedRfq(u, rfqId);
            Db.one("SELECT id FROM rfqs WHERE id = ? FOR UPDATE", rfqId);
            if (!"OPEN".equals(r.str("status")) || r.date("dueDate").isBefore(LocalDate.now())) {
                throw ApiException.conflict("This RFQ is no longer accepting quotations");
            }
            Map<String, Row> rfqLines = new HashMap<>();
            for (Row l : Db.query("SELECT id, item_code, quantity FROM rfq_lines WHERE rfq_id = ?", rfqId)) rfqLines.put(l.str("id"), l);
            Row existing = Db.one("SELECT id, revision FROM quotations WHERE rfq_id = ? AND vendor_id = ?", rfqId, u.vendor());
            UUID qid;
            if (existing == null) {
                qid = Db.scalar("INSERT INTO quotations(rfq_id, vendor_id, delivery_date, payment_terms, notes) VALUES (?,?,?,?,?) RETURNING id",
                        UUID.class, rfqId, u.vendor(), delivery, Json.optText(b, "paymentTerms"), Json.optText(b, "notes"));
            } else {
                qid = existing.uuid("id");
                Db.exec("""
                        UPDATE quotations SET delivery_date = ?, payment_terms = ?, notes = ?, revision = revision + 1, status = 'SUBMITTED',
                               submitted_at = now(), updated_at = now() WHERE id = ?""", delivery, Json.optText(b, "paymentTerms"), Json.optText(b, "notes"), qid);
                Db.exec("DELETE FROM quotation_lines WHERE quotation_id = ?", qid);
            }
            boolean any = false;
            for (JsonNode l : lines) {
                String lineId = Json.reqText(l, "rfqLineId");
                Row rl = rfqLines.get(lineId);
                if (rl == null) throw ApiException.badRequest("Unknown RFQ line");
                BigDecimal price = Json.reqDec(l, "unitPrice");
                BigDecimal qty = Json.reqDec(l, "fulfilQty");
                if (price.signum() < 0 || qty.signum() < 0) throw ApiException.badRequest("Prices and quantities cannot be negative");
                if (qty.compareTo(rl.dec("quantity")) > 0) throw ApiException.badRequest(rl.str("itemCode") + ": you cannot offer more than the requested " + rl.dec("quantity").toPlainString());
                if (qty.signum() > 0 && price.signum() == 0) throw ApiException.badRequest(rl.str("itemCode") + ": enter a unit price");
                any |= qty.signum() > 0;
                Db.exec("INSERT INTO quotation_lines(quotation_id, rfq_line_id, unit_price, fulfil_qty, tax_percent) VALUES (?,?,?,?,?)",
                        qid, UUID.fromString(lineId), price, qty, Json.optDec(l, "taxPercent"));
            }
            if (!any) throw ApiException.badRequest("Quote at least one line with a quantity greater than zero");
        });
        return vendorDetail(rfqId, u);
    }

    @PostMapping("/api/vendor/rfqs/{id}/withdraw")
    @Roles(Role.VENDOR)
    public Object withdrawQuote(@PathVariable String id, CurrentUser u) {
        UUID rfqId = Ids.uuid(id);
        Db.txv(() -> {
            Row r = invitedRfq(u, rfqId);
            if (!"OPEN".equals(r.str("status"))) throw ApiException.conflict("The RFQ is closed");
            if (Db.exec("UPDATE quotations SET status = 'WITHDRAWN', updated_at = now() WHERE rfq_id = ? AND vendor_id = ? AND status = 'SUBMITTED'",
                    rfqId, u.vendor()) == 0) throw ApiException.conflict("No submitted quotation to withdraw");
        });
        return vendorDetail(rfqId, u);
    }
}
