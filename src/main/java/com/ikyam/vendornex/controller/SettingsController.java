package com.ikyam.vendornex.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.SettingsService;
import com.ikyam.vendornex.web.Roles;
import org.springframework.web.bind.annotation.*;

/** Company Admin settings: PR source, approval chains, B1 posting defaults. */
@RestController
public class SettingsController {

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping("/api/settings")
    @Roles(Role.ADMIN)
    public Object get(CurrentUser u) {
        return service.get(u.company());
    }

    @PutMapping("/api/settings")
    @Roles(Role.ADMIN)
    public Object update(@RequestBody JsonNode b, CurrentUser u) {
        return service.update(u.company(), b);
    }

    @PostMapping("/api/settings/test-connection")
    @Roles(Role.ADMIN)
    public Object testConnection(CurrentUser u) {
        return service.testConnection(u.company());
    }
}
