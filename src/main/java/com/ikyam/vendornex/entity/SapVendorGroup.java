package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** Maps {@code sap_vendor_groups} (OCRG mirror). */
@Entity
@Table(name = "ik_sap_vendor_groups")
public class SapVendorGroup {

    @EmbeddedId
    private SapVendorGroupId id;

    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName;

    protected SapVendorGroup() {}

    public SapVendorGroupId getId() { return id; }
    public String getGroupName() { return groupName; }
}
