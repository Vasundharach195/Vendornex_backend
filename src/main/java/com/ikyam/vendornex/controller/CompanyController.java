package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.dto.CreateCompanyRequest;
import com.ikyam.vendornex.dto.SetCompanyStatusRequest;
import com.ikyam.vendornex.dto.TestConnectionRequest;
import com.ikyam.vendornex.dto.UpdateCompanyRequest;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.CompanyService;
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.Roles;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Ikyam Super Admin: onboarding customer companies and their SAP B1 Service Layer connection. */
@RestController
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    @GetMapping("/api/sa/companies")
    @Roles(Role.SUPER_ADMIN)
    public Object list() {
        return service.list();
    }

    @GetMapping("/api/sa/companies/{id}")
    @Roles(Role.SUPER_ADMIN)
    public Object detail(@PathVariable String id) {
        return service.detail(Ids.uuid(id));
    }

    @PostMapping("/api/sa/companies")
    @Roles(Role.SUPER_ADMIN)
    public Object create(@RequestBody CreateCompanyRequest req) {
        UUID id = service.create(req);
        
        
       return service.afterCreate(id);
		//return id;
    }

    @PutMapping("/api/sa/companies/{id}")
    @Roles(Role.SUPER_ADMIN)
    public Object update(@PathVariable String id, @RequestBody UpdateCompanyRequest req) {
        return service.update(Ids.uuid(id), req);
    }

    @PostMapping("/api/sa/companies/test-connection")
    @Roles(Role.SUPER_ADMIN)
    public Object testUnsaved(@RequestBody TestConnectionRequest req) {
        return service.testUnsaved(req);
    }

    @PostMapping("/api/sa/companies/{id}/test-connection")
    @Roles(Role.SUPER_ADMIN)
    public Object testStored(@PathVariable String id) {
        return service.testStored(Ids.uuid(id));
    }

    @PostMapping("/api/sa/companies/{id}/status")
    @Roles(Role.SUPER_ADMIN)
    public Object setStatus(@PathVariable String id, @RequestBody SetCompanyStatusRequest req) {
        boolean active = req.active == null || req.active;
        return service.setStatus(Ids.uuid(id), active);
    }

    @PostMapping("/api/sa/companies/{id}/resend-admin-invite")
    @Roles(Role.SUPER_ADMIN)
    public Object resendInvite(@PathVariable String id) {
        return Map.of("inviteLink", service.resendInvite(Ids.uuid(id)));
    }

    @PostMapping("/api/sa/companies/{id}/sync")
    @Roles(Role.SUPER_ADMIN)
    public ResponseEntity<?> sync(@PathVariable String id) {
        UUID companyId = Ids.uuid(id);
        if (service.isSyncRunning(companyId)) throw ApiException.conflict("A sync is already running for this company");
        service.triggerSync(companyId);
        return ResponseEntity.status(202).body(Map.of("started", true));
    }
}
