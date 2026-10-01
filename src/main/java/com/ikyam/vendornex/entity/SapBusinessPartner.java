package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/** Maps {@code sap_business_partners} (OCRD CardType=S mirror). */
@Entity
@Table(name = "sap_business_partners")
@DynamicUpdate
public class SapBusinessPartner {

    @EmbeddedId
    private SapBusinessPartnerId id;

    @Column(name = "card_name", nullable = false, length = 200)
    private String cardName;

    @Column(name = "group_code")
    private Integer groupCode;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "federal_tax_id", length = 32)
    private String federalTaxId;

    @Column(length = 200)
    private String email;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    protected SapBusinessPartner() {}

    public SapBusinessPartnerId getId() { return id; }
    public String getCardName() { return cardName; }
    public Integer getGroupCode() { return groupCode; }
    public boolean isActive() { return active; }
    public String getFederalTaxId() { return federalTaxId; }
    public String getEmail() { return email; }
}
