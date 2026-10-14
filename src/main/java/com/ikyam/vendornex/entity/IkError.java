package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code ik_error} (tenant schema): one failed integration run. {@code type} is Branch, Bank,
 * Business Partner, GL Accounts, Incoming Payment, Outgoing Payment, ITR or IT; {@code json} keeps the payload. */
@Entity
@Table(name = "ik_error")
public class IkError {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "error_id", length = 255)
    private String errorId;

    @Column(name = "schema_id", nullable = false)
    private String schemaId;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt = Instant.now();

    @Column(name = "type", nullable = false, length = 250)
    private String type;

    @Column(name = "error_desc", nullable = false, columnDefinition = "text")
    private String errorDesc;

    @Column(name = "success_desc", columnDefinition = "text")
    private String successDesc;

    @Column(name = "json", columnDefinition = "text")
    private String json;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    private UUID createdBy;

    protected IkError() {}

    public IkError(String schemaId, String type, String errorDesc, String json, UUID createdBy) {
        this.schemaId = schemaId;
        this.type = type;
        this.errorDesc = errorDesc;
        this.json = json;
        this.createdBy = createdBy;
    }

    public String getErrorId() { return errorId; }
    public String getSchemaId() { return schemaId; }
    public Instant getExecutedAt() { return executedAt; }
    public String getType() { return type; }
    public String getErrorDesc() { return errorDesc; }
    public String getSuccessDesc() { return successDesc; }
    public String getJson() { return json; }
    public UUID getCreatedBy() { return createdBy; }
}
