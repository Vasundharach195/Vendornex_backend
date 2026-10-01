package com.ikyam.vendornex.common;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.db.Row;

import java.time.Year;
import java.util.UUID;

/**
 * Gap-tolerant document numbers (PR-2026-0001). UPDATE … RETURNING takes a row lock, so two
 * concurrent requests can never receive the same number; works under transaction pooling.
 */
public final class DocNumbers {
    private DocNumbers() {}

    public static String next(UUID companyId, String docType) {
        Row r = Db.one("""
                INSERT INTO doc_sequences(company_id, doc_type, prefix, next_value) VALUES (?, ?, ?, 2)
                ON CONFLICT (company_id, doc_type) DO UPDATE SET next_value = doc_sequences.next_value + 1
                RETURNING prefix, next_value - 1 AS value""", companyId, docType, docType + "-" + Year.now() + "-");
        return r.str("prefix") + String.format("%04d", r.integer("value"));
    }
}
