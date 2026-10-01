package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.service.ApprovalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.ApprovalQueries;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.SyncService;
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** HTTP surface of the approval engine (see {@link ApprovalService} for the shared business logic). */
@RestController
public class ApprovalController {

    private final ApprovalService service;
    private final ApprovalQueries queries;

    public ApprovalController(ApprovalService service, ApprovalQueries queries) {
        this.service = service;
        this.queries = queries;
    }

    private static String[] myStages(CurrentUser u) {
        if (u.is(Role.ADMIN)) return new String[]{"ADMIN"};
        return u.stages().toArray(new String[0]);
    }

    @GetMapping("/api/approvals/queue")
    @Roles({Role.ADMIN, Role.APPROVER})
    public Object queue(CurrentUser u) {
        return queries.queue(u.company(), myStages(u));
    }

    @GetMapping("/api/approvals/history")
    @Roles({Role.ADMIN, Role.APPROVER})
    public Object history(CurrentUser u) {
        return queries.history(u.company(), u.userId());
    }

    @PostMapping("/api/approvals/{id}/approve")
    @Roles({Role.ADMIN, Role.APPROVER})
    public Object approve(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        return act(Ids.uuid(id), b, u, true);
    }

    @PostMapping("/api/approvals/{id}/reject")
    @Roles({Role.ADMIN, Role.APPROVER})
    public Object reject(@PathVariable String id, @RequestBody JsonNode b, CurrentUser u) {
        return act(Ids.uuid(id), b, u, false);
    }

    private Object act(UUID stepId, JsonNode b, CurrentUser u, boolean approve) {
        String remarks = Json.optText(b, "remarks");
        if (!approve && remarks == null) throw ApiException.badRequest("Please give a reason for the rejection");
        ApprovalService.ActResult result = service.act(stepId, u, approve, remarks);
        // B1 writes happen after the approval is committed, so a Service Layer outage never loses an approval.
        Row out = result.response;
        for (UUID tx : result.postCommitSyncIds) out.put("sapSync", SyncService.processNow(tx));
        return out;
    }
}
