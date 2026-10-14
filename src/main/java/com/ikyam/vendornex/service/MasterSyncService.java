package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Settings;
import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.sap.SapB1Gateway.*;
import com.ikyam.vendornex.sap.SapB1Gateway;
import com.ikyam.vendornex.sap.SapGatewayFactory;
import com.ikyam.vendornex.tenant.TenantContext;
import com.ikyam.vendornex.tenant.Tenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls SAP B1 master data (and, depending on settings, open Purchase Requests and Purchase
 * Orders) of every active SAP DB of the company into the read-only sap_* cache tables, each row tagged with its
 * connection_id. Each entity runs in its own transaction and
 * writes a sync_runs row, so one failing entity never blocks the others.
 */
public final class MasterSyncService {

    private static final Logger log = LoggerFactory.getLogger(MasterSyncService.class);
    private static final Set<UUID> RUNNING = ConcurrentHashMap.newKeySet();

    /** Lightest first, Items (heaviest read) last, so a Service Layer that struggles still gets the small masters done. */
    public static final List<String> ALL = List.of("EMPLOYEES", "WAREHOUSES", "VENDOR_GROUPS", "TAX_CODES", "COUNTRIES", "STATES", "BRANCHES", "BUSINESS_PARTNERS",
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
                List<UUID> connections = SapGatewayFactory.activeConnectionIds(companyId);
                if (connections.isEmpty()) log.warn("Master sync skipped for company {}: no active SAP connection", companyId);
                // Every SAP DB of the company is synced, one after the other; one DB failing never blocks the next.
                for (UUID conn : connections) {
                    try {
                        SapB1Gateway g = SapGatewayFactory.forConnection(companyId, conn);
                        boolean first = true;
                        for (String e : (entities == null || entities.isEmpty() ? ALL : entities)) {
                            if (!first) pause();
                            first = false;
                            // The Service Layer is unreachable: stop this DB here instead of retrying every remaining entity.
                            if (!run(companyId, conn, g, e, trigger)) break;
                        }
                    } catch (Exception ex) {
                        log.warn("Master sync failed for company {} SAP connection {}: {}", companyId, conn, ex.getMessage());
                    }
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
    private static boolean run(UUID companyId, UUID k, SapB1Gateway g, String entity, String trigger) {
        Instant startedAt = Instant.now();
        UUID runId = Db.scalar("INSERT INTO ik_sync_runs(company_id, connection_id, entity, trigger_type, status) VALUES (?,?,?,?, 'RUNNING') RETURNING id",
                UUID.class, companyId, k, entity, trigger);
        try {
            int n = switch (entity) {
                case "WAREHOUSES" -> warehouses(companyId, k, g.warehouses());
                case "ITEMS" -> items(companyId, k, g.items());
                case "VENDOR_GROUPS" -> vendorGroups(companyId, k, g.vendorGroups());
                case "BUSINESS_PARTNERS" -> businessPartners(companyId, k, g.vendors());
                case "EMPLOYEES" -> employees(companyId, k, g.employees());
                case "TAX_CODES" -> taxCodes(companyId, k, g.taxCodes());
                case "COUNTRIES" -> countries(k, g.countries());
                case "STATES" -> states(k, g.states());
                case "BRANCHES" -> branches(k, g.branches());
                case "PURCHASE_REQUESTS" -> purchaseRequests(companyId, k, g);
                case "PURCHASE_ORDERS" -> purchaseOrders(companyId, k, g);
                default -> throw new IllegalArgumentException("Unknown sync entity " + entity);
            };
            Db.exec("UPDATE ik_sync_runs SET status = 'SUCCESS', records = ?, finished_at = now() WHERE id = ?", n, runId);
            recordSuccess(k, entity, n, startedAt);
        } catch (Exception e) {
            log.warn("Master sync {} failed for company {} SAP connection {}: {}", entity, companyId, k, e.getMessage());
            Db.exec("UPDATE ik_sync_runs SET status = 'FAILED', error_detail = ?, finished_at = now() WHERE id = ?", e.getMessage(), runId);
            recordError(k, entity, e, startedAt);
            // httpStatus 0 = no HTTP response at all (connection refused / timed out), already retried by the gateway.
            return !(e instanceof com.ikyam.vendornex.sap.SapException se && se.httpStatus == 0);
        }
        return true;
    }

    // ------------------------------------------------------------------ outcome log (ik_success / ik_error)

    /** One ik_success row per successful sync of one entity of one SAP DB. Its last_sync_at is what the next sync reads. */
    private static void recordSuccess(UUID k, String entity, int records, Instant startedAt) {
        try {
            Db.exec("""
                    INSERT INTO ik_success(schema_id, connection_id, type, last_sync_at, success_desc, created_by)
                    VALUES (?,?,?,?,?,?)""", TenantContext.get(), k, entity, Timestamp.from(startedAt),
                    "Synced " + records + " record(s)", syncUser());
        } catch (Exception ex) {
            log.warn("Could not write ik_success for {} / {}: {}", entity, k, ex.getMessage());
        }
    }

    /** One ik_error row per failed sync. */
    private static void recordError(UUID k, String entity, Exception e, Instant startedAt) {
        try {
            Db.exec("""
                    INSERT INTO ik_error(schema_id, connection_id, executed_at, type, error_desc, created_by)
                    VALUES (?,?,?,?,?,?)""", TenantContext.get(), k, Timestamp.from(startedAt), entity,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), syncUser());
        } catch (Exception ex) {
            log.warn("Could not write ik_error for {} / {}: {}", entity, k, ex.getMessage());
        }
    }

    /** ik_success / ik_error need a user: the Super Admin who onboarded the company (ik_config), else any Super Admin. */
    private static UUID syncUser() {
        return Db.scalar("""
                SELECT COALESCE((SELECT created_by FROM ik_config WHERE schema_id = ? LIMIT 1),
                                (SELECT id FROM ik_global_users WHERE role = 'SUPER_ADMIN' ORDER BY created_at LIMIT 1))""",
                UUID.class, TenantContext.get());
    }

    // ------------------------------------------------------------------ masters
    //
    // Every master is written with INSERT ... ON CONFLICT (key) DO UPDATE ... WHERE <a value changed>:
    //   key not stored yet            -> inserted
    //   key stored, a value changed   -> updated
    //   key stored, nothing changed   -> left alone (not written)
    // Each method returns the number of rows inserted or updated, so a run where SAP and the portal already agree
    // reports 0 (that number is ik_sync_runs.records and the "Synced N record(s)" text in ik_success).

    private static int warehouses(UUID c, UUID k, List<Warehouse> list) {
        int[] n = {0};
        Db.txv(() -> {
            List<Object[]> rows = new ArrayList<>();
            for (Warehouse w : list) rows.add(new Object[]{c, k, w.code(), w.name(), w.active()});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_sap_warehouses(company_id, connection_id, warehouse_code, warehouse_name, is_active) VALUES (?,?,?,?,?)
                    ON CONFLICT (company_id, connection_id, warehouse_code) DO UPDATE SET warehouse_name = EXCLUDED.warehouse_name,
                        is_active = EXCLUDED.is_active
                     WHERE (ik_sap_warehouses.warehouse_name, ik_sap_warehouses.is_active) IS DISTINCT FROM (EXCLUDED.warehouse_name, EXCLUDED.is_active)""", rows);
            // codes SAP no longer returns are switched off (rows are never deleted)
            n[0] += deactivateMissing("ik_sap_warehouses", "warehouse_code", c, k, list.stream().map(Warehouse::code).toArray(String[]::new));
        });
        return n[0];
    }

    /** Switches off rows of this SAP DB whose code is not in {@code codes}. Counts only rows that were still active. */
    private static int deactivateMissing(String table, String codeColumn, UUID c, UUID k, Object codes) {
        if (codes instanceof String[] a && a.length == 0) return 0;
        return Db.exec("UPDATE " + table + " SET is_active = FALSE WHERE company_id = ? AND connection_id = ? AND is_active AND "
                + codeColumn + " <> ALL(?)", c, k, codes);
    }

    private static int items(UUID c, UUID k, List<Item> list) {
        int[] n = {0};
        Db.txv(() -> {
            List<Object[]> rows = new ArrayList<>();
            List<Object[]> stock = new ArrayList<>();
            for (Item i : list) {
                rows.add(new Object[]{c, k, i.code(), i.name(), i.groupCode(), i.groupName(), i.purchaseUom(), i.inventoryUom(), i.avgPrice(), i.active()});
                for (Stock s : i.stock()) stock.add(new Object[]{c, k, i.code(), s.warehouseCode(), s.inStock(), s.committed(), s.ordered()});
            }
            n[0] += Db.batchCount("""
                    INSERT INTO ik_sap_items(company_id, connection_id, item_code, item_name, item_group_code, item_group_name, purchase_uom, inventory_uom, avg_price, is_active)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (company_id, connection_id, item_code) DO UPDATE SET item_name = EXCLUDED.item_name, item_group_code = EXCLUDED.item_group_code,
                        item_group_name = EXCLUDED.item_group_name, purchase_uom = EXCLUDED.purchase_uom, inventory_uom = EXCLUDED.inventory_uom,
                        avg_price = EXCLUDED.avg_price, is_active = EXCLUDED.is_active
                     WHERE (ik_sap_items.item_name, ik_sap_items.item_group_code, ik_sap_items.item_group_name, ik_sap_items.purchase_uom,
                            ik_sap_items.inventory_uom, ik_sap_items.avg_price, ik_sap_items.is_active)
                           IS DISTINCT FROM (EXCLUDED.item_name, EXCLUDED.item_group_code, EXCLUDED.item_group_name, EXCLUDED.purchase_uom,
                                             EXCLUDED.inventory_uom, EXCLUDED.avg_price, EXCLUDED.is_active)""", rows);
            n[0] += Db.batchCount("""
                    INSERT INTO ik_sap_item_stock(company_id, connection_id, item_code, warehouse_code, in_stock, committed, ordered)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (company_id, connection_id, item_code, warehouse_code) DO UPDATE SET in_stock = EXCLUDED.in_stock,
                        committed = EXCLUDED.committed, ordered = EXCLUDED.ordered
                     WHERE (ik_sap_item_stock.in_stock, ik_sap_item_stock.committed, ik_sap_item_stock.ordered)
                           IS DISTINCT FROM (EXCLUDED.in_stock, EXCLUDED.committed, EXCLUDED.ordered)""", stock);
            n[0] += deactivateMissing("ik_sap_items", "item_code", c, k, list.stream().map(Item::code).toArray(String[]::new));
        });
        return n[0];
    }

    private static int vendorGroups(UUID c, UUID k, List<VendorGroup> list) {
        int[] n = {0};
        Db.txv(() -> {
            List<Object[]> rows = new ArrayList<>();
            for (VendorGroup g : list) rows.add(new Object[]{c, k, g.code(), g.name()});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_sap_vendor_groups(company_id, connection_id, group_code, group_name) VALUES (?,?,?,?)
                    ON CONFLICT (company_id, connection_id, group_code) DO UPDATE SET group_name = EXCLUDED.group_name
                     WHERE ik_sap_vendor_groups.group_name IS DISTINCT FROM EXCLUDED.group_name""", rows);
        });
        return n[0];
    }

