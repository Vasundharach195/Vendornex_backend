package com.ikyam.vendornex.config;

import com.ikyam.vendornex.db.Migrator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * The Spring-managed connection pool that JPA/Hibernate and {@link NamedParameterJdbcTemplate}
 * share, tuned identically to {@code Db.init} (which the legacy {@code Db} helper still uses in
 * parallel while domains are converted stage by stage — see the migration plan). Both pools point
 * at pgbouncer in transaction-pooling mode, hence the same {@code prepareThreshold=0} / shortened
 * lifetimes.
 *
 * <p>The migration runs inside this bean's factory method, before the {@link HikariDataSource} is
 * returned, so Spring's dependency graph — not {@code @PostConstruct} ordering luck — guarantees
 * every schema-touching bean (Hibernate's schema validator included, since {@code ddl-auto=validate}
 * runs at {@code EntityManagerFactory} creation) sees an already-migrated database. {@link StartupConfig}
 * depends on this bean for the same reason.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource() throws Exception {
        AppConfig cfg = AppConfig.get();
        Migrator.migrate(cfg);

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.dbUrl);
        hc.setUsername(cfg.dbUser);
        hc.setPassword(cfg.dbPassword);
        hc.setMaximumPoolSize(cfg.dbPoolSize);
        hc.setMinimumIdle(Math.min(4, cfg.dbPoolSize));
        hc.setPoolName("vendornex-jpa");
        hc.setConnectionTimeout(10_000);
        hc.setMaxLifetime(25 * 60_000L);
        hc.setIdleTimeout(5 * 60_000L);
        hc.addDataSourceProperty("prepareThreshold", "0");
        hc.addDataSourceProperty("ApplicationName", "vendornex-api-jpa");
        return new HikariDataSource(hc);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Plain positional-parameter template for read-model/report queries ported verbatim from
     * {@code Db}. Query classes bind parameters via {@code SqlBind.bind(...)} (a
     * {@code PreparedStatementSetter} lambda), not JdbcTemplate's own varargs binding, so arrays/
     * jsonb/enum params marshal exactly as they did through {@code Db.exec}/{@code Db.query}.
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
