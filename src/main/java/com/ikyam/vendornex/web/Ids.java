package com.ikyam.vendornex.web;

import com.ikyam.vendornex.http.ApiException;

import java.util.UUID;

/**
 * Parses a path variable as a UUID, mirroring {@code Req.id()}: a malformed id is treated
 * as "record not found" (404), not a generic bad-request (400), so path variables stay
 * declared as {@code String} in controllers rather than {@code UUID} (which would let
 * Spring's own type-conversion failure produce a different error shape).
 */
public final class Ids {
    private Ids() {}

    public static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Record");
        }
    }
}
