package com.ikyam.vendornex.controller;

import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.dto.SetVendorStatusRequest;
import com.ikyam.vendornex.dto.VendorFieldsRequest;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.security.CurrentUser;
import com.ikyam.vendornex.security.Role;
import com.ikyam.vendornex.service.SyncService;
import com.ikyam.vendornex.service.VendorService;
import com.ikyam.vendornex.web.Bodies;
import com.ikyam.vendornex.web.Ids;
import com.ikyam.vendornex.web.QueryParams;
import com.ikyam.vendornex.web.Roles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Vendor onboarding (4-step wizard: Basic -> Tax -> Bank -> Uploads), approval submission,
 * document management, activation/deactivation and B1 retry.
 */
@RestController
public class VendorController {

    private static final Set<String> DOC_TYPES = Set.of("GST", "PAN", "MSME", "INSURANCE", "CANCELLED_CHEQUE", "OTHER");
    private static final int MAX_DOC_BYTES = 10 * 1024 * 1024;

    private final VendorService service;

    public VendorController(VendorService service) {
        this.service = service;
    }

    @GetMapping("/api/vendors")
    @Roles(Role.ADMIN)
    public Object list(@RequestParam(required = false) String status, @RequestParam(required = false) String q, CurrentUser u) {
        return service.list(u.company(), QueryParams.orNull(status), QueryParams.orNull(q));
    }

    @GetMapping("/api/vendors-lookup")
    @Roles(Role.ADMIN)
    public Object lookup(CurrentUser u) {
        return service.lookup(u.company());
    }

    @GetMapping("/api/vendors/{id}")
    @Roles({Role.ADMIN, Role.APPROVER})
    public Object detail(@PathVariable String id, CurrentUser u) {
        return service.detail(Ids.uuid(id), u.company());
    }

    @PostMapping("/api/vendors")
    @Roles(Role.ADMIN)
    public Object create(@RequestBody VendorFieldsRequest req, CurrentUser u) {
        return service.create(req, u.company(), u.userId());
    }

    @PutMapping("/api/vendors/{id}")
    @Roles(Role.ADMIN)
    public Object update(@PathVariable String id, @RequestBody VendorFieldsRequest req, CurrentUser u) {
        return service.update(Ids.uuid(id), req, u.company());
    }

    @DeleteMapping("/api/vendors/{id}")
    @Roles(Role.ADMIN)
    public ResponseEntity<Void> deleteDraft(@PathVariable String id, CurrentUser u) {
        service.deleteDraft(Ids.uuid(id), u.company());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/vendors/{id}/submit")
    @Roles(Role.ADMIN)
    public Object submit(@PathVariable String id, CurrentUser u) {
        UUID vendorId = Ids.uuid(id);
        UUID companyId = u.company();
        VendorService.SubmitResult result = service.submit(vendorId, companyId, u.userId());
        Row out = new Row();
        for (UUID tx : result.postCommitSyncIds) out.put("sapSync", SyncService.processNow(tx));
        out.put("vendor", service.detail(vendorId, companyId));
        out.put("warnings", result.warnings);
        return out;
    }

    @PostMapping("/api/vendors/{id}/retry-sap")
    @Roles(Role.ADMIN)
    public Object retrySap(@PathVariable String id, CurrentUser u) {
        UUID vendorId = Ids.uuid(id);
        UUID companyId = u.company();
        service.retrySapValidate(vendorId, companyId);
        Row tx = SyncService.latestFor("VENDOR", vendorId);
        if (tx == null) throw ApiException.conflict("No previous B1 transaction to retry");
        Row out = new Row();
        out.put("sapSync", SyncService.retry(companyId, tx.uuid("id")));
        out.put("vendor", service.detail(vendorId, companyId));
        return out;
    }

    @PostMapping("/api/vendors/{id}/status")
    @Roles(Role.ADMIN)
    public Object setStatus(@PathVariable String id, @RequestBody SetVendorStatusRequest req, CurrentUser u) {
        UUID vendorId = Ids.uuid(id);
        boolean active = req.active == null || req.active;
        service.setStatus(vendorId, active, u.company());
        return service.detail(vendorId, u.company());
    }

    @PostMapping("/api/vendors/{id}/invite")
    @Roles(Role.ADMIN)
    public Object reinvite(@PathVariable String id, CurrentUser u) {
        return Map.of("inviteLink", service.reinvite(Ids.uuid(id), u.company()));
    }

    // ------------------------------------------------------------------ documents

    @PostMapping("/api/vendors/{id}/documents/{type}")
    @Roles(Role.ADMIN)
    public Object upload(@PathVariable String id, @PathVariable String type,
                          @RequestParam(required = false) String fileName, @RequestParam(required = false) String expiryDate,
                          @RequestHeader(value = "X-File-Name", required = false) String xFileName,
                          @RequestHeader(value = "Content-Type", required = false) String contentTypeHeader,
                          HttpServletRequest req, CurrentUser u) {
        String docType = type.toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(docType)) throw ApiException.badRequest("Unknown document type " + docType);
        String name = Optional.ofNullable(QueryParams.orNull(fileName)).orElse(Optional.ofNullable(xFileName).orElse(docType.toLowerCase(Locale.ROOT) + ".pdf"));
        String contentType = Optional.ofNullable(contentTypeHeader).orElse("application/octet-stream");
        if (!contentType.startsWith("application/pdf") && !contentType.startsWith("image/")) {
            throw ApiException.badRequest("Upload a PDF or an image (JPG/PNG)");
        }
        LocalDate expiry = QueryParams.orNull(expiryDate) == null ? null : LocalDate.parse(expiryDate);
        byte[] data = Bodies.readBytes(req, MAX_DOC_BYTES);
        if (data.length == 0) throw ApiException.badRequest("The file is empty");
        return service.upload(Ids.uuid(id), docType, data, name, contentType, expiry, u.company(), u.userId());
    }

    @GetMapping("/api/vendors/{id}/documents/{docId}/file")
    @Roles({Role.ADMIN, Role.APPROVER})
    public ResponseEntity<byte[]> download(@PathVariable String id, @PathVariable String docId, CurrentUser u) {
        return service.download(Ids.uuid(id), Ids.uuid(docId), u.company());
    }

    @DeleteMapping("/api/vendors/{id}/documents/{docId}")
    @Roles(Role.ADMIN)
    public ResponseEntity<Void> deleteDoc(@PathVariable String id, @PathVariable String docId, CurrentUser u) {
        service.deleteDoc(Ids.uuid(id), Ids.uuid(docId), u.company());
        return ResponseEntity.noContent().build();
    }
}
