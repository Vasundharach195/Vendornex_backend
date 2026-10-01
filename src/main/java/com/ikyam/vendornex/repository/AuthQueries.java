package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.db.SqlBind;
import com.ikyam.vendornex.service.AuthService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Authentication and session read/write queries: login credentials lookup, session token verification,
 * user profile assembly, invitation issuing/acceptance, password management, and login activity logging.
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
                SELECT u.id, u.company_id, u.password_hash, u.status, u.role, c.is_active AS company_active, v.status AS vendor_status
                  FROM users u LEFT JOIN companies c ON c.id = u.company_id LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE lower(u.email) = ?""";
        return one(sql, email);
    }

    public void updateLastLogin(UUID userId) {
        jdbc.update("UPDATE users SET last_login_at = now() WHERE id = ?", userId);
    }

    public Row findInviteInfo(String token) {
        String sql = """
                SELECT u.name, u.email, u.role, u.invite_expires_at, c.name AS company_name, v.legal_name AS vendor_name
                  FROM users u LEFT JOIN companies c ON c.id = u.company_id LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE u.invite_token = ? AND u.status = 'INVITED'""";
        return one(sql, token);
    }

    public Row findUserByInviteToken(String token) {
        String sql = "SELECT id, company_id, email, invite_expires_at FROM users WHERE invite_token = ? AND status = 'INVITED'";
        return one(sql, token);
    }

    public void acceptInvite(UUID userId, String passwordHash) {
        String sql = """
                UPDATE users SET password_hash = ?, status = 'ACTIVE', invite_token = NULL, invite_expires_at = NULL, updated_at = now()
                 WHERE id = ?""";
        jdbc.update(sql, passwordHash, userId);
    }

    public String getPasswordHash(UUID userId) {
        String sql = "SELECT password_hash FROM users WHERE id = ?";
        return scalar(sql, String.class, userId);
    }

    public void updatePassword(UUID userId, String passwordHash) {
        String sql = "UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?";
        jdbc.update(sql, passwordHash, userId);
    }

    public Row findUserForAuth(UUID userId) {
        String sql = """
                SELECT u.id, u.company_id, u.role, u.vendor_id, u.name, u.email, u.status,
                       c.is_active AS company_active, v.status AS vendor_status,
                       ARRAY(SELECT s.stage FROM user_approval_stages s WHERE s.user_id = u.id) AS stages
                  FROM users u
                  LEFT JOIN companies c ON c.id = u.company_id
                  LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE u.id = ?""";
        return one(sql, userId);
    }

    public Row findUserProfile(UUID userId) {
        String sql = """
                SELECT u.id, u.name, u.email, u.role, u.department, u.company_id, u.vendor_id,
                       c.name AS company_name, c.integration_mode, c.connection_status,
                       v.legal_name AS vendor_name, v.sap_card_code AS vendor_card_code,
                       s.pr_mode,
                       ARRAY(SELECT st.stage FROM user_approval_stages st WHERE st.user_id = u.id ORDER BY st.stage) AS stages
                  FROM users u
                  LEFT JOIN companies c ON c.id = u.company_id
                  LEFT JOIN company_settings s ON s.company_id = u.company_id
                  LEFT JOIN vendors v ON v.id = u.vendor_id
                 WHERE u.id = ?""";
        return one(sql, userId);
    }

    public void updateInviteToken(UUID userId, String token, Timestamp expiresAt) {
        String sql = "UPDATE users SET invite_token = ?, invite_expires_at = ?, status = 'INVITED', updated_at = now() WHERE id = ?";
        jdbc.update(sql, token, expiresAt, userId);
    }

    public void insertLoginActivity(UUID userId, UUID companyId, String email, String event, String ipAddress, String userAgent) {
        String sql = "INSERT INTO login_activity(user_id, company_id, email, event, ip_address, user_agent) VALUES (?,?,?,?,?,?)";
        jdbc.update(sql, userId, companyId, email, event, ipAddress, userAgent);
    }

    // ------------------------------------------------------------------ helpers

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
