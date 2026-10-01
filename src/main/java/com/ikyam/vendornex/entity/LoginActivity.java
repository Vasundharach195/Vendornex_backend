package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code login_activity} (audit log, insert-only). {@code AuthService} still writes to this
 * table via the legacy {@code Db} helper until the security/auth domain is converted (a later
 * migration stage) — this entity exists now so {@code UserController}'s login-activity read (a
 * join, done via JdbcTemplate, not this repository) has a validated schema mapping ready for when
 * the write side switches over.
 */
@Entity
@Table(name = "login_activity")
public class LoginActivity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(length = 200)
    private String email;

    @Column(nullable = false, length = 20)
    private String event;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected LoginActivity() {}

    public LoginActivity(UUID userId, UUID companyId, String email, String event, String ipAddress, String userAgent) {
        this.userId = userId;
        this.companyId = companyId;
        this.email = email;
        this.event = event;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }

    public UUID getId() { return id; }
}
