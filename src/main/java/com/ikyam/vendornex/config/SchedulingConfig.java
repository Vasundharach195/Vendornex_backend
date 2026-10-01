package com.ikyam.vendornex.config;

import com.ikyam.vendornex.controller.RfqController;
import com.ikyam.vendornex.service.MasterSyncService;
import com.ikyam.vendornex.service.SyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Reproduces App.startSchedulers() exactly: same two jobs, same delays, same enable flag. */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    // Outbox worker: retries queued Service Layer writes.
    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    public void outboxWorker() {
        if (!AppConfig.get().schedulerEnabled) return;
        safe("outbox", () -> SyncService.runWorker(20));
    }

    // Master data + PR/PO reconciliation per company interval; RFQ auto-close.
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void masterSync() {
        if (!AppConfig.get().schedulerEnabled) return;
        safe("master-sync", () -> {
            RfqController.closeOverdue();
            MasterSyncService.runDue();
        });
    }

    private static void safe(String name, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.warn("Scheduled job {} failed: {}", name, e.getMessage());
        }
    }
}
