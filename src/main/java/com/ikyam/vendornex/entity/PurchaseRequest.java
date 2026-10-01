package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code purchase_requests}. Lines live in {@link PurchaseRequestLine} (plain
 * {@code purchaseRequestId} column, no JPA association — same convention as the other entities).
 * {@code @DynamicUpdate} so status changes emit a narrow UPDATE.
 */
@Entity
@Table(name = "purchase_requests")
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PurchaseRequest {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "company_id", nullable = false, columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "pr_no", nullable = false, length = 30)
    private String prNo;

    /** APP = raised in VendorNex, B1 = mirrored from SAP B1. */
    @Column(nullable = false, length = 5)
    private String source;

    @Column(name = "sap_doc_entry")
    private Integer sapDocEntry;

    @Column(name = "sap_doc_num")
    private Integer sapDocNum;

    @Column(name = "requester_user_id", columnDefinition = "uuid")
    private UUID requesterUserId;

    @Column(name = "requester_name", nullable = false, length = 150)
    private String requesterName;

    @Column(length = 100)
    private String department;

    @Column(name = "required_date")
    private LocalDate requiredDate;

    @Column(length = 2000)
    private String justification;

    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "PENDING_APPROVAL";

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Builder.Default
    @Column(name = "sap_push_status", nullable = false, length = 15)
    private String sapPushStatus = "NOT_REQUIRED";

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
