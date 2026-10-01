package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.Vendor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByIdAndCompanyId(UUID id, UUID companyId);

    /** Mirrors {@code SELECT * FROM vendors WHERE id=? AND company_id=? FOR UPDATE} — the row lock
     * {@code VendorController.submit} takes before mutating the vendor + starting the approval chain. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vendor v where v.id = :id and v.companyId = :companyId")
    Optional<Vendor> lockByIdAndCompanyId(UUID id, UUID companyId);
}
