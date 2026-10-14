package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapVendorGroup;
import com.ikyam.vendornex.entity.SapVendorGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SapVendorGroupRepository extends JpaRepository<SapVendorGroup, SapVendorGroupId> {
    /** Rows of the company's default SAP DB (the one shown in the app until a DB selector exists). */
    @Query(value = "SELECT * FROM ik_sap_vendor_groups WHERE company_id = :companyId AND connection_id = ik_default_connection() ORDER BY group_name", nativeQuery = true)
    List<SapVendorGroup> findByIdCompanyIdOrderByGroupName(@Param("companyId") UUID companyId);
}
