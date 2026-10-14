package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SapBusinessPartnerId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "connection_id", columnDefinition = "uuid")
    private UUID connectionId;

    @Column(name = "card_code", length = 15)
    private String cardCode;

    protected SapBusinessPartnerId() {}

    public SapBusinessPartnerId(UUID companyId, UUID connectionId, String cardCode) {
        this.companyId = companyId;
        this.connectionId = connectionId;
        this.cardCode = cardCode;
    }

    public UUID getCompanyId() { return companyId; }
    public UUID getConnectionId() { return connectionId; }
    public String getCardCode() { return cardCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapBusinessPartnerId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(connectionId, that.connectionId) && Objects.equals(cardCode, that.cardCode);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, connectionId, cardCode); }
}
