package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Maps {@code sap_tax_codes} (VatGroups/OSTC mirror). */
@Entity
@Table(name = "sap_tax_codes")
public class SapTaxCode {

    @EmbeddedId
    private SapTaxCodeId id;

    @Column(name = "tax_name", length = 100)
    private String taxName;

    @Column(precision = 9, scale = 4)
    private BigDecimal rate;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    protected SapTaxCode() {}

    public SapTaxCodeId getId() { return id; }
    public String getTaxName() { return taxName; }
    public BigDecimal getRate() { return rate; }
}
