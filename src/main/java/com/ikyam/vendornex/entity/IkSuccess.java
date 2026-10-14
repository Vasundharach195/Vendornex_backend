package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code ik_success} (tenant schema): one successful integration run. {@code type} is Branch, Bank,
 * Business Partner, GL Accounts, Incoming Payment, Outgoing Payment, ITR or IT; {@code json} keeps the payload. */
@Entity
@Table(name = "ik_success")
public class IkSuccess {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "success_id", length = 255)
    private String successId;

    @Column(name = "schema_id", nullable = false)
    private String schemaId;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt = Instant.now();

    @Column(name = "type", nullable = false, length = 250)
    private String type;

    @Column(name = "last_sync_at", nullable = false)
    private Instant lastSyncAt = Instant.now();

    @Column(name = "success_desc", columnDefinition = "text")
    private String successDesc;

    @Column(name = "json", columnDefinition = "text")
    private String json;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    private UUID createdBy;

    protected IkSuccess() {}

    public IkSuccess(String schemaId, String type, String successDesc, String json, UUID createdBy) {
        this.schemaId = schemaId;
        this.type = type;
        this.successDesc = successDesc;
        this.json = json;
        this.createdBy = createdBy;
    }

    public String getSuccessId() { return successId; }
    public String getSchemaId() { return schemaId; }
    public Instant getExecutedAt() { return executedAt; }
    public String getType() { return type; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public String getSuccessDesc() { return successDesc; }
    public String getJson() { return json; }
    public UUID getCreatedBy() { return createdBy; }
}
