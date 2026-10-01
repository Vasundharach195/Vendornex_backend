package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.PurchaseRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, UUID> {

    /** Tenant-scoped lookup: a PR of another company is "not found". */
    Optional<PurchaseRequest> findByIdAndCompanyId(UUID id, UUID companyId);
}
