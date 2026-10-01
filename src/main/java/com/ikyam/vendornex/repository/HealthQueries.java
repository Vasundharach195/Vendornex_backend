package com.ikyam.vendornex.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Health check ping query.
 */
@Repository
public class HealthQueries {

    private final JdbcTemplate jdbc;

    public HealthQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean ping() {
        Integer res = jdbc.queryForObject("SELECT 1", Integer.class);
        return res != null && res == 1;
    }
}
