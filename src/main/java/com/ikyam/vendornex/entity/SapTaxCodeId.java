package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SapTaxCodeId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "tax_code", length = 20)
    private String taxCode;

    protected SapTaxCodeId() {}

    public SapTaxCodeId(UUID companyId, String taxCode) {
        this.companyId = companyId;
        this.taxCode = taxCode;
    }

    public UUID getCompanyId() { return companyId; }
    public String getTaxCode() { return taxCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapTaxCodeId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(taxCode, that.taxCode);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, taxCode); }
}
