package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Maps {@code vendor_documents}. Straightforward single-table CRUD — a real entity/repository. */
@Entity
@Table(name = "vendor_documents")
public class VendorDocument {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "company_id", nullable = false, columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "vendor_id", nullable = false, columnDefinition = "uuid")
    private UUID vendorId;

    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "uploaded_by_user_id", columnDefinition = "uuid")
    private UUID uploadedByUserId;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    protected VendorDocument() {}

    public VendorDocument(UUID companyId, UUID vendorId, String docType, String fileName, String contentType,
                           long sizeBytes, String storageKey, LocalDate expiryDate, UUID uploadedByUserId) {
        this.companyId = companyId;
        this.vendorId = vendorId;
        this.docType = docType;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.expiryDate = expiryDate;
        this.uploadedByUserId = uploadedByUserId;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public UUID getVendorId() { return vendorId; }
    public String getDocType() { return docType; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Instant getUploadedAt() { return uploadedAt; }
}
