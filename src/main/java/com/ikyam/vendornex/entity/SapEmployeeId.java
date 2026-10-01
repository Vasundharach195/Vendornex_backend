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

    @Column(name = "employee_id")
    private Integer employeeId;

    protected SapEmployeeId() {}

    public SapEmployeeId(UUID companyId, Integer employeeId) {
        this.companyId = companyId;
        this.employeeId = employeeId;
    }

    public UUID getCompanyId() { return companyId; }
    public Integer getEmployeeId() { return employeeId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SapEmployeeId that)) return false;
        return Objects.equals(companyId, that.companyId) && Objects.equals(employeeId, that.employeeId);
    }

    @Override
    public int hashCode() { return Objects.hash(companyId, employeeId); }
}
