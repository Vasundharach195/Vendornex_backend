package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.RoleMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleMasterRepository extends JpaRepository<RoleMaster, UUID> {

    List<RoleMaster> findBySchemaIdOrderByRoleName(String schemaId);

    List<RoleMaster> findBySchemaIdAndActiveTrueOrderByRoleName(String schemaId);

    Optional<RoleMaster> findByRoleIdAndSchemaId(UUID roleId, String schemaId);

    boolean existsBySchemaIdAndRoleNameIgnoreCase(String schemaId, String roleName);
}
