package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapTaxCode;
import com.ikyam.vendornex.entity.SapTaxCodeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SapTaxCodeRepository extends JpaRepository<SapTaxCode, SapTaxCodeId> {
    /** Rows of the company's default SAP DB (the one shown in the app until a DB selector exists). */
    @Query(value = "SELECT * FROM ik_sap_tax_codes WHERE company_id = :companyId AND connection_id = ik_default_connection() ORDER BY tax_code", nativeQuery = true)
    List<SapTaxCode> findByIdCompanyIdOrderByIdTaxCode(@Param("companyId") UUID companyId);
}
