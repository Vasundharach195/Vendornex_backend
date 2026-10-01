package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Passwords;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.web.Roles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Login, session validation, invitations. */
@RestController
public class AuthController {

    @PostMapping("/api/auth/login")
    public Object login(@RequestBody JsonNode body, HttpServletRequest req) {
        String email = Json.reqText(body, "email").toLowerCase(Locale.ROOT);
        String password = Json.reqText(body, "password");
        Row u = Db.one("""
                SELECT u.id, u.company_id, u.password_hash, u.status, u.role, c.is_active AS company_active, v.status AS vendor_status
                  FROM users u LEFT JOIN companies c ON c.id = u.company_id LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE lower(u.email) = ?""", email);
        if (u == null || !Passwords.matches(password, u.str("passwordHash"))) {
            AuthService.logActivity(u == null ? null : u.uuid("id"), u == null ? null : u.uuid("companyId"), email, "LOGIN_FAILED", req);
            throw ApiException.unauthorized("Invalid email or password");
        }
        String status = u.str("status");
        if ("PENDING_APPROVAL".equals(status)) throw ApiException.forbidden("Your account is awaiting internal approval");
        if (!"ACTIVE".equals(status)) throw ApiException.forbidden("Your account is " + status.toLowerCase(Locale.ROOT).replace('_', ' '));
        if (u.get("companyId") != null && !u.bool("companyActive")) throw ApiException.forbidden("Your company account is inactive");
        if ("VENDOR".equals(u.str("role")) && !"ACTIVE".equals(u.str("vendorStatus"))) throw ApiException.forbidden("Vendor account is not active yet");

        Db.exec("UPDATE users SET last_login_at = now() WHERE id = ?", u.uuid("id"));
        AuthService.logActivity(u.uuid("id"), u.uuid("companyId"), email, "LOGIN", req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", AuthService.issueToken(u.uuid("id")));
        out.put("user", AuthService.profile(u.uuid("id")));
        return out;
    }

    @GetMapping("/api/auth/invite/{token}")
    public Object inviteInfo(@PathVariable String token) {
        Row u = Db.one("""
                SELECT u.name, u.email, u.role, u.invite_expires_at, c.name AS company_name, v.legal_name AS vendor_name
                  FROM users u LEFT JOIN companies c ON c.id = u.company_id LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE u.invite_token = ? AND u.status = 'INVITED'""", token);
        if (u == null) throw ApiException.notFound("Invitation");
        if (Instant.parse(u.str("inviteExpiresAt")).isBefore(Instant.now())) throw new ApiException(410, "This invitation has expired — ask your admin to resend it");
        u.remove("inviteExpiresAt");
        return u;
    }

    @PostMapping("/api/auth/invite/{token}")
    public Object acceptInvite(@PathVariable String token, @RequestBody JsonNode body, HttpServletRequest req) {
        String password = Json.reqText(body, "password");
        Passwords.validate(password);
        Row u = Db.one("SELECT id, company_id, email, invite_expires_at FROM users WHERE invite_token = ? AND status = 'INVITED'", token);
        if (u == null) throw ApiException.notFound("Invitation");
        if (Instant.parse(u.str("inviteExpiresAt")).isBefore(Instant.now())) throw new ApiException(410, "This invitation has expired — ask your admin to resend it");
        Db.exec("""
                UPDATE users SET password_hash = ?, status = 'ACTIVE', invite_token = NULL, invite_expires_at = NULL, updated_at = now()
                 WHERE id = ?""", Passwords.hash(password), u.uuid("id"));
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
        String hash = Db.scalar("SELECT password_hash FROM users WHERE id = ?", String.class, u.userId());
        if (!Passwords.matches(current, hash)) throw ApiException.badRequest("Current password is incorrect");
        Db.exec("UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?", Passwords.hash(next), u.userId());
        return Map.of("ok", true);
    }

    @PostMapping("/api/auth/logout")
    @Roles({Role.SUPER_ADMIN, Role.ADMIN, Role.APPROVER, Role.REQUESTER, Role.VENDOR})
    public ResponseEntity<Void> logout(CurrentUser u, HttpServletRequest req) {
        AuthService.logActivity(u.userId(), u.companyId(), u.email(), "LOGOUT", req);
        return ResponseEntity.noContent().build();
    }
}
