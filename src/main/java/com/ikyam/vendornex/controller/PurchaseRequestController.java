package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.dto.CreatePurchaseRequestRequest;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.PurchaseRequestService;
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.QueryParams;
import com.ikyam.vendornex.web.Roles;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** HTTP surface of Purchase Requests — business logic lives in {@link PurchaseRequestService}. */
@RestController
@RequiredArgsConstructor
public class PurchaseRequestController {

    private final PurchaseRequestService service;

    @GetMapping("/api/purchase-requests")
    @Roles({Role.ADMIN, Role.APPROVER, Role.REQUESTER})
    public Object list(@RequestParam(required = false) String status, @RequestParam(required = false) String source, CurrentUser u) {
        return service.list(u, QueryParams.orNull(status), QueryParams.orNull(source));
    }

    @GetMapping("/api/purchase-requests/{id}")
    @Roles({Role.ADMIN, Role.APPROVER, Role.REQUESTER})
    public Object detail(@PathVariable String id, CurrentUser u) {
        return service.detail(Ids.uuid(id), u);
    }

    @GetMapping("/api/purchase-requests/sourceable-lines")
    @Roles(Role.ADMIN)
    public Object sourceableLines(@RequestParam(required = false) String ids, CurrentUser u) {
        return service.sourceableLines(QueryParams.orNull(ids), u.company());
    }

    @PostMapping("/api/purchase-requests")
    @Roles({Role.ADMIN, Role.REQUESTER})
    public Object create(@RequestBody CreatePurchaseRequestRequest req, CurrentUser u) {
        return service.create(req, u);
    }

    @PostMapping("/api/purchase-requests/{id}/cancel")
    @Roles({Role.ADMIN, Role.REQUESTER})
    public Object cancel(@PathVariable String id, CurrentUser u) {
        return service.cancel(Ids.uuid(id), u);
    }

    @PostMapping("/api/purchase-requests/{id}/resubmit")
    @Roles({Role.ADMIN, Role.REQUESTER})
    public Object resubmit(@PathVariable String id, CurrentUser u) {
        return service.resubmit(Ids.uuid(id), u);
    }

    @PostMapping("/api/purchase-requests/{id}/retry-b1")
    @Roles(Role.ADMIN)
    public Object retryPush(@PathVariable String id, CurrentUser u) {
        return service.retryPush(Ids.uuid(id), u);
    }
}
