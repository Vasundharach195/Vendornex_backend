package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.service.PoStatus;
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
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.QueryParams;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Purchase Orders (B1 is authoritative: every PO is created in B1 and carries its DocNum),
 * vendor acknowledgement, ASNs (partial shipments supported) and GRPO confirmation.
 *
 * <p>ASN → GRPO: when a vendor submits an ASN a GRPO <em>draft</em> is created in B1 (Drafts,
 * DocObjectCode oPurchaseDeliveryNotes) based on the PO lines. The buyer confirms what physically
 * arrived — quantity, rejected quantity and warehouse per line — and the GRPO is posted in B1
 * (PurchaseDeliveryNotes, BaseType 22) after which the draft is removed.
 */
@RestController
public class PurchaseOrderController {

    private final PurchaseRequestService purchaseRequestService;

    public PurchaseOrderController(PurchaseRequestService purchaseRequestService) {
        this.purchaseRequestService = purchaseRequestService;
    }

    // =================================================================== reads

    private static final String LIST = """
            SELECT po.id, po.sap_doc_num, po.source, po.doc_date, po.doc_due_date, po.status, po.doc_total, po.currency,
                   po.acknowledged_at, v.id AS vendor_id, v.legal_name AS vendor_name, po.card_code, r.rfq_no,
                   (SELECT count(*) FROM purchase_order_lines l WHERE l.purchase_order_id = po.id) AS line_count,
                   (SELECT count(*) FROM grpos g WHERE g.purchase_order_id = po.id AND g.status IN ('DRAFT_CREATED','DRAFT_FAILED','POST_FAILED')) AS grpo_pending
              FROM purchase_orders po JOIN vendors v ON v.id = po.vendor_id LEFT JOIN rfqs r ON r.id = po.rfq_id
            """;

    @GetMapping("/api/purchase-orders")
    @Roles(Role.ADMIN)
    public Object list(@RequestParam(required = false) String status, @RequestParam(required = false) String vendorId, CurrentUser u) {
        String st = QueryParams.orNull(status);
        String v = QueryParams.orNull(vendorId);
        return Db.query(LIST + " WHERE po.company_id = ? AND (?::text IS NULL OR po.status = ?) AND (?::uuid IS NULL OR po.vendor_id = ?::uuid) " +
                        "ORDER BY po.created_at DESC LIMIT 500",
                u.company(), st, st, v, v);
    }

    @GetMapping("/api/purchase-orders/{id}")
    @Roles(Role.ADMIN)
    public Object detailEndpoint(@PathVariable String id, CurrentUser u) {
        return detail(u.company(), Ids.uuid(id), null);
    }

    @GetMapping("/api/vendor/purchase-orders")
    @Roles(Role.VENDOR)
    public Object vendorList(CurrentUser u) {
        return Db.query(LIST + " WHERE po.company_id = ? AND po.vendor_id = ? AND po.status NOT IN ('SAP_PENDING','SAP_FAILED') " +
                "ORDER BY po.created_at DESC", u.company(), u.vendor());
    }

    @GetMapping("/api/vendor/purchase-orders/{id}")
    @Roles(Role.VENDOR)
    public Object vendorDetailEndpoint(@PathVariable String id, CurrentUser u) {
        return detail(u.company(), Ids.uuid(id), u.vendor());
    }

