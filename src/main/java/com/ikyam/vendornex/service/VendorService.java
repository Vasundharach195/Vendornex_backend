package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Validators;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.dto.VendorFieldsRequest;
import com.ikyam.vendornex.entity.User;
import com.ikyam.vendornex.entity.Vendor;
import com.ikyam.vendornex.entity.VendorDocument;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.UserRepository;
import com.ikyam.vendornex.repository.VendorDocumentRepository;
import com.ikyam.vendornex.repository.VendorQueries;
import com.ikyam.vendornex.repository.VendorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic moved out of {@code VendorController}, plus the vendor lifecycle transitions
 * the old static {@code VendorService} held ({@code onApprovalComplete}/{@code onActivated}) — now
 * shared by the approval engine and the B1 sync worker via this bean instead of static calls.
 *
 * <p>{@code submit(...)} is {@code @Transactional} and returns the ids of any sync transaction
 * enqueued by an auto-approval; the caller ({@code VendorController}, a different, non-transactional
 * bean) processes them against SAP <em>after</em> this method returns/commits — the same
 * "commit, then call the external system" ordering the original {@code Db.tx} + post-commit
 * {@code SyncService.processNow} call had, achieved here simply because the controller is a
 * separate bean (no self-invocation proxy issue to work around).
 */
@Service
public class VendorService {

    private static final Logger log = LoggerFactory.getLogger(VendorService.class);
    private static final List<String> REQUIRED_DOCS = List.of("GST", "PAN");

    private final VendorRepository vendors;
    private final VendorDocumentRepository documents;
    private final VendorQueries queries;
    private final ApprovalService approvalService;
    private final UserRepository users;

    public VendorService(VendorRepository vendors, VendorDocumentRepository documents, VendorQueries queries,
                          ApprovalService approvalService, UserRepository users) {
        this.vendors = vendors;
        this.documents = documents;
        this.queries = queries;
        this.approvalService = approvalService;
        this.users = users;
    }

    // ------------------------------------------------------------------ reads

    public List<Row> list(UUID companyId, String status, String q) {
        return queries.list(companyId, status, q, ApprovalService.summarySql("VENDOR", "v.id"));
    }

    public List<Row> lookup(UUID companyId) {
        return queries.lookup(companyId);
    }

    public Row detail(UUID id, UUID companyId) {
        Row v = queries.findFullById(id, companyId);
        if (v == null) throw ApiException.notFound("Vendor");
        v.put("approvals", approvalService.steps("VENDOR", v.uuid("id")));
        v.put("documents", documents.findByVendorIdOrderByDocType(v.uuid("id")).stream().map(this::docRow).toList());
        List<Row> users = queries.vendorUsers(v.uuid("id"));
        for (Row user : users) user.put("inviteLink", AuthService.inviteLink((String) user.remove("inviteToken")));
        v.put("portalUsers", users);
        v.put("sapSync", SyncService.latestFor("VENDOR", v.uuid("id")));
        if (v.str("sapCardCode") != null) {
            v.put("b1BusinessPartner", queries.businessPartnerForDetail(companyId, v.str("sapCardCode")));
        }
        return v;
    }

    private Row docRow(VendorDocument d) {
        return new Row().with("id", d.getId().toString()).with("docType", d.getDocType()).with("fileName", d.getFileName())
                .with("contentType", d.getContentType()).with("sizeBytes", d.getSizeBytes())
                .with("expiryDate", d.getExpiryDate() == null ? null : d.getExpiryDate().toString())
                .with("uploadedAt", d.getUploadedAt() == null ? null : d.getUploadedAt().toString());
    }

    // ------------------------------------------------------------------ wizard

