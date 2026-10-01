package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.dto.CreateInternalUserRequest;
import com.ikyam.vendornex.dto.CreateRequesterRequest;
import com.ikyam.vendornex.dto.SetActiveRequest;
import com.ikyam.vendornex.dto.UpdateInternalUserRequest;
import com.ikyam.vendornex.dto.UpdateRequesterRequest;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.AuthService;
import com.ikyam.vendornex.service.UserService;
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal users (Admins, stage Approvers) and Requesters. Nobody sets another person's password:
 * accounts are created INVITED and the person activates via a one-time link.
 */
@RestController
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ internal users

    @GetMapping("/api/users/internal")
    @Roles(Role.ADMIN)
    public Object listInternal(CurrentUser u) {
        return service.listInternal(u.company());
    }

    @PostMapping("/api/users/internal")
    @Roles(Role.ADMIN)
    public Object createInternal(@RequestBody CreateInternalUserRequest req, CurrentUser me) {
        return service.createInternal(req, me.company());
    }

    @PutMapping("/api/users/internal/{id}")
    @Roles(Role.ADMIN)
    public Object updateInternal(@PathVariable String id, @RequestBody UpdateInternalUserRequest req, CurrentUser me) {
        return service.updateInternal(Ids.uuid(id), req, me.company());
    }

    @PostMapping("/api/users/{id}/invite")
    @Roles(Role.ADMIN)
    public Object reinvite(@PathVariable String id, CurrentUser me) {
        return java.util.Map.of("inviteLink", service.reinvite(Ids.uuid(id), me.company()));
    }

    @PostMapping("/api/users/{id}/status")
    @Roles(Role.ADMIN)
    public Object setStatus(@PathVariable String id, @RequestBody SetActiveRequest req, CurrentUser me) {
        boolean active = req.active == null || req.active;
        service.setStatus(Ids.uuid(id), active, me.company(), me.userId());
        return java.util.Map.of("ok", true);
    }

    @GetMapping("/api/users/login-activity")
    @Roles(Role.ADMIN)
    public Object loginActivity(CurrentUser u) {
        return service.loginActivity(u.company());
    }

    // ------------------------------------------------------------------ requesters

    @GetMapping("/api/requesters")
    @Roles(Role.ADMIN)
    public Object listRequesters(CurrentUser u) {
        return service.listRequesters(u.company());
    }

    @GetMapping("/api/requesters/{id}")
    @Roles({Role.ADMIN, Role.APPROVER})
    public Object requesterDetail(@PathVariable String id, CurrentUser u) {
        return service.requesterDetail(Ids.uuid(id), u.company());
    }

    @PostMapping("/api/requesters")
    @Roles(Role.ADMIN)
    public Object createRequester(@RequestBody CreateRequesterRequest req, CurrentUser me) {
        return service.createRequester(req, me.company());
    }

    @PutMapping("/api/requesters/{id}")
    @Roles(Role.ADMIN)
    public Object updateRequester(@PathVariable String id, @RequestBody UpdateRequesterRequest req, CurrentUser me) {
        return service.updateRequester(Ids.uuid(id), req, me.company());
    }

    @PostMapping("/api/requesters/{id}/resubmit")
    @Roles(Role.ADMIN)
    public Object resubmitRequester(@PathVariable String id, CurrentUser me) {
        return service.resubmitRequester(Ids.uuid(id), me.company());
    }

    /** Final approval of a requester: they receive their activation link. Called directly (static)
     * from the not-yet-converted {@code ApprovalService.onApproved} switch — kept as a trivial
     * static passthrough since it holds no state of its own. */
    public static void onRequesterApproved(UUID userId) {
        AuthService.issueInvite(userId);
    }
}
