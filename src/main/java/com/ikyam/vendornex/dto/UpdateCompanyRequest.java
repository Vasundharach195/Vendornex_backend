package com.ikyam.vendornex.dto;

/** Request body for {@code PUT /api/sa/companies/{id}}. */
public class UpdateCompanyRequest {
    public String name;
    public String industry;
    public String integrationMode;
    public String serviceLayerBaseUrl;
    public String sapCompanyDb;
    public String slUsername;
    public String slPassword;
    public Boolean slVerifyTls;
}
