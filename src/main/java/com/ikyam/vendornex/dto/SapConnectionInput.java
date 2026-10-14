package com.ikyam.vendornex.dto;

/** One SAP B1 company DB picked during onboarding; stored in the tenant's {@code ik_sap_connection}.
 * Plain field carrier — validation lives in {@code CompanyService}. */
public class SapConnectionInput {
    public String connectionName;
    public String companyDbCode;
    public String baseUrl;
    public String sapUsername;
    public String sapPassword;
    public Boolean isDefault;
    public Integer sequenceNo;
}
