package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapTaxCode;
import com.ikyam.vendornex.entity.SapTaxCodeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SapTaxCodeRepository extends JpaRepository<SapTaxCode, SapTaxCodeId> {
    List<SapTaxCode> findByIdCompanyIdOrderByIdTaxCode(UUID companyId);
}
