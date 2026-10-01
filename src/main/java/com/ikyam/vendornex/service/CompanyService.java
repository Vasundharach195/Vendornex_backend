package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Background;
import com.ikyam.vendornex.common.Validators;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.dto.CreateCompanyRequest;
import com.ikyam.vendornex.dto.TestConnectionRequest;
import com.ikyam.vendornex.dto.UpdateCompanyRequest;
import com.ikyam.vendornex.entity.Company;
import com.ikyam.vendornex.entity.CompanySettings;
import com.ikyam.vendornex.entity.User;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.CompanyQueries;
import com.ikyam.vendornex.repository.CompanyRepository;
import com.ikyam.vendornex.repository.CompanySettingsRepository;
import com.ikyam.vendornex.repository.UserRepository;
import com.ikyam.vendornex.sap.SapB1Gateway;
import com.ikyam.vendornex.sap.SapGatewayFactory;
import com.ikyam.vendornex.security.Crypto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic moved out of {@code CompanyController} (Super Admin: onboarding customer
 * companies and their SAP B1 Service Layer connection). SQL: entity CRUD via
 * {@link CompanyRepository}/{@link CompanySettingsRepository}/{@link UserRepository}; the
 * multi-table list/detail read-model stays on {@link CompanyQueries} (JdbcTemplate) per the
 * migration's query-routing rule.
 */
@Service
public class CompanyService {

    private final CompanyRepository companies;
    private final CompanySettingsRepository companySettings;
    private final UserRepository users;
    private final CompanyQueries queries;

    public CompanyService(CompanyRepository companies, CompanySettingsRepository companySettings,
                           UserRepository users, CompanyQueries queries) {
        this.companies = companies;
        this.companySettings = companySettings;
        this.users = users;
        this.queries = queries;
    }

    public List<Row> list() {
        List<Row> rows = queries.list();
        rows.forEach(CompanyService::exposeInvite);
        return rows;
    }

    public Row detail(UUID id) {
        Row c = queries.findById(id);
        if (c == null) throw ApiException.notFound("Company");
        exposeInvite(c);
        c.put("syncRuns", queries.syncRuns(id));
        return c;
    }

    private static void exposeInvite(Row c) {
        c.put("adminInviteLink", AuthService.inviteLink((String) c.remove("adminInviteToken")));
    }

    private record ConnInput(String mode, String url, String db, String user, String password, boolean verifyTls) {}

    private static ConnInput conn(String integrationMode, String serviceLayerBaseUrl, String sapCompanyDb,
                                   String slUsername, String slPassword, Boolean slVerifyTls, Company existing) {
        String mode = Optional.ofNullable(Json.optText(integrationMode))
                .orElse(existing == null ? "MOCK" : existing.getIntegrationMode());
        if (!Set.of("MOCK", "SERVICE_LAYER").contains(mode)) throw ApiException.badRequest("integrationMode must be MOCK or SERVICE_LAYER");
        String url = Json.optText(serviceLayerBaseUrl);
        String db = Json.optText(sapCompanyDb);
        String user = Json.optText(slUsername);
        String pw = Json.optText(slPassword);
        if (pw == null && existing != null && existing.getSlPasswordEnc() != null) pw = Crypto.decrypt(existing.getSlPasswordEnc());
        boolean verify = slVerifyTls == null || slVerifyTls;
        if ("SERVICE_LAYER".equals(mode)) {
            List<String> missing = new ArrayList<>();
            if (url == null) missing.add("Service Layer URL");
            if (db == null) missing.add("Company DB");
            if (user == null) missing.add("B1 user name");
            if (pw == null) missing.add("B1 password");
            if (!missing.isEmpty()) throw ApiException.badRequest("For a live SAP B1 connection enter: " + String.join(", ", missing));
            if (url == null || !url.matches("^https?://.+")) throw ApiException.badRequest("Service Layer URL must start with https:// (e.g. https://b1server:50000/b1s/v1)");
        }
        return new ConnInput(mode, url, db, user, pw, verify);
    }

    @Transactional(rollbackFor = Exception.class)
    public UUID create(CreateCompanyRequest req) {
        String name = Json.reqText(req.name, "name222", 200);
        String adminName = Json.reqText(req.adminName, "adminName", 150);
        String adminEmail = Validators.email(Json.reqText(req.adminEmail, "adminEmail"));
        ConnInput ci = conn(req.integrationMode, req.serviceLayerBaseUrl, req.sapCompanyDb, req.slUsername, req.slPassword, req.slVerifyTls, null);
        if (users.existsByEmailIgnoreCase(adminEmail)) throw ApiException.conflict("A user with e-mail " + adminEmail + " already exists");

        Company company = new Company();
        company.setName(name);
        company.setIndustry(Json.optText(req.industry));
        company.setIntegrationMode(ci.mode());
        company.setServiceLayerBaseUrl(ci.url());
        company.setSapCompanyDb(ci.db());
        company.setSlUsername(ci.user());
        company.setSlPasswordEnc(ci.password() == null ? null : Crypto.encrypt(ci.password()));
        company.setSlVerifyTls(ci.verifyTls());
        companies.save(company);
        companySettings.save(new CompanySettings(company.getId()));

        User admin = new User();
        admin.setCompanyId(company.getId());
        admin.setName(adminName);
        admin.setEmail(adminEmail);
        admin.setRole("ADMIN");
        admin.setStatus("INVITED");
        users.save(admin);

        return company.getId();
    }

