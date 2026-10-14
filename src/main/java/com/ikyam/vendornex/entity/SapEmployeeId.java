package com.ikyam.vendornex.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SapEmployeeId implements Serializable {

    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "connection_id", columnDefinition = "uuid")
    private UUID connectionId;

    @Column(name = "employee_id")
    private Integer employeeId;

    protected SapEmployeeId() {}

    public SapEmployeeId(UUID companyId, UUID connectionId, Integer employeeId) {
        this.companyId = companyId;
        this.connectionId = connectionId;
        this.employeeId = employeeId;
    }

    public UUID getCompanyId() { return companyId; }
    public UUID getConnectionId() { return connectionId; }
    public Integer getEmployeeId() { return employeeId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapEmployeeId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(connectionId, that.connectionId) && Objects.equals(employeeId, that.employeeId);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, connectionId, employeeId); }
}
