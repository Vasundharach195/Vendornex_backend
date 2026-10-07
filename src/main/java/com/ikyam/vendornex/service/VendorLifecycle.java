package com.ikyam.vendornex.service;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Vendor lifecycle transitions shared by the approval engine and the B1 sync worker. */
public final class VendorLifecycle {

    private static final Logger log = LoggerFactory.getLogger(VendorLifecycle.class);

    private VendorLifecycle() {}

    /**
     * All approval stages passed. A vendor linked to an existing B1 Business Partner goes live
     * immediately (B1 already has the master record). A new vendor is queued for creation in B1 as
     * a Business Partner (CardType = Supplier) and goes live once Service Layer confirms the CardCode.
     *
     * @return the sync transaction to process after commit, or null
     */
    public static UUID onApprovalComplete(UUID companyId, UUID vendorId, UUID userId) {
        Row v = Db.one("SELECT link_type, sap_card_code FROM vendors WHERE id = ? AND company_id = ?", vendorId, companyId);
        if ("EXISTING".equals(v.str("linkType")) && v.str("sapCardCode") != null) {
            Db.exec("UPDATE vendors SET status = 'ACTIVE', activated_at = now(), updated_at = now() WHERE id = ?", vendorId);
            onActivated(vendorId);
            return null;
        }
        Db.exec("UPDATE vendors SET status = 'SAP_SYNC_PENDING', updated_at = now() WHERE id = ?", vendorId);
        return SyncService.enqueue(companyId, "CREATE_VENDOR", vendorId, userId);
    }

    /** Vendor is live: make sure it has a portal login and send (return) its invitation. */
    public static void onActivated(UUID vendorId) {
        Row v = Db.one("SELECT id, company_id, legal_name, contact_name, email FROM vendors WHERE id = ?", vendorId);
        if (v.str("email") == null) return;
        Row existing = Db.one("SELECT id, vendor_id, status FROM global_users WHERE lower(email) = lower(?)", v.str("email"));
        if (existing != null) {
            if (!vendorId.equals(existing.uuid("vendorId"))) {
                log.warn("Vendor {} activated but e-mail {} already belongs to another user — no portal login created", vendorId, v.str("email"));
            }
            return;
        }
        UUID userId = Db.scalar("""
                INSERT INTO users(company_id, name, email, role, status, vendor_id) VALUES (?,?,?, 'VENDOR', 'INVITED', ?) RETURNING id""",
                UUID.class, v.uuid("companyId"), v.str("contactName") == null ? v.str("legalName") : v.str("contactName"),
                v.str("email").toLowerCase(), vendorId);
        String link = AuthService.issueInvite(userId);
        log.info("Vendor portal invitation for {}: {}", v.str("legalName"), link);
    }
}
