package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapEmployee;
import com.ikyam.vendornex.entity.SapEmployeeId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SapEmployeeRepository extends JpaRepository<SapEmployee, SapEmployeeId> {
    List<SapEmployee> findByIdCompanyIdOrderByFullName(UUID companyId);
}
