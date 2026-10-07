package com.ikyam.vendornex.service;

import com.ikyam.vendornex.config.AppConfig;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.repository.AuthQueries;
import com.ikyam.vendornex.security.Crypto;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Jwt;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.UUID;

/** Session validation, profile lookup, and invitation issuing — reused across controllers. */
public final class AuthService {

    private static Jwt jwt;
    private static AuthQueries queries;
    private static int jwtTtlMinutes;

    private AuthService() {}

    public static void setQueries(AuthQueries authQueries) {
        queries = authQueries;
    }

    public static void init(AppConfig cfg) {
        jwt = new Jwt(cfg.jwtSecret, cfg.jwtTtlMinutes);
        jwtTtlMinutes = cfg.jwtTtlMinutes;
    }

    public static void init(AppConfig cfg, AuthQueries authQueries) {
        init(cfg);
        if (authQueries != null) {
            queries = authQueries;
        }
    }

    public static String issueToken(UUID userId) {
        String token = jwt.issue(userId.toString());
        try {
            Row target = queries.findTokenTarget(userId);
            String schema = target == null ? null : target.str("schemaId");
            String role = target == null ? null : target.str("role");
            String hash = sha256(token);
            java.sql.Timestamp expires = java.sql.Timestamp.from(Instant.now().plus(jwtTtlMinutes, ChronoUnit.MINUTES));
            TenantContext.run(schema, () -> queries.insertJwtRecord(userId, role, hash, expires));
        } catch (Exception e) {
            // audit-only bookkeeping — never block login over it
        }
        return token;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- per-request authentication

    public static CurrentUser authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) throw ApiException.unauthorized("Not signed in");
        UUID userId;
        try {
            userId = UUID.fromString(jwt.verify(authHeader.substring(7).trim()));
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("Invalid session token");
        }
        // Re-read the user on every request so disabling a user / company takes effect immediately.
        Row u = queries.findUserForAuth(userId);
        if (u == null || !"ACTIVE".equals(u.str("status"))) throw ApiException.unauthorized("Account is not active");
        if (u.get("companyId") != null && !u.bool("companyActive")) throw ApiException.unauthorized("Company account is inactive");
        if (u.get("vendorId") != null && !"ACTIVE".equals(u.str("vendorStatus"))) throw ApiException.unauthorized("Vendor account is not active");
        // The rest of this request runs in the caller's company schema (TenantContextFilter clears it).
        TenantContext.set(u.str("schemaId"));
        return new CurrentUser(u.uuid("id"), u.uuid("companyId"), Role.valueOf(u.str("role")), u.uuid("vendorId"),
                new HashSet<>(u.strList("stages")), u.str("name"), u.str("email"));
    }

    /** Everything the frontend needs to build the shell for this user. */
    public static Row profile(UUID userId) {
        Row p = queries.findUserProfile(userId);
        if (p == null) throw ApiException.notFound("User");
        return p;
    }

    // ---------------------------------------------------------------- invitations

    /**
     * Issues (or re-issues) a 7-day invite token and returns the activation link. There is no
     * mail server in scope; the admin shares the link. Plug an SMTP sender in here if needed.
     */
    public static String issueInvite(UUID userId) {
        String token = Crypto.randomToken();
        queries.updateInviteToken(userId, token, java.sql.Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)));
        return inviteLink(token);
    }

    public static String inviteLink(String token) {
        return token == null ? null : AppConfig.get().publicAppUrl + "/accept-invite/" + token;
    }

    public static void logActivity(UUID userId, UUID companyId, String email, String event, HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        queries.insertLoginActivity(userId, companyId, email, event, clientIp(req), ua == null ? null : ua.substring(0, Math.min(ua.length(), 400)));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
