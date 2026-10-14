package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** Maps {@code ik_vendor.ik_role_permission}: what one role may do in one module (one row per role + module). */
@Entity
@Table(name = "ik_role_permission")
@DynamicUpdate
public class RolePermission {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "permission_id", columnDefinition = "uuid")
    private UUID permissionId;

    @Column(name = "role_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID roleId;

    @Column(name = "module_code", nullable = false, length = 50)
    private String moduleCode;

    @Column(name = "module_name", nullable = false, length = 100)
    private String moduleName;

    @Column(name = "can_view", nullable = false)
    private boolean canView;

    @Column(name = "can_create", nullable = false)
    private boolean canCreate;

    @Column(name = "can_edit", nullable = false)
    private boolean canEdit;

    @Column(name = "can_delete", nullable = false)
    private boolean canDelete;

    @Column(name = "can_approve", nullable = false)
    private boolean canApprove;

    @Column(name = "can_export", nullable = false)
    private boolean canExport;

    @Column(name = "can_import", nullable = false)
    private boolean canImport;

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

    protected RolePermission() {}

    public RolePermission(UUID roleId, String schemaId, String moduleCode, String moduleName, UUID by) {
        this.roleId = roleId;
        this.schemaId = schemaId;
        this.moduleCode = moduleCode;
        this.moduleName = moduleName;
        this.createdBy = by;
        this.updatedBy = by;
    }

    public void setAccess(boolean view, boolean create, boolean edit, boolean delete,
                          boolean approve, boolean export, boolean imp, UUID by) {
        this.canView = view;
        this.canCreate = create;
        this.canEdit = edit;
        this.canDelete = delete;
        this.canApprove = approve;
        this.canExport = export;
        this.canImport = imp;
        this.updatedAt = Instant.now();
        this.updatedBy = by;
    }

    public void setActive(boolean active, UUID by) { this.active = active; this.updatedAt = Instant.now(); this.updatedBy = by; }

    public UUID getPermissionId() { return permissionId; }
    public UUID getRoleId() { return roleId; }
    public String getModuleCode() { return moduleCode; }
    public String getModuleName() { return moduleName; }
    public boolean isCanView() { return canView; }
    public boolean isCanCreate() { return canCreate; }
    public boolean isCanEdit() { return canEdit; }
    public boolean isCanDelete() { return canDelete; }
    public boolean isCanApprove() { return canApprove; }
    public boolean isCanExport() { return canExport; }
    public boolean isCanImport() { return canImport; }
    public boolean isActive() { return active; }
    public String getSchemaId() { return schemaId; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
}
