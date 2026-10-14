package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.entity.SapWarehouse;
import com.ikyam.vendornex.entity.SapWarehouseId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SapWarehouseRepository extends JpaRepository<SapWarehouse, SapWarehouseId> {
    /** Rows of the company's default SAP DB (the one shown in the app until a DB selector exists). */
    @Query(value = "SELECT * FROM ik_sap_warehouses WHERE company_id = :companyId AND connection_id = ik_default_connection() AND is_active ORDER BY warehouse_code", nativeQuery = true)
    List<SapWarehouse> findByIdCompanyIdAndActiveTrueOrderByIdWarehouseCode(@Param("companyId") UUID companyId);
}
