package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.repository.HealthQueries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final HealthQueries healthQueries;

    public HealthController(HealthQueries healthQueries) {
        this.healthQueries = healthQueries;
    }

    @GetMapping("/api/health")
    public Object health() {
        healthQueries.ping();
        return Map.of("status", "UP");
    }
}
