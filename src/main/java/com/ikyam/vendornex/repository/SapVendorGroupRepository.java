package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapVendorGroup;
import com.ikyam.vendornex.entity.SapVendorGroupId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SapVendorGroupRepository extends JpaRepository<SapVendorGroup, SapVendorGroupId> {
    List<SapVendorGroup> findByIdCompanyIdOrderByGroupName(UUID companyId);
}
