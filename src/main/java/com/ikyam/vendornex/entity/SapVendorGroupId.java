package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SapVendorGroupId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "group_code")
    private Integer groupCode;

    protected SapVendorGroupId() {}

    public SapVendorGroupId(UUID companyId, Integer groupCode) {
        this.companyId = companyId;
        this.groupCode = groupCode;
    }

    public UUID getCompanyId() { return companyId; }
    public Integer getGroupCode() { return groupCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapVendorGroupId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(groupCode, that.groupCode);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, groupCode); }
}
