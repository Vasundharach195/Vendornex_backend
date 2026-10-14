package com.ikyam.vendornex.repository;

import com.ikyam.vendornex.db.GenericRowMapper;
import com.ikyam.vendornex.db.Row;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-model queries for {@code MasterDataController} that don't map onto a single entity's full
 * column set (correlated stock sums, joins, {@code json_agg}/{@code DISTINCT ON} aggregation) —
 * stays on {@code NamedParameterJdbcTemplate} with the exact original SQL text per the migration's
 * query-routing rule.
 */
@Repository
public class MasterDataQueries {

    private final NamedParameterJdbcTemplate jdbc;

    public MasterDataQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> items(UUID companyId, String q, int limit) {
        return jdbc.query("""
                SELECT i.item_code, i.item_name, i.item_group_name, i.purchase_uom, i.avg_price,
                       COALESCE((SELECT sum(s.in_stock) FROM ik_sap_item_stock s WHERE s.company_id = i.company_id AND s.connection_id = i.connection_id AND s.item_code = i.item_code), 0) AS in_stock
                  FROM ik_sap_items i
                 WHERE i.company_id = :company AND i.connection_id = ik_default_connection() AND i.is_active
                   AND (:q::text IS NULL OR i.item_code ILIKE '%' || :q || '%' OR i.item_name ILIKE '%' || :q || '%')
                 ORDER BY i.item_code LIMIT :limit""",
                new MapSqlParameterSource().addValue("company", companyId).addValue("q", q).addValue("limit", limit),
                GenericRowMapper.INSTANCE);
    }

    public List<Row> businessPartners(UUID companyId, String q, int limit) {
        return jdbc.query("""
                SELECT b.card_code, b.card_name, b.group_code, g.group_name, b.is_active, b.federal_tax_id, b.email,
                       v.id AS linked_vendor_id, v.legal_name AS linked_vendor_name
                  FROM ik_sap_business_partners b
                  LEFT JOIN ik_sap_vendor_groups g ON g.company_id = b.company_id AND g.connection_id = ik_default_connection() AND g.group_code = b.group_code
                  LEFT JOIN ik_vendors v ON v.company_id = b.company_id AND v.sap_card_code = b.card_code
                 WHERE b.company_id = :company AND b.connection_id = ik_default_connection() AND (:q::text IS NULL OR b.card_code ILIKE '%' || :q || '%' OR b.card_name ILIKE '%' || :q || '%')
                 ORDER BY b.card_name LIMIT :limit""",
                new MapSqlParameterSource().addValue("company", companyId).addValue("q", q).addValue("limit", limit),
                GenericRowMapper.INSTANCE);
    }

    public List<Row> dataHubItems(UUID companyId, String q, String warehouse, String group) {
        return jdbc.query("""
                SELECT i.item_code, i.item_name, i.item_group_name, i.purchase_uom, i.inventory_uom, i.avg_price, i.is_active,
                       (SELECT max(ls.last_sync_at) FROM ik_success ls WHERE ls.connection_id = i.connection_id AND ls.type = 'ITEMS') AS last_synced_at,
                       COALESCE(json_agg(json_build_object('warehouseCode', s.warehouse_code, 'warehouseName', w.warehouse_name,
                                'inStock', s.in_stock, 'committed', s.committed, 'ordered', s.ordered) ORDER BY s.warehouse_code)
                                FILTER (WHERE s.warehouse_code IS NOT NULL), '[]') AS stock
                  FROM ik_sap_items i
                  LEFT JOIN ik_sap_item_stock s ON s.company_id = i.company_id AND s.connection_id = ik_default_connection() AND s.item_code = i.item_code AND (:wh::text IS NULL OR s.warehouse_code = :wh)
                  LEFT JOIN ik_sap_warehouses w ON w.company_id = s.company_id AND w.connection_id = ik_default_connection() AND w.warehouse_code = s.warehouse_code
                 WHERE i.company_id = :company AND i.connection_id = ik_default_connection()
                   AND (:q::text IS NULL OR i.item_code ILIKE '%' || :q || '%' OR i.item_name ILIKE '%' || :q || '%')
                   AND (:grp::text IS NULL OR i.item_group_name = :grp)
                 GROUP BY i.company_id, i.connection_id, i.item_code
                 ORDER BY i.item_code LIMIT 500""",
                new MapSqlParameterSource().addValue("company", companyId).addValue("q", q).addValue("wh", warehouse).addValue("grp", group),
                GenericRowMapper.INSTANCE);
    }

