package com.ikyam.vendornex.http;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JSON helpers plus typed, validating accessors for request bodies. */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        SimpleModule time = new SimpleModule();
        time.addSerializer(LocalDate.class, ToStringSerializer.instance);
        time.addSerializer(Instant.class, ToStringSerializer.instance);
        time.addSerializer(UUID.class, ToStringSerializer.instance);
        MAPPER.registerModule(time);
    }

    private Json() {}

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode parse(String s) {
        try {
            return MAPPER.readTree(s == null || s.isBlank() ? "{}" : s);
        } catch (Exception e) {
            throw ApiException.badRequest("Malformed JSON body");
        }
    }

    public static ObjectNode obj() { return JsonNodeFactory.instance.objectNode(); }

    public static ArrayNode arr() { return JsonNodeFactory.instance.arrayNode(); }

    public static JsonNode valueToTree(Object o) { return MAPPER.valueToTree(o); }

    // ---------------------------------------------------------------- body accessors

    private static JsonNode field(JsonNode n, String f) {
        JsonNode v = n == null ? null : n.get(f);
        return (v == null || v.isNull()) ? null : v;
    }

    public static String optText(JsonNode n, String f) {
        JsonNode v = field(n, f);
        if (v == null) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    public static String reqText(JsonNode n, String f) {
        String s = optText(n, f);
        if (s == null) throw ApiException.badRequest("'" + f + "' is required");
        return s;
    }

    public static String reqText(JsonNode n, String f, int maxLen) {
        String s = reqText(n, f);
        if (s.length() > maxLen) throw ApiException.badRequest("'" + f + "' must be at most " + maxLen + " characters");
        return s;
    }

    public static BigDecimal optDec(JsonNode n, String f) {
        JsonNode v = field(n, f);
        if (v == null || v.asText().isBlank()) return null;
        try {
            return new BigDecimal(v.asText());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + f + "' must be a number");
        }
    }

    public static BigDecimal reqDec(JsonNode n, String f) {
        BigDecimal d = optDec(n, f);
        if (d == null) throw ApiException.badRequest("'" + f + "' is required");
        return d;
    }

    public static BigDecimal reqPositive(JsonNode n, String f) {
        BigDecimal d = reqDec(n, f);
        if (d.signum() <= 0) throw ApiException.badRequest("'" + f + "' must be greater than zero");
        return d;
    }

    public static Integer optInt(JsonNode n, String f) {
        JsonNode v = field(n, f);
        if (v == null || v.asText().isBlank()) return null;
        try {
            return Integer.valueOf(v.asText());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + f + "' must be an integer");
        }
    }

    public static boolean optBool(JsonNode n, String f, boolean def) {
        JsonNode v = field(n, f);
        return v == null ? def : v.asBoolean();
    }

    public static LocalDate optDate(JsonNode n, String f) {
        String s = optText(n, f);
        if (s == null) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            throw ApiException.badRequest("'" + f + "' must be a date (YYYY-MM-DD)");
        }
    }

    public static LocalDate reqDate(JsonNode n, String f) {
        LocalDate d = optDate(n, f);
        if (d == null) throw ApiException.badRequest("'" + f + "' is required");
        return d;
    }

    public static UUID optUuid(JsonNode n, String f) {
        String s = optText(n, f);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("'" + f + "' is not a valid id");
        }
    }

    public static UUID reqUuid(JsonNode n, String f) {
        UUID u = optUuid(n, f);
        if (u == null) throw ApiException.badRequest("'" + f + "' is required");
        return u;
    }

    public static List<JsonNode> reqArray(JsonNode n, String f) {
        JsonNode v = field(n, f);
        if (v == null || !v.isArray() || v.isEmpty()) throw ApiException.badRequest("'" + f + "' must contain at least one entry");
        List<JsonNode> out = new ArrayList<>();
        v.forEach(out::add);
        return out;
    }

    public static List<JsonNode> optArray(JsonNode n, String f) {
        JsonNode v = field(n, f);
        List<JsonNode> out = new ArrayList<>();
        if (v != null && v.isArray()) v.forEach(out::add);
        return out;
    }

    public static List<String> textList(JsonNode n, String f) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : optArray(n, f)) if (!e.asText().isBlank()) out.add(e.asText().trim());
        return out;
    }

    // ---------------------------------------------------------------- raw-value accessors
    // Same trim/blank/parse/error-message rules as the JsonNode accessors above, operating on a
    // value already bound onto a request DTO field instead of extracted from a JsonNode — lets
    // Controllers/Services keep byte-identical validation wording after JsonNode bodies are
    // replaced with typed DTOs.

    public static String optText(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }

    public static String reqText(String raw, String field) {
        String s = optText(raw);
        if (s == null) throw ApiException.badRequest("'" + field + "' is required");
        return s;
    }

    public static String reqText(String raw, String field, int maxLen) {
        String s = reqText(raw, field);
        if (s.length() > maxLen) throw ApiException.badRequest("'" + field + "' must be at most " + maxLen + " characters");
        return s;
    }

    public static BigDecimal optDec(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + field + "' must be a number");
        }
    }

    public static BigDecimal reqDec(String raw, String field) {
        BigDecimal d = optDec(raw, field);
        if (d == null) throw ApiException.badRequest("'" + field + "' is required");
        return d;
    }

    public static BigDecimal reqPositive(String raw, String field) {
        BigDecimal d = reqDec(raw, field);
        if (d.signum() <= 0) throw ApiException.badRequest("'" + field + "' must be greater than zero");
        return d;
    }

    public static boolean optBool(Boolean raw, boolean def) {
        return raw == null ? def : raw;
    }

    public static LocalDate optDate(String raw, String field) {
        String s = optText(raw);
        if (s == null) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (Exception e) {
            throw ApiException.badRequest("'" + field + "' must be a date (YYYY-MM-DD)");
        }
    }

    public static LocalDate reqDate(String raw, String field) {
        LocalDate d = optDate(raw, field);
        if (d == null) throw ApiException.badRequest("'" + field + "' is required");
        return d;
    }

    public static UUID optUuid(String raw, String field) {
        String s = optText(raw);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("'" + field + "' is not a valid id");
        }
    }

    public static List<String> textList(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) for (String e : raw) if (e != null && !e.isBlank()) out.add(e.trim());
        return out;
    }
}
