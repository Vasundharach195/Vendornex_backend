package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.db.SqlBind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Vendor read/write queries kept on JdbcTemplate rather than the {@code Vendor} entity: every
 * "full row for a JSON response" read (25+ columns, too large a surface to hand-map field-by-field
 * without real risk of a silent omission) and the wizard's UPDATE, which sets {@code sap_bp_status}
 * from a correlated subquery — no JPA equivalent — per the migration's query-routing rule.
 */
@Repository
public class VendorQueries {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbc;

    public VendorQueries(NamedParameterJdbcTemplate namedJdbc, JdbcTemplate jdbc) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
    }

    public List<Row> list(UUID companyId, String status, String q, String approvalsSummarySql) {
        String sql = "SELECT v.id, v.legal_name, v.contact_name, v.email, v.link_type, v.sap_card_code, v.sap_bp_status, " +
                "v.vendor_group_name, v.status, v.wizard_step, v.invited_on, v.rejection_reason, v.gstin, " +
                approvalsSummarySql + " AS approvals " +
                "FROM vendors v WHERE v.company_id = :company AND (:status::text IS NULL OR v.status = :status) " +
                "AND (:q::text IS NULL OR v.legal_name ILIKE '%' || :q || '%' OR v.sap_card_code ILIKE '%' || :q || '%' OR v.email ILIKE '%' || :q || '%') " +
                "ORDER BY v.created_at DESC";
        return namedJdbc.query(sql, new MapSqlParameterSource().addValue("company", companyId).addValue("status", status).addValue("q", q),
                GenericRowMapper.INSTANCE);
    }

    public List<Row> lookup(UUID companyId) {
        return namedJdbc.query("""
                SELECT id, legal_name, sap_card_code, vendor_group_name, sap_bp_status FROM vendors
                 WHERE company_id = :company AND status = 'ACTIVE' ORDER BY legal_name""",
                new MapSqlParameterSource("company", companyId), GenericRowMapper.INSTANCE);
    }

    public Row findFullById(UUID id, UUID companyId) {
        List<Row> rows = namedJdbc.query("SELECT * FROM vendors WHERE id = :id AND company_id = :company",
                new MapSqlParameterSource().addValue("id", id).addValue("company", companyId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Row findFullById(UUID id) {
        List<Row> rows = namedJdbc.query("SELECT * FROM vendors WHERE id = :id",
                new MapSqlParameterSource("id", id), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The wizard's field-mapping UPDATE — see {@code VendorController.Fields.write}. */
    public void write(UUID id, String legalName, String contactName, String email, String phone, String linkType,
                       String sapCardCode, Integer groupCode, String groupName, String street, String city, String state,
                       String zipCode, String country, String gstin, String pan, String bankName, String bankAccountNo,
                       String bankIfsc, String sapBankCode, Integer wizardStep) {
        String sql = """
                UPDATE vendors SET legal_name = COALESCE(:legalName, legal_name), contact_name = :contactName, email = :email, phone = :phone, link_type = :linkType,
                       sap_card_code = :sapCardCode, sap_bp_status = CASE WHEN :sapCardCode::text IS NULL THEN NULL
                           ELSE (SELECT CASE WHEN is_active THEN 'ACTIVE' ELSE 'INACTIVE' END FROM sap_business_partners b
                                  WHERE b.company_id = vendors.company_id AND b.card_code = :sapCardCode) END,
                       vendor_group_code = :groupCode, vendor_group_name = :groupName, street = :street, city = :city, state = :state, zip_code = :zipCode, country = :country,
                       gstin = :gstin, pan = :pan, bank_name = :bankName, bank_account_no = :bankAccountNo, bank_ifsc = :bankIfsc, sap_bank_code = :sapBankCode,
                       wizard_step = COALESCE(:wizardStep, wizard_step), updated_at = now()
                 WHERE id = :id""";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("legalName", legalName)
                .addValue("contactName", contactName)
                .addValue("email", email)
                .addValue("phone", phone)
                .addValue("linkType", linkType)
                .addValue("sapCardCode", sapCardCode)
                .addValue("groupCode", groupCode)
                .addValue("groupName", groupName)
                .addValue("street", street)
                .addValue("city", city)
                .addValue("state", state)
                .addValue("zipCode", zipCode)
                .addValue("country", country)
                .addValue("gstin", gstin)
                .addValue("pan", pan)
                .addValue("bankName", bankName)
                .addValue("bankAccountNo", bankAccountNo)
                .addValue("bankIfsc", bankIfsc)
                .addValue("sapBankCode", sapBankCode)
                .addValue("wizardStep", wizardStep);
        namedJdbc.update(sql, params);
    }

    public void updateStatusPendingApproval(UUID id) {
        jdbc.update("UPDATE vendors SET status = 'PENDING_APPROVAL', rejection_reason = NULL, wizard_step = 4, updated_at = now() WHERE id = ?", id);
    }

    public void updateStatus(UUID id, String status) {
        jdbc.update("UPDATE vendors SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    public String groupName(UUID companyId, Integer groupCode) {
        List<String> rows = namedJdbc.query("SELECT group_name FROM sap_vendor_groups WHERE company_id = :c AND group_code = :g",
                new MapSqlParameterSource().addValue("c", companyId).addValue("g", groupCode), (rs, n) -> rs.getString(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Row businessPartner(UUID companyId, String cardCode) {
        List<Row> rows = namedJdbc.query("SELECT card_name, group_code FROM sap_business_partners WHERE company_id = :c AND card_code = :code",
                new MapSqlParameterSource().addValue("c", companyId).addValue("code", cardCode), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Row businessPartnerActive(UUID companyId, String cardCode) {
        List<Row> rows = namedJdbc.query("SELECT is_active FROM sap_business_partners WHERE company_id = :c AND card_code = :code",
                new MapSqlParameterSource().addValue("c", companyId).addValue("code", cardCode), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Row businessPartnerForDetail(UUID companyId, String cardCode) {
        List<Row> rows = namedJdbc.query("""
                SELECT card_code, card_name, is_active, group_code, last_synced_at FROM sap_business_partners
                 WHERE company_id = :c AND card_code = :code""",
                new MapSqlParameterSource().addValue("c", companyId).addValue("code", cardCode), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean anyVendorLinkedTo(UUID companyId, String cardCode) {
        Boolean r = namedJdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vendors WHERE company_id = :c AND sap_card_code = :code)",
                new MapSqlParameterSource().addValue("c", companyId).addValue("code", cardCode), Boolean.class);
        return Boolean.TRUE.equals(r);
    }

    /** Another vendor (not {@code excludeId}) already linked to the same B1 card code, in the same company as {@code excludeId}. */
    public UUID otherVendorWithCardCode(String cardCode, UUID excludeId) {
        List<UUID> rows = namedJdbc.query(
                "SELECT id FROM vendors WHERE sap_card_code = :code AND company_id = (SELECT company_id FROM vendors WHERE id = :id) LIMIT 1",
                new MapSqlParameterSource().addValue("code", cardCode).addValue("id", excludeId), (rs, n) -> (UUID) rs.getObject(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean vendorGroupExists(UUID companyId, Integer groupCode) {
        Boolean r = namedJdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sap_vendor_groups WHERE company_id = :c AND group_code = :g)",
                new MapSqlParameterSource().addValue("c", companyId).addValue("g", groupCode), Boolean.class);
        return Boolean.TRUE.equals(r);
    }

    public Row userByEmail(String email) {
        List<Row> rows = namedJdbc.query("SELECT role, vendor_id FROM global_users WHERE lower(email) = lower(:email)",
                new MapSqlParameterSource("email", email), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int duplicateGstinCount(UUID companyId, String gstin, UUID excludeId) {
        Integer n = namedJdbc.queryForObject("""
                SELECT count(*)::int FROM vendors WHERE company_id = :c AND gstin = :gstin AND id <> :id AND status NOT IN ('DRAFT','REJECTED')""",
                new MapSqlParameterSource().addValue("c", companyId).addValue("gstin", gstin).addValue("id", excludeId), Integer.class);
        return n == null ? 0 : n;
    }

    public List<Row> vendorUsers(UUID vendorId) {
        return namedJdbc.query("SELECT id, name, email, status, invite_token, last_login_at FROM users WHERE vendor_id = :id",
                new MapSqlParameterSource("id", vendorId), GenericRowMapper.INSTANCE);
    }

    public Row reinviteCandidateUser(UUID vendorId, UUID companyId) {
        List<Row> rows = namedJdbc.query("""
                SELECT u.id, u.status FROM users u JOIN vendors v ON v.id = u.vendor_id
                 WHERE v.id = :vendor AND v.company_id = :company ORDER BY u.created_at LIMIT 1""",
                new MapSqlParameterSource().addValue("vendor", vendorId).addValue("company", companyId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
