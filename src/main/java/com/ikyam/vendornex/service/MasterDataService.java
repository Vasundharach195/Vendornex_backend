package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Background;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.entity.SapEmployee;
import com.ikyam.vendornex.entity.SapTaxCode;
import com.ikyam.vendornex.entity.SapVendorGroup;
import com.ikyam.vendornex.entity.SapWarehouse;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.repository.MasterDataQueries;
import com.ikyam.vendornex.repository.SapEmployeeRepository;
import com.ikyam.vendornex.repository.SapTaxCodeRepository;
import com.ikyam.vendornex.repository.SapVendorGroupRepository;
import com.ikyam.vendornex.repository.SapWarehouseRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Business logic moved out of {@code MasterDataController} (read access to synced SAP B1 masters
 * plus sync controls). Simple single-table lists go through the Stage-2 sap_* entities/repositories;
 * everything else (joins, correlated subqueries, json aggregation) stays on {@link MasterDataQueries}
 * (JdbcTemplate) per the migration's query-routing rule. {@code MasterSyncService} itself keeps
 * writing via the legacy {@code Db} helper for now — it touches vendors/purchase_requests/
 * purchase_orders, none of which are entities yet.
 */
@Service
public class MasterDataService {

    private final MasterDataQueries queries;
    private final SapWarehouseRepository warehouses;
    private final SapVendorGroupRepository vendorGroups;
    private final SapTaxCodeRepository taxCodes;
    private final SapEmployeeRepository employees;

    public MasterDataService(MasterDataQueries queries, SapWarehouseRepository warehouses,
                              SapVendorGroupRepository vendorGroups, SapTaxCodeRepository taxCodes,
                              SapEmployeeRepository employees) {
        this.queries = queries;
        this.warehouses = warehouses;
        this.vendorGroups = vendorGroups;
        this.taxCodes = taxCodes;
        this.employees = employees;
    }

    public List<Row> items(UUID companyId, String q, int limit) {
        return queries.items(companyId, q, limit);
    }

    public List<Row> warehouses(UUID companyId) {
        return warehouses.findByIdCompanyIdAndActiveTrueOrderByIdWarehouseCode(companyId).stream()
                .map(w -> new Row().with("warehouseCode", w.getId().getWarehouseCode()).with("warehouseName", w.getWarehouseName()))
                .toList();
    }

    public List<Row> vendorGroups(UUID companyId) {
        return vendorGroups.findByIdCompanyIdOrderByGroupName(companyId).stream()
                .map(g -> new Row().with("groupCode", g.getId().getGroupCode()).with("groupName", g.getGroupName()))
                .toList();
    }

    public List<Row> taxCodes(UUID companyId) {
        List<SapTaxCode> list = taxCodes.findByIdCompanyIdOrderByIdTaxCode(companyId);
        return list.stream()
                .map(t -> new Row().with("taxCode", t.getId().getTaxCode()).with("taxName", t.getTaxName())
                        .with("rate", com.ikyam.vendornex.db.Db.trimDecimal(t.getRate())))
                .toList();
    }

    public List<Row> employees(UUID companyId) {
        return employees.findByIdCompanyIdOrderByFullName(companyId).stream()
                .map(e -> new Row().with("employeeId", e.getId().getEmployeeId()).with("fullName", e.getFullName())
                        .with("department", e.getDepartment()).with("email", e.getEmail()))
                .toList();
    }

    public List<Row> businessPartners(UUID companyId, String q, int limit) {
        return queries.businessPartners(companyId, q, limit);
    }

    public List<Row> dataHubItems(UUID companyId, String q, String warehouse, String group) {
        return queries.dataHubItems(companyId, q, warehouse, group);
    }

    public Row summary(UUID companyId) {
        Row r = queries.summary(companyId);
        r.put("running", MasterSyncService.isRunning(companyId));
        return r;
    }

    public List<Row> syncRuns(UUID companyId) {
        return queries.syncRuns(companyId);
    }

    public List<Row> transactions(UUID companyId, String status) {
        return queries.transactions(companyId, status);
    }

    public Row retryTransaction(UUID companyId, UUID txId) {
        return SyncService.retry(companyId, txId);
    }

    public void syncNow(UUID companyId, List<String> entities) {
        for (String e : entities) if (!MasterSyncService.ALL.contains(e)) throw ApiException.badRequest("Unknown entity " + e);
        if (MasterSyncService.isRunning(companyId)) throw ApiException.conflict("A sync is already running");
        Background.run("manual-sync", () -> MasterSyncService.sync(companyId, entities, "MANUAL"));
    }
}
