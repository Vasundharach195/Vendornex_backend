package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code approval_steps}. {@code entityId}/{@code entityType} are a polymorphic reference
 * (VENDOR/REQUESTER/PURCHASE_REQUEST) — plain columns, no JPA association, resolved by switching
 * on the type exactly as {@code ApprovalService} already does. {@code @DynamicUpdate} so the
 * single/few-column status transitions this entity goes through (act/approve/reject) emit the
 * same narrow UPDATE the original {@code Db.exec} calls did, not a full-column rewrite.
 */
@Entity
@Table(name = "approval_steps")
@DynamicUpdate
public class ApprovalStep {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "company_id", nullable = false, columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType;

    @Column(name = "entity_id", nullable = false, columnDefinition = "uuid")
    private UUID entityId;

    @Column(nullable = false, length = 20)
    private String stage;

    @Column(nullable = false)
    private short seq;

    @Column(nullable = false, length = 15)
    private String status = "NOT_STARTED";

    @Column(nullable = false)
    private short round = 1;

    @Column(name = "acted_by_user_id", columnDefinition = "uuid")
    private UUID actedByUserId;

    @Column(name = "acted_at")
    private Instant actedAt;

    @Column(length = 1000)
    private String remarks;

    protected ApprovalStep() {}

    public ApprovalStep(UUID companyId, String entityType, UUID entityId, String stage, int seq, String status, int round) {
        this.companyId = companyId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.stage = stage;
        this.seq = (short) seq;
        this.status = status;
        this.round = (short) round;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getStage() { return stage; }
    public int getSeq() { return seq; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRound() { return round; }
    public UUID getActedByUserId() { return actedByUserId; }
    public void setActedByUserId(UUID actedByUserId) { this.actedByUserId = actedByUserId; }
    public Instant getActedAt() { return actedAt; }
    public void setActedAt(Instant actedAt) { this.actedAt = actedAt; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
}
