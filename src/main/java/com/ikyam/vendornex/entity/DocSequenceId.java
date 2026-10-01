package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class DocSequenceId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "doc_type", length = 10)
    private String docType;

    protected DocSequenceId() {}

    public DocSequenceId(UUID companyId, String docType) {
        this.companyId = companyId;
        this.docType = docType;
    }

    public UUID getCompanyId() { return companyId; }
    public String getDocType() { return docType; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocSequenceId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(docType, that.docType);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, docType); }
}
