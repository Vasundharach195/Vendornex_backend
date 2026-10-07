package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Maps {@code companies} (tenant root). See V1__init.sql for the authoritative schema. */
@Entity
@Table(name = "companies")
@DynamicUpdate
public class Company {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    private String industry;

    @Column(name = "integration_mode", nullable = false, length = 20)
    private String integrationMode = "MOCK";

    @Column(name = "service_layer_base_url", length = 500)
    private String serviceLayerBaseUrl;

    @Column(name = "sap_company_db", length = 100)
    private String sapCompanyDb;

    @Column(name = "sl_username", length = 100)
    private String slUsername;

    @Column(name = "sl_password_enc", columnDefinition = "text")
    private String slPasswordEnc;

    @Column(name = "sl_verify_tls", nullable = false)
    private boolean slVerifyTls = true;

    @Column(name = "connection_status", nullable = false, length = 20)
    private String connectionStatus = "NOT_TESTED";

    @Column(name = "connection_message", length = 1000)
    private String connectionMessage;

    @Column(name = "sap_b1_version", length = 50)
    private String sapB1Version;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Postgres schema holding this company's tables (vnx_c00001, ...). Set once at onboarding. */
    @Column(name = "schema_id", length = 30, updatable = false)
    private String schemaId;

    @Column(name = "onboarded_on", nullable = false)
    private LocalDate onboardedOn = LocalDate.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getIntegrationMode() { return integrationMode; }
    public void setIntegrationMode(String integrationMode) { this.integrationMode = integrationMode; }

    public String getServiceLayerBaseUrl() { return serviceLayerBaseUrl; }
    public void setServiceLayerBaseUrl(String serviceLayerBaseUrl) { this.serviceLayerBaseUrl = serviceLayerBaseUrl; }

    public String getSapCompanyDb() { return sapCompanyDb; }
    public void setSapCompanyDb(String sapCompanyDb) { this.sapCompanyDb = sapCompanyDb; }

    public String getSlUsername() { return slUsername; }
    public void setSlUsername(String slUsername) { this.slUsername = slUsername; }

    public String getSlPasswordEnc() { return slPasswordEnc; }
    public void setSlPasswordEnc(String slPasswordEnc) { this.slPasswordEnc = slPasswordEnc; }

    public boolean isSlVerifyTls() { return slVerifyTls; }
    public void setSlVerifyTls(boolean slVerifyTls) { this.slVerifyTls = slVerifyTls; }

    public String getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; }

    public String getConnectionMessage() { return connectionMessage; }
    public void setConnectionMessage(String connectionMessage) { this.connectionMessage = connectionMessage; }

    public String getSapB1Version() { return sapB1Version; }
    public void setSapB1Version(String sapB1Version) { this.sapB1Version = sapB1Version; }

    public Instant getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Instant lastTestedAt) { this.lastTestedAt = lastTestedAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }

    public LocalDate getOnboardedOn() { return onboardedOn; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
