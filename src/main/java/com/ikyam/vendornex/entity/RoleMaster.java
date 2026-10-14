package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code ik_vendor.ik_role_master}: a system or custom role of one company ({@code schemaId}). */
@Entity
@Table(name = "ik_role_master")
@DynamicUpdate
public class RoleMaster {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "role_id", columnDefinition = "uuid")
    private UUID roleId;

    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(name = "role_description", length = 500)
    private String roleDescription;

    @Column(name = "is_system_role", nullable = false)
    private boolean systemRole;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "schema_id", nullable = false, length = 25)
    private String schemaId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    private UUID updatedBy;

    @Column(name = "ref1")
    private String ref1;

    @Column(name = "ref2")
    private String ref2;

    protected RoleMaster() {}

    public RoleMaster(String schemaId, String roleName, String roleDescription, boolean systemRole, UUID by) {
        this.schemaId = schemaId;
        this.roleName = roleName;
        this.roleDescription = roleDescription;
        this.systemRole = systemRole;
        this.createdBy = by;
        this.updatedBy = by;
    }

    public void update(String roleName, String roleDescription, boolean active, UUID by) {
        this.roleName = roleName;
        this.roleDescription = roleDescription;
        this.active = active;
        this.updatedAt = Instant.now();
        this.updatedBy = by;
    }

    public UUID getRoleId() { return roleId; }
    public String getRoleName() { return roleName; }
    public String getRoleDescription() { return roleDescription; }
    public boolean isSystemRole() { return systemRole; }
    public boolean isActive() { return active; }
    public String getSchemaId() { return schemaId; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public String getRef1() { return ref1; }
    public void setRef1(String ref1) { this.ref1 = ref1; }
    public String getRef2() { return ref2; }
    public void setRef2(String ref2) { this.ref2 = ref2; }
}
