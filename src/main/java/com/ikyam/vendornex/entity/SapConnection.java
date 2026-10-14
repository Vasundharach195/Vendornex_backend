package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code ik_sap_connection} (tenant schema): one SAP B1 company DB a company selected during
 * onboarding. {@code sapPassword} holds the AES-256-GCM ciphertext ({@code Crypto}), never plaintext. */
@Entity
@Table(name = "ik_sap_connection")
@DynamicUpdate
public class SapConnection {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "connection_id", columnDefinition = "uuid")
    private UUID connectionId;

    @Column(name = "schema_id", nullable = false, length = 25)
    private String schemaId;

    @Column(name = "connection_name", nullable = false, length = 100)
    private String connectionName;

    @Column(name = "company_db_code", nullable = false, length = 100)
    private String companyDbCode;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "sap_username", nullable = false, length = 100)
    private String sapUsername;

    @Column(name = "sap_password", nullable = false)
    private String sapPassword;

    @Column(name = "is_default", nullable = false)
    private boolean defaultConnection;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sequence_no")
    private Integer sequenceNo;

    @Column(name = "last_health_check_at")
    private Instant lastHealthCheckAt;

    @Column(name = "last_health_status", length = 15)
    private String lastHealthStatus;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    private UUID updatedBy;

    protected SapConnection() {}

    public SapConnection(String schemaId, String connectionName, String companyDbCode, String baseUrl,
                         String sapUsername, String sapPassword, boolean defaultConnection,
                         Integer sequenceNo, UUID createdBy) {
        this.schemaId = schemaId;
        this.connectionName = connectionName;
        this.companyDbCode = companyDbCode;
        this.baseUrl = baseUrl;
        this.sapUsername = sapUsername;
        this.sapPassword = sapPassword;
        this.defaultConnection = defaultConnection;
        this.sequenceNo = sequenceNo;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    public void recordHealthCheck(String status, UUID by) {
        this.lastHealthCheckAt = Instant.now();
        this.lastHealthStatus = status;
        touch(by);
    }

    public void setActive(boolean active, UUID by) { this.active = active; touch(by); }

    private void touch(UUID by) { this.updatedAt = Instant.now(); this.updatedBy = by; }

    public UUID getConnectionId() { return connectionId; }
    public String getSchemaId() { return schemaId; }
    public String getConnectionName() { return connectionName; }
    public String getCompanyDbCode() { return companyDbCode; }
    public String getBaseUrl() { return baseUrl; }
    public String getSapUsername() { return sapUsername; }
    public String getSapPassword() { return sapPassword; }
    public boolean isDefaultConnection() { return defaultConnection; }
    public boolean isActive() { return active; }
    public Integer getSequenceNo() { return sequenceNo; }
    public Instant getLastHealthCheckAt() { return lastHealthCheckAt; }
    public String getLastHealthStatus() { return lastHealthStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
}
