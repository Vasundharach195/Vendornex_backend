package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Maps {@code users}. {@code companyId}/{@code vendorId} stay plain UUID columns (no {@code @ManyToOne})
 * since no query in the codebase navigates them as an object graph — every read is an explicit SQL
 * join — and {@code Vendor} isn't converted to an entity until a later migration stage.
 *
 * <p>{@code approvalStages} is an {@code @ElementCollection}: {@code user_approval_stages} is only
 * ever fully replaced (delete-all, re-insert) on save and never queried independently of its owning
 * user, which is exactly what Hibernate's default collection persistence does.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, length = 20)
    private String status = "INVITED";

    @Column(name = "vendor_id", columnDefinition = "uuid")
    private UUID vendorId;

    @Column(length = 100)
    private String department;

    @Column(name = "sap_employee_id")
    private Integer sapEmployeeId;

    @Column(name = "invite_token", length = 80)
    private String inviteToken;

    @Column(name = "invite_expires_at")
    private Instant inviteExpiresAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_approval_stages", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "stage", length = 20)
    private Set<String> approvalStages = new LinkedHashSet<>();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public Integer getSapEmployeeId() { return sapEmployeeId; }
    public void setSapEmployeeId(Integer sapEmployeeId) { this.sapEmployeeId = sapEmployeeId; }

    public String getInviteToken() { return inviteToken; }
    public void setInviteToken(String inviteToken) { this.inviteToken = inviteToken; }

    public Instant getInviteExpiresAt() { return inviteExpiresAt; }
    public void setInviteExpiresAt(Instant inviteExpiresAt) { this.inviteExpiresAt = inviteExpiresAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Set<String> getApprovalStages() { return approvalStages; }
    public void setApprovalStages(Set<String> approvalStages) {
        this.approvalStages.clear();
        this.approvalStages.addAll(approvalStages);
    }
}