    @Transactional(rollbackFor = Exception.class)
    public Row create(VendorFieldsRequest req, UUID companyId, UUID userId) {
        Fields f = Fields.from(req, companyId, queries);
        if (f.legalName == null && f.contactName == null && f.email == null) {
            throw ApiException.badRequest("Add at least a name or e-mail before saving");
        }
        Vendor v = new Vendor(companyId, f.legalName == null ? "(untitled vendor)" : f.legalName, userId);
        vendors.save(v);
        f.write(v.getId(), req.wizardStep, queries);
        return queries.findFullById(v.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public Row update(UUID id, VendorFieldsRequest req, UUID companyId) {
        Row v = editable(id, companyId);
        Fields f = Fields.from(req, companyId, queries);
        f.write(v.uuid("id"), req.wizardStep, queries);
        return queries.findFullById(v.uuid("id"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(UUID id, UUID companyId) {
        Vendor v = vendors.findByIdAndCompanyId(id, companyId).orElseThrow(() -> ApiException.notFound("Vendor"));
        if (!"DRAFT".equals(v.getStatus())) throw ApiException.conflict("Only drafts can be deleted");
        for (VendorDocument d : documents.findByVendorIdOrderByDocType(v.getId())) DocumentStore.delete(d.getStorageKey());
        documents.deleteByVendorId(v.getId());
        vendors.delete(v);
    }

    private Row editable(UUID id, UUID companyId) {
        Row v = queries.findFullById(id, companyId);
        if (v == null) throw ApiException.notFound("Vendor");
        String s = v.str("status");
        if (!s.equals("DRAFT") && !s.equals("REJECTED") && !s.equals("SAP_SYNC_FAILED")) {
            throw ApiException.conflict("Vendor details are locked while " + s.toLowerCase().replace('_', ' '));
        }
        return v;
    }

    public static final class SubmitResult {
        public final List<UUID> postCommitSyncIds;
        public final List<String> warnings;
        public SubmitResult(List<UUID> postCommitSyncIds, List<String> warnings) {
            this.postCommitSyncIds = postCommitSyncIds;
            this.warnings = warnings;
        }
    }

    /** Submit a draft (or resubmit a rejected vendor) into the approval chain. */
    @Transactional(rollbackFor = Exception.class)
    public SubmitResult submit(UUID vendorId, UUID companyId, UUID userId) {
        List<UUID> postCommit = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Vendor locked = vendors.lockByIdAndCompanyId(vendorId, companyId).orElseThrow(() -> ApiException.notFound("Vendor"));
        if (!Set.of("DRAFT", "REJECTED").contains(locked.getStatus())) {
            throw ApiException.conflict("Vendor is already " + locked.getStatus().toLowerCase().replace('_', ' '));
        }
        Row v = queries.findFullById(vendorId, companyId);
        validateComplete(v, companyId, warnings);
        queries.updateStatusPendingApproval(vendorId);
        if (approvalService.start(companyId, "VENDOR", vendorId) == ApprovalService.Start.AUTO) {
            UUID tx = approvalService.onApproved("VENDOR", vendorId, companyId, userId);
            if (tx != null) postCommit.add(tx);
        }
        return new SubmitResult(postCommit, warnings);
    }

    private void validateComplete(Row v, UUID c, List<String> warnings) {
        List<String> missing = new ArrayList<>();
        if (v.str("legalName") == null || v.str("legalName").startsWith("(untitled")) missing.add("legal entity name");
        if (v.str("contactName") == null) missing.add("contact name");
        if (v.str("email") == null) missing.add("e-mail");
        if (v.integer("vendorGroupCode") == null) missing.add("vendor group");
        if (v.str("gstin") == null) missing.add("GSTIN");
        if (v.str("pan") == null) missing.add("PAN");
        if (v.str("bankName") == null || v.str("bankAccountNo") == null || v.str("bankIfsc") == null) missing.add("bank details");
        Set<String> docTypes = new HashSet<>();
        for (VendorDocument d : documents.findByVendorIdOrderByDocType(v.uuid("id"))) docTypes.add(d.getDocType());
        for (String d : REQUIRED_DOCS) if (!docTypes.contains(d)) missing.add(d + " certificate upload");
        if (!missing.isEmpty()) throw ApiException.badRequest("Complete these before submitting: " + String.join(", ", missing));
        Validators.gstinMatchesPan(v.str("gstin"), v.str("pan"));
        if (!queries.vendorGroupExists(c, v.integer("vendorGroupCode"))) {
            throw ApiException.badRequest("Vendor group " + v.integer("vendorGroupCode") + " does not exist in SAP B1 — sync masters or pick another group");
        }
        Row other = queries.userByEmail(v.str("email"));
        if (other != null && !v.uuid("id").equals(other.uuid("vendorId"))) {
            throw ApiException.conflict("The e-mail " + v.str("email") + " is already used by another portal user");
        }
        if ("EXISTING".equals(v.str("linkType"))) {
            Row bp = queries.businessPartnerActive(c, v.str("sapCardCode"));
            if (bp == null) throw ApiException.badRequest("Business Partner " + v.str("sapCardCode") + " was not found in SAP B1");
            if (!bp.bool("isActive")) {
                warnings.add("B1 Business Partner " + v.str("sapCardCode") + " is Inactive in SAP B1 — purchase orders will be rejected by B1 until it is activated there.");
            }
            warnings.add("Linked to an existing B1 Business Partner: the tax and bank details captured here are kept in VendorNex for approval and are not written back to B1.");
        }
        int dupGstin = queries.duplicateGstinCount(c, v.str("gstin"), v.uuid("id"));
        if (dupGstin > 0) warnings.add("Another vendor with GSTIN " + v.str("gstin") + " already exists.");
        for (VendorDocument d : documents.findByVendorIdAndExpiryDateBefore(v.uuid("id"), LocalDate.now())) {
            warnings.add(d.getDocType() + " document expired on " + d.getExpiryDate() + ".");
        }
    }

    public void retrySapValidate(UUID vendorId, UUID companyId) {
        Vendor v = vendors.findByIdAndCompanyId(vendorId, companyId).orElseThrow(() -> ApiException.notFound("Vendor"));
        if (!"SAP_SYNC_FAILED".equals(v.getStatus())) throw ApiException.conflict("Vendor is not in a failed B1 sync state");
    }

    @Transactional(rollbackFor = Exception.class)
    public void setStatus(UUID vendorId, boolean active, UUID companyId) {
        Vendor v = vendors.findByIdAndCompanyId(vendorId, companyId).orElseThrow(() -> ApiException.notFound("Vendor"));
        String s = v.getStatus();
        if (active && !"INACTIVE".equals(s)) throw ApiException.conflict("Only inactive vendors can be re-activated");
        if (!active && !"ACTIVE".equals(s)) throw ApiException.conflict("Only active vendors can be deactivated");
        v.setStatus(active ? "ACTIVE" : "INACTIVE");
        vendors.save(v);
    }

    public String reinvite(UUID vendorId, UUID companyId) {
        Row userRow = queries.reinviteCandidateUser(vendorId, companyId);
        if (userRow == null) {
            Vendor v = vendors.findByIdAndCompanyId(vendorId, companyId).orElseThrow(() -> ApiException.notFound("Vendor"));
            if (!"ACTIVE".equals(v.getStatus())) throw ApiException.conflict("The vendor gets a portal login once it is active");
            onActivated(vendorId);
            userRow = queries.reinviteCandidateUser(vendorId, companyId);
            if (userRow == null) throw ApiException.conflict("The vendor's e-mail is already used by another user");
        }
        if ("ACTIVE".equals(userRow.str("status"))) throw ApiException.conflict("The vendor user has already activated their login");
        return AuthService.issueInvite(userRow.uuid("id"));
    }

    // ------------------------------------------------------------------ documents

    @Transactional(rollbackFor = Exception.class)
    public List<Row> upload(UUID id, String docType, byte[] data, String fileName, String contentType, LocalDate expiry,
                             UUID companyId, UUID userId) {
        Row v = editable(id, companyId);
        String key = DocumentStore.save(companyId, v.uuid("id"), data);
        if (!"OTHER".equals(docType)) {
            for (VendorDocument old : documents.findByVendorIdAndDocType(v.uuid("id"), docType)) DocumentStore.delete(old.getStorageKey());
            documents.deleteAll(documents.findByVendorIdAndDocType(v.uuid("id"), docType));
        }
        documents.save(new VendorDocument(companyId, v.uuid("id"), docType, fileName, contentType, data.length, key, expiry, userId));
        return documents.findByVendorIdOrderByDocType(v.uuid("id")).stream().map(this::docRow).toList();
    }

    public ResponseEntity<byte[]> download(UUID id, UUID docId, UUID companyId) {
        VendorDocument d = documents.findByIdAndVendorIdAndCompanyId(docId, id, companyId).orElseThrow(() -> ApiException.notFound("Document"));
        byte[] data = DocumentStore.load(d.getStorageKey());
        String contentType = d.getContentType() == null ? "application/octet-stream" : d.getContentType();
        String fileName = d.getFileName().replace("\"", "");
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .body(data);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDoc(UUID id, UUID docId, UUID companyId) {
        Row v = editable(id, companyId);
        VendorDocument d = documents.findByIdAndVendorId(docId, v.uuid("id")).orElseThrow(() -> ApiException.notFound("Document"));
        DocumentStore.delete(d.getStorageKey());
        documents.delete(d);
    }

    // ------------------------------------------------------------------ lifecycle (formerly the static VendorService)

    /** All approval stages passed. A vendor linked to an existing B1 Business Partner goes live
     * immediately; a new vendor is queued for creation in B1 as a Business Partner.
     * @return the sync transaction to process after commit, or null */
    @Transactional(rollbackFor = Exception.class)
    public UUID onApprovalComplete(UUID companyId, UUID vendorId, UUID userId) {
        Vendor v = vendors.findByIdAndCompanyId(vendorId, companyId).orElseThrow();
        if ("EXISTING".equals(v.getLinkType()) && v.getSapCardCode() != null) {
            v.setStatus("ACTIVE");
            v.setActivatedAt(Instant.now());
            vendors.save(v);
            onActivated(vendorId);
            return null;
        }
        v.setStatus("SAP_SYNC_PENDING");
        vendors.save(v);
        return SyncService.enqueue(companyId, "CREATE_VENDOR", vendorId, userId);
    }

    /** Vendor is live: make sure it has a portal login and send (return) its invitation. */
    @Transactional(rollbackFor = Exception.class)
    public void onActivated(UUID vendorId) {
        Row v = queries.findFullById(vendorId);
        if (v.str("email") == null) return;
        Row existing = queries.userByEmail(v.str("email"));
        if (existing != null) {
            UUID existingVendorId = existing.str("vendorId") == null ? null : existing.uuid("vendorId");
            if (!vendorId.equals(existingVendorId)) {
                log.warn("Vendor {} activated but e-mail {} already belongs to another user — no portal login created", vendorId, v.str("email"));
            }
            return;
        }
        User u = new User();
        u.setCompanyId(v.uuid("companyId"));
        u.setName(v.str("contactName") == null ? v.str("legalName") : v.str("contactName"));
        u.setEmail(v.str("email").toLowerCase(Locale.ROOT));
        u.setRole("VENDOR");
        u.setStatus("INVITED");
        u.setVendorId(vendorId);
        users.save(u);
        String link = AuthService.issueInvite(u.getId());
        log.info("Vendor portal invitation for {}: {}", v.str("legalName"), link);
    }
}
