package com.ikyam.vendornex.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.common.Background;
import com.ikyam.vendornex.common.Settings;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.SettingsQueries;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic moved out of {@code SettingsController} (Company Admin settings: PR source,
 * approval chains, B1 posting defaults). {@code update} still takes the raw {@link JsonNode} body
 * — several fields (default warehouse/tax code, bp series, the three approval-stage arrays)
 * distinguish "key not sent" (keep current value) from "key sent as null" (clear it), a genuine
 * partial-update semantic a plain request DTO can't represent without reintroducing JsonNode
 * under another name; see the JPA migration plan.
 */
@Service
public class SettingsService {

    private static final List<String> STAGE_ORDER = List.of("ADMIN", "FINANCE", "PROCUREMENT", "COMPLIANCE");

    private final SettingsQueries queries;
    private final CompanyService companyService;

    public SettingsService(SettingsQueries queries, CompanyService companyService) {
        this.queries = queries;
        this.companyService = companyService;
    }

    public Row get(UUID companyId) {
        Row s = Settings.of(companyId);
        s.put("integration", queries.integrationInfo(companyId));
        s.put("approvers", queries.approverCounts(companyId));
        return s;
    }

    public Row update(UUID companyId, JsonNode b) {
        Row cur = Settings.of(companyId);
        String prMode = Optional.ofNullable(Json.optText(b, "prMode")).orElse(cur.str("prMode"));
        if (!Set.of("APP", "B1_SYNC").contains(prMode)) throw ApiException.badRequest("prMode must be APP or B1_SYNC");

        String wh = b.has("defaultWarehouseCode") ? Json.optText(b, "defaultWarehouseCode") : cur.str("defaultWarehouseCode");
        if (wh != null && !queries.warehouseExists(companyId, wh)) {
            throw ApiException.badRequest("Warehouse " + wh + " not found in SAP B1 master");
        }
        String tax = b.has("defaultTaxCode") ? Json.optText(b, "defaultTaxCode") : cur.str("defaultTaxCode");
        if (tax != null && !queries.taxCodeExists(companyId, tax)) {
            throw ApiException.badRequest("Tax code " + tax + " not found in SAP B1 master");
        }
        Integer series = b.has("bpSeries") ? Json.optInt(b, "bpSeries") : cur.integer("bpSeries");
        String prefix = Optional.ofNullable(Json.optText(b, "bpCodePrefix")).orElse(cur.str("bpCodePrefix"));
        if (!prefix.matches("[A-Za-z0-9-]{0,6}")) throw ApiException.badRequest("BP code prefix: up to 6 letters/digits");
        Integer next = Optional.ofNullable(Json.optInt(b, "bpCodeNext")).orElse(cur.integer("bpCodeNext"));
        if (next < 1 || (prefix + next).length() > 15) throw ApiException.badRequest("BP code must fit in 15 characters");
        Integer interval = Optional.ofNullable(Json.optInt(b, "masterSyncIntervalMin")).orElse(cur.integer("masterSyncIntervalMin"));
        if (interval < 5) throw ApiException.badRequest("Sync interval must be at least 5 minutes");

        queries.update(companyId, prMode, Json.optBool(b, "pushPrToB1", cur.bool("pushPrToB1")),
                stages(b, "vendorApprovalStages", cur), stages(b, "requesterApprovalStages", cur), stages(b, "prApprovalStages", cur),
                series, prefix, next, wh, tax,
                Optional.ofNullable(Json.optText(b, "currency")).orElse(cur.str("currency")).toUpperCase(),
                Json.optBool(b, "indiaLocalization", cur.bool("indiaLocalization")), interval);

        if ("B1_SYNC".equals(prMode) && !"B1_SYNC".equals(cur.str("prMode"))) {
            Background.run("pr-sync", () -> MasterSyncService.sync(companyId, List.of("PURCHASE_REQUESTS"), "MANUAL"));
        }
        return get(companyId);
    }

    public Row testConnection(UUID companyId) {
        return companyService.testStored(companyId);
    }

    /** Validates a stage list and stores it in canonical order (Admin -> Finance -> Procurement -> Compliance). */
    private static String[] stages(JsonNode b, String field, Row cur) {
        if (!b.has(field)) return cur.strList(Character.toLowerCase(field.charAt(0)) + field.substring(1)).toArray(new String[0]);
        List<String> given = Json.textList(b, field);
        for (String s : given) if (!STAGE_ORDER.contains(s)) throw ApiException.badRequest("Unknown approval stage " + s);
        return STAGE_ORDER.stream().filter(given::contains).toArray(String[]::new);
    }
}
