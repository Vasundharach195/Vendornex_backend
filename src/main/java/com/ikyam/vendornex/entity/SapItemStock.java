package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** Maps {@code sap_item_stock} (OITW mirror). Not read directly via this entity today — the only
 * consumers (item picker's in-stock sum, Data Hub's per-warehouse breakdown) are correlated-
 * subquery/json_agg read-models and stay on JdbcTemplate — this exists for schema validation. */
@Entity
@Table(name = "sap_item_stock")
public class SapItemStock {

    @EmbeddedId
    private SapItemStockId id;

    @Column(name = "in_stock", nullable = false, precision = 19, scale = 6)
    private BigDecimal inStock = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal committed = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal ordered = BigDecimal.ZERO;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    protected SapItemStock() {}

    public SapItemStockId getId() { return id; }
}
