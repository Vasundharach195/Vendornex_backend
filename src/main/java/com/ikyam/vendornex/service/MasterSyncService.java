package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Settings;
import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.sap.SapB1Gateway.*;
import com.ikyam.vendornex.sap.SapB1Gateway;
import com.ikyam.vendornex.sap.SapGatewayFactory;
import com.ikyam.vendornex.tenant.Tenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls SAP B1 master data (and, depending on settings, open Purchase Requests and Purchase
 * Orders) into the read-only sap_* cache tables. Each entity runs in its own transaction and
 * writes a sync_runs row, so one failing entity never blocks the others.
 */
public final class MasterSyncService {

    private static final Logger log = LoggerFactory.getLogger(MasterSyncService.class);
    private static final Set<UUID> RUNNING = ConcurrentHashMap.newKeySet();

    /** Lightest first, Items (heaviest read) last, so a Service Layer that struggles still gets the small masters done. */
    public static final List<String> ALL = List.of("EMPLOYEES", "WAREHOUSES", "VENDOR_GROUPS", "TAX_CODES", "BUSINESS_PARTNERS",
            "ITEMS", "PURCHASE_REQUESTS", "PURCHASE_ORDERS");

    /** Pause between entities so the Service Layer is not hit with back-to-back heavy reads. */
    private static final long PAUSE_BETWEEN_ENTITIES_MS = 3000;

    private MasterSyncService() {}

    public static boolean isRunning(UUID companyId) {
        return RUNNING.contains(companyId);
    }

