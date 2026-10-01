package com.ikyam.vendornex.security;

import com.ikyam.vendornex.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for secrets at rest (Service Layer passwords). Format: base64(iv[12] || ciphertext+tag).
 * The key comes from APP_SECRET_KEY (base64, 32 bytes). In production, APP_SECRET_KEY should be
 * injected from a secret manager / KMS-wrapped value.
 */
public final class Crypto {

    private static final Logger log = LoggerFactory.getLogger(Crypto.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static SecretKeySpec key;

    private Crypto() {}

    public static void init(AppConfig cfg) {
        try {
            byte[] k;
            if (cfg.secretKeyB64.isEmpty()) {
                log.warn("APP_SECRET_KEY not set — deriving a DEVELOPMENT key. Never run production like this.");
                k = MessageDigest.getInstance("SHA-256").digest("vendornex-dev-key".getBytes(StandardCharsets.UTF_8));
            } else {
                k = Base64.getDecoder().decode(cfg.secretKeyB64);
                if (k.length != 32) throw new IllegalStateException("APP_SECRET_KEY must decode to 32 bytes");
            }
            key = new SecretKeySpec(k, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialise encryption", e);
        }
    }

    public static String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static String decrypt(String enc) {
        if (enc == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt stored secret — was APP_SECRET_KEY changed?", e);
        }
    }

    public static String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
