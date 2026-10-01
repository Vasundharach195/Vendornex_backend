package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Validators;
import com.ikyam.vendornex.dto.VendorFieldsRequest;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.VendorQueries;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Wizard field mapping/validation — ported unchanged from {@code VendorController.Fields}. */
final class Fields {
    String legalName, contactName, email, phone, linkType, sapCardCode, street, city, state, zipCode, country,
            gstin, pan, bankName, bankAccountNo, bankIfsc, sapBankCode, groupName;
    Integer groupCode;
    final Set<String> present = new HashSet<>();

    static Fields from(VendorFieldsRequest b, UUID companyId, VendorQueries queries) {
        Fields f = new Fields();
        f.legalName = Json.optText(b.legalName);
        f.contactName = Json.optText(b.contactName);
        f.email = Validators.email(Json.optText(b.email));
        f.phone = Json.optText(b.phone);
        f.linkType = Optional.ofNullable(Json.optText(b.linkType)).orElse("NEW");
        if (!Set.of("NEW", "EXISTING").contains(f.linkType)) throw ApiException.badRequest("linkType must be NEW or EXISTING");
        f.sapCardCode = "EXISTING".equals(f.linkType) ? Json.reqText(b.sapCardCode, "sapCardCode") : null;
        f.street = Json.optText(b.street);
        f.city = Json.optText(b.city);
        f.state = Json.optText(b.state);
        f.zipCode = Json.optText(b.zipCode);
        f.country = Json.optText(b.country) == null ? null : Json.optText(b.country).toUpperCase(Locale.ROOT);
        if (f.country != null && !f.country.matches("[A-Z]{2}")) throw ApiException.badRequest("Country must be a 2-letter ISO code (e.g. IN)");
        f.gstin = Validators.gstin(Json.optText(b.gstin));
        f.pan = Validators.pan(Json.optText(b.pan));
        f.bankName = Json.optText(b.bankName);
        f.bankAccountNo = Json.optText(b.bankAccountNo);
        if (f.bankAccountNo != null && !f.bankAccountNo.matches("\\d{6,20}")) throw ApiException.badRequest("Account number must be 6–20 digits");
        f.bankIfsc = Validators.ifsc(Json.optText(b.bankIfsc));
        f.sapBankCode = Json.optText(b.sapBankCode);
        f.groupCode = b.vendorGroupCode;
        if (f.groupCode != null) {
            f.groupName = queries.groupName(companyId, f.groupCode);
            if (f.groupName == null) throw ApiException.badRequest("Unknown vendor group " + f.groupCode);
        }
        if (f.sapCardCode != null) {
            var bp = queries.businessPartner(companyId, f.sapCardCode);
            if (bp == null) throw ApiException.badRequest("Business Partner " + f.sapCardCode + " is not in the synced B1 vendor master");
            boolean linked = queries.anyVendorLinkedTo(companyId, f.sapCardCode);
            f.present.add("__checkLinked");
            if (linked) f.present.add("__alreadyLinked");
            if (f.legalName == null) f.legalName = bp.str("cardName");
            if (f.groupCode == null && bp.integer("groupCode") != null) {
                f.groupCode = bp.integer("groupCode");
                f.groupName = queries.groupName(companyId, f.groupCode);
            }
        }
        return f;
    }

    void write(UUID id, Integer wizardStep, VendorQueries queries) {
        if (present.contains("__alreadyLinked")) {
            UUID other = queries.otherVendorWithCardCode(sapCardCode, id);
            if (other != null && !other.equals(id)) throw ApiException.conflict("Business Partner " + sapCardCode + " is already linked to another vendor");
        }
        Integer clampedStep = wizardStep == null ? null : Math.max(1, Math.min(4, wizardStep));
        queries.write(id, legalName, contactName, email, phone, linkType, sapCardCode, groupCode, groupName,
                street, city, state, zipCode, country, gstin, pan, bankName, bankAccountNo, bankIfsc, sapBankCode, clampedStep);
    }
}
