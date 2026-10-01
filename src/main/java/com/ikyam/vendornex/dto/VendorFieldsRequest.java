package com.ikyam.vendornex.dto;

/** Request body for {@code POST /api/vendors} and {@code PUT /api/vendors/{id}} (the onboarding
 * wizard) — every field is optional on save (draft), validated for format when present, exactly
 * as the pre-DTO {@code Fields.from(JsonNode,...)} did. */
public class VendorFieldsRequest {
    public String legalName;
    public String contactName;
    public String email;
    public String phone;
    public String linkType;
    public String sapCardCode;
    public String street;
    public String city;
    public String state;
    public String zipCode;
    public String country;
    public String gstin;
    public String pan;
    public String bankName;
    public String bankAccountNo;
    public String bankIfsc;
    public String sapBankCode;
    public Integer vendorGroupCode;
    public Integer wizardStep;
}
