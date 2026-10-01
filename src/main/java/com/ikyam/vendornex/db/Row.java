package com.ikyam.vendornex.db;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * One result row. Keys are camelCase versions of the column names so a Row can be
 * serialised straight into an API response. Values are JSON-friendly: UUIDs and dates
 * are strings, numerics are BigDecimal, arrays are lists.
 */
public class Row extends LinkedHashMap<String, Object> {

    public String str(String k) {
        Object v = get(k);
        return v == null ? null : v.toString();
    }

    public UUID uuid(String k) {
        String s = str(k);
        return s == null ? null : UUID.fromString(s);
    }

    public BigDecimal dec(String k) {
        Object v = get(k);
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }

    public BigDecimal decOrZero(String k) {
        BigDecimal b = dec(k);
        return b == null ? BigDecimal.ZERO : b;
    }

    public Integer integer(String k) {
        Object v = get(k);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.valueOf(v.toString());
    }

    public boolean bool(String k) {
        Object v = get(k);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    public LocalDate date(String k) {
        String s = str(k);
        return s == null ? null : LocalDate.parse(s.substring(0, 10));
    }

    @SuppressWarnings("unchecked")
    public List<String> strList(String k) {
        Object v = get(k);
        return v == null ? List.of() : (List<String>) v;
    }

    public Row with(String k, Object v) {
        put(k, v);
        return this;
    }
}
