package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.db.Db;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Object health() {
        Db.scalar("SELECT 1", Integer.class);
        return Map.of("status", "UP");
    }
}
