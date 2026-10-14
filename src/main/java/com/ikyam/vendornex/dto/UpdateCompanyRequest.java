package com.ikyam.vendornex.dto;

/** Request body for {@code PUT /api/sa/companies/{id}}. */
public class UpdateCompanyRequest {
    public String name;
    public String industry;
    public String integrationMode;
    public Boolean slVerifyTls;
    

    // ik_onboarding_company sheet fields — omitted (null) = left unchanged
    public String companyPhoneNo;
    public String companyStreet;
    public String companyCity;
    public String companyState;
    public String companyZipcode;
    public String companyWebsite;
    public String headofficeLocation;
    public String companyGst;
    public String registrationNumber;
    public String userPhoneNo;
    public String emailId;
    public String emailPwd;
    public String smtpServer;
    public String smtpPort;
    public String companyLogo;
}
