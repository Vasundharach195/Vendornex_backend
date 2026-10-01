package com.ikyam.vendornex.security;

import com.ikyam.vendornex.http.ApiException;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller. {@code companyId} is the tenant every query must be scoped to;
 * it is taken from the user's row, never from the request.
 */
public record CurrentUser(UUID userId, UUID companyId, Role role, UUID vendorId,
                          Set<String> stages, String name, String email) {

    /** Company of the caller; SUPER_ADMIN has none and cannot call tenant endpoints. */
    public UUID company() {
        if (companyId == null) throw ApiException.forbidden("This action needs a company context");
        return companyId;
    }

    public UUID vendor() {
        if (vendorId == null) throw ApiException.forbidden("Only vendor users can do this");
        return vendorId;
    }

    public boolean is(Role r) { return role == r; }

    /** Separation of duties: only users holding a stage can act on it (ADMIN stage = ADMIN role). */
    public boolean canActOnStage(String stage) {
        if ("ADMIN".equals(stage)) return role == Role.ADMIN;
        return role == Role.APPROVER && stages.contains(stage);
    }
}
