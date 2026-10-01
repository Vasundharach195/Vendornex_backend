package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;

/** Maps {@code sap_warehouses} (OWHS mirror). Bulk-synced by {@code MasterSyncService}, which
 * keeps writing via {@code Db.batch}'s ON CONFLICT upsert (unconverted until Db.java's final
 * retirement) — this entity backs the simple read side ({@code MasterDataController.warehouses}). */
@Entity
@Table(name = "sap_warehouses")
@DynamicUpdate
public class SapWarehouse {

    @EmbeddedId
    private SapWarehouseId id;

    @Column(name = "warehouse_name", nullable = false, length = 100)
    private String warehouseName;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    protected SapWarehouse() {}

    public SapWarehouseId getId() { return id; }
    public String getWarehouseName() { return warehouseName; }
    public boolean isActive() { return active; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
}
