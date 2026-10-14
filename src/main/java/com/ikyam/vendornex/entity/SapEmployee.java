package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/** Maps {@code sap_employees} (OHEM mirror). */
@Entity
@Table(name = "ik_sap_employees")
public class SapEmployee {

    @EmbeddedId
    private SapEmployeeId id;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(length = 100)
    private String department;

    @Column(length = 200)
    private String email;

    protected SapEmployee() {}

    public SapEmployeeId getId() { return id; }
    public String getFullName() { return fullName; }
    public String getDepartment() { return department; }
    public String getEmail() { return email; }
}
