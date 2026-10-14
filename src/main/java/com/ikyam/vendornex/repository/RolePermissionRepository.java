package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    List<RolePermission> findByRoleIdOrderByModuleCode(UUID roleId);

    Optional<RolePermission> findByRoleIdAndModuleCode(UUID roleId, String moduleCode);

    void deleteByRoleId(UUID roleId);
}
