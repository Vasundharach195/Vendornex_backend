package com.ikyam.vendornex.controller;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.AuthQueries;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Passwords;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.AuthService;
import com.ikyam.vendornex.web.Roles;

import jakarta.servlet.http.HttpServletRequest;

/** Login, session validation, invitations. */
@RestController
public class AuthController {

    private final AuthQueries authQueries;

    public AuthController(AuthQueries authQueries) {
        this.authQueries = authQueries;
    }

    @PostMapping("/api/auth/login")
    public Object login(@RequestBody JsonNode body, HttpServletRequest req) {
        String email = Json.reqText(body, "email").toLowerCase(Locale.ROOT);
        String password = Json.reqText(body, "password");
        Row u = authQueries.findUserForLogin(email);
        if (u == null || !Passwords.matches(password, u.str("passwordHash"))) {
            AuthService.logActivity(u == null ? null : u.uuid("id"), u == null ? null : u.uuid("companyId"), email, "LOGIN_FAILED", req);
            throw ApiException.unauthorized("Invalid email or password");
        }
        String status = u.str("status");
        if ("PENDING_APPROVAL".equals(status)) throw ApiException.forbidden("Your account is awaiting internal approval");
        if (!"ACTIVE".equals(status)) throw ApiException.forbidden("Your account is " + status.toLowerCase(Locale.ROOT).replace('_', ' '));
        if (u.get("companyId") != null && !u.bool("companyActive")) throw ApiException.forbidden("Your company account is inactive");
        if ("VENDOR".equals(u.str("role")) && !"ACTIVE".equals(u.str("vendorStatus"))) throw ApiException.forbidden("Vendor account is not active yet");

        authQueries.updateLastLogin(u.uuid("id"));
        AuthService.logActivity(u.uuid("id"), u.uuid("companyId"), email, "LOGIN", req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", AuthService.issueToken(u.uuid("id")));
        out.put("user", AuthService.profile(u.uuid("id")));
        return out;
    }

    @GetMapping("/api/auth/invite/{token}")
    public Object inviteInfo(@PathVariable String token) {
        Row u = authQueries.findInviteInfo(token);
        if (u == null) throw ApiException.notFound("Invitation");
        if (Instant.parse(u.str("inviteExpiresAt")).isBefore(Instant.now())) throw new ApiException(410, "This invitation has expired — ask your admin to resend it");
        u.remove("inviteExpiresAt");
        return u;
    }

    @PostMapping("/api/auth/invite/{token}")
    public Object acceptInvite(@PathVariable String token, @RequestBody JsonNode body, HttpServletRequest req) {
        String password = Json.reqText(body, "password");
        Passwords.validate(password);
        Row u = authQueries.findUserByInviteToken(token);
        if (u == null) throw ApiException.notFound("Invitation");
        if (Instant.parse(u.str("inviteExpiresAt")).isBefore(Instant.now())) throw new ApiException(410, "This invitation has expired — ask your admin to resend it");
        authQueries.acceptInvite(u.uuid("id"), Passwords.hash(password));
        AuthService.logActivity(u.uuid("id"), u.uuid("companyId"), u.str("email"), "INVITE_ACCEPTED", req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", AuthService.issueToken(u.uuid("id")));
        out.put("user", AuthService.profile(u.uuid("id")));
        return out;
    }

    @GetMapping("/api/auth/me")
    @Roles({Role.SUPER_ADMIN, Role.ADMIN, Role.APPROVER, Role.REQUESTER, Role.VENDOR})
    public Object me(CurrentUser u) {
        return AuthService.profile(u.userId());
    }

    @PostMapping("/api/auth/change-password")
    @Roles({Role.SUPER_ADMIN, Role.ADMIN, Role.APPROVER, Role.REQUESTER, Role.VENDOR})
    public Object changePassword(@RequestBody JsonNode body, CurrentUser u) {
        String current = Json.reqText(body, "currentPassword");
        String next = Json.reqText(body, "newPassword");
        Passwords.validate(next);
        String hash = authQueries.getPasswordHash(u.userId());
        if (!Passwords.matches(current, hash)) throw ApiException.badRequest("Current password is incorrect");
        authQueries.updatePassword(u.userId(), Passwords.hash(next));
        return Map.of("ok", true);
    }

    @PostMapping("/api/auth/logout")
    @Roles({Role.SUPER_ADMIN, Role.ADMIN, Role.APPROVER, Role.REQUESTER, Role.VENDOR})
    public ResponseEntity<Void> logout(CurrentUser u, HttpServletRequest req) {
        AuthService.logActivity(u.userId(), u.companyId(), u.email(), "LOGOUT", req);
        return ResponseEntity.noContent().build();
    }
}
