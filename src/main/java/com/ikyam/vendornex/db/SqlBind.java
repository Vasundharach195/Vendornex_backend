package com.ikyam.vendornex.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.http.Json;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Positional JDBC parameter binding, shared by {@link Db} and the JdbcTemplate-based read-model
 * queries introduced during the JPA migration, so both paths marshal parameters identically
 * (arrays, jsonb, enums, dates) regardless of which one issues the statement.
 */
public final class SqlBind {
    private SqlBind() {}

    public static void bind(Connection c, PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            int idx = i + 1;
            if (p == null) {
                ps.setNull(idx, Types.NULL);
            } else if (p instanceof String[] arr) {
                ps.setArray(idx, c.createArrayOf("varchar", arr));
            } else if (p instanceof List<?> list) {
                ps.setArray(idx, c.createArrayOf("varchar", list.stream().map(String::valueOf).toArray()));
            } else if (p instanceof Integer[] arr) {
                ps.setArray(idx, c.createArrayOf("int4", arr));
            } else if (p instanceof UUID[] arr) {
                ps.setArray(idx, c.createArrayOf("uuid", arr));
            } else if (p instanceof JsonNode node) {
                PGobject o = new PGobject();
                o.setType("jsonb");
                o.setValue(Json.write(node));
                ps.setObject(idx, o);
            } else if (p instanceof LocalDate d) {
                ps.setObject(idx, d);
            } else if (p instanceof Enum<?> e) {
                ps.setString(idx, e.name());
            } else {
                ps.setObject(idx, p);
            }
        }
    }
}
