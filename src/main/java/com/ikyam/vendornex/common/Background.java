package com.ikyam.vendornex.common;

import com.ikyam.vendornex.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fire-and-forget work on virtual threads (long master syncs triggered from a request). */
public final class Background {
    private static final Logger log = LoggerFactory.getLogger(Background.class);
    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    private Background() {}

    public static void run(String name, Runnable r) {
        String schema = TenantContext.get(); // the task keeps working for the company that started it
        EXEC.submit(() -> {
            try {
                TenantContext.run(schema, r);
            } catch (Exception e) {
                log.error("Background task {} failed", name, e);
            }
        });
    }
}
