package com.ikyam.vendornex.dto;

/** Request body for {@code POST /api/sa/companies/test-connection} (unsaved connection test). */
public class TestConnectionRequest {
    public String integrationMode;
    public String serviceLayerBaseUrl;
    public String sapCompanyDb;
    public String slUsername;
    public String slPassword;
    public Boolean slVerifyTls;
}
