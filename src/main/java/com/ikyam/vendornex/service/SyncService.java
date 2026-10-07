package com.ikyam.vendornex.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikyam.vendornex.common.Settings;
import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.sap.B1Payloads;
import com.ikyam.vendornex.sap.SapB1Gateway;
import com.ikyam.vendornex.sap.SapException;
import com.ikyam.vendornex.sap.SapGatewayFactory;
import com.ikyam.vendornex.tenant.Tenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Transactional outbox for every write to SAP Business One (table sync_transactions).
 *
 * <p>Flow: business code calls {@link #enqueue} inside its own DB transaction, commits, then calls
 * {@link #processNow} so the user normally sees the B1 result in the same request. If B1 is
 * unreachable the row stays QUEUED and the background worker retries with exponential backoff.
 * Business-rule rejections from B1 (HTTP 4xx) are not retried: the row goes to FAILED, the source
 * record is flagged, and an admin fixes the data and presses Retry (which rebuilds the payload).
 */
public final class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private SyncService() {}

    private record Built(String sourceType, String target, ObjectNode payload) {}

    // ================================================================= enqueue / retry

    public static UUID enqueue(UUID companyId, String op, UUID sourceId, UUID userId) {
        Built b = build(companyId, op, sourceId, null);
        return Db.scalar("""
                INSERT INTO sync_transactions(company_id, operation, source_type, source_id, target_b1_object, payload_snapshot, requested_by_user_id)
                VALUES (?,?,?,?,?,?,?) RETURNING id""", UUID.class, companyId, op, b.sourceType(), sourceId, b.target(), b.payload(), userId);
    }

    /** Admin retry of a FAILED row — rebuilds the payload from current data (the admin may have fixed it). */
    public static Row retry(UUID companyId, UUID txId) {
        Row tx = Db.one("SELECT * FROM sync_transactions WHERE id = ? AND company_id = ?", txId, companyId);
        if (tx == null) throw ApiException.notFound("Sync transaction");
        if (!"FAILED".equals(tx.str("status"))) throw ApiException.conflict("Only failed transactions can be retried");
        Built b = build(companyId, tx.str("operation"), tx.uuid("sourceId"), (JsonNode) tx.get("payloadSnapshot"));
        Db.txv(() -> {
            Db.exec("""
                    UPDATE sync_transactions SET status = 'QUEUED', attempts = 0, next_attempt_at = now(), payload_snapshot = ?,
                           error_detail = NULL, resolved_at = NULL WHERE id = ?""", b.payload(), txId);
            markSourcePending(tx.str("operation"), tx.uuid("sourceId"));
        });
        return processNow(txId);
    }

    /** Latest transaction for a source record, if any. */
    public static Row latestFor(String sourceType, UUID sourceId) {
        return Db.one("""
                SELECT id, operation, target_b1_object, status, attempts, sap_doc_entry, sap_key, error_detail, created_at, resolved_at
                  FROM sync_transactions WHERE source_type = ? AND source_id = ? ORDER BY created_at DESC LIMIT 1""", sourceType, sourceId);
    }

    // ================================================================= processing

    /** Attempts one row immediately (call after the enqueueing transaction has committed). */
    public static Row processNow(UUID txId) {
        Row tx = Db.one("""
                UPDATE sync_transactions SET status = 'RUNNING', attempts = attempts + 1, next_attempt_at = now()
                 WHERE id = ? AND status = 'QUEUED' RETURNING *""", txId);
        if (tx != null) execute(tx);
        return Db.one("SELECT id, operation, status, attempts, sap_doc_entry, sap_key, error_detail FROM sync_transactions WHERE id = ?", txId);
    }

    /** Background worker tick: claims due rows with SKIP LOCKED so several instances can run safely. */
    public static int runWorker(int batch) {
        // sync_transactions is a per-company table: the worker visits each company's schema in turn.
        int[] total = {0};
        Tenants.forEach(companyId -> total[0] += runWorkerForCurrentCompany(batch));
        return total[0];
    }

    private static int runWorkerForCurrentCompany(int batch) {
        // Rows stuck in RUNNING (instance died mid-call) are released after 10 minutes.
        Db.exec("UPDATE sync_transactions SET status = 'QUEUED' WHERE status = 'RUNNING' AND next_attempt_at < now() - interval '10 minutes'");
        List<Row> claimed = Db.query("""
                UPDATE sync_transactions SET status = 'RUNNING', attempts = attempts + 1, next_attempt_at = now()
                 WHERE id IN (SELECT id FROM sync_transactions
                               WHERE status = 'QUEUED' AND next_attempt_at <= now()
                               ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING *""", batch);
        claimed.forEach(SyncService::execute);
        return claimed.size();
    }

    private static void execute(Row tx) {
        String op = tx.str("operation");
        UUID companyId = tx.uuid("companyId");
        UUID sourceId = tx.uuid("sourceId");
        ObjectNode payload = (ObjectNode) tx.get("payloadSnapshot");
        try {
            SapB1Gateway g = SapGatewayFactory.forCompany(companyId);
            SapB1Gateway.Result r = switch (op) {
                case "CREATE_VENDOR" -> {
                    String code = payload.path("CardCode").asText(null);
                    if (code != null) {
                        String existing = g.businessPartnerName(code);
                        // Same code + same name: an earlier attempt succeeded but the response was lost — treat as done.
                        if (existing != null && existing.equals(payload.path("CardName").asText())) {
                            yield new SapB1Gateway.Result(null, null, code, payload);
                        }
                        if (existing != null) {
                            throw SapException.business("Business Partner code " + code + " is already used in SAP B1 by '" + existing
                                    + "'. Press Retry to allocate the next free code, or adjust the code sequence in Settings.");
                        }
                    }
                    yield g.createBusinessPartner(payload);
                }
                case "CREATE_PURCHASE_REQUEST" -> g.createPurchaseRequest(payload);
                case "CREATE_PURCHASE_ORDER" -> g.createPurchaseOrder(payload);
                case "CREATE_GRPO_DRAFT" -> g.createDraft(payload);
                case "POST_GRPO" -> g.createGoodsReceiptPo(payload);
                default -> throw new IllegalStateException("Unknown operation " + op);
            };
            Db.txv(() -> {
                applySuccess(op, companyId, sourceId, r);
                Db.exec("""
                        UPDATE sync_transactions SET status = 'POSTED', sap_doc_entry = ?, sap_key = ?, response_snapshot = ?,
                               error_detail = NULL, resolved_at = now() WHERE id = ?""",
                        r.docEntry(), r.key(), r.raw() == null ? null : trimResponse(r.raw()), tx.uuid("id"));
            });
            if ("POST_GRPO".equals(op)) deleteDraftQuietly(g, sourceId);
            log.info("B1 {} for {} posted (key {})", op, sourceId, r.key());
        } catch (SapException e) {
            fail(tx, e.getMessage(), e.retryable);
        } catch (Exception e) {
            log.error("Sync {} failed unexpectedly", op, e);
            fail(tx, e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private static JsonNode trimResponse(JsonNode raw) {
        ObjectNode o = com.ikyam.vendornex.http.Json.obj();
        for (String f : List.of("CardCode", "CardName", "DocEntry", "DocNum", "DocTotal", "DocumentStatus")) {
            if (raw.has(f)) o.set(f, raw.get(f));
        }
        return o;
    }

    private static void fail(Row tx, String message, boolean retryable) {
        int attempts = tx.integer("attempts");
        boolean exhausted = !retryable || attempts >= tx.integer("maxAttempts");
        log.warn("B1 {} for {} failed (attempt {}, {}): {}", tx.str("operation"), tx.str("sourceId"), attempts,
                exhausted ? "giving up" : "will retry", message);
        Db.txv(() -> {
            if (exhausted) {
                Db.exec("UPDATE sync_transactions SET status = 'FAILED', error_detail = ?, resolved_at = now() WHERE id = ?", message, tx.uuid("id"));
                markSourceFailed(tx.str("operation"), tx.uuid("sourceId"), message);
            } else {
                long delaySec = 30L * (1L << Math.min(attempts - 1, 6));
                Db.exec("UPDATE sync_transactions SET status = 'QUEUED', error_detail = ?, next_attempt_at = now() + (? * interval '1 second') WHERE id = ?",
                        message, delaySec, tx.uuid("id"));
            }
        });
    }

    // ================================================================= effects on source records

    private static void applySuccess(String op, UUID companyId, UUID sourceId, SapB1Gateway.Result r) {
        switch (op) {
            case "CREATE_VENDOR" -> {
                Db.exec("""
                        UPDATE vendors SET sap_card_code = ?, sap_bp_status = 'ACTIVE', status = 'ACTIVE', activated_at = now(), updated_at = now()
                         WHERE id = ?""", r.key(), sourceId);
                Row v = Db.one("SELECT legal_name, vendor_group_code, email FROM vendors WHERE id = ?", sourceId);
                Db.exec("""
                        INSERT INTO sap_business_partners(company_id, card_code, card_name, group_code, is_active, email)
                        VALUES (?,?,?,?,TRUE,?) ON CONFLICT (company_id, card_code) DO UPDATE SET card_name = EXCLUDED.card_name, is_active = TRUE""",
                        companyId, r.key(), v.str("legalName"), v.integer("vendorGroupCode"), v.str("email"));
                VendorLifecycle.onActivated(sourceId);
            }
            case "CREATE_PURCHASE_REQUEST" -> Db.exec(
                    "UPDATE purchase_requests SET sap_doc_entry = ?, sap_doc_num = ?, sap_push_status = 'POSTED', updated_at = now() WHERE id = ?",
                    r.docEntry(), r.docNum(), sourceId);
            case "CREATE_PURCHASE_ORDER" -> {
                Object total = r.raw() == null ? null : r.raw().get("DocTotal");
                Db.exec("""
                        UPDATE purchase_orders SET sap_doc_entry = ?, sap_doc_num = ?, status = 'PENDING_ACK',
                               doc_total = COALESCE(?, doc_total), updated_at = now() WHERE id = ?""",
                        r.docEntry(), r.docNum(), total == null ? null : new java.math.BigDecimal(((JsonNode) total).asText()), sourceId);
            }
            // After a vendor ASN: SAP accepted the GRPO draft → the buyer can now confirm the receipt.
            case "CREATE_GRPO_DRAFT" -> {
                Db.exec("UPDATE grpos SET sap_draft_entry = ?, status = 'DRAFT_CREATED' WHERE id = ?", r.docEntry(), sourceId);
                Db.exec("UPDATE asns SET status = 'GRPO_DRAFTED' WHERE id = (SELECT asn_id FROM grpos WHERE id = ?)", sourceId);
            }
            // After buyer confirm: GRPO posted in SAP → count accepted qty as received on the PO lines,
            // close the ASN (RECEIVED) and move the PO to PARTIALLY_RECEIVED / COMPLETED.
            // (The SAP draft is deleted separately in deleteDraftQuietly.)
            case "POST_GRPO" -> {
                Db.exec("UPDATE grpos SET sap_doc_entry = ?, sap_doc_num = ?, status = 'POSTED' WHERE id = ?", r.docEntry(), r.docNum(), sourceId);
                Db.exec("""
                        UPDATE purchase_order_lines pl SET received_qty = pl.received_qty + gl.received_qty
                          FROM grpo_lines gl WHERE gl.grpo_id = ? AND gl.po_line_id = pl.id""", sourceId);
                Row g = Db.one("SELECT asn_id, purchase_order_id FROM grpos WHERE id = ?", sourceId);
                Db.exec("UPDATE asns SET status = 'RECEIVED' WHERE id = ?", g.uuid("asnId"));
                PoStatus.recompute(g.uuid("purchaseOrderId"));
            }
            default -> throw new IllegalStateException(op);
        }
    }

    private static void markSourceFailed(String op, UUID sourceId, String msg) {
        switch (op) {
            case "CREATE_VENDOR" -> Db.exec("UPDATE vendors SET status = 'SAP_SYNC_FAILED', updated_at = now() WHERE id = ?", sourceId);
            case "CREATE_PURCHASE_REQUEST" -> Db.exec("UPDATE purchase_requests SET sap_push_status = 'FAILED' WHERE id = ?", sourceId);
            case "CREATE_PURCHASE_ORDER" -> Db.exec("UPDATE purchase_orders SET status = 'SAP_FAILED', updated_at = now() WHERE id = ?", sourceId);
            case "CREATE_GRPO_DRAFT" -> Db.exec("UPDATE grpos SET status = 'DRAFT_FAILED' WHERE id = ?", sourceId);
            case "POST_GRPO" -> Db.exec("UPDATE grpos SET status = 'POST_FAILED' WHERE id = ?", sourceId);
            default -> { }
        }
    }

    private static void markSourcePending(String op, UUID sourceId) {
        switch (op) {
            case "CREATE_VENDOR" -> Db.exec("UPDATE vendors SET status = 'SAP_SYNC_PENDING' WHERE id = ?", sourceId);
            case "CREATE_PURCHASE_REQUEST" -> Db.exec("UPDATE purchase_requests SET sap_push_status = 'QUEUED' WHERE id = ?", sourceId);
            case "CREATE_PURCHASE_ORDER" -> Db.exec("UPDATE purchase_orders SET status = 'SAP_PENDING' WHERE id = ?", sourceId);
            case "CREATE_GRPO_DRAFT" -> Db.exec("UPDATE grpos SET status = 'DRAFT_PENDING' WHERE id = ?", sourceId);
            case "POST_GRPO" -> Db.exec("UPDATE grpos SET status = 'POSTING' WHERE id = ?", sourceId);
            default -> { }
        }
    }

    private static void deleteDraftQuietly(SapB1Gateway g, UUID grpoId) {
        Integer draft = Db.scalar("SELECT sap_draft_entry FROM grpos WHERE id = ?", Integer.class, grpoId);
        if (draft == null) return;
        try {
            g.deleteDraft(draft);
        } catch (Exception e) {
            log.warn("GRPO posted but draft {} could not be deleted: {}", draft, e.getMessage());
        }
    }

    // ================================================================= payload building

    private static Built build(UUID companyId, String op, UUID sourceId, JsonNode previousPayload) {
        switch (op) {
            case "CREATE_VENDOR": {
                Row v = must(Db.one("SELECT * FROM vendors WHERE id = ? AND company_id = ?", sourceId, companyId), "Vendor");
                Row s = Settings.of(companyId);
                String code = null;
                if (s.integer("bpSeries") == null) {
                    code = previousPayload != null && previousPayload.hasNonNull("CardCode") ? previousPayload.get("CardCode").asText() : null;
                    if (code != null) {
                        // Keep the same code on retry (idempotent) unless B1 now has a *different* BP under it.
                        String holder = SapGatewayFactory.forCompany(companyId).businessPartnerName(code);
                        if (holder != null && !holder.equals(v.str("legalName"))) code = null;
                    }
                    if (code == null) code = nextCardCode(companyId);
                }
                return new Built("VENDOR", "BusinessPartners", B1Payloads.businessPartner(v, s, code));
            }
            case "CREATE_PURCHASE_REQUEST": {
                Row pr = must(Db.one("SELECT * FROM purchase_requests WHERE id = ? AND company_id = ?", sourceId, companyId), "Purchase request");
                List<Row> lines = Db.query("SELECT * FROM purchase_request_lines WHERE purchase_request_id = ? ORDER BY line_num", sourceId);
                Integer emp = pr.get("requesterUserId") == null ? null
                        : Db.scalar("SELECT sap_employee_id FROM users WHERE id = ?", Integer.class, pr.uuid("requesterUserId"));
                String slUser = Db.scalar("SELECT sl_username FROM companies WHERE id = ?", String.class, companyId);
                return new Built("PURCHASE_REQUEST", "PurchaseRequests", B1Payloads.purchaseRequest(pr, lines, emp, slUser));
            }
            case "CREATE_PURCHASE_ORDER": {
                Row po = must(Db.one("SELECT * FROM purchase_orders WHERE id = ? AND company_id = ?", sourceId, companyId), "Purchase order");
                List<Row> lines = Db.query("SELECT * FROM purchase_order_lines WHERE purchase_order_id = ? ORDER BY line_num", sourceId);
                Map<String, int[]> base = new HashMap<>();
                for (Row b : Db.query("""
                        SELECT pl.id, pr.sap_doc_entry, prl.line_num FROM purchase_order_lines pl
                          JOIN purchase_request_lines prl ON prl.id = pl.base_pr_line_id
                          JOIN purchase_requests pr ON pr.id = prl.purchase_request_id
                         WHERE pl.purchase_order_id = ? AND pr.source = 'B1' AND pr.sap_doc_entry IS NOT NULL""", sourceId)) {
                    base.put(b.str("id"), new int[]{b.integer("sapDocEntry"), b.integer("lineNum")});
                }
                String ref = po.get("rfqId") != null
                        ? Db.scalar("SELECT rfq_no FROM rfqs WHERE id = ?", String.class, po.uuid("rfqId"))
                        : "PO " + po.str("id").substring(0, 8);
                return new Built("PURCHASE_ORDER", "PurchaseOrders", B1Payloads.purchaseOrder(po, lines, base, ref));
            }
            case "CREATE_GRPO_DRAFT": {
                Row g = must(Db.one("SELECT * FROM grpos WHERE id = ? AND company_id = ?", sourceId, companyId), "GRPO");
                Row po = Db.one("SELECT * FROM purchase_orders WHERE id = ?", g.uuid("purchaseOrderId"));
                Row asn = Db.one("SELECT * FROM asns WHERE id = ?", g.uuid("asnId"));
                List<Row> lines = Db.query("""
                        SELECT al.shipped_qty, al.batch_no, pl.line_num, pl.warehouse_code FROM asn_lines al
                          JOIN purchase_order_lines pl ON pl.id = al.po_line_id WHERE al.asn_id = ? ORDER BY pl.line_num""", g.uuid("asnId"));
                return new Built("GRPO", "Drafts", B1Payloads.grpoDraft(po, asn, lines));
            }
            case "POST_GRPO": {
                Row g = must(Db.one("SELECT * FROM grpos WHERE id = ? AND company_id = ?", sourceId, companyId), "GRPO");
                Row po = Db.one("SELECT * FROM purchase_orders WHERE id = ?", g.uuid("purchaseOrderId"));
                Row asn = Db.one("SELECT * FROM asns WHERE id = ?", g.uuid("asnId"));
                List<Row> lines = Db.query("""
                        SELECT gl.received_qty, gl.warehouse_code, pl.line_num, al.batch_no FROM grpo_lines gl
                          JOIN purchase_order_lines pl ON pl.id = gl.po_line_id
                          LEFT JOIN asn_lines al ON al.asn_id = ? AND al.po_line_id = gl.po_line_id
                         WHERE gl.grpo_id = ? ORDER BY pl.line_num""", g.uuid("asnId"), sourceId);
                return new Built("GRPO", "PurchaseDeliveryNotes", B1Payloads.grpo(po, asn, g, lines));
            }
            default:
                throw new IllegalArgumentException(op);
        }
    }

    /** Manual BP code: prefix + running number, skipping codes that already exist in B1. */
    private static String nextCardCode(UUID companyId) {
        for (int i = 0; i < 1000; i++) {
            Row r = Db.one("UPDATE company_settings SET bp_code_next = bp_code_next + 1 WHERE company_id = ? RETURNING bp_code_prefix, bp_code_next - 1 AS n",
                    companyId);
            String code = r.str("bpCodePrefix") + r.integer("n");
            Integer taken = Db.scalar("""
                    SELECT 1 FROM sap_business_partners WHERE company_id = ? AND card_code = ?
                    UNION ALL SELECT 1 FROM vendors WHERE company_id = ? AND sap_card_code = ? LIMIT 1""",
                    Integer.class, companyId, code, companyId, code);
            if (taken == null) return code;
        }
        throw ApiException.conflict("Could not allocate a free Business Partner code — check the code prefix/sequence in Settings");
    }

    private static Row must(Row r, String what) {
        if (r == null) throw ApiException.notFound(what);
        return r;
    }
}
