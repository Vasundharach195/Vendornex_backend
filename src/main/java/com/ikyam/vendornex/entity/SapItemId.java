package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SapItemId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "connection_id", columnDefinition = "uuid")
    private UUID connectionId;

    @Column(name = "item_code", length = 50)
    private String itemCode;

    protected SapItemId() {}

    public SapItemId(UUID companyId, UUID connectionId, String itemCode) {
        this.companyId = companyId;
        this.connectionId = connectionId;
        this.itemCode = itemCode;
    }

    public UUID getCompanyId() { return companyId; }
    public UUID getConnectionId() { return connectionId; }
    public String getItemCode() { return itemCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapItemId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(connectionId, that.connectionId) && Objects.equals(itemCode, that.itemCode);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, connectionId, itemCode); }
}
