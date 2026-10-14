package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Maps {@code ik_onboarding_company} (tenant root). See V1__init.sql for the authoritative schema. */
@Entity
@Table(name = "ik_onboarding_company")
@DynamicUpdate
public class Company {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "onboard_company_id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "company_name", nullable = false, length = 200)
    private String name;

    @Column(name = "industry_type", length = 255)
    private String industry;

    @Column(name = "integration_mode", nullable = false, length = 20)
    private String integrationMode = "MOCK";

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
    @Column(name = "schema_id", nullable = false, length = 255, updatable = false)
    private String schemaId;

    @Column(name = "onboarded_on", nullable = false)
    private LocalDate onboardedOn = LocalDate.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
    
    

    // ---- onboarding sheet columns (see V1__init.sql) ----
    @Column(name = "company_phone_no", nullable = false, length = 15)
    private String companyPhoneNo;

    @Column(name = "company_street", nullable = false, length = 255)
    private String companyStreet;

    @Column(name = "company_city", nullable = false, length = 255)
    private String companyCity;

    @Column(name = "company_state", nullable = false, length = 255)
    private String companyState;

    @Column(name = "company_zipcode", nullable = false, length = 6)
    private String companyZipcode;

    @Column(name = "company_website", nullable = false, length = 255)
    private String companyWebsite;

    @Column(name = "headoffice_location", nullable = false, length = 255)
    private String headofficeLocation;

    @Column(name = "company_gst", nullable = false, length = 50)
    private String companyGst;

    @Column(name = "registration_number", nullable = false, length = 50)
    private String registrationNumber;

    @Column(name = "user_name", nullable = false, length = 255)
    private String userName;

    @Column(name = "email", nullable = false, length = 100)
    private String email;

    @Column(name = "user_phone_no", nullable = false, length = 15)
    private String userPhoneNo;

    @Column(name = "is_approved", nullable = false)
    private boolean approved;

    @Column(name = "email_id", nullable = false, length = 100)
    private String emailId;

    /** AES-256-GCM ciphertext (Crypto.encrypt), never plaintext. */
    @Column(name = "email_pwd", nullable = false, length = 100)
    private String emailPwd;

    @Column(name = "smtp_server", nullable = false, length = 100)
    private String smtpServer;

    @Column(name = "smtp_port", nullable = false, length = 4)
    private String smtpPort;


    /** Logo image bytes (Postgres bytea). Plain byte[] — @Lob would map to a large-object oid on Postgres. */
    @Column(name = "company_logo", columnDefinition = "bytea")
    private byte[] companyLogo;

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
    
    

    public String getCompanyPhoneNo() { return companyPhoneNo; }
    public void setCompanyPhoneNo(String v) { this.companyPhoneNo = v; }

    public String getCompanyStreet() { return companyStreet; }
    public void setCompanyStreet(String v) { this.companyStreet = v; }

    public String getCompanyCity() { return companyCity; }
    public void setCompanyCity(String v) { this.companyCity = v; }

    public String getCompanyState() { return companyState; }
    public void setCompanyState(String v) { this.companyState = v; }

    public String getCompanyZipcode() { return companyZipcode; }
    public void setCompanyZipcode(String v) { this.companyZipcode = v; }

    public String getCompanyWebsite() { return companyWebsite; }
    public void setCompanyWebsite(String v) { this.companyWebsite = v; }

    public String getHeadofficeLocation() { return headofficeLocation; }
    public void setHeadofficeLocation(String v) { this.headofficeLocation = v; }

    public String getCompanyGst() { return companyGst; }
    public void setCompanyGst(String v) { this.companyGst = v; }

    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String v) { this.registrationNumber = v; }

    

    public String getUserName() { return userName; }
    public void setUserName(String v) { this.userName = v; }

    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }

    public String getUserPhoneNo() { return userPhoneNo; }
    public void setUserPhoneNo(String v) { this.userPhoneNo = v; }

    public boolean isApproved() { return approved; }
    public void setApproved(boolean approved) { this.approved = approved; }

    public String getEmailId() { return emailId; }
    public void setEmailId(String v) { this.emailId = v; }

    public String getEmailPwd() { return emailPwd; }
    public void setEmailPwd(String v) { this.emailPwd = v; }

    public String getSmtpServer() { return smtpServer; }
    public void setSmtpServer(String v) { this.smtpServer = v; }

    public String getSmtpPort() { return smtpPort; }
    public void setSmtpPort(String v) { this.smtpPort = v; }

    public byte[] getCompanyLogo() { return companyLogo; }
    public void setCompanyLogo(byte[] v) { this.companyLogo = v; }


    public LocalDate getOnboardedOn() { return onboardedOn; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
