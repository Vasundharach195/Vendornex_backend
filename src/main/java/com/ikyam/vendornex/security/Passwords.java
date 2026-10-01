package com.ikyam.vendornex.security;

import com.ikyam.vendornex.http.ApiException;
import org.mindrot.jbcrypt.BCrypt;

public final class Passwords {
    private Passwords() {}

    public static String hash(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt(11));
    }

    public static boolean matches(String raw, String hash) {
        if (raw == null || hash == null) return false;
        try {
            return BCrypt.checkpw(raw, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Minimum policy: 8+ chars with a letter and a digit. */
    public static void validate(String raw) {
        if (raw == null || raw.length() < 8 || !raw.matches(".*[A-Za-z].*") || !raw.matches(".*\\d.*")) {
            throw ApiException.badRequest("Password must be at least 8 characters and contain a letter and a digit");
        }
    }
}
