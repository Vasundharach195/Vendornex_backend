package com.ikyam.vendornex.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** Compact HS256 JWT implementation (header.payload.signature). */
public final class Jwt {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final long ttlSeconds;

    public Jwt(String secret, int ttlMinutes) {
        if (secret.length() < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlMinutes * 60L;
    }

    public String issue(String subject) {
        ObjectNode p = Json.obj();
        long now = Instant.now().getEpochSecond();
        p.put("sub", subject);
        p.put("iat", now);
        p.put("exp", now + ttlSeconds);
        p.put("iss", "vendornex");
        String body = HEADER + "." + ENC.encodeToString(Json.write(p).getBytes(StandardCharsets.UTF_8));
        return body + "." + ENC.encodeToString(sign(body));
    }

    /** Returns the subject if the token is valid and unexpired. */
    public String verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) throw ApiException.unauthorized("Invalid session token");
        byte[] expected = sign(parts[0] + "." + parts[1]);
        byte[] given;
        try {
            given = DEC.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("Invalid session token");
        }
        if (!MessageDigest.isEqual(expected, given)) throw ApiException.unauthorized("Invalid session token");
        JsonNode p = Json.parse(new String(DEC.decode(parts[1]), StandardCharsets.UTF_8));
        if (p.path("exp").asLong(0) < Instant.now().getEpochSecond()) throw ApiException.unauthorized("Session expired — please sign in again");
        return p.path("sub").asText();
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
