package com.ikyam.vendornex.config;

import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.sap.SapGatewayFactory;
import com.ikyam.vendornex.sap.mock.MockServiceLayer;
import com.ikyam.vendornex.security.Crypto;
import com.ikyam.vendornex.seed.DemoSeeder;
import com.ikyam.vendornex.service.AuthService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Runs the same startup sequence App.main() used to run directly, but as Spring bean
 * lifecycle callbacks. Spring finishes creating every singleton bean (this one included)
 * before the embedded servlet container starts accepting connections, so migrations, the
 * connection pool, and (in MOCK mode) the SAP Service Layer simulator are all guaranteed
 * ready before any controller — including the health check — can be called, and before
 * DemoSeeder pushes its seed data through the real sync outbox.
 *
 * <p>The {@link DataSource} constructor parameter is handed to {@code Db.bindSpringDataSource} (so
 * legacy {@code Db} calls join Spring transactions), and it makes this bean depend on
 * {@code DataSourceConfig.dataSource()}, which runs the SQL migrations
 * as part of its own construction. That gives a deterministic ordering guarantee (migrations
 * before this bean's {@code @PostConstruct}, hence before {@code Db.init}/DemoSeeder ever touch
 * the database) without relying on unspecified {@code @PostConstruct} ordering between beans.
 */
@Configuration
public class StartupConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupConfig.class);

    private final DataSource dataSource;

    public StartupConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void start() throws Exception {
        AppConfig cfg = AppConfig.get();
        Crypto.init(cfg);
        AuthService.init(cfg);

        Db.init(cfg);
        Db.bindSpringDataSource(dataSource);
        log.info("Connection pool ready (via pgbouncer at {})", cfg.dbUrl.replaceAll("\\?.*", ""));

        if (Boolean.parseBoolean(System.getenv().getOrDefault("MOCK_SL_ENABLED", "true"))) {
            MockServiceLayer mock = new MockServiceLayer(Path.of(cfg.documentStoreDir).resolveSibling("mock-service-layer"));
            mock.start("127.0.0.1", Integer.parseInt(System.getenv().getOrDefault("MOCK_SL_PORT", "0")));
            SapGatewayFactory.useMock(mock);
            log.info("SAP B1 Service Layer simulator on http://127.0.0.1:{}/b1s/v1 (companies in MOCK mode use it)", mock.port());
        }

        if (cfg.seedDemo) DemoSeeder.seedIfEmpty();
    }

    @PreDestroy
    public void stop() {
        Db.close();
    }
}
