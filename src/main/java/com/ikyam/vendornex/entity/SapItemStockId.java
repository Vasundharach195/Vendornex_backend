package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SapItemStockId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "connection_id", columnDefinition = "uuid")
    private UUID connectionId;

    @Column(name = "item_code", length = 50)
    private String itemCode;

    @Column(name = "warehouse_code", length = 8)
    private String warehouseCode;

    protected SapItemStockId() {}

    public SapItemStockId(UUID companyId, UUID connectionId, String itemCode, String warehouseCode) {
        this.companyId = companyId;
        this.connectionId = connectionId;
        this.itemCode = itemCode;
        this.warehouseCode = warehouseCode;
    }

    public UUID getCompanyId() { return companyId; }
    public UUID getConnectionId() { return connectionId; }
    public String getItemCode() { return itemCode; }
    public String getWarehouseCode() { return warehouseCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapItemStockId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(connectionId, that.connectionId) && Objects.equals(itemCode, that.itemCode) && Objects.equals(warehouseCode, that.warehouseCode);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, connectionId, itemCode, warehouseCode); }
}
