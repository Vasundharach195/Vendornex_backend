package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.VendorDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorDocumentRepository extends JpaRepository<VendorDocument, UUID> {

    List<VendorDocument> findByVendorIdOrderByDocType(UUID vendorId);

    List<VendorDocument> findByVendorIdAndDocType(UUID vendorId, String docType);

    /** {@code expiryDate < :date} naturally excludes NULLs (SQL comparison with NULL is never
     * true), matching {@code expiry_date IS NOT NULL AND expiry_date < CURRENT_DATE} exactly. */
    List<VendorDocument> findByVendorIdAndExpiryDateBefore(UUID vendorId, LocalDate date);

    Optional<VendorDocument> findByIdAndVendorIdAndCompanyId(UUID id, UUID vendorId, UUID companyId);

    Optional<VendorDocument> findByIdAndVendorId(UUID id, UUID vendorId);

    void deleteByVendorId(UUID vendorId);
}
