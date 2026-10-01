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
 * Read-model + write queries for {@code SettingsController} that don't map onto
 * {@code CompanySettings}' full column set 1:1 (a join, and an UPDATE touching Postgres array
 * columns). The array-column UPDATE binds positionally via {@link SqlBind} — the same binder
 * {@code Db.exec} uses — since Spring Data JPA native-query binding of raw {@code String[]}
 * parameters to {@code varchar(20)[]} columns isn't reliable enough to trust for this migration's
 * zero-functional-change bar; going through the identical binder is the safer, provably-equivalent
 * path.
 */
@Repository
public class SettingsQueries {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbc;

    public SettingsQueries(NamedParameterJdbcTemplate namedJdbc, JdbcTemplate jdbc) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
    }

    public Row integrationInfo(UUID companyId) {
        List<Row> rows = namedJdbc.query("""
                SELECT integration_mode, service_layer_base_url, sap_company_db, sl_username, connection_status, connection_message,
                       sap_b1_version, last_tested_at FROM companies WHERE id = :id""",
                new MapSqlParameterSource("id", companyId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Row> approverCounts(UUID companyId) {
        return namedJdbc.query("""
                SELECT s.stage, count(*) AS users FROM user_approval_stages s JOIN users u ON u.id = s.user_id
                 WHERE u.company_id = :id AND u.status IN ('ACTIVE','INVITED') GROUP BY s.stage""",
                new MapSqlParameterSource("id", companyId), GenericRowMapper.INSTANCE);
    }

    public boolean warehouseExists(UUID companyId, String code) {
        Boolean r = namedJdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sap_warehouses WHERE company_id = :c AND warehouse_code = :w)",
                new MapSqlParameterSource("c", companyId).addValue("w", code), Boolean.class);
        return Boolean.TRUE.equals(r);
    }

    public boolean taxCodeExists(UUID companyId, String code) {
        Boolean r = namedJdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sap_tax_codes WHERE company_id = :c AND tax_code = :t)",
                new MapSqlParameterSource("c", companyId).addValue("t", code), Boolean.class);
        return Boolean.TRUE.equals(r);
    }

    public void update(UUID companyId, String prMode, boolean pushPrToB1, String[] vendorStages, String[] requesterStages,
                        String[] prStages, Integer bpSeries, String bpCodePrefix, int bpCodeNext, String defaultWarehouseCode,
                        String defaultTaxCode, String currency, boolean indiaLocalization, int masterSyncIntervalMin) {
        String sql = """
                UPDATE company_settings SET pr_mode = ?, push_pr_to_b1 = ?, vendor_approval_stages = ?, requester_approval_stages = ?,
                       pr_approval_stages = ?, bp_series = ?, bp_code_prefix = ?, bp_code_next = ?, default_warehouse_code = ?,
                       default_tax_code = ?, currency = ?, india_localization = ?, master_sync_interval_min = ?, updated_at = now()
                 WHERE company_id = ?""";
        Object[] params = { prMode, pushPrToB1, vendorStages, requesterStages, prStages, bpSeries, bpCodePrefix, bpCodeNext,
                defaultWarehouseCode, defaultTaxCode, currency, indiaLocalization, masterSyncIntervalMin, companyId };
        jdbc.update(sql, ps -> SqlBind.bind(ps.getConnection(), ps, params));
    }
}
