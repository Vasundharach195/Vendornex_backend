package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.dto.SyncNowRequest;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.MasterDataService;
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.QueryParams;
import com.ikyam.vendornex.web.Roles;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Read access to the synced SAP B1 masters (pickers + Data Hub) and the sync controls. */
@RestController
public class MasterDataController {

    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    @GetMapping("/api/master/items")
    @Roles({Role.ADMIN, Role.APPROVER, Role.REQUESTER})
    public Object items(@RequestParam(required = false) String q, @RequestParam(required = false) String limit, CurrentUser u) {
        return service.items(u.company(), QueryParams.orNull(q), QueryParams.clampInt(limit, 25, 200));
    }

    @GetMapping("/api/master/warehouses")
    @Roles({Role.ADMIN, Role.APPROVER, Role.REQUESTER})
    public Object warehouses(CurrentUser u) {
        return service.warehouses(u.company());
    }

    @GetMapping("/api/master/vendor-groups")
    @Roles(Role.ADMIN)
    public Object vendorGroups(CurrentUser u) {
        return service.vendorGroups(u.company());
    }

    @GetMapping("/api/master/tax-codes")
    @Roles(Role.ADMIN)
    public Object taxCodes(CurrentUser u) {
        return service.taxCodes(u.company());
    }

    @GetMapping("/api/master/employees")
    @Roles(Role.ADMIN)
    public Object employees(CurrentUser u) {
        return service.employees(u.company());
    }

    @GetMapping("/api/master/business-partners")
    @Roles(Role.ADMIN)
    public Object businessPartners(@RequestParam(required = false) String q, @RequestParam(required = false) String limit, CurrentUser u) {
        return service.businessPartners(u.company(), QueryParams.orNull(q), QueryParams.clampInt(limit, 50, 500));
    }

    @GetMapping("/api/datahub/items")
    @Roles(Role.ADMIN)
    public Object dataHubItems(@RequestParam(required = false) String q, @RequestParam(required = false) String warehouse,
                                @RequestParam(required = false) String group, CurrentUser u) {
        return service.dataHubItems(u.company(), QueryParams.orNull(q), QueryParams.orNull(warehouse), QueryParams.orNull(group));
    }

    @GetMapping("/api/datahub/summary")
    @Roles(Role.ADMIN)
    public Object summary(CurrentUser u) {
        return service.summary(u.company());
    }

    @GetMapping("/api/datahub/sync-runs")
    @Roles(Role.ADMIN)
    public Object syncRuns(CurrentUser u) {
        return service.syncRuns(u.company());
    }

    @GetMapping("/api/datahub/sync-transactions")
    @Roles(Role.ADMIN)
    public Object transactions(@RequestParam(required = false) String status, CurrentUser u) {
        return service.transactions(u.company(), QueryParams.orNull(status));
    }

    @PostMapping("/api/datahub/sync-transactions/{id}/retry")
    @Roles(Role.ADMIN)
    public Object retryTransaction(@PathVariable String id, CurrentUser u) {
        return service.retryTransaction(u.company(), Ids.uuid(id));
    }

    @PostMapping("/api/datahub/sync")
    @Roles(Role.ADMIN)
    public ResponseEntity<?> syncNow(@RequestBody SyncNowRequest req, CurrentUser u) {
        service.syncNow(u.company(), Json.textList(req.entities));
        return ResponseEntity.status(202).body(Map.of("started", true));
    }
}