    /** Runs the given entities (or all) for one company. Returns false if a sync is already running. */
    public static boolean sync(UUID companyId, List<String> entities, String trigger) {
        if (!RUNNING.add(companyId)) return false;
        try {
            // Callers (Super Admin, scheduler, background jobs) may have no company context yet.
            Tenants.run(companyId, () -> {
                SapB1Gateway g = SapGatewayFactory.forCompany(companyId);
                boolean first = true;
                for (String e : (entities == null || entities.isEmpty() ? ALL : entities)) {
                    if (!first) pause();
                    first = false;
                    // The Service Layer is unreachable: stop here instead of retrying every remaining entity.
                    if (!run(companyId, g, e, trigger)) break;
                }
            });
            return true;
        } finally {
            RUNNING.remove(companyId);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE_BETWEEN_ENTITIES_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns false when the sync should stop because the Service Layer could not be reached at all. */
    private static boolean run(UUID companyId, SapB1Gateway g, String entity, String trigger) {
        UUID runId = Db.scalar("INSERT INTO sync_runs(company_id, entity, trigger_type, status) VALUES (?,?,?, 'RUNNING') RETURNING id",
                UUID.class, companyId, entity, trigger);
        try {
            int n = switch (entity) {
                case "WAREHOUSES" -> warehouses(companyId, g.warehouses());
                case "ITEMS" -> items(companyId, g.items());
                case "VENDOR_GROUPS" -> vendorGroups(companyId, g.vendorGroups());
                case "BUSINESS_PARTNERS" -> businessPartners(companyId, g.vendors());
                case "EMPLOYEES" -> employees(companyId, g.employees());
                case "TAX_CODES" -> taxCodes(companyId, g.taxCodes());
                case "PURCHASE_REQUESTS" -> purchaseRequests(companyId, g);
                case "PURCHASE_ORDERS" -> purchaseOrders(companyId, g);
                default -> throw new IllegalArgumentException("Unknown sync entity " + entity);
            };
            Db.exec("UPDATE sync_runs SET status = 'SUCCESS', records = ?, finished_at = now() WHERE id = ?", n, runId);
        } catch (Exception e) {
            log.warn("Master sync {} failed for company {}: {}", entity, companyId, e.getMessage());
            Db.exec("UPDATE sync_runs SET status = 'FAILED', error_detail = ?, finished_at = now() WHERE id = ?", e.getMessage(), runId);
            // httpStatus 0 = no HTTP response at all (connection refused / timed out), already retried by the gateway.
            return !(e instanceof com.ikyam.vendornex.sap.SapException se && se.httpStatus == 0);
        }
        return true;
    }

    // ------------------------------------------------------------------ masters

    private static int warehouses(UUID c, List<Warehouse> list) {
        Db.txv(() -> {
            Db.exec("UPDATE sap_warehouses SET is_active = FALSE WHERE company_id = ?", c);
            List<Object[]> rows = new ArrayList<>();
            for (Warehouse w : list) rows.add(new Object[]{c, w.code(), w.name(), w.active()});
            Db.batch("""
                    INSERT INTO sap_warehouses(company_id, warehouse_code, warehouse_name, is_active, last_synced_at) VALUES (?,?,?,?, now())
                    ON CONFLICT (company_id, warehouse_code) DO UPDATE SET warehouse_name = EXCLUDED.warehouse_name,
                        is_active = EXCLUDED.is_active, last_synced_at = now()""", rows);
        });
        return list.size();
    }

    private static int items(UUID c, List<Item> list) {
        Db.txv(() -> {
            Db.exec("UPDATE sap_items SET is_active = FALSE WHERE company_id = ?", c);
            List<Object[]> rows = new ArrayList<>();
            List<Object[]> stock = new ArrayList<>();
            for (Item i : list) {
                rows.add(new Object[]{c, i.code(), i.name(), i.groupCode(), i.groupName(), i.purchaseUom(), i.inventoryUom(), i.avgPrice(), i.active()});
                for (Stock s : i.stock()) stock.add(new Object[]{c, i.code(), s.warehouseCode(), s.inStock(), s.committed(), s.ordered()});
            }
            Db.batch("""
                    INSERT INTO sap_items(company_id, item_code, item_name, item_group_code, item_group_name, purchase_uom, inventory_uom, avg_price, is_active, last_synced_at)
                    VALUES (?,?,?,?,?,?,?,?,?, now())
                    ON CONFLICT (company_id, item_code) DO UPDATE SET item_name = EXCLUDED.item_name, item_group_code = EXCLUDED.item_group_code,
                        item_group_name = EXCLUDED.item_group_name, purchase_uom = EXCLUDED.purchase_uom, inventory_uom = EXCLUDED.inventory_uom,
                        avg_price = EXCLUDED.avg_price, is_active = EXCLUDED.is_active, last_synced_at = now()""", rows);
            Db.exec("DELETE FROM sap_item_stock WHERE company_id = ?", c);
            Db.batch("INSERT INTO sap_item_stock(company_id, item_code, warehouse_code, in_stock, committed, ordered) VALUES (?,?,?,?,?,?)", stock);
        });
        return list.size();
    }

    private static int vendorGroups(UUID c, List<VendorGroup> list) {
        Db.txv(() -> {
            Db.exec("DELETE FROM sap_vendor_groups WHERE company_id = ?", c);
            List<Object[]> rows = new ArrayList<>();
            for (VendorGroup g : list) rows.add(new Object[]{c, g.code(), g.name()});
            Db.batch("INSERT INTO sap_vendor_groups(company_id, group_code, group_name) VALUES (?,?,?)", rows);
        });
        return list.size();
    }

    private static int businessPartners(UUID c, List<BusinessPartner> list) {
        Db.txv(() -> {
            List<Object[]> rows = new ArrayList<>();
            for (BusinessPartner b : list) rows.add(new Object[]{c, b.cardCode(), b.cardName(), b.groupCode(), b.active(), b.federalTaxId(), b.email()});
            Db.batch("""
                    INSERT INTO sap_business_partners(company_id, card_code, card_name, group_code, is_active, federal_tax_id, email, last_synced_at)
                    VALUES (?,?,?,?,?,?,?, now())
                    ON CONFLICT (company_id, card_code) DO UPDATE SET card_name = EXCLUDED.card_name, group_code = EXCLUDED.group_code,
                        is_active = EXCLUDED.is_active, federal_tax_id = EXCLUDED.federal_tax_id, email = EXCLUDED.email, last_synced_at = now()""", rows);
            // Reflect B1 activity status on linked portal vendors (an Inactive BP cannot receive POs).
            Db.exec("""
                    UPDATE vendors v SET sap_bp_status = CASE WHEN b.is_active THEN 'ACTIVE' ELSE 'INACTIVE' END
                      FROM sap_business_partners b
                     WHERE v.company_id = ? AND b.company_id = v.company_id AND b.card_code = v.sap_card_code""", c);
        });
        return list.size();
    }

    private static int employees(UUID c, List<Employee> list) {
        Db.txv(() -> {
            Db.exec("DELETE FROM sap_employees WHERE company_id = ?", c);
            List<Object[]> rows = new ArrayList<>();
            for (Employee e : list) rows.add(new Object[]{c, e.id(), e.name(), e.department(), e.email()});
            Db.batch("INSERT INTO sap_employees(company_id, employee_id, full_name, department, email) VALUES (?,?,?,?,?)", rows);
        });
        return list.size();
    }

    private static int taxCodes(UUID c, List<TaxCode> list) {
        Db.txv(() -> {
            Db.exec("DELETE FROM sap_tax_codes WHERE company_id = ?", c);
            List<Object[]> rows = new ArrayList<>();
            for (TaxCode t : list) rows.add(new Object[]{c, t.code(), t.name(), t.rate()});
            Db.batch("INSERT INTO sap_tax_codes(company_id, tax_code, tax_name, rate) VALUES (?,?,?,?)", rows);
        });
        return list.size();
    }

    // ------------------------------------------------------------------ documents

    /** B1_SYNC mode only: mirrors open B1 Purchase Requests so they can be sourced via RFQ / PO. */
    private static int purchaseRequests(UUID c, SapB1Gateway g) {
        if (!"B1_SYNC".equals(Settings.of(c).str("prMode"))) return 0;
        List<Document> docs = g.openPurchaseRequests();
        Set<Integer> open = new HashSet<>();
        for (Document d : docs) {
            open.add(d.docEntry());
            Db.txv(() -> {
                UUID prId = Db.scalar("SELECT id FROM purchase_requests WHERE company_id = ? AND sap_doc_entry = ?", UUID.class, c, d.docEntry());
                if (prId == null) {
                    prId = Db.scalar("""
                            INSERT INTO purchase_requests(company_id, pr_no, source, sap_doc_entry, sap_doc_num, requester_name, department,
                                   required_date, justification, status, created_at)
                            VALUES (?,?, 'B1', ?,?,?,?,?,?, 'APPROVED', ?::date) RETURNING id""", UUID.class,
                            c, "B1-" + d.docNum(), d.docEntry(), d.docNum(),
                            d.requesterName() == null ? "SAP B1 user" : d.requesterName(), d.department(), d.dueDate(), d.comments(),
                            d.docDate() == null ? LocalDate.now() : d.docDate());
                    for (DocLine l : d.lines()) {
                        Db.exec("""
                                INSERT INTO purchase_request_lines(purchase_request_id, line_num, item_code, item_name, uom, quantity, warehouse_code, required_date)
                                VALUES (?,?,?,?,?,?,?,?)""", prId, l.lineNum(), l.itemCode(), l.itemName(), l.uom(),
                                l.openQty().signum() > 0 ? l.openQty() : l.quantity(), l.warehouseCode(), l.date());
                    }
                }
            });
        }
        // Portal copies of B1 PRs that B1 has since closed (e.g. converted in B1 directly) and nobody sourced yet
        Integer[] openArr = open.isEmpty() ? new Integer[]{-1} : open.toArray(new Integer[0]);
        Db.exec("""
                UPDATE purchase_requests SET status = 'CLOSED', updated_at = now()
                 WHERE company_id = ? AND source = 'B1' AND status = 'APPROVED' AND NOT (sap_doc_entry = ANY(?))""", c, openArr);
        return docs.size();
    }

    /**
     * Two-way PO reconciliation: (1) POs raised directly in B1 for portal vendors appear in the
     * portal (source = B1); (2) receipts posted in B1 update received quantities / completion.
     */
    private static int purchaseOrders(UUID c, SapB1Gateway g) {
        LocalDate since = Db.scalar("""
                SELECT (max(started_at) - interval '1 day')::date FROM sync_runs
                 WHERE company_id = ? AND entity = 'PURCHASE_ORDERS' AND status = 'SUCCESS'""", LocalDate.class, c);
        List<Document> docs = g.openPurchaseOrders(since);
        Map<String, UUID> vendorsByCard = new HashMap<>();
        for (Row v : Db.query("SELECT id, sap_card_code FROM vendors WHERE company_id = ? AND status = 'ACTIVE' AND sap_card_code IS NOT NULL", c)) {
            vendorsByCard.put(v.str("sapCardCode"), v.uuid("id"));
        }
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (Document d : docs) {
            seen.add(d.docEntry());
            UUID poId = Db.scalar("SELECT id FROM purchase_orders WHERE company_id = ? AND sap_doc_entry = ?", UUID.class, c, d.docEntry());
            if (poId != null) {
                applyB1Quantities(poId, d);
                n++;
            } else if (vendorsByCard.containsKey(d.cardCode())) {
                importB1Po(c, vendorsByCard.get(d.cardCode()), d);
                n++;
            }
        }
        // Portal POs no longer in the open list: read them individually (bounded) to pick up closure in B1.
        for (Row po : Db.query("""
                SELECT id, sap_doc_entry FROM purchase_orders
                 WHERE company_id = ? AND sap_doc_entry IS NOT NULL
                   AND status IN ('PENDING_ACK','ACKNOWLEDGED','PARTIALLY_SHIPPED','SHIPPED','PARTIALLY_RECEIVED')
                 ORDER BY updated_at LIMIT 200""", c)) {
            if (seen.contains(po.integer("sapDocEntry"))) continue;
            try {
                applyB1Quantities(po.uuid("id"), g.purchaseOrder(po.integer("sapDocEntry")));
            } catch (Exception e) {
                log.debug("Could not refresh PO {}: {}", po.integer("sapDocEntry"), e.getMessage());
            }
        }
        return n;
    }

    private static void applyB1Quantities(UUID poId, Document d) {
        Db.txv(() -> {
            for (DocLine l : d.lines()) {
                BigDecimal received = l.quantity().subtract(l.openQty()).max(BigDecimal.ZERO);
                // B1 is authoritative for receipts: never lower what the portal has posted itself.
                Db.exec("""
                        UPDATE purchase_order_lines SET received_qty = GREATEST(received_qty, ?), shipped_qty = GREATEST(shipped_qty, ?)
                         WHERE purchase_order_id = ? AND line_num = ?""", received, received, poId, l.lineNum());
            }
            PoStatus.recompute(poId);
        });
    }

    private static void importB1Po(UUID c, UUID vendorId, Document d) {
        Db.txv(() -> {
            BigDecimal total = BigDecimal.ZERO;
            for (DocLine l : d.lines()) total = total.add(l.price().multiply(l.quantity()));
            UUID poId = Db.scalar("""
                    INSERT INTO purchase_orders(company_id, vendor_id, card_code, source, sap_doc_entry, sap_doc_num, doc_date, doc_due_date,
                           currency, remarks, doc_total, status)
                    VALUES (?,?,?, 'B1', ?,?,?,?,?,?,?, 'PENDING_ACK') RETURNING id""", UUID.class,
                    c, vendorId, d.cardCode(), d.docEntry(), d.docNum(), d.docDate() == null ? LocalDate.now() : d.docDate(),
                    d.dueDate() == null ? LocalDate.now() : d.dueDate(), d.currency() == null ? "INR" : d.currency(), d.comments(), total);
            for (DocLine l : d.lines()) {
                BigDecimal received = l.quantity().subtract(l.openQty()).max(BigDecimal.ZERO);
                Db.exec("""
                        INSERT INTO purchase_order_lines(purchase_order_id, line_num, item_code, item_name, uom, quantity, unit_price,
                               warehouse_code, tax_code, ship_date, shipped_qty, received_qty)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", poId, l.lineNum(), l.itemCode(), l.itemName(), l.uom(), l.quantity(), l.price(),
                        l.warehouseCode() == null ? "01" : l.warehouseCode(), l.taxCode(), l.date(), received, received);
            }
            PoStatus.recompute(poId);
        });
    }

    /** Called by the scheduler: syncs every active company whose interval has elapsed. */
    public static void runDue() {
        for (Row c : Db.query("""
                SELECT c.id, s.master_sync_interval_min FROM companies c JOIN company_settings s ON s.company_id = c.id
                 WHERE c.is_active AND c.connection_status <> 'FAILED' AND c.schema_id IS NOT NULL""")) {
            UUID id = c.uuid("id");
            try {
                // sync_runs is a per-company table, so "is a sync due?" is asked inside that company's schema.
                Boolean recent = Tenants.call(id, () -> Db.scalar("""
                        SELECT EXISTS (SELECT 1 FROM sync_runs r WHERE r.company_id = ? AND r.entity = 'ITEMS'
                                         AND r.started_at > now() - (? * interval '1 minute'))""",
                        Boolean.class, id, c.integer("masterSyncIntervalMin")));
                if (!Boolean.TRUE.equals(recent)) sync(id, null, "SCHEDULED");
            } catch (Exception e) {
                log.warn("Scheduled sync failed for {}: {}", c.str("id"), e.getMessage());
            }
        }
    }
}
