package com.ikyam.vendornex.seed;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.security.Crypto;
import com.ikyam.vendornex.security.Passwords;
import com.ikyam.vendornex.service.DocumentStore;
import com.ikyam.vendornex.service.MasterSyncService;
import com.ikyam.vendornex.service.PoStatus;
import com.ikyam.vendornex.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Loads the wireframe's demo data into an empty database. B1 documents (POs, GRPO drafts, GRPOs) are
 * created through the real sync outbox against the Service Layer simulator, so every seeded PO has a
 * genuine B1 DocEntry/DocNum and later actions (ASN, GRPO) work end to end.
 */
public final class DemoSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private DemoSeeder() {}

    public static void seedIfEmpty() {
        if (Db.scalar("SELECT count(*) FROM companies", Long.class) > 0) return;
        log.info("Empty database — loading demo data");
        String superHash = Passwords.hash("Ikyam@2026");
        Db.exec("INSERT INTO users(name, email, role, status, password_hash) VALUES ('Product Admin', 'superadmin@ikyam.com', 'SUPER_ADMIN', 'ACTIVE', ?)", superHash);

        UUID acme = company("Acme Precision Works", "Manufacturing", "ACME_LIVE", LocalDate.now().minusMonths(6));
        company("Dhash Fabrication Pvt Ltd", "Fabrication", "DHASH_LIVE", LocalDate.now().minusMonths(4));
        company("Ikyam Solutions Pvt Ltd", "Technology", "IKYAM_TEST", LocalDate.now().minusMonths(8));

        Db.exec("""
                UPDATE company_settings SET default_warehouse_code = 'WH-01', default_tax_code = 'GST18'
                 WHERE company_id = ?""", acme);
        MasterSyncService.sync(acme, null, "MANUAL");

        String admin = Passwords.hash("Admin@123");
        String approve = Passwords.hash("Approve@123");
        String reqPw = Passwords.hash("Requester@123");
        String vendorPw = Passwords.hash("Vendor@123");
        UUID adminId = user(acme, "Bipro Roy", "admin@acmemfg.com", "ADMIN", admin, null, null, null);
        UUID fin = user(acme, "Kavitha Iyer", "finance@acmemfg.com", "APPROVER", approve, null, null, null);
        UUID proc = user(acme, "Rajeev Menon", "procurement@acmemfg.com", "APPROVER", approve, null, null, null);
        UUID comp = user(acme, "Sandra Fernandes", "compliance@acmemfg.com", "APPROVER", approve, null, null, null);
        Db.exec("INSERT INTO user_approval_stages VALUES (?, 'FINANCE'), (?, 'PROCUREMENT'), (?, 'COMPLIANCE')", fin, proc, comp);
        UUID karthik = user(acme, "Karthik Subramanian", "karthik.s@acmemfg.com", "REQUESTER", reqPw, "Production", 1, null);
        UUID ananya = user(acme, "Ananya Desai", "ananya.d@acmemfg.com", "REQUESTER", reqPw, "Maintenance", 2, null);
        steps(acme, "REQUESTER", karthik, new String[]{"FINANCE"}, new String[]{"APPROVED"}, fin);
        steps(acme, "REQUESTER", ananya, new String[]{"FINANCE"}, new String[]{"APPROVED"}, fin);

        // ------------------------------------------------------------ vendors
        Map<String, UUID> v = new HashMap<>();
        v.put("v1", linked(acme, "V-10023", "Precision Auto Components", "Arjun Iyer", "vendor@precisionauto.com", "27AAACP1234F1Z5", "HDFC Bank", "50100234567890", "HDFC0001234", 103, vendorPw, fin, proc));
        v.put("v2", linked(acme, "V-10024", "SteelCraft Industries", "Meera Nair", "vendor@steelcraft.com", "29AACCS5678K1Z2", "ICICI Bank", "602301122334", "ICIC0006023", 101, vendorPw, fin, proc));
        v.put("v5", linked(acme, "V-10031", "Vantage Electricals", "Kabir Sheikh", "vendor@vantageelec.com", "19AAGCV9988N1Z6", "SBI", "34456712398", "SBIN0004567", 103, vendorPw, fin, proc));
        v.put("v7", linked(acme, "V-10038", "Meridian Tooling Works", "Farah Khan", "vendor@meridiantooling.com", "27AACCM6543F1Z1", "HDFC Bank", "50100987654321", "HDFC0002211", 103, null, fin, proc));
        v.put("v8", linked(acme, "V-10041", "GreenLeaf Packaging", "Nisha Verma", "vendor@greenleafpack.com", "06AADCG9081K1Z4", "Axis Bank", "917020098765", "UTIB0002234", 102, null, fin, proc));
        v.put("v9", linked(acme, "V-10056", "Falcon Hydraulics Pvt Ltd", "Rohit Malhotra", "vendor@falconhydraulics.com", "29AAECF3344L1Z7", "ICICI Bank", "602301199887", "ICIC0007712", 103, null, fin, proc));

        UUID bright = newVendor(acme, "BrightWeld Fabricators", "Suresh Pillai", "vendor@brightweld.com", 104, "33AABCB4321L1Z9", "Axis Bank", "917020045678", "UTIB0001789", "PENDING_APPROVAL");
        steps(acme, "VENDOR", bright, new String[]{"FINANCE", "PROCUREMENT"}, new String[]{"APPROVED", "PENDING"}, fin);
        UUID orion = newVendor(acme, "Orion Plastics Ltd", "Divya Rao", "vendor@orionplastics.com", 101, "24AAECO7788M1Z3", "Kotak Mahindra Bank", "8912340056781", "KKBK0001234", "PENDING_APPROVAL");
        steps(acme, "VENDOR", orion, new String[]{"FINANCE", "PROCUREMENT"}, new String[]{"PENDING", "NOT_STARTED"}, null);
        UUID sunrise = newVendor(acme, "Sunrise Electricals & Controls", "Priya Nambiar", "vendor@sunriseelectricals.com", 107, "33AABCS7766M1Z9", "SBI", "34456799012", "SBIN0005678", "PENDING_APPROVAL");
        steps(acme, "VENDOR", sunrise, new String[]{"FINANCE", "PROCUREMENT"}, new String[]{"PENDING", "NOT_STARTED"}, null);
        UUID nova = newVendor(acme, "Nova Packaging Co.", "Ritu Sharma", "vendor@novapack.com", 102, "07AACCN1122P1Z8", "Yes Bank", "0012345678901", "YESB0000012", "REJECTED");
        steps(acme, "VENDOR", nova, new String[]{"FINANCE", "PROCUREMENT"}, new String[]{"REJECTED", "SKIPPED"}, fin);
        Db.exec("UPDATE vendors SET rejection_reason = 'GST certificate is illegible and bank proof is missing — please re-upload.' WHERE id = ?", nova);
        Db.exec("UPDATE approval_steps SET remarks = 'GST certificate is illegible and bank proof is missing — please re-upload.' WHERE entity_id = ? AND status = 'REJECTED'", nova);
        UUID draft = Db.scalar("""
                INSERT INTO vendors(company_id, legal_name, contact_name, status, wizard_step, created_by_user_id)
                VALUES (?, 'Bluewave Instruments', 'Anil Kapoor', 'DRAFT', 1, ?) RETURNING id""", UUID.class, acme, adminId);
        log.debug("draft vendor {}", draft);

        // ------------------------------------------------------------ monthly scorecard history
        snapshots(acme, v.get("v1"), new int[]{91, 92, 90, 94, 95, 94}, new int[]{96, 97, 96, 97, 98, 97}, -2.1);
        snapshots(acme, v.get("v2"), new int[]{85, 86, 88, 87, 89, 88}, new int[]{90, 91, 90, 92, 91, 91}, 1.4);
        snapshots(acme, v.get("v5"), new int[]{96, 97, 98, 97, 98, 97}, new int[]{99, 99, 98, 99, 99, 99}, -0.6);
        snapshots(acme, v.get("v7"), new int[]{89, 90, 91, 92, 93, 92}, new int[]{94, 95, 95, 94, 96, 95}, 0.8);
        snapshots(acme, v.get("v8"), new int[]{87, 88, 90, 89, 91, 90}, new int[]{93, 94, 94, 93, 95, 94}, 1.9);
        snapshots(acme, v.get("v9"), new int[]{93, 94, 95, 94, 96, 95}, new int[]{95, 96, 96, 97, 96, 96}, -1.2);

        // ------------------------------------------------------------ purchase requests (APP mode)
        UUID pr1 = pr(acme, "PR-" + LocalDate.now().getYear() + "-0101", karthik, "Karthik Subramanian", "Production", "PENDING_APPROVAL",
                "Line 3 fastener stock is running low ahead of the next production run.", 1, new Object[]{"ITM-1010", "Hex Bolt M10x40", "EA", 2000});
        steps(acme, "PURCHASE_REQUEST", pr1, new String[]{"ADMIN"}, new String[]{"PENDING"}, null);
        UUID pr2 = pr(acme, "PR-" + LocalDate.now().getYear() + "-0098", ananya, "Ananya Desai", "Maintenance", "APPROVED",
                "Preventive maintenance schedule for conveyor motors.", 5, new Object[]{"ITM-1050", "Ball Bearing 6205ZZ", "EA", 200});
        steps(acme, "PURCHASE_REQUEST", pr2, new String[]{"ADMIN"}, new String[]{"APPROVED"}, adminId);
        UUID pr3 = pr(acme, "PR-" + LocalDate.now().getYear() + "-0091", karthik, "Karthik Subramanian", "Production", "SOURCED",
                "Q3 restock for the fabrication line.", 14, new Object[]{"ITM-1001", "Steel Rod 12mm", "KG", 2000}, new Object[]{"ITM-1002", "Aluminium Sheet 2mm", "SHT", 400});
        steps(acme, "PURCHASE_REQUEST", pr3, new String[]{"ADMIN"}, new String[]{"APPROVED"}, adminId);
        UUID pr4 = pr(acme, "PR-" + LocalDate.now().getYear() + "-0087", ananya, "Ananya Desai", "Maintenance", "REJECTED",
                "General maintenance consumables top-up.", 18, new Object[]{"ITM-1022", "PVC Insulation Tape", "ROL", 50});
        steps(acme, "PURCHASE_REQUEST", pr4, new String[]{"ADMIN"}, new String[]{"REJECTED"}, adminId);
        Db.exec("UPDATE purchase_requests SET rejection_reason = 'Not urgent — defer to next quarter''s consumables budget.' WHERE id = ?", pr4);
        Db.exec("INSERT INTO doc_sequences(company_id, doc_type, prefix, next_value) VALUES (?, 'PR', ?, 102), (?, 'RFQ', ?, 16), (?, 'ASN', ?, 31)",
                acme, "PR-" + LocalDate.now().getYear() + "-", acme, "RFQ-" + LocalDate.now().getYear() + "-", acme, "ASN-" + LocalDate.now().getYear() + "-");

        // ------------------------------------------------------------ RFQs
        int y = LocalDate.now().getYear();
        UUID r1 = rfq(acme, adminId, "RFQ-" + y + "-0014", "Steel Rod & Aluminium Sheet — Q3 Restock", 9, "OPEN");
        UUID r1l1 = rfqLine(r1, 0, "ITM-1001", "Steel Rod 12mm", "KG", 2000, "WH-01");
        UUID r1l2 = rfqLine(r1, 1, "ITM-1002", "Aluminium Sheet 2mm", "SHT", 400, "WH-01");
        linkPr(r1l1, pr3, 0, 2000);
        linkPr(r1l2, pr3, 1, 400);
        invite(r1, v.get("v1"), v.get("v2"), v.get("v5"));
        quote(r1, v.get("v1"), 13, "Can deliver in 2 batches over 10 days.", new Object[]{r1l1, 76.9, 2000}, new Object[]{r1l2, 1105, 400});
        quote(r1, v.get("v2"), 11, "Can only source 1800 kg of steel rod this cycle; aluminium sheet fully available.",
                new Object[]{r1l1, 79.2, 1800}, new Object[]{r1l2, 1140, 400});

        UUID r2 = rfq(acme, adminId, "RFQ-" + y + "-0015", "Fan Motors — New Line Setup", 5, "OPEN");
        UUID r2l1 = rfqLine(r2, 0, "ITM-1015", "Industrial Fan Motor 1HP", "EA", 50, "WH-01");
        invite(r2, v.get("v1"), v.get("v5"));
        quote(r2, v.get("v5"), 18, "2 year warranty included.", new Object[]{r2l1, 4180, 50});

        UUID r3 = rfq(acme, adminId, "RFQ-" + y + "-0011", "Packaging Cartons — Bulk Order", -20, "AWARDED");
        UUID r3l1 = rfqLine(r3, 0, "ITM-1041", "Corrugated Carton Box L", "EA", 10000, "WH-03");
        invite(r3, v.get("v2"), v.get("v5"));
        UUID q3a = quote(r3, v.get("v2"), -14, "", new Object[]{r3l1, 36.5, 10000});
        quote(r3, v.get("v5"), -16, "Can ship 9500 units by the 20th; remainder a week later.", new Object[]{r3l1, 37.8, 9500});

        // ------------------------------------------------------------ purchase orders, created in B1 via the outbox
        UUID po1 = po(acme, adminId, v.get("v2"), "V-10024", "RFQ", r3, -3, new Object[]{"ITM-1041", "Corrugated Carton Box L", "EA", 10000, 36.5, "WH-03"});
        Db.exec("UPDATE rfq_lines SET awarded_qty = 10000 WHERE id = ?", r3l1);
        Db.exec("INSERT INTO rfq_awards(rfq_id, rfq_line_id, quotation_id, vendor_id, awarded_qty, unit_price, purchase_order_id, awarded_by_user_id) VALUES (?,?,?,?,10000,36.5,?,?)",
                r3, r3l1, q3a, v.get("v2"), po1, adminId);
        Db.exec("UPDATE quotations SET status = CASE WHEN id = ? THEN 'AWARDED' ELSE 'NOT_AWARDED' END WHERE rfq_id = ?", q3a, r3);
        ack(po1);
        UUID g1 = asn(acme, po1, "ASN-" + y + "-0028", "BlueDart Logistics", "BD778812345", -8, 200, 8, "4200 kg");
        confirm(acme, g1, adminId);

        UUID po2 = po(acme, adminId, v.get("v1"), "V-10023", "DIRECT", null, 6, new Object[]{"ITM-1010", "Hex Bolt M10x40", "EA", 5000, 3.2, "WH-01"});
        ack(po2);
        asn(acme, po2, "ASN-" + y + "-0030", "DHL Express", "DHL5590012", -1, 20, 2, "640 kg");

        UUID po3 = po(acme, adminId, v.get("v1"), "V-10023", "DIRECT", null, 10, new Object[]{"ITM-1030", "Copper Wire 4sq.mm", "MTR", 3000, 145, "WH-01"});
        ack(po3);
        po(acme, adminId, v.get("v5"), "V-10031", "DIRECT", null, 12, new Object[]{"ITM-1050", "Ball Bearing 6205ZZ", "EA", 800, 95, "WH-01"});
        po(acme, adminId, v.get("v2"), "V-10024", "DIRECT", null, 14, new Object[]{"ITM-1002", "Aluminium Sheet 2mm", "SHT", 150, 1120, "WH-01"});
        log.info("Demo data loaded");
    }

    // =================================================================== helpers

    private static UUID company(String name, String industry, String db, LocalDate onboarded) {
        UUID id = Db.scalar("""
                INSERT INTO companies(name, industry, integration_mode, sap_company_db, sl_username, sl_password_enc, onboarded_on,
                                      connection_status, connection_message, last_tested_at)
                VALUES (?,?, 'MOCK', ?, 'manager', ?, ?, 'OK', 'Connected to ' || ?, now()) RETURNING id""", UUID.class,
                name, industry, db, Crypto.encrypt("manager"), onboarded, db);
        Db.exec("INSERT INTO company_settings(company_id) VALUES (?)", id);
        String adminEmail = switch (name) {
            case "Dhash Fabrication Pvt Ltd" -> "admin@dhashfab.com";
            case "Ikyam Solutions Pvt Ltd" -> "vijaya.shree@ikyam.com";
            default -> null;
        };
        if (adminEmail != null) {
            user(id, adminEmail.startsWith("admin") ? "D. Selvam" : "Vijaya Shree", adminEmail, "ADMIN", null, null, null, "invite-" + UUID.randomUUID());
        }
        return id;
    }

    private static UUID user(UUID c, String name, String email, String role, String hash, String dept, Integer emp, String invite) {
        return Db.scalar("""
                INSERT INTO users(company_id, name, email, role, status, password_hash, department, sap_employee_id, invite_token, invite_expires_at)
                VALUES (?,?,?,?,?,?,?,?,?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() + interval '7 days' END) RETURNING id""", UUID.class,
                c, name, email, role, hash == null ? "INVITED" : "ACTIVE", hash, dept, emp, invite, invite);
    }

    private static void steps(UUID c, String type, UUID id, String[] stages, String[] statuses, UUID actor) {
        for (int i = 0; i < stages.length; i++) {
            boolean acted = statuses[i].equals("APPROVED") || statuses[i].equals("REJECTED");
            Db.exec("""
                    INSERT INTO approval_steps(company_id, entity_type, entity_id, stage, seq, status, acted_by_user_id, acted_at)
                    VALUES (?,?,?,?,?,?,?, CASE WHEN ? THEN now() - interval '3 days' END)""",
                    c, type, id, stages[i], i + 1, statuses[i], acted ? actor : null, acted);
        }
    }

    private static UUID linked(UUID c, String card, String name, String contact, String email, String gstin, String bank, String acc, String ifsc,
                               int group, String vendorPw, UUID fin, UUID proc) {
        UUID id = Db.scalar("""
                INSERT INTO vendors(company_id, legal_name, contact_name, email, link_type, sap_card_code, sap_bp_status, vendor_group_code, vendor_group_name,
                       city, country, gstin, pan, bank_name, bank_account_no, bank_ifsc, status, wizard_step, invited_on, activated_at)
                SELECT ?,?,?,?, 'EXISTING', ?, CASE WHEN b.is_active THEN 'ACTIVE' ELSE 'INACTIVE' END, ?, g.group_name, 'Chennai', 'IN', ?, ?, ?, ?, ?,
                       'ACTIVE', 4, CURRENT_DATE - 120, now() - interval '110 days'
                  FROM sap_business_partners b LEFT JOIN sap_vendor_groups g ON g.company_id = b.company_id AND g.group_code = ?
                 WHERE b.company_id = ? AND b.card_code = ? RETURNING id""", UUID.class,
                c, name, contact, email, card, group, gstin, gstin.substring(2, 12), bank, acc, ifsc, group, c, card);
        steps(c, "VENDOR", id, new String[]{"FINANCE", "PROCUREMENT"}, new String[]{"APPROVED", "APPROVED"}, fin);
        Db.exec("UPDATE approval_steps SET acted_by_user_id = ? WHERE entity_id = ? AND stage = 'PROCUREMENT'", proc, id);
        docs(c, id, "GST", "PAN", "MSME");
        String invite = vendorPw == null ? "invite-" + UUID.randomUUID() : null;
        Db.exec("""
                INSERT INTO users(company_id, name, email, role, status, password_hash, vendor_id, invite_token, invite_expires_at)
                VALUES (?,?,?, 'VENDOR', ?,?,?,?, now() + interval '7 days')""", c, contact, email, vendorPw == null ? "INVITED" : "ACTIVE", vendorPw, id, invite);
        return id;
    }

    private static UUID newVendor(UUID c, String name, String contact, String email, int group, String gstin, String bank, String acc, String ifsc, String status) {
        UUID id = Db.scalar("""
                INSERT INTO vendors(company_id, legal_name, contact_name, email, link_type, vendor_group_code, vendor_group_name, street, city, state, country,
                       gstin, pan, bank_name, bank_account_no, bank_ifsc, status, wizard_step, invited_on)
                SELECT ?,?,?,?, 'NEW', ?, g.group_name, '12 Industrial Estate', 'Coimbatore', 'TN', 'IN', ?, ?, ?, ?, ?, ?, 4, CURRENT_DATE - 10
                  FROM sap_vendor_groups g WHERE g.company_id = ? AND g.group_code = ? RETURNING id""", UUID.class,
                c, name, contact, email, group, gstin, gstin.substring(2, 12), bank, acc, ifsc, status, c, group);
        docs(c, id, "GST", "PAN");
        return id;
    }

    private static void docs(UUID c, UUID vendor, String... types) {
        for (String t : types) {
            String pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj " +
                    "3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 300 144]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n% Demo " + t + " certificate";
            String key = DocumentStore.save(c, vendor, pdf.getBytes(StandardCharsets.UTF_8));
            Db.exec("""
                    INSERT INTO vendor_documents(company_id, vendor_id, doc_type, file_name, content_type, size_bytes, storage_key, expiry_date)
                    VALUES (?,?,?,?, 'application/pdf', ?, ?, ?)""", c, vendor, t, t.toLowerCase() + "-certificate.pdf", (long) pdf.length(), key,
                    t.equals("MSME") ? LocalDate.now().plusMonths(8) : null);
        }
    }

    private static void snapshots(UUID c, UUID vendor, int[] otif, int[] quality, double pv) {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(otif.length);
        for (int i = 0; i < otif.length; i++) {
            Db.exec("INSERT INTO vendor_score_snapshots(company_id, vendor_id, period, otif_pct, quality_pct, price_variance_pct, po_count) VALUES (?,?,?,?,?,?,?)",
                    c, vendor, start.plusMonths(i), BigDecimal.valueOf(otif[i]), BigDecimal.valueOf(quality[i]),
                    BigDecimal.valueOf(pv + (i - 3) * 0.2).setScale(2, java.math.RoundingMode.HALF_UP), 3 + i % 3);
        }
    }

    private static UUID pr(UUID c, String no, UUID requester, String name, String dept, String status, String why, int daysAgo, Object[]... lines) {
        UUID id = Db.scalar("""
                INSERT INTO purchase_requests(company_id, pr_no, source, requester_user_id, requester_name, department, required_date, justification, status, created_at)
                VALUES (?,?, 'APP', ?,?,?,?,?,?, now() - (? * interval '1 day')) RETURNING id""", UUID.class,
                c, no, requester, name, dept, LocalDate.now().plusDays(20 - daysAgo), why, status, daysAgo);
        int n = 0;
        for (Object[] l : lines) {
            Db.exec("""
                    INSERT INTO purchase_request_lines(purchase_request_id, line_num, item_code, item_name, uom, quantity, sourced_qty, warehouse_code)
                    VALUES (?,?,?,?,?,?,?, 'WH-01')""", id, n++, l[0], l[1], l[2], new BigDecimal(l[3].toString()),
                    status.equals("SOURCED") ? new BigDecimal(l[3].toString()) : BigDecimal.ZERO);
        }
        return id;
    }

    private static UUID rfq(UUID c, UUID by, String no, String title, int dueInDays, String status) {
        return Db.scalar("""
                INSERT INTO rfqs(company_id, rfq_no, title, due_date, status, created_by_user_id, created_at) VALUES (?,?,?,?,?,?, now() - interval '6 days')
                RETURNING id""", UUID.class, c, no, title, LocalDate.now().plusDays(dueInDays), status, by);
    }

    private static UUID rfqLine(UUID rfq, int n, String item, String name, String uom, int qty, String wh) {
        return Db.scalar("INSERT INTO rfq_lines(rfq_id, line_num, item_code, item_name, uom, quantity, warehouse_code) VALUES (?,?,?,?,?,?,?) RETURNING id",
                UUID.class, rfq, n, item, name, uom, BigDecimal.valueOf(qty), wh);
    }

    private static void linkPr(UUID rfqLine, UUID pr, int lineNum, int qty) {
        Db.exec("""
                INSERT INTO rfq_line_sources(rfq_line_id, pr_line_id, quantity)
                SELECT ?, id, ? FROM purchase_request_lines WHERE purchase_request_id = ? AND line_num = ?""", rfqLine, BigDecimal.valueOf(qty), pr, lineNum);
    }

    private static void invite(UUID rfq, UUID... vendors) {
        for (UUID v : vendors) Db.exec("INSERT INTO rfq_vendors(rfq_id, vendor_id) VALUES (?,?)", rfq, v);
    }

    private static UUID quote(UUID rfq, UUID vendor, int deliveryInDays, String notes, Object[]... lines) {
        UUID q = Db.scalar("INSERT INTO quotations(rfq_id, vendor_id, delivery_date, notes, submitted_at) VALUES (?,?,?,?, now() - interval '3 days') RETURNING id",
                UUID.class, rfq, vendor, LocalDate.now().plusDays(deliveryInDays), notes.isEmpty() ? null : notes);
        for (Object[] l : lines) {
            Db.exec("INSERT INTO quotation_lines(quotation_id, rfq_line_id, unit_price, fulfil_qty) VALUES (?,?,?,?)",
                    q, l[0], new BigDecimal(l[1].toString()), new BigDecimal(l[2].toString()));
        }
        return q;
    }

    private static UUID po(UUID c, UUID by, UUID vendor, String card, String source, UUID rfq, int dueInDays, Object[] line) {
        BigDecimal qty = new BigDecimal(line[3].toString());
        BigDecimal price = new BigDecimal(line[4].toString());
        UUID id = Db.scalar("""
                INSERT INTO purchase_orders(company_id, vendor_id, card_code, source, rfq_id, doc_date, doc_due_date, doc_total, status, created_by_user_id)
                VALUES (?,?,?,?,?, CURRENT_DATE, ?, ?, 'SAP_PENDING', ?) RETURNING id""", UUID.class,
                c, vendor, card, source, rfq, LocalDate.now().plusDays(dueInDays), qty.multiply(price), by);
        Db.exec("""
                INSERT INTO purchase_order_lines(purchase_order_id, line_num, item_code, item_name, uom, quantity, unit_price, warehouse_code, tax_code, ship_date)
                VALUES (?,0,?,?,?,?,?,?, 'GST18', ?)""", id, line[0], line[1], line[2], qty, price, line[5], LocalDate.now().plusDays(dueInDays));
        Row r = SyncService.processNow(SyncService.enqueue(c, "CREATE_PURCHASE_ORDER", id, by));
        if (!"POSTED".equals(r.str("status"))) log.warn("Seed PO was not accepted by the B1 simulator: {}", r.str("errorDetail"));
        return id;
    }

    private static void ack(UUID po) {
        Db.exec("UPDATE purchase_orders SET status = 'ACKNOWLEDGED', acknowledged_at = now() - interval '5 days' WHERE id = ?", po);
    }

    private static UUID asn(UUID c, UUID po, String no, String carrier, String awb, int shippedDaysAgo, int cartons, int pallets, String weight) {
        UUID asn = Db.scalar("""
                INSERT INTO asns(company_id, purchase_order_id, asn_no, ship_date, expected_delivery, carrier, tracking_no, cartons, pallets, total_weight, submitted_at)
                VALUES (?,?,?,?,?,?,?,?,?,?, now() - (? * interval '1 day')) RETURNING id""", UUID.class,
                c, po, no, LocalDate.now().plusDays(shippedDaysAgo), LocalDate.now().plusDays(shippedDaysAgo + 3), carrier, awb, cartons, pallets, weight, -shippedDaysAgo);
        Row l = Db.one("SELECT id, quantity, warehouse_code FROM purchase_order_lines WHERE purchase_order_id = ?", po);
        Db.exec("INSERT INTO asn_lines(asn_id, po_line_id, shipped_qty) VALUES (?,?,?)", asn, l.uuid("id"), l.dec("quantity"));
        Db.exec("UPDATE purchase_order_lines SET shipped_qty = quantity WHERE id = ?", l.uuid("id"));
        UUID g = Db.scalar("INSERT INTO grpos(company_id, asn_id, purchase_order_id) VALUES (?,?,?) RETURNING id", UUID.class, c, asn, po);
        Db.exec("INSERT INTO grpo_lines(grpo_id, po_line_id, asn_qty, received_qty, warehouse_code) VALUES (?,?,?,?,?)",
                g, l.uuid("id"), l.dec("quantity"), l.dec("quantity"), l.str("warehouseCode"));
        PoStatus.recompute(po);
        SyncService.processNow(SyncService.enqueue(c, "CREATE_GRPO_DRAFT", g, null));
        return g;
    }

    private static void confirm(UUID c, UUID grpo, UUID by) {
        Db.exec("UPDATE grpos SET status = 'POSTING', posting_date = CURRENT_DATE - 5, confirmed_by_user_id = ?, confirmed_at = now() - interval '5 days' WHERE id = ?", by, grpo);
        SyncService.processNow(SyncService.enqueue(c, "POST_GRPO", grpo, by));
    }
}