    /** Branches (ik_branch) of the selected SAP DB. */
    public List<Row> branches() {
        return jdbc.query("""
                SELECT branch_id, branch_name, is_active FROM ik_branch
                 WHERE connection_id = ik_default_connection() ORDER BY branch_name""", GenericRowMapper.INSTANCE);
    }

    /** States (ik_state) of the selected SAP DB, optionally for one country. */
    public List<Row> states(String countryCode) {
        return jdbc.query("""
                SELECT state_code, state_name, country_code, is_active FROM ik_state
                 WHERE connection_id = ik_default_connection() AND (:country::text IS NULL OR country_code = :country)
                 ORDER BY state_name""", new MapSqlParameterSource("country", countryCode), GenericRowMapper.INSTANCE);
    }

    /** Countries (ik_country) of the selected SAP DB. */
    public List<Row> countries() {
        return jdbc.query("""
                SELECT country_code, country_name, is_active FROM ik_country
                 WHERE connection_id = ik_default_connection() ORDER BY country_name""", GenericRowMapper.INSTANCE);
    }

    public Row summary(UUID companyId) {
        List<Row> rows = jdbc.query("""
                SELECT (SELECT count(*) FROM ik_sap_items WHERE company_id = :c AND connection_id = ik_default_connection() AND is_active) AS items,
                       (SELECT count(*) FROM ik_sap_warehouses WHERE company_id = :c AND connection_id = ik_default_connection() AND is_active) AS warehouses,
                       (SELECT count(*) FROM ik_sap_vendor_groups WHERE company_id = :c AND connection_id = ik_default_connection()) AS vendor_groups,
                       (SELECT count(*) FROM ik_sap_business_partners WHERE company_id = :c AND connection_id = ik_default_connection()) AS business_partners,
                       (SELECT count(*) FROM ik_sap_employees WHERE company_id = :c AND connection_id = ik_default_connection()) AS employees,
                       (SELECT count(*) FROM ik_sap_tax_codes WHERE company_id = :c AND connection_id = ik_default_connection()) AS tax_codes,
                       (SELECT count(*) FROM ik_sync_transactions WHERE company_id = :c AND status = 'FAILED') AS failed_transactions,
                       (SELECT count(*) FROM ik_sync_transactions WHERE company_id = :c AND status IN ('QUEUED','RUNNING')) AS queued_transactions,
                       (SELECT max(last_sync_at) FROM ik_success) AS last_sync_at,
                       (SELECT json_object_agg(entity, json_build_object('status', status, 'at', started_at, 'records', records, 'error', error_detail))
                          FROM (SELECT DISTINCT ON (entity) entity, status, started_at, records, error_detail FROM ik_sync_runs
                                 WHERE company_id = :c ORDER BY entity, started_at DESC) x) AS last_runs""",
                new MapSqlParameterSource("c", companyId), GenericRowMapper.INSTANCE);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Row> syncRuns(UUID companyId) {
        return jdbc.query("""
                SELECT id, entity, trigger_type, status, records, error_detail, started_at, finished_at FROM ik_sync_runs
                 WHERE company_id = :c ORDER BY started_at DESC LIMIT 100""",
                new MapSqlParameterSource("c", companyId), GenericRowMapper.INSTANCE);
    }

    public List<Row> transactions(UUID companyId, String status) {
        return jdbc.query("""
                SELECT t.id, t.operation, t.source_type, t.source_id, t.target_b1_object, t.status, t.attempts, t.sap_doc_entry, t.sap_key,
                       t.error_detail, t.payload_snapshot, t.created_at, t.resolved_at, t.next_attempt_at, u.name AS requested_by
                  FROM ik_sync_transactions t LEFT JOIN ik_users u ON u.id = t.requested_by_user_id
                 WHERE t.company_id = :c AND (:status::text IS NULL OR t.status = :status)
                 ORDER BY t.created_at DESC LIMIT 200""",
                new MapSqlParameterSource().addValue("c", companyId).addValue("status", status), GenericRowMapper.INSTANCE);
    }
}
