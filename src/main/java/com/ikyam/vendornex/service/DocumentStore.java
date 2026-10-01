package com.ikyam.vendornex.service;

import com.ikyam.vendornex.config.AppConfig;
import com.ikyam.vendornex.http.ApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stores uploaded documents on a mounted volume (DOCUMENT_STORE_DIR). Keys are
 * company/vendor/uuid so one tenant's files can never be addressed through another's path.
 * Swap this class for S3 / Azure Blob in production without touching callers.
 */
public final class DocumentStore {

    private DocumentStore() {}

    private static Path root() {
        return Path.of(AppConfig.get().documentStoreDir).toAbsolutePath().normalize();
    }

    public static String save(UUID companyId, UUID ownerId, byte[] data) {
        String key = companyId + "/" + ownerId + "/" + UUID.randomUUID();
        Path p = resolve(key);
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, data);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store document", e);
        }
        return key;
    }

    public static byte[] load(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw ApiException.notFound("Document file");
        }
    }

    public static void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static Path resolve(String key) {
        Path p = root().resolve(key).normalize();
        if (!p.startsWith(root())) throw ApiException.badRequest("Invalid document key");
        return p;
    }
}
