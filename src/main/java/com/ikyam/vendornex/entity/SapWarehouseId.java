package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SapWarehouseId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "warehouse_code", length = 8)
    private String warehouseCode;

    protected SapWarehouseId() {}

    public SapWarehouseId(UUID companyId, String warehouseCode) {
        this.companyId = companyId;
        this.warehouseCode = warehouseCode;
    }

    public UUID getCompanyId() { return companyId; }
    public String getWarehouseCode() { return warehouseCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapWarehouseId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(warehouseCode, that.warehouseCode);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, warehouseCode); }
}
