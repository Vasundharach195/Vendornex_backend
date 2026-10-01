package com.ikyam.vendornex.common;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.http.ApiException;

import java.util.List;
import java.util.UUID;

/** Company-level configuration (company_settings). */
public final class Settings {
    private Settings() {}

    public static Row of(UUID companyId) {
        Row s = Db.one("SELECT * FROM company_settings WHERE company_id = ?", companyId);
        if (s == null) throw ApiException.notFound("Company settings");
        return s;
    }

    /** Ordered approval chain for an entity type: VENDOR / REQUESTER / PURCHASE_REQUEST. */
    public static List<String> stages(UUID companyId, String entityType) {
        Row s = of(companyId);
        return switch (entityType) {
            case "VENDOR" -> s.strList("vendorApprovalStages");
            case "REQUESTER" -> s.strList("requesterApprovalStages");
            case "PURCHASE_REQUEST" -> s.strList("prApprovalStages");
            default -> throw new IllegalArgumentException(entityType);
        };
    }
}
