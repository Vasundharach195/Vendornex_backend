package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code vendors}. Used as a real JPA entity only where its exact-shape is not needed for a
 * JSON response and no subquery-driven write is involved — i.e. the pessimistic lock in
 * {@code VendorService.submit} and simple status-only mutations from the approval engine. Every
 * "give me the full flat vendor row for a response" read, and the wizard's big multi-column
 * UPDATE (which sets {@code sap_bp_status} from a correlated subquery — no JPA equivalent), stay
 * on {@code VendorQueries} (JdbcTemplate) with unchanged SQL text — 25+ columns is too large a
 * surface to hand-map field-by-field into a response Row without real risk of a silent omission.
 */
@Entity
@Table(name = "vendors")
public class Vendor {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "company_id", nullable = false, columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(nullable = false, length = 25)
    private String status = "DRAFT";

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "link_type", nullable = false, length = 10)
    private String linkType = "NEW";

    @Column(name = "sap_card_code", length = 15)
    private String sapCardCode;

    @Column(name = "created_by_user_id", columnDefinition = "uuid")
    private UUID createdByUserId;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    protected Vendor() {}

    public Vendor(UUID companyId, String legalName, UUID createdByUserId) {
        this.companyId = companyId;
        this.legalName = legalName;
        this.createdByUserId = createdByUserId;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public String getLegalName() { return legalName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public String getLinkType() { return linkType; }
    public String getSapCardCode() { return sapCardCode; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant activatedAt) { this.activatedAt = activatedAt; }
}
