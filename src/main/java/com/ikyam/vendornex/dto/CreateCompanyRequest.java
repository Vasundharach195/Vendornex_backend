package com.ikyam.vendornex.dto;

/** Request body for {@code POST /api/sa/companies}. Plain field carrier — all validation and
 * error-message wording stays in {@code CompanyService}, unchanged from before this DTO existed. */
public class CreateCompanyRequest {
    public String name;
    public String adminName;
    public String adminEmail;
    public String industry;
    public String integrationMode;
    public String serviceLayerBaseUrl;
    public String sapCompanyDb;
    public String slUsername;
    public String slPassword;
    public Boolean slVerifyTls;
}
