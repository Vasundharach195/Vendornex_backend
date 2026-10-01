package com.ikyam.vendornex.db;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Maps a JDBC row to a {@link Row} exactly the way {@code Db.query} always has (snake_case
 * columns to camelCase keys, JSON-friendly value normalization via {@link Db#convert}), so
 * read-model queries ported to {@code NamedParameterJdbcTemplate} during the JPA migration
 * produce byte-identical response shapes to their pre-migration raw-JDBC equivalents.
 */
public final class GenericRowMapper implements RowMapper<Row> {

    public static final GenericRowMapper INSTANCE = new GenericRowMapper();

    @Override
    public Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        Row r = new Row();
        for (int i = 1; i <= n; i++) r.put(Db.camel(md.getColumnLabel(i)), Db.convert(rs.getObject(i)));
        return r;
    }
}