    private static int businessPartners(UUID c, UUID k, List<BusinessPartner> list) {
        int[] n = {0};
        Db.txv(() -> {
            List<Object[]> rows = new ArrayList<>();
            for (BusinessPartner b : list) rows.add(new Object[]{c, k, b.cardCode(), b.cardName(), b.groupCode(), b.active(), b.federalTaxId(), b.email()});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_sap_business_partners(company_id, connection_id, card_code, card_name, group_code, is_active, federal_tax_id, email)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON CONFLICT (company_id, connection_id, card_code) DO UPDATE SET card_name = EXCLUDED.card_name, group_code = EXCLUDED.group_code,
                        is_active = EXCLUDED.is_active, federal_tax_id = EXCLUDED.federal_tax_id, email = EXCLUDED.email
                     WHERE (ik_sap_business_partners.card_name, ik_sap_business_partners.group_code, ik_sap_business_partners.is_active,
                            ik_sap_business_partners.federal_tax_id, ik_sap_business_partners.email)
                           IS DISTINCT FROM (EXCLUDED.card_name, EXCLUDED.group_code, EXCLUDED.is_active, EXCLUDED.federal_tax_id, EXCLUDED.email)""", rows);
            // Reflect B1 activity status on linked portal vendors (an Inactive BP cannot receive POs).
            Db.exec("""
                    UPDATE ik_vendors v SET sap_bp_status = CASE WHEN b.is_active THEN 'ACTIVE' ELSE 'INACTIVE' END
                      FROM ik_sap_business_partners b
                     WHERE v.company_id = ? AND b.company_id = v.company_id AND b.connection_id = ?
                       AND v.connection_id IS NOT DISTINCT FROM b.connection_id AND b.card_code = v.sap_card_code
                       AND v.sap_bp_status IS DISTINCT FROM CASE WHEN b.is_active THEN 'ACTIVE' ELSE 'INACTIVE' END""", c, k);
        });
        return n[0];
    }

    private static int employees(UUID c, UUID k, List<Employee> list) {
        int[] n = {0};
        Db.txv(() -> {
            List<Object[]> rows = new ArrayList<>();
            for (Employee e : list) rows.add(new Object[]{c, k, e.id(), e.name(), e.department(), e.email()});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_sap_employees(company_id, connection_id, employee_id, full_name, department, email) VALUES (?,?,?,?,?,?)
                    ON CONFLICT (company_id, connection_id, employee_id) DO UPDATE SET full_name = EXCLUDED.full_name,
                        department = EXCLUDED.department, email = EXCLUDED.email
                     WHERE (ik_sap_employees.full_name, ik_sap_employees.department, ik_sap_employees.email)
                           IS DISTINCT FROM (EXCLUDED.full_name, EXCLUDED.department, EXCLUDED.email)""", rows);
        });
        return n[0];
    }

    private static int taxCodes(UUID c, UUID k, List<TaxCode> list) {
        int[] n = {0};
        Db.txv(() -> {
            List<Object[]> rows = new ArrayList<>();
            for (TaxCode t : list) rows.add(new Object[]{c, k, t.code(), t.name(), t.rate()});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_sap_tax_codes(company_id, connection_id, tax_code, tax_name, rate) VALUES (?,?,?,?,?)
                    ON CONFLICT (company_id, connection_id, tax_code) DO UPDATE SET tax_name = EXCLUDED.tax_name, rate = EXCLUDED.rate
                     WHERE (ik_sap_tax_codes.tax_name, ik_sap_tax_codes.rate) IS DISTINCT FROM (EXCLUDED.tax_name, EXCLUDED.rate)""", rows);
        });
        return n[0];
    }

    // ------------------------------------------------------------------ geo masters (ik_country / ik_state / ik_branch)

    /** /Counties → ik_country. Keyed on country_code. */
    private static int countries(UUID k, List<Country> list) {
        int[] n = {0};
        Db.txv(() -> {
            String schema = TenantContext.get();
            List<Object[]> rows = new ArrayList<>();
            for (Country x : list) rows.add(new Object[]{x.code(), x.name(), schema, k});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_country(country_code, country_name, schema_id, connection_id) VALUES (?,?,?,?)
                    ON CONFLICT (connection_id, country_code) DO UPDATE SET country_name = EXCLUDED.country_name
                     WHERE ik_country.country_name IS DISTINCT FROM EXCLUDED.country_name""", rows);
        });
        return n[0];
    }

    /** /States → ik_state. Keyed on state_code. A state whose country is not in ik_country yet gets that country added first (name = code until the country master brings the real name), so the foreign key always holds. */
    private static int states(UUID k, List<State> list) {
        int[] n = {0};
        Db.txv(() -> {
            String schema = TenantContext.get();
            Set<String> missing = new LinkedHashSet<>();
            for (State s : list) if (s.countryCode() != null) missing.add(s.countryCode());
            List<Object[]> parents = new ArrayList<>();
            for (String code : missing) parents.add(new Object[]{code, code, schema, k});
            Db.batch("""
                    INSERT INTO ik_country(country_code, country_name, schema_id, connection_id) VALUES (?,?,?,?)
                    ON CONFLICT (connection_id, country_code) DO NOTHING""", parents);
            List<Object[]> rows = new ArrayList<>();
            for (State s : list) if (s.countryCode() != null) rows.add(new Object[]{s.code(), s.name(), s.countryCode(), schema, k});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_state(state_code, state_name, country_code, schema_id, connection_id) VALUES (?,?,?,?,?)
                    ON CONFLICT (connection_id, state_code) DO UPDATE SET state_name = EXCLUDED.state_name, country_code = EXCLUDED.country_code
                     WHERE (ik_state.state_name, ik_state.country_code) IS DISTINCT FROM (EXCLUDED.state_name, EXCLUDED.country_code)""", rows);
        });
        return n[0];
    }

    /** /BusinessPlaces → ik_branch. Keyed on branch_id (BPLID). */
    private static int branches(UUID k, List<Branch> list) {
        int[] n = {0};
        Db.txv(() -> {
            String schema = TenantContext.get();
            UUID by = syncUser();
            List<Object[]> rows = new ArrayList<>();
            for (Branch b : list) rows.add(new Object[]{b.id(), b.name(), b.active(), by, by, schema, k});
            n[0] += Db.batchCount("""
                    INSERT INTO ik_branch(branch_id, branch_name, is_active, created_by, updated_by, schema_id, connection_id) VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (connection_id, branch_id) DO UPDATE SET branch_name = EXCLUDED.branch_name, is_active = EXCLUDED.is_active,
                        updated_at = now(), updated_by = EXCLUDED.updated_by
                     WHERE (ik_branch.branch_name, ik_branch.is_active) IS DISTINCT FROM (EXCLUDED.branch_name, EXCLUDED.is_active)""", rows);
        });
        return n[0];
    }

    // ------------------------------------------------------------------ documents

    /** B1_SYNC mode only: mirrors open B1 Purchase Requests so they can be sourced via RFQ / PO. */
    private static int purchaseRequests(UUID c, UUID k, SapB1Gateway g) {
        if (!"B1_SYNC".equals(Settings.of(c).str("prMode"))) return 0;
        List<Document> docs = g.openPurchaseRequests();
        Set<Integer> open = new HashSet<>();
        for (Document d : docs) {
            open.add(d.docEntry());
            Db.txv(() -> {
                UUID prId = Db.scalar("SELECT id FROM ik_purchase_requests WHERE company_id = ? AND connection_id = ? AND sap_doc_entry = ?", UUID.class, c, k, d.docEntry());
                if (prId == null) {
                    prId = Db.scalar("""
                            INSERT INTO ik_purchase_requests(company_id, connection_id, pr_no, source, sap_doc_entry, sap_doc_num, requester_name, department,
                                   required_date, justification, status, created_at)
                            VALUES (?,?,?, 'B1', ?,?,?,?,?,?, 'APPROVED', ?::date) RETURNING id""", UUID.class,
                            c, k, "B1-" + d.docNum(), d.docEntry(), d.docNum(),
                            d.requesterName() == null ? "SAP B1 user" : d.requesterName(), d.department(), d.dueDate(), d.comments(),
                            d.docDate() == null ? LocalDate.now() : d.docDate());
                    for (DocLine l : d.lines()) {
                        Db.exec("""
                                INSERT INTO ik_purchase_request_lines(purchase_request_id, connection_id, line_num, item_code, item_name, uom, quantity, warehouse_code, required_date)
                                VALUES (?,?,?,?,?,?,?,?,?)""", prId, k, l.lineNum(), l.itemCode(), l.itemName(), l.uom(),
                                l.openQty().signum() > 0 ? l.openQty() : l.quantity(), l.warehouseCode(), l.date());
                    }
                }
            });
        }
        // Portal copies of B1 PRs that B1 has since closed (e.g. converted in B1 directly) and nobody sourced yet
        Integer[] openArr = open.isEmpty() ? new Integer[]{-1} : open.toArray(new Integer[0]);
        Db.exec("""
                UPDATE ik_purchase_requests SET status = 'CLOSED', updated_at = now()
                 WHERE company_id = ? AND connection_id = ? AND source = 'B1' AND status = 'APPROVED' AND NOT (sap_doc_entry = ANY(?))""", c, k, openArr);
        return docs.size();
    }

    /**
     * Two-way PO reconciliation: (1) POs raised directly in B1 for portal vendors appear in the
     * portal (source = B1); (2) receipts posted in B1 update received quantities / completion.
     */
    private static int purchaseOrders(UUID c, UUID k, SapB1Gateway g) {
        List<Document> docs = g.openPurchaseOrders();
        Map<String, UUID> vendorsByCard = new HashMap<>();
        for (Row v : Db.query("SELECT id, sap_card_code FROM ik_vendors WHERE company_id = ? AND status = 'ACTIVE' AND sap_card_code IS NOT NULL", c)) {
            vendorsByCard.put(v.str("sapCardCode"), v.uuid("id"));
        }
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (Document d : docs) {
            seen.add(d.docEntry());
            UUID poId = Db.scalar("SELECT id FROM ik_purchase_orders WHERE company_id = ? AND connection_id = ? AND sap_doc_entry = ?", UUID.class, c, k, d.docEntry());
            if (poId != null) {
                applyB1Quantities(poId, d);
                n++;
            } else if (vendorsByCard.containsKey(d.cardCode())) {
                importB1Po(c, k, vendorsByCard.get(d.cardCode()), d);
                n++;
            }
        }
        // Portal POs no longer in the open list: read them individually (bounded) to pick up closure in B1.
        for (Row po : Db.query("""
                SELECT id, sap_doc_entry FROM ik_purchase_orders
                 WHERE company_id = ? AND connection_id = ? AND sap_doc_entry IS NOT NULL
                   AND status IN ('PENDING_ACK','ACKNOWLEDGED','PARTIALLY_SHIPPED','SHIPPED','PARTIALLY_RECEIVED')
                 ORDER BY updated_at LIMIT 200""", c, k)) {
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
                        UPDATE ik_purchase_order_lines SET received_qty = GREATEST(received_qty, ?), shipped_qty = GREATEST(shipped_qty, ?)
                         WHERE purchase_order_id = ? AND line_num = ?""", received, received, poId, l.lineNum());
            }
            PoStatus.recompute(poId);
        });
    }

    private static void importB1Po(UUID c, UUID k, UUID vendorId, Document d) {
        Db.txv(() -> {
            BigDecimal total = BigDecimal.ZERO;
            for (DocLine l : d.lines()) total = total.add(l.price().multiply(l.quantity()));
            UUID poId = Db.scalar("""
                    INSERT INTO ik_purchase_orders(company_id, connection_id, vendor_id, card_code, source, sap_doc_entry, sap_doc_num, doc_date, doc_due_date,
                           currency, remarks, doc_total, status)
                    VALUES (?,?,?,?, 'B1', ?,?,?,?,?,?,?, 'PENDING_ACK') RETURNING id""", UUID.class,
                    c, k, vendorId, d.cardCode(), d.docEntry(), d.docNum(), d.docDate() == null ? LocalDate.now() : d.docDate(),
                    d.dueDate() == null ? LocalDate.now() : d.dueDate(), d.currency() == null ? "INR" : d.currency(), d.comments(), total);
            for (DocLine l : d.lines()) {
                BigDecimal received = l.quantity().subtract(l.openQty()).max(BigDecimal.ZERO);
                Db.exec("""
                        INSERT INTO ik_purchase_order_lines(purchase_order_id, connection_id, line_num, item_code, item_name, uom, quantity, unit_price,
                               warehouse_code, tax_code, ship_date, shipped_qty, received_qty)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""", poId, k, l.lineNum(), l.itemCode(), l.itemName(), l.uom(), l.quantity(), l.price(),
                        l.warehouseCode() == null ? "01" : l.warehouseCode(), l.taxCode(), l.date(), received, received);
            }
            PoStatus.recompute(poId);
        });
    }

    /** Called by the scheduler: syncs every active company whose interval has elapsed. */
    public static void runDue() {
        for (Row c : Db.query("""
                SELECT c.onboard_company_id AS id, s.master_sync_interval_min FROM ik_onboarding_company c JOIN ik_company_settings s ON s.company_id = c.onboard_company_id
                 WHERE c.is_active AND c.connection_status <> 'FAILED' AND c.schema_id IS NOT NULL""")) {
            UUID id = c.uuid("id");
            try {
                // sync_runs is a per-company table, so "is a sync due?" is asked inside that company's schema.
                Boolean recent = Tenants.call(id, () -> Db.scalar("""
                        SELECT EXISTS (SELECT 1 FROM ik_success s WHERE s.type = 'ITEMS' AND s.last_sync_at > now() - (? * interval '1 minute'))
                            OR EXISTS (SELECT 1 FROM ik_error e WHERE e.type = 'ITEMS' AND e.executed_at > now() - (? * interval '1 minute'))""",
                        Boolean.class, c.integer("masterSyncIntervalMin"), c.integer("masterSyncIntervalMin")));
                if (!Boolean.TRUE.equals(recent)) sync(id, null, "SCHEDULED");
            } catch (Exception e) {
                log.warn("Scheduled sync failed for {}: {}", c.str("id"), e.getMessage());
            }
        }
    }
}