    /** @param vendorId when set, restricts to that vendor's POs (vendor portal) and hides internal fields. */
    public static Row detail(UUID c, UUID id, UUID vendorId) {
        Row po = Db.one(LIST + " WHERE po.id = ? AND po.company_id = ? AND (?::uuid IS NULL OR po.vendor_id = ?)", id, c, vendorId, vendorId);
        if (po == null || (vendorId != null && Set.of("SAP_PENDING", "SAP_FAILED").contains(po.str("status")))) throw ApiException.notFound("Purchase order");
        Row full = Db.one("SELECT remarks, ack_remarks FROM purchase_orders WHERE id = ?", id);
        po.putAll(full);
        po.put("lines", Db.query("""
                SELECT l.id, l.line_num, l.item_code, l.item_name, l.uom, l.quantity, l.unit_price, l.warehouse_code, w.warehouse_name,
                       l.tax_code, l.ship_date, l.shipped_qty, l.received_qty, l.quantity - l.shipped_qty AS open_to_ship
                  FROM purchase_order_lines l LEFT JOIN sap_warehouses w ON w.company_id = ? AND w.warehouse_code = l.warehouse_code
                 WHERE l.purchase_order_id = ? ORDER BY l.line_num""", c, id));
        List<Row> asns = Db.query("""
                SELECT a.*, g.id AS grpo_id, g.status AS grpo_status, g.sap_draft_entry, g.sap_doc_num AS grpo_doc_num, g.confirmed_at
                  FROM asns a LEFT JOIN grpos g ON g.asn_id = a.id WHERE a.purchase_order_id = ? ORDER BY a.submitted_at""", id);
        for (Row a : asns) {
            a.put("lines", Db.query("""
                    SELECT al.po_line_id, al.shipped_qty, al.batch_no, pl.item_code, pl.item_name, pl.uom, pl.warehouse_code,
                           gl.received_qty, gl.rejected_qty, gl.warehouse_code AS received_warehouse
                      FROM asn_lines al JOIN purchase_order_lines pl ON pl.id = al.po_line_id
                      LEFT JOIN grpos g ON g.asn_id = al.asn_id LEFT JOIN grpo_lines gl ON gl.grpo_id = g.id AND gl.po_line_id = al.po_line_id
                     WHERE al.asn_id = ? ORDER BY pl.line_num""", a.uuid("id")));
        }
        po.put("asns", asns);
        if (vendorId == null) po.put("sapSync", SyncService.latestFor("PURCHASE_ORDER", id));
        return po;
    }

    // =================================================================== create

    private record LineIn(String itemCode, BigDecimal qty, BigDecimal price, String wh, String tax, LocalDate shipDate, UUID prLineId) {}

    @PostMapping("/api/purchase-orders")
    @Roles(Role.ADMIN)
    public Object createDirect(@RequestBody JsonNode b, CurrentUser u) {
        List<LineIn> lines = new ArrayList<>();
        for (JsonNode l : Json.reqArray(b, "lines")) {
            lines.add(new LineIn(Json.reqText(l, "itemCode"), Json.reqPositive(l, "quantity"), Json.reqDec(l, "unitPrice"),
                    Json.optText(l, "warehouseCode"), Json.optText(l, "taxCode"), Json.optDate(l, "shipDate"), null));
        }
        return create(u, Json.reqUuid(b, "vendorId"), Json.reqDate(b, "docDueDate"), Json.optText(b, "remarks"), "DIRECT", lines);
    }

    /** Convert approved PR lines straight into a PO (no RFQ). Body lines: [{prLineId, quantity, unitPrice, taxCode?}] */
    @PostMapping("/api/purchase-orders/from-prs")
    @Roles(Role.ADMIN)
    public Object createFromPrs(@RequestBody JsonNode b, CurrentUser u) {
        UUID c = u.company();
        List<LineIn> lines = new ArrayList<>();
        for (JsonNode l : Json.reqArray(b, "lines")) {
            UUID prLine = Json.reqUuid(l, "prLineId");
            Row pl = Db.one("""
                    SELECT l.item_code, l.warehouse_code, l.required_date FROM purchase_request_lines l
                      JOIN purchase_requests p ON p.id = l.purchase_request_id WHERE l.id = ? AND p.company_id = ?""", prLine, c);
            if (pl == null) throw ApiException.badRequest("PR line not found");
            lines.add(new LineIn(pl.str("itemCode"), Json.reqPositive(l, "quantity"), Json.reqDec(l, "unitPrice"),
                    Optional.ofNullable(Json.optText(l, "warehouseCode")).orElse(pl.str("warehouseCode")), Json.optText(l, "taxCode"),
                    pl.date("requiredDate"), prLine));
        }
        return create(u, Json.reqUuid(b, "vendorId"), Json.reqDate(b, "docDueDate"), Json.optText(b, "remarks"), "PR", lines);
    }