    /** Runs after the create transaction commits — invites the admin, tests the connection, and
     * (if it's live) kicks off the initial master-data sync. Mirrors the original sequencing. */
    public Row afterCreate(UUID companyId) {
        List<User> admins = users.findByCompanyIdAndRoleOrderByCreatedAt(companyId, "ADMIN");
        if (admins.isEmpty()) throw new IllegalStateException("admin not found for company " + companyId);
        AuthService.issueInvite(admins.get(0).getId());
        Row conn = testStored(companyId);
        if ("OK".equals(conn.str("connectionStatus"))) {
            Background.run("initial-sync", () -> MasterSyncService.sync(companyId, null, "MANUAL"));
        }
        return detail(companyId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Row update(UUID companyId, UpdateCompanyRequest req) {
        Company company = companies.findById(companyId).orElseThrow(() -> ApiException.notFound("Company"));
        ConnInput ci = conn(req.integrationMode, req.serviceLayerBaseUrl, req.sapCompanyDb, req.slUsername, req.slPassword, req.slVerifyTls, company);
        company.setName(Json.optText(req.name));
        company.setIndustry(Json.optText(req.industry));
        company.setIntegrationMode(ci.mode());
        company.setServiceLayerBaseUrl(ci.url());
        company.setSapCompanyDb(ci.db());
        company.setSlUsername(ci.user());
        company.setSlPasswordEnc(ci.password() == null ? null : Crypto.encrypt(ci.password()));
        company.setSlVerifyTls(ci.verifyTls());
        company.setConnectionStatus("NOT_TESTED");
        companies.save(company);
        SapGatewayFactory.evict(companyId);
        testStored(companyId);
        return detail(companyId);
    }

    public Row testUnsaved(TestConnectionRequest req) {
        ConnInput ci = conn(req.integrationMode, req.serviceLayerBaseUrl, req.sapCompanyDb, req.slUsername, req.slPassword, req.slVerifyTls, null);
        SapB1Gateway g = SapGatewayFactory.create(
                new SapGatewayFactory.Conn(ci.mode(), ci.url(), ci.db(), ci.user(), ci.password(), ci.verifyTls()),
                UUID.randomUUID().toString());
        SapB1Gateway.ConnectionInfo info = g.testConnection();
        return new Row().with("ok", info.ok()).with("message", info.message()).with("version", info.version());
    }

    /** Tests the saved connection and records the outcome on the company. */
    @Transactional(rollbackFor = Exception.class)
    public Row testStored(UUID companyId) {
        Company company = companies.findById(companyId).orElseThrow(() -> ApiException.notFound("Company"));
        SapB1Gateway.ConnectionInfo info;
        try {
            info = SapGatewayFactory.forCompany(companyId).testConnection();
        } catch (ApiException e) {
            info = new SapB1Gateway.ConnectionInfo(false, e.getMessage(), null);
        } catch (Exception e) {
            info = new SapB1Gateway.ConnectionInfo(false, e.getMessage(), null);
        }
        company.setConnectionStatus(info.ok() ? "OK" : "FAILED");
        company.setConnectionMessage(info.message());
        if (info.version() != null) company.setSapB1Version(info.version());
        company.setLastTestedAt(Instant.now());
        companies.save(company);
        return new Row()
                .with("connectionStatus", company.getConnectionStatus())
                .with("connectionMessage", company.getConnectionMessage())
                .with("sapB1Version", company.getSapB1Version())
                .with("lastTestedAt", company.getLastTestedAt() == null ? null : company.getLastTestedAt().toString());
    }

    @Transactional(rollbackFor = Exception.class)
    public Row setStatus(UUID companyId, boolean active) {
        Company company = companies.findById(companyId).orElseThrow(() -> ApiException.notFound("Company"));
        company.setActive(active);
        companies.save(company);
        return detail(companyId);
    }

    public String resendInvite(UUID companyId) {
        List<User> admins = users.findByCompanyIdAndRoleOrderByCreatedAt(companyId, "ADMIN");
        if (admins.isEmpty()) throw ApiException.notFound("Company admin");
        User admin = admins.get(0);
        if (!"INVITED".equals(admin.getStatus())) throw ApiException.conflict("The admin has already activated their account");
        return AuthService.issueInvite(admin.getId());
    }

    public boolean isSyncRunning(UUID companyId) {
        return MasterSyncService.isRunning(companyId);
    }

    public void triggerSync(UUID companyId) {
        Background.run("sa-sync", () -> MasterSyncService.sync(companyId, null, "MANUAL"));
    }
}
