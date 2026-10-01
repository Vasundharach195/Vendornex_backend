package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapWarehouse;
import com.ikyam.vendornex.entity.SapWarehouseId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SapWarehouseRepository extends JpaRepository<SapWarehouse, SapWarehouseId> {
    List<SapWarehouse> findByIdCompanyIdAndActiveTrueOrderByIdWarehouseCode(UUID companyId);
}