    private Row create(CurrentUser u, UUID vendorId, LocalDate due, String remarks, String source, List<LineIn> lines) {
        UUID c = u.company();
        if (due.isBefore(LocalDate.now())) throw ApiException.badRequest("Delivery date cannot be in the past");
        Row settings = Settings.of(c);
        Set<UUID> prs = new HashSet<>();
        UUID[] out = new UUID[2];
        Db.txv(() -> {
            Row v = Db.one("SELECT legal_name, status, sap_card_code, sap_bp_status FROM vendors WHERE id = ? AND company_id = ?", vendorId, c);
            if (v == null) throw ApiException.badRequest("Vendor not found");
            if (!"ACTIVE".equals(v.str("status")) || v.str("sapCardCode") == null) throw ApiException.badRequest(v.str("legalName") + " is not an active vendor with a B1 code");
            if ("INACTIVE".equals(v.str("sapBpStatus"))) {
                throw ApiException.conflict(v.str("legalName") + " (" + v.str("sapCardCode") + ") is Inactive in SAP B1 — activate the Business Partner in B1 first");
            }
            UUID poId = Db.scalar("""
                    INSERT INTO purchase_orders(company_id, vendor_id, card_code, source, doc_due_date, currency, remarks, created_by_user_id, status)
                    VALUES (?,?,?,?,?,?,?,?, 'SAP_PENDING') RETURNING id""", UUID.class, c, vendorId, v.str("sapCardCode"), source, due,
                    settings.str("currency"), remarks, u.userId());
            BigDecimal total = BigDecimal.ZERO;
            int n = 0;
            for (LineIn l : lines) {
                Row it = Db.one("SELECT item_name, purchase_uom FROM sap_items WHERE company_id = ? AND item_code = ? AND is_active", c, l.itemCode());
                if (it == null) throw ApiException.badRequest("Line " + (n + 1) + ": item " + l.itemCode() + " is not an active purchase item in SAP B1");
                if (l.price().signum() < 0) throw ApiException.badRequest("Line " + (n + 1) + ": price cannot be negative");
                String wh = l.wh() != null ? l.wh() : settings.str("defaultWarehouseCode");
                if (wh == null || Db.scalar("SELECT 1 FROM sap_warehouses WHERE company_id = ? AND warehouse_code = ? AND is_active", Integer.class, c, wh) == null) {
                    throw ApiException.badRequest("Line " + (n + 1) + ": choose a valid SAP B1 warehouse");
                }
                String tax = l.tax() != null ? l.tax() : settings.str("defaultTaxCode");
                if (tax != null && Db.scalar("SELECT 1 FROM sap_tax_codes WHERE company_id = ? AND tax_code = ?", Integer.class, c, tax) == null) {
                    throw ApiException.badRequest("Line " + (n + 1) + ": tax code " + tax + " not found in SAP B1");
                }
                if (l.prLineId() != null) prs.add(RfqController.consumePrLine(c, l.prLineId(), l.itemCode(), l.qty()));
                Db.exec("""
                        INSERT INTO purchase_order_lines(purchase_order_id, line_num, item_code, item_name, uom, quantity, unit_price, warehouse_code,
                               tax_code, ship_date, base_pr_line_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                        poId, n++, l.itemCode(), it.str("itemName"), it.str("purchaseUom"), l.qty(), l.price(), wh, tax,
                        l.shipDate() == null ? due : l.shipDate(), l.prLineId());
                total = total.add(l.qty().multiply(l.price()));
            }
            Db.exec("UPDATE purchase_orders SET doc_total = ? WHERE id = ?", total, poId);
            out[0] = poId;
            out[1] = SyncService.enqueue(c, "CREATE_PURCHASE_ORDER", poId, u.userId());
        });
        prs.forEach(purchaseRequestService::recomputeSourcing);
        Row sync = SyncService.processNow(out[1]);
        return detail(c, out[0], null).with("sapSyncResult", sync);
    }

    @PostMapping("/api/purchase-orders/{id}/retry-sap")
    @Roles(Role.ADMIN)
    public Object retry(@PathVariable String id, CurrentUser u) {
        UUID c = u.company();
        UUID poId = Ids.uuid(id);
        Row po = Db.one("SELECT id, status FROM purchase_orders WHERE id = ? AND company_id = ?", poId, c);
        if (po == null) throw ApiException.notFound("Purchase order");
        if (!"SAP_FAILED".equals(po.str("status"))) throw ApiException.conflict("PO is not in a failed state");
        SyncService.retry(c, SyncService.latestFor("PURCHASE_ORDER", poId).uuid("id"));
        return detail(c, poId, null);
    }

    /** Only POs that never reached B1 can be cancelled here; B1 POs are closed/cancelled in B1. */
    @PostMapping("/api/purchase-orders/{id}/cancel")
    @Roles(Role.ADMIN)
    public Object cancel(@PathVariable String id, CurrentUser u) {
        UUID c = u.company();
        UUID poId = Ids.uuid(id);
        Set<UUID> prs = new HashSet<>();
        Db.txv(() -> {
            Row po = Db.one("SELECT id, status, rfq_id FROM purchase_orders WHERE id = ? AND company_id = ? FOR UPDATE", poId, c);
            if (po == null) throw ApiException.notFound("Purchase order");
            if (!"SAP_FAILED".equals(po.str("status"))) throw ApiException.conflict("Only POs that failed to post to SAP B1 can be cancelled here — cancel B1 POs in SAP B1");
            for (Row l : Db.query("""
                    SELECT l.base_pr_line_id, l.quantity, pl.purchase_request_id FROM purchase_order_lines l
                      JOIN purchase_request_lines pl ON pl.id = l.base_pr_line_id WHERE l.purchase_order_id = ? AND ? = 'PR'""",
                    poId, Db.scalar("SELECT source FROM purchase_orders WHERE id = ?", String.class, poId))) {
                Db.exec("UPDATE purchase_request_lines SET sourced_qty = GREATEST(0, sourced_qty - ?) WHERE id = ?", l.dec("quantity"), l.uuid("basePrLineId"));
                prs.add(l.uuid("purchaseRequestId"));
            }
            if (po.get("rfqId") != null) {
                for (Row a : Db.query("SELECT rfq_line_id, awarded_qty FROM rfq_awards WHERE purchase_order_id = ?", poId)) {
                    Db.exec("UPDATE rfq_lines SET awarded_qty = GREATEST(0, awarded_qty - ?) WHERE id = ?", a.dec("awardedQty"), a.uuid("rfqLineId"));
                }
                Db.exec("UPDATE rfq_awards SET purchase_order_id = NULL WHERE purchase_order_id = ?", poId);
                Db.exec("DELETE FROM rfq_awards WHERE purchase_order_id IS NULL AND rfq_id = ?", po.uuid("rfqId"));
                Db.exec("""
                        UPDATE rfqs SET status = CASE WHEN EXISTS (SELECT 1 FROM rfq_awards WHERE rfq_id = rfqs.id) THEN 'PARTIALLY_AWARDED' ELSE 'CLOSED' END
                         WHERE id = ?""", po.uuid("rfqId"));
            }
            Db.exec("UPDATE purchase_orders SET status = 'CANCELLED', updated_at = now() WHERE id = ?", poId);
            for (UUID pr : prs) {
                Db.exec("""
                        UPDATE purchase_requests p SET status = CASE WHEN EXISTS (SELECT 1 FROM purchase_request_lines l WHERE l.purchase_request_id = p.id AND l.sourced_qty > 0)
                               THEN 'PARTIALLY_SOURCED' ELSE 'APPROVED' END WHERE p.id = ?""", pr);
            }
        });
        return detail(c, poId, null);
    }

    // =================================================================== vendor actions

    @PostMapping("/api/vendor/purchase-orders/{id}/acknowledge")
    @Roles(Role.VENDOR)
    public Object acknowledge(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        UUID poId = Ids.uuid(id);
        boolean accept = Json.optBool(b, "accept", true);
        String remarks = Json.optText(b, "remarks");
        if (!accept && remarks == null) throw ApiException.badRequest("Tell the buyer why you are declining this PO");
        Db.txv(() -> {
            Row po = Db.one("SELECT id, status FROM purchase_orders WHERE id = ? AND company_id = ? AND vendor_id = ? FOR UPDATE", poId, u.company(), u.vendor());
            if (po == null) throw ApiException.notFound("Purchase order");
            if (!"PENDING_ACK".equals(po.str("status"))) throw ApiException.conflict("This PO has already been acknowledged");
            Db.exec("""
                    UPDATE purchase_orders SET status = ?, acknowledged_at = now(), acknowledged_by_user_id = ?, ack_remarks = ?, updated_at = now()
                     WHERE id = ?""", accept ? "ACKNOWLEDGED" : "DECLINED", u.userId(), remarks, poId);
        });
        return detail(u.company(), poId, u.vendor());
    }

    /**
     * ASN (Advance Shipping Notice): the vendor tells the buyer "I have shipped these quantities".
     * Partial shipments are allowed, so one PO can have several ASNs.
     *
     * <p>Body: {shipDate, expectedDelivery, carrier, trackingNo, vendorInvoiceNo?, cartons?, pallets?,
     * totalWeight?, lines:[{poLineId, shippedQty, batchNo?}]}
     *
     * <p>What this method does (all in ONE DB transaction):
     * <ol>
     *   <li>Checks the PO belongs to this vendor and is in a shippable status.</li>
     *   <li>Creates the ASN header (number ASN-YYYY-NNNN) and one asn_lines row per shipped line.</li>
     *   <li>Adds the shipped quantity to purchase_order_lines.shipped_qty.</li>
     *   <li>Creates a GRPO record (status DRAFT_PENDING) with grpo_lines pre-filled as
     *       "received = shipped" — the buyer corrects these later when the goods arrive.</li>
     *   <li>Recalculates the PO status (PARTIALLY_SHIPPED / SHIPPED).</li>
     *   <li>Queues a CREATE_GRPO_DRAFT job in the SAP outbox (sync_transactions).</li>
     * </ol>
     *
     * <p>What happens AFTER the ASN (outside this transaction):
     * <ol>
     *   <li>{@code SyncService.processNow} sends the GRPO draft to SAP B1 (Drafts, DocObjectCode
     *       oPurchaseDeliveryNotes). Success: grpos → DRAFT_CREATED (sap_draft_entry saved) and the
     *       ASN → GRPO_DRAFTED. SAP down: stays queued and the background worker retries. SAP rejects
     *       the data: grpos → DRAFT_FAILED.</li>
     *   <li>The GRPO now appears in the buyer's queue ({@code GET /api/grpos}).</li>
     *   <li>When the goods physically arrive, the buyer calls {@code POST /api/grpos/{id}/confirm}
     *       with accepted / rejected quantity and warehouse per line — see {@link #confirmGrpo}.</li>
     * </ol>
     *
     * <p>The ASN itself is saved even if the SAP draft fails, so the vendor's shipment is never lost.
     */
    @PostMapping("/api/vendor/purchase-orders/{id}/asns")
    @Roles(Role.VENDOR)
    public Object submitAsn(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        UUID c = u.company();
        UUID poId = Ids.uuid(id);

        // ---- 1. Validate the shipment header (dates, carrier, tracking) before touching the DB.
        LocalDate ship = Json.reqDate(b, "shipDate");
        LocalDate expected = Json.reqDate(b, "expectedDelivery");
        if (expected.isBefore(ship)) throw ApiException.badRequest("Expected delivery cannot be before the ship date");
        String carrier = Json.reqText(b, "carrier", 100);
        String tracking = Json.reqText(b, "trackingNo", 100);
        List<JsonNode> lines = Json.reqArray(b, "lines");

        // ids[0] = new ASN id, ids[1] = outbox (sync_transactions) id for the SAP GRPO draft.
        // An array is used because lambdas can only write to effectively-final holders.
        UUID[] ids = new UUID[2];
        Db.txv(() -> {
            // ---- 2. Lock the PO row (FOR UPDATE) so two ASNs for the same PO cannot over-ship at the same time.
            // The vendor_id filter makes sure a vendor can only ship against their own POs.
            Row po = Db.one("SELECT * FROM purchase_orders WHERE id = ? AND company_id = ? AND vendor_id = ? FOR UPDATE", poId, c, u.vendor());
            if (po == null) throw ApiException.notFound("Purchase order");
            // Shipping is allowed only after the vendor acknowledged the PO and while quantity is still open.
            // (PENDING_ACK, DECLINED, SHIPPED, COMPLETED, CANCELLED, SAP_* are all blocked.)
            if (!Set.of("ACKNOWLEDGED", "PARTIALLY_SHIPPED", "PARTIALLY_RECEIVED").contains(po.str("status"))) {
                throw ApiException.conflict("PO must be acknowledged and have quantity left to ship (status: " + po.str("status").toLowerCase().replace('_', ' ') + ")");
            }

            // ---- 3. Create the ASN header with the next company-wide ASN number (e.g. ASN-2026-0007).
            UUID asnId = Db.scalar("""
                    INSERT INTO asns(company_id, purchase_order_id, asn_no, ship_date, expected_delivery, carrier, tracking_no, vendor_invoice_no,
                           cartons, pallets, total_weight, submitted_by_user_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id""", UUID.class,
                    c, poId, DocNumbers.next(c, "ASN"), ship, expected, carrier, tracking, Json.optText(b, "vendorInvoiceNo"),
                    Json.optInt(b, "cartons"), Json.optInt(b, "pallets"), Json.optText(b, "totalWeight"), u.userId());
            // ---- 4. One asn_lines row per PO line the vendor is shipping now.
            boolean any = false;
            for (JsonNode l : lines) {
                UUID lineId = Json.reqUuid(l, "poLineId");
                BigDecimal qty = Json.optDec(l, "shippedQty");
                // Blank / zero quantity = "not shipping this line in this ASN" → skip it.
                if (qty == null || qty.signum() == 0) continue;
                if (qty.signum() < 0) throw ApiException.badRequest("Shipped quantity cannot be negative");
                // Lock the PO line too, and make sure it really belongs to this PO.
                Row pl = Db.one("SELECT item_code, quantity, shipped_qty FROM purchase_order_lines WHERE id = ? AND purchase_order_id = ? FOR UPDATE", lineId, poId);
                if (pl == null) throw ApiException.badRequest("Line does not belong to this PO");
                // Open to ship = ordered qty − already shipped on earlier ASNs. The vendor cannot ship more than that.
                BigDecimal open = pl.dec("quantity").subtract(pl.dec("shippedQty"));
                if (qty.compareTo(open) > 0) throw ApiException.badRequest(pl.str("itemCode") + ": only " + open.toPlainString() + " left to ship");
                Db.exec("INSERT INTO asn_lines(asn_id, po_line_id, shipped_qty, batch_no) VALUES (?,?,?,?)", asnId, lineId, qty, Json.optText(l, "batchNo"));
                // Running total of shipped qty on the PO line (drives PO status and the "left to ship" check above).
                Db.exec("UPDATE purchase_order_lines SET shipped_qty = shipped_qty + ? WHERE id = ?", qty, lineId);
                any = true;
            }
            if (!any) throw ApiException.badRequest("Enter a shipped quantity for at least one line");

            // ---- 5. Create the GRPO (Goods Receipt PO) that the buyer will confirm when the goods arrive.
            // One GRPO per ASN. It starts as DRAFT_PENDING (default) until SAP creates the draft.
            UUID grpoId = Db.scalar("INSERT INTO grpos(company_id, asn_id, purchase_order_id) VALUES (?,?,?) RETURNING id", UUID.class, c, asnId, poId);
            // GRPO lines are pre-filled from the ASN: asn_qty = what the vendor says they shipped,
            // received_qty = same value as a default (buyer edits it on confirm), warehouse = the PO line's warehouse.
            for (Row al : Db.query("SELECT al.po_line_id, al.shipped_qty, pl.warehouse_code FROM asn_lines al JOIN purchase_order_lines pl ON pl.id = al.po_line_id WHERE al.asn_id = ?", asnId)) {
                Db.exec("INSERT INTO grpo_lines(grpo_id, po_line_id, asn_qty, received_qty, warehouse_code) VALUES (?,?,?,?,?)",
                        grpoId, al.uuid("poLineId"), al.dec("shippedQty"), al.dec("shippedQty"), al.str("warehouseCode"));
            }

            // ---- 6. Recalculate PO status from line quantities → PARTIALLY_SHIPPED or SHIPPED.
            PoStatus.recompute(poId);

            // ---- 7. Queue the SAP write in the outbox (same transaction), so if this transaction rolls back
            // nothing is sent to SAP, and if it commits the SAP job is guaranteed to exist.
            ids[0] = asnId;
            ids[1] = SyncService.enqueue(c, "CREATE_GRPO_DRAFT", grpoId, u.userId());
        });

        // ---- 8. AFTER commit: try the SAP call right away so the vendor normally sees the result now.
        //   OK          → grpos.status = DRAFT_CREATED (sap_draft_entry saved), asns.status = GRPO_DRAFTED
        //   SAP offline → stays QUEUED; SchedulingConfig.outboxWorker retries with backoff
        //   SAP rejects → grpos.status = DRAFT_FAILED; admin can fix data and call POST /api/grpos/{id}/retry
        // Any of these outcomes still returns 200 here: the ASN is already safely saved.
        //
        // NEXT STEP: the GRPO shows in the buyer's queue (GET /api/grpos). When the goods arrive the buyer
        // confirms it via POST /api/grpos/{id}/confirm (see confirmGrpo below).
        SyncService.processNow(ids[1]);
        return detail(c, poId, u.vendor());
    }

    // =================================================================== GRPO (buyer)

    @GetMapping("/api/grpos")
    @Roles(Role.ADMIN)
    public Object grpoQueue(@RequestParam(required = false) String status, CurrentUser u) {
        String st = QueryParams.orNull(status);
        return Db.query("""
                SELECT g.id, g.status, g.sap_draft_entry, g.sap_doc_num, g.confirmed_at, g.created_at, a.asn_no, a.carrier, a.tracking_no,
                       a.expected_delivery, po.id AS purchase_order_id, po.sap_doc_num AS po_doc_num, v.legal_name AS vendor_name,
                       (SELECT sum(gl.asn_qty) FROM grpo_lines gl WHERE gl.grpo_id = g.id) AS asn_qty
                  FROM grpos g JOIN asns a ON a.id = g.asn_id JOIN purchase_orders po ON po.id = g.purchase_order_id JOIN vendors v ON v.id = po.vendor_id
                 WHERE g.company_id = ? AND (?::text IS NULL OR g.status = ?)
                 ORDER BY g.created_at DESC LIMIT 300""", u.company(), st, st);
    }

    /**
     * Step after the ASN: the buyer confirms what physically arrived and the GRPO is posted in SAP B1.
     *
     * <p>Body: {postingDate?, lines:[{poLineId, receivedQty, rejectedQty, warehouseCode}]}
     *
     * <p>In ONE DB transaction:
     * <ol>
     *   <li>GRPO must be DRAFT_CREATED (or DRAFT_FAILED, so a buyer is not blocked by a failed draft).</li>
     *   <li>For each line, save accepted (receivedQty) and rejected qty and the warehouse. Accepted qty
     *       cannot exceed what is still open to receive on the PO line; the warehouse must exist in SAP.</li>
     *   <li>GRPO → POSTING.</li>
     *   <li>Shipped-but-not-accepted quantity is given back to "open to ship" on the PO line, so the
     *       vendor can ship it again on a new ASN.</li>
     *   <li>Queues a POST_GRPO job in the SAP outbox.</li>
     * </ol>
     *
     * <p>After commit, {@code SyncService.processNow} posts the GRPO to SAP (PurchaseDeliveryNotes,
     * based on the PO). On success the sync service:
     * <ul>
     *   <li>sets grpos → POSTED with SAP DocEntry / DocNum;</li>
     *   <li>adds the accepted qty to purchase_order_lines.received_qty;</li>
     *   <li>sets the ASN → RECEIVED;</li>
     *   <li>recalculates the PO status → PARTIALLY_RECEIVED or COMPLETED;</li>
     *   <li>deletes the now-unneeded GRPO draft in SAP.</li>
     * </ul>
     * On SAP failure the GRPO becomes POST_FAILED and can be retried with {@link #retryGrpo}.
     * Posted receipts also feed the vendor scorecard (OTIF and quality %).
     */
    @PostMapping("/api/grpos/{id}/confirm")
    @Roles(Role.ADMIN)
    public Object confirmGrpo(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        UUID c = u.company();
        UUID grpoId = Ids.uuid(id);
        LocalDate posting = Optional.ofNullable(Json.optDate(b, "postingDate")).orElse(LocalDate.now());
        UUID[] tx = new UUID[1];
        UUID poId = Db.tx(() -> {
            Row g = Db.one("SELECT * FROM grpos WHERE id = ? AND company_id = ? FOR UPDATE", grpoId, c);
            if (g == null) throw ApiException.notFound("GRPO");
            if (!Set.of("DRAFT_CREATED", "DRAFT_FAILED").contains(g.str("status"))) {
                throw ApiException.conflict("GRPO is " + g.str("status").toLowerCase().replace('_', ' '));
            }
            BigDecimal total = BigDecimal.ZERO;
            for (JsonNode l : Json.optArray(b, "lines")) {
                UUID lineId = Json.reqUuid(l, "poLineId");
                Row gl = Db.one("""
                        SELECT gl.id, gl.asn_qty, pl.item_code, pl.quantity, pl.received_qty FROM grpo_lines gl JOIN purchase_order_lines pl ON pl.id = gl.po_line_id
                         WHERE gl.grpo_id = ? AND gl.po_line_id = ?""", grpoId, lineId);
                if (gl == null) throw ApiException.badRequest("Line is not part of this receipt");
                BigDecimal received = Json.reqDec(l, "receivedQty");
                BigDecimal rejected = Optional.ofNullable(Json.optDec(l, "rejectedQty")).orElse(BigDecimal.ZERO);
                if (received.signum() < 0 || rejected.signum() < 0) throw ApiException.badRequest("Quantities cannot be negative");
                BigDecimal openToReceive = gl.dec("quantity").subtract(gl.dec("receivedQty"));
                if (received.compareTo(openToReceive) > 0) {
                    throw ApiException.badRequest(gl.str("itemCode") + ": accepting " + received.toPlainString() + " exceeds the " + openToReceive.toPlainString() + " still open on the PO");
                }
                String wh = Optional.ofNullable(Json.optText(l, "warehouseCode")).orElse(null);
                if (wh != null && Db.scalar("SELECT 1 FROM sap_warehouses WHERE company_id = ? AND warehouse_code = ?", Integer.class, c, wh) == null) {
                    throw ApiException.badRequest("Warehouse " + wh + " not found in SAP B1");
                }
                Db.exec("UPDATE grpo_lines SET received_qty = ?, rejected_qty = ?, warehouse_code = COALESCE(?, warehouse_code) WHERE id = ?",
                        received, rejected, wh, gl.uuid("id"));
                total = total.add(received);
            }
            BigDecimal sum = Db.scalar("SELECT COALESCE(sum(received_qty), 0) FROM grpo_lines WHERE grpo_id = ?", BigDecimal.class, grpoId);
            if (sum.signum() <= 0) throw ApiException.badRequest("Nothing to receive — enter an accepted quantity on at least one line");
            Db.exec("UPDATE grpos SET status = 'POSTING', posting_date = ?, confirmed_by_user_id = ?, confirmed_at = now() WHERE id = ?",
                    posting, u.userId(), grpoId);
            // Quantity the vendor shipped but the buyer did not accept goes back to "open to ship".
            Db.exec("""
                    UPDATE purchase_order_lines pl SET shipped_qty = GREATEST(0, pl.shipped_qty - (gl.asn_qty - gl.received_qty))
                      FROM grpo_lines gl WHERE gl.grpo_id = ? AND gl.po_line_id = pl.id AND gl.asn_qty > gl.received_qty""", grpoId);
            tx[0] = SyncService.enqueue(c, "POST_GRPO", grpoId, u.userId());
            return g.uuid("purchaseOrderId");
        });
        Row sync = SyncService.processNow(tx[0]);
        return detail(c, poId, null).with("sapSyncResult", sync);
    }

    /**
     * Admin retry of the latest FAILED SAP call for this GRPO — either the draft (CREATE_GRPO_DRAFT,
     * after an ASN) or the posting (POST_GRPO, after confirm). The SAP payload is rebuilt from the
     * current data, so any fix the admin made is picked up.
     */
    @PostMapping("/api/grpos/{id}/retry")
    @Roles(Role.ADMIN)
    public Object retryGrpo(@PathVariable String id, CurrentUser u) {
        UUID c = u.company();
        UUID grpoId = Ids.uuid(id);
        Row g = Db.one("SELECT id, status, purchase_order_id FROM grpos WHERE id = ? AND company_id = ?", grpoId, c);
        if (g == null) throw ApiException.notFound("GRPO");
        Row last = SyncService.latestFor("GRPO", grpoId);
        if (last == null || !"FAILED".equals(last.str("status"))) throw ApiException.conflict("Nothing to retry");
        SyncService.retry(c, last.uuid("id"));
        return detail(c, g.uuid("purchaseOrderId"), null);
    }
}
