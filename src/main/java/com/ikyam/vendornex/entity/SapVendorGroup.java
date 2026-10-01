package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** Maps {@code sap_vendor_groups} (OCRG mirror). */
@Entity
@Table(name = "sap_vendor_groups")
public class SapVendorGroup {

    @EmbeddedId
    private SapVendorGroupId id;

    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt = Instant.now();

    protected SapVendorGroup() {}

    public SapVendorGroupId getId() { return id; }
    public String getGroupName() { return groupName; }
}
