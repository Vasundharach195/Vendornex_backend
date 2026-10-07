package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.db.SqlBind;
import com.ikyam.vendornex.service.AuthService;
import com.ikyam.vendornex.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Authentication and session read/write queries: login credentials lookup, session token verification,
 * user profile assembly, invitation issuing/acceptance, password management, and login activity logging.
 *
 * <p>All of it reads and writes {@code ik_vendor.global_users}. Database triggers copy every change to
 * the {@code users} table of the user's company schema. The few facts that only exist per company
 * (vendor name/status, approval stages) are fetched from that company's schema in a second query.
 */
@Repository
public class AuthQueries {

    private final JdbcTemplate jdbc;

    public AuthQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        AuthService.setQueries(this);
    }

    public Row findUserForLogin(String email) {
        String sql = """
                SELECT u.id, u.company_id, u.password_hash, u.status, u.role, u.vendor_id, c.is_active AS company_active, c.schema_id
                  FROM global_users u LEFT JOIN companies c ON c.id = u.company_id
                 WHERE lower(u.email) = ?""";

        Row u = one(sql, email);
        if (u == null) return null;
        u.put("vendorStatus", tenantParts(u.str("schemaId"), u.uuid("id"), u.uuid("vendorId")).get("vendorStatus"));
        return u;
    }

    public void updateLastLogin(UUID userId) {
        jdbc.update("UPDATE global_users SET last_login_at = now() WHERE id = ?", userId);
    }

    public Row findInviteInfo(String token) {
        String sql = """
                SELECT u.name, u.email, u.role, u.invite_expires_at, c.name AS company_name, u.vendor_id, c.schema_id
                  FROM global_users u LEFT JOIN companies c ON c.id = u.company_id
                 WHERE u.invite_token = ? AND u.status = 'INVITED'""";
        Row u = one(sql, token);
        if (u == null) return null;
        UUID vendorId = u.uuid("vendorId");
        u.remove("vendorId");
        String schema = (String) u.remove("schemaId");
        u.put("vendorName", tenantParts(schema, null, vendorId).get("vendorName"));
        return u;
    }

    public Row findUserByInviteToken(String token) {
        String sql = "SELECT id, company_id, email, invite_expires_at FROM global_users WHERE invite_token = ? AND status = 'INVITED'";
        return one(sql, token);
    }

    public void acceptInvite(UUID userId, String passwordHash) {
        String sql = """
                UPDATE global_users SET password_hash = ?, status = 'ACTIVE', invite_token = NULL, invite_expires_at = NULL, updated_at = now()
                 WHERE id = ?""";
        jdbc.update(sql, passwordHash, userId);
    }

    public String getPasswordHash(UUID userId) {
        String sql = "SELECT password_hash FROM global_users WHERE id = ?";
        return scalar(sql, String.class, userId);
    }

    public void updatePassword(UUID userId, String passwordHash) {
        String sql = "UPDATE global_users SET password_hash = ?, updated_at = now() WHERE id = ?";
        jdbc.update(sql, passwordHash, userId);
    }

    /** Includes {@code schemaId}: the caller's company schema, which the request then runs in. */
    public Row findUserForAuth(UUID userId) {
        String sql = """
                SELECT u.id, u.company_id, u.role, u.vendor_id, u.name, u.email, u.status,
                       c.is_active AS company_active, c.schema_id
                  FROM global_users u
                  LEFT JOIN companies c ON c.id = u.company_id
                 WHERE u.id = ?""";
        Row u = one(sql, userId);
        if (u == null) return null;
        Row t = tenantParts(u.str("schemaId"), userId, u.uuid("vendorId"));
        u.put("vendorStatus", t.get("vendorStatus"));
        u.put("stages", t.get("stages"));
        return u;
    }

    public Row findUserProfile(UUID userId) {
        String sql = """
                SELECT u.id, u.name, u.email, u.role, u.department, u.company_id, u.vendor_id,
                       c.name AS company_name, c.integration_mode, c.connection_status,
                       s.pr_mode, c.schema_id
                  FROM global_users u
                  LEFT JOIN companies c ON c.id = u.company_id
                  LEFT JOIN company_settings s ON s.company_id = u.company_id
                 WHERE u.id = ?""";
        Row u = one(sql, userId);
        if (u == null) return null;
        Object prMode = u.remove("prMode");
        Row t = tenantParts((String) u.remove("schemaId"), userId, u.uuid("vendorId"));
        u.put("vendorName", t.get("vendorName"));
        u.put("vendorCardCode", t.get("vendorCardCode"));
        u.put("prMode", prMode);
        u.put("stages", t.get("stages"));
        return u;
    }

    public void updateInviteToken(UUID userId, String token, Timestamp expiresAt) {
        String sql = "UPDATE global_users SET invite_token = ?, invite_expires_at = ?, status = 'INVITED', updated_at = now() WHERE id = ?";
        jdbc.update(sql, token, expiresAt, userId);
    }

    public void insertLoginActivity(UUID userId, UUID companyId, String email, String event, String ipAddress, String userAgent) {
        String sql = "INSERT INTO login_activity(user_id, company_id, email, event, ip_address, user_agent) VALUES (?,?,?,?,?,?)";
        jdbc.update(sql, userId, companyId, email, event, ipAddress, userAgent);
    }

    /** Role and tenant schema of a user; schemaId is null for SUPER_ADMIN. */
    public Row findTokenTarget(UUID userId) {
        String sql = """
                SELECT u.role, c.schema_id
                  FROM global_users u LEFT JOIN companies c ON c.id = u.company_id
                 WHERE u.id = ?""";
        return one(sql, userId);
    }

    /** Unqualified table name: resolves to the tenant's jwt_tokens inside a tenant context, else to the global one. */
    public void insertJwtRecord(UUID userId, String role, String tokenHash, Timestamp expiresAt) {
        String sql = "INSERT INTO jwt_tokens(user_id, role, token_hash, expires_at) VALUES (?, ?, ?, ?)";
        jdbc.update(sql, userId, role, tokenHash, expiresAt);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Vendor name/card code/status and approval stages, read from the user's company schema.
     * A user without a company (SUPER_ADMIN) has none of these.
     */
    private Row tenantParts(String schema, UUID userId, UUID vendorId) {
        if (schema == null) {
            return new Row().with("vendorName", null).with("vendorCardCode", null).with("vendorStatus", null)
                    .with("stages", new ArrayList<String>());
        }
        String sql = """
                SELECT v.legal_name AS vendor_name, v.sap_card_code AS vendor_card_code, v.status AS vendor_status,
                       ARRAY(SELECT st.stage FROM user_approval_stages st WHERE st.user_id = ?::uuid ORDER BY st.stage) AS stages
                  FROM (SELECT 1) one LEFT JOIN vendors v ON v.id = ?::uuid""";
        return TenantContext.call(schema, () -> one(sql, userId, vendorId));
    }

    private List<Row> query(String sql, Object... params) {
        return jdbc.query(sql, ps -> SqlBind.bind(ps.getConnection(), ps, params), GenericRowMapper.INSTANCE);
    }

    private Row one(String sql, Object... params) {
        List<Row> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private <T> T scalar(String sql, Class<T> type, Object... params) {
        List<T> list = jdbc.query(sql, ps -> SqlBind.bind(ps.getConnection(), ps, params), (rs, rowNum) -> rs.getObject(1, type));
        return list.isEmpty() ? null : list.get(0);
    }
}
