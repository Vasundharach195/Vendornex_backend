package com.ikyam.vendornex.service;

import com.ikyam.vendornex.config.AppConfig;
import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.security.Crypto;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Jwt;
import com.ikyam.vendornex.security.Role;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.UUID;

/** Session validation, profile lookup, and invitation issuing — reused across controllers. */
public final class AuthService {

    private static Jwt jwt;

    private AuthService() {}

    public static void init(AppConfig cfg) {
        jwt = new Jwt(cfg.jwtSecret, cfg.jwtTtlMinutes);
    }

    public static String issueToken(UUID userId) {
        return jwt.issue(userId.toString());
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
        Row u = Db.one("""
                SELECT u.id, u.company_id, u.role, u.vendor_id, u.name, u.email, u.status,
                       c.is_active AS company_active, v.status AS vendor_status,
                       ARRAY(SELECT s.stage FROM user_approval_stages s WHERE s.user_id = u.id) AS stages
                  FROM users u
                  LEFT JOIN companies c ON c.id = u.company_id
                  LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE u.id = ?""", userId);
        if (u == null || !"ACTIVE".equals(u.str("status"))) throw ApiException.unauthorized("Account is not active");
        if (u.get("companyId") != null && !u.bool("companyActive")) throw ApiException.unauthorized("Company account is inactive");
        if (u.get("vendorId") != null && !"ACTIVE".equals(u.str("vendorStatus"))) throw ApiException.unauthorized("Vendor account is not active");
        return new CurrentUser(u.uuid("id"), u.uuid("companyId"), Role.valueOf(u.str("role")), u.uuid("vendorId"),
                new HashSet<>(u.strList("stages")), u.str("name"), u.str("email"));
    }

    /** Everything the frontend needs to build the shell for this user. */
    public static Row profile(UUID userId) {
        Row p = Db.one("""
                SELECT u.id, u.name, u.email, u.role, u.department, u.company_id, u.vendor_id,
                       c.name AS company_name, c.integration_mode, c.connection_status,
                       v.legal_name AS vendor_name, v.sap_card_code AS vendor_card_code,
                       s.pr_mode,
                       ARRAY(SELECT st.stage FROM user_approval_stages st WHERE st.user_id = u.id ORDER BY st.stage) AS stages
                  FROM users u
                  LEFT JOIN companies c ON c.id = u.company_id
                  LEFT JOIN company_settings s ON s.company_id = u.company_id
                  LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE u.id = ?""", userId);
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
        Db.exec("UPDATE users SET invite_token = ?, invite_expires_at = ?, status = 'INVITED', updated_at = now() WHERE id = ?",
                token, java.sql.Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS)), userId);
        return inviteLink(token);
    }

    public static String inviteLink(String token) {
        return token == null ? null : AppConfig.get().publicAppUrl + "/accept-invite/" + token;
    }

    public static void logActivity(UUID userId, UUID companyId, String email, String event, HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        Db.exec("INSERT INTO login_activity(user_id, company_id, email, event, ip_address, user_agent) VALUES (?,?,?,?,?,?)",
                userId, companyId, email, event, clientIp(req), ua == null ? null : ua.substring(0, Math.min(ua.length(), 400)));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
