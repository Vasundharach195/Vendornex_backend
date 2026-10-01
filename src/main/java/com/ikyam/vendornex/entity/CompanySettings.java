package com.ikyam.vendornex.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code company_settings} — shared primary key 1:1 with {@link Company} (PK = company_id,
 * no surrogate id). Reads still go through {@code common.Settings.of(...)} (kept on the legacy
 * {@code Db} path until every one of its many callers across the codebase is converted); this
 * entity exists so {@code CompanyController} can insert the initial all-defaults row via JPA
 * instead of a raw {@code Db.exec}. The actual settings UPDATE (array columns) is done via
 * {@code JdbcTemplate}+{@code SqlBind}, not entity dirty-checking — see {@code SettingsQueries}.
 */
@Entity
@Table(name = "company_settings")
public class CompanySettings {

    @Id
    @Column(name = "company_id", columnDefinition = "uuid")
    private UUID companyId;

    @Column(name = "pr_mode", nullable = false, length = 20)
    private String prMode = "APP";

    @Column(name = "push_pr_to_b1", nullable = false)
    private boolean pushPrToB1 = false;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "vendor_approval_stages", nullable = false, columnDefinition = "varchar(20)[]")
    private String[] vendorApprovalStages = { "FINANCE", "PROCUREMENT" };

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "requester_approval_stages", nullable = false, columnDefinition = "varchar(20)[]")
    private String[] requesterApprovalStages = { "FINANCE" };

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pr_approval_stages", nullable = false, columnDefinition = "varchar(20)[]")
    private String[] prApprovalStages = { "ADMIN" };

    @Column(name = "bp_series")
    private Integer bpSeries;

    @Column(name = "bp_code_prefix", nullable = false, length = 10)
    private String bpCodePrefix = "V";

    @Column(name = "bp_code_next", nullable = false)
    private int bpCodeNext = 20001;

    @Column(name = "default_warehouse_code", length = 20)
    private String defaultWarehouseCode;

    @Column(name = "default_tax_code", length = 20)
    private String defaultTaxCode;

    @Column(name = "india_localization", nullable = false)
    private boolean indiaLocalization = true;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "master_sync_interval_min", nullable = false)
    private int masterSyncIntervalMin = 30;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CompanySettings() {}

    public CompanySettings(UUID companyId) {
        this.companyId = companyId;
    }

    public UUID getCompanyId() { return companyId; }
}
