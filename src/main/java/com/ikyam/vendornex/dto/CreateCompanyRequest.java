package com.ikyam.vendornex.dto;

/** Request body for {@code POST /api/sa/companies}. Plain field carrier — all validation and
 * error-message wording stays in {@code CompanyService}, unchanged from before this DTO existed. */
public class CreateCompanyRequest {
    public String name;
    public String adminName;
    public String adminEmail;
    public String industry;
    public String integrationMode;
    public Boolean slVerifyTls;
    /** SAP B1 company DBs selected at onboarding (one or more) → ik_sap_connection. */
    public java.util.List<SapConnectionInput> sapConnections;

    // ik_onboarding_company sheet fields (adminName / adminEmail fill user_name / email)
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
