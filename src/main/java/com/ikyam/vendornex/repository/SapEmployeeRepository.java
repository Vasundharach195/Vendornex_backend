package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapEmployee;
import com.ikyam.vendornex.entity.SapEmployeeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SapEmployeeRepository extends JpaRepository<SapEmployee, SapEmployeeId> {
    /** Rows of the company's default SAP DB (the one shown in the app until a DB selector exists). */
    @Query(value = "SELECT * FROM ik_sap_employees WHERE company_id = :companyId AND connection_id = ik_default_connection() ORDER BY full_name", nativeQuery = true)
    List<SapEmployee> findByIdCompanyIdOrderByFullName(@Param("companyId") UUID companyId);
}
