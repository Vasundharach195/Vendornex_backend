package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;

/** Maps {@code sap_items} (OITM mirror). */
@Entity
@Table(name = "sap_items")
@DynamicUpdate
public class SapItem {

    @EmbeddedId
    private SapItemId id;

    @Column(name = "item_name", nullable = false, length = 200)
    private String itemName;

    @Column(name = "item_group_code")
    private Integer itemGroupCode;

    @Column(name = "item_group_name", length = 100)
    private String itemGroupName;

    @Column(name = "purchase_uom", length = 20)
    private String purchaseUom;

    @Column(name = "inventory_uom", length = 20)
    private String inventoryUom;

    @Column(name = "avg_price", precision = 19, scale = 6)
    private BigDecimal avgPrice;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    protected SapItem() {}

    public SapItemId getId() { return id; }
    public String getItemName() { return itemName; }
    public String getItemGroupName() { return itemGroupName; }
    public String getPurchaseUom() { return purchaseUom; }
    public BigDecimal getAvgPrice() { return avgPrice; }
    public boolean isActive() { return active; }
}
