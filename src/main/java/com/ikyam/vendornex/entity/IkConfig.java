package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code ik_vendor.ik_config}: per-company mail (SMTP) and SAP Service Layer settings, written once
 * when the company's schema is created at onboarding. Passwords hold AES-256-GCM ciphertext ({@code Crypto}). */
@Entity
@Table(name = "ik_config")
public class IkConfig {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "config_id", columnDefinition = "uuid")
    private UUID configId;

    @Column(name = "schema_id", nullable = false, length = 25)
    private String schemaId;

    @Column(name = "email_id", nullable = false, length = 100)
    private String emailId;

    @Column(name = "email_pwd", nullable = false, length = 100)
    private String emailPwd;

    @Column(name = "smtp_server", nullable = false, length = 100)
    private String smtpServer;

    @Column(name = "smtp_port", nullable = false, length = 4)
    private String smtpPort;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    private UUID updatedBy;

   

    protected IkConfig() {}

    public IkConfig(String schemaId, String emailId, String emailPwd, String smtpServer, String smtpPort, UUID by) {
        this.schemaId = schemaId;
        this.emailId = emailId;
        this.emailPwd = emailPwd;
        this.smtpServer = smtpServer;
        this.smtpPort = smtpPort;
   
        this.createdBy = by;
        this.updatedBy = by;
    }

    public UUID getConfigId() { return configId; }
    public String getSchemaId() { return schemaId; }
    public String getEmailId() { return emailId; }
    public String getEmailPwd() { return emailPwd; }
    public String getSmtpServer() { return smtpServer; }
    public String getSmtpPort() { return smtpPort; }
   
}
