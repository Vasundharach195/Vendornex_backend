package com.ikyam.vendornex.service;

import com.ikyam.vendornex.common.Background;
import com.ikyam.vendornex.common.Validators;
import com.ikyam.vendornex.db.Row;
import com.ikyam.vendornex.dto.CreateCompanyRequest;
import com.ikyam.vendornex.dto.SapConnectionInput;
import com.ikyam.vendornex.dto.TestConnectionRequest;
import com.ikyam.vendornex.dto.UpdateCompanyRequest;
import com.ikyam.vendornex.entity.Company;
import com.ikyam.vendornex.entity.CompanySettings;
import com.ikyam.vendornex.entity.IkConfig;
import com.ikyam.vendornex.entity.SapConnection;
import com.ikyam.vendornex.entity.User;
import com.ikyam.vendornex.http.ApiException;
import com.ikyam.vendornex.http.Json;
import com.ikyam.vendornex.repository.CompanyQueries;
import com.ikyam.vendornex.repository.CompanyRepository;
import com.ikyam.vendornex.repository.CompanySettingsRepository;
import com.ikyam.vendornex.repository.IkConfigRepository;
import com.ikyam.vendornex.repository.SapConnectionRepository;
import com.ikyam.vendornex.repository.UserRepository;
import com.ikyam.vendornex.sap.SapB1Gateway;
import com.ikyam.vendornex.sap.SapGatewayFactory;
import com.ikyam.vendornex.security.Crypto;
import com.ikyam.vendornex.tenant.TenantContext;
import com.ikyam.vendornex.tenant.Tenants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic moved out of {@code CompanyController} (Super Admin:
 * onboarding customer companies and their SAP B1 Service Layer connection).
 * SQL: entity CRUD via
 * {@link CompanyRepository}/{@link CompanySettingsRepository}/{@link UserRepository};
 * the multi-table list/detail read-model stays on {@link CompanyQueries}
 * (JdbcTemplate) per the migration's query-routing rule.
 */
@Service
public class CompanyService {

	private final CompanyRepository companies;
	private final CompanySettingsRepository companySettings;
	private final UserRepository users;
	private final SapConnectionRepository sapConnections;
	private final IkConfigRepository ikConfigs;
	private final CompanyQueries queries;

	public CompanyService(CompanyRepository companies, CompanySettingsRepository companySettings, UserRepository users, SapConnectionRepository sapConnections, IkConfigRepository ikConfigs,
			CompanyQueries queries) {
		this.companies = companies;
		this.companySettings = companySettings;
		this.users = users;
		this.sapConnections = sapConnections;
		this.ikConfigs = ikConfigs;
		this.queries = queries;
	}

	public List<Row> list() {
		List<Row> rows = queries.list();
		rows.forEach(CompanyService::exposeInvite);
		return rows;
	}

	public Row detail(UUID id) {
		Row c = queries.findById(id);
		if (c == null)
			throw ApiException.notFound("Company");
		exposeInvite(c);
		c.put("syncRuns", queries.syncRuns(id, c.str("schemaId")));
		c.put("sapConnections", queries.sapConnections(c.str("schemaId")));		return c;
	}

	private static void exposeInvite(Row c) {
		c.put("adminInviteLink", AuthService.inviteLink((String) c.remove("adminInviteToken")));
	}

	private record ConnInput(String mode, String url, String db, String user, String password, boolean verifyTls) {
	}

	private static String mode(String integrationMode, Company existing) {
		String mode = Optional.ofNullable(Json.optText(integrationMode)).orElse(existing == null ? "MOCK" : existing.getIntegrationMode());
		if (!Set.of("MOCK", "SERVICE_LAYER").contains(mode))
			throw ApiException.badRequest("integrationMode must be MOCK or SERVICE_LAYER");
		return mode;
	}

	/** Validates the SAP DBs chosen at onboarding and builds the rows for the tenant's ik_sap_connection. */
	private static List<SapConnection> connections(List<SapConnectionInput> in, String mode, String schema, UUID createdBy) {
		List<SapConnectionInput> list = in == null ? List.of() : in;
		if (list.isEmpty() && "SERVICE_LAYER".equals(mode))
			throw ApiException.badRequest("Select at least one SAP B1 company DB (sapConnections)");
		boolean anyDefault = list.stream().anyMatch(c -> Boolean.TRUE.equals(c.isDefault));
		if (list.stream().filter(c -> Boolean.TRUE.equals(c.isDefault)).count() > 1)
			throw ApiException.badRequest("Only one SAP connection can be the default");
		List<SapConnection> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < list.size(); i++) {
			SapConnectionInput c = list.get(i);
			String p = "sapConnections[" + i + "].";
			String db = Json.reqText(c.companyDbCode, p + "companyDbCode", 100);
			if (!seen.add(db.toLowerCase()))
				throw ApiException.badRequest("SAP company DB '" + db + "' is listed twice");
			String name = Json.optText(c.connectionName) == null ? db : Json.reqText(c.connectionName, p + "connectionName", 100);
			String url = Json.reqText(c.baseUrl, p + "baseUrl", 255);
			if (!url.matches("^https?://.+"))
				throw ApiException.badRequest("'" + p + "baseUrl' must start with https:// (e.g. https://b1server:50000/b1s/v1)");
			String user = Json.reqText(c.sapUsername, p + "sapUsername", 100);
			String pw = Json.reqText(c.sapPassword, p + "sapPassword", 40);
			boolean isDefault = anyDefault ? Boolean.TRUE.equals(c.isDefault) : i == 0;
			out.add(new SapConnection(schema, name, db, url, user, Crypto.encrypt(pw), isDefault,
					c.sequenceNo == null ? Integer.valueOf(i + 1) : c.sequenceNo, createdBy));
		}
		return out;
	}

	/** Validates one SAP B1 connection (URL, CompanyDB, user, password) for the unsaved connection test. */
	private static ConnInput conn(String integrationMode, String serviceLayerBaseUrl, String sapCompanyDb, String slUsername, String slPassword, Boolean slVerifyTls) {
		String mode = mode(integrationMode, null);
		String url = Json.optText(serviceLayerBaseUrl);
		String db = Json.optText(sapCompanyDb);
		String user = Json.optText(slUsername);
		String pw = Json.optText(slPassword);
		boolean verify = slVerifyTls == null || slVerifyTls;
		if ("SERVICE_LAYER".equals(mode)) {
			List<String> missing = new ArrayList<>();
			if (url == null)
				missing.add("Service Layer URL");
			if (db == null)
				missing.add("Company DB");
			if (user == null)
				missing.add("B1 user name");
			if (pw == null)
				missing.add("B1 password");
			if (!missing.isEmpty())
				throw ApiException.badRequest("For a live SAP B1 connection enter: " + String.join(", ", missing));
			if (url == null || !url.matches("^https?://.+"))
				throw ApiException
						.badRequest("Service Layer URL must start with https:// (e.g. https://b1server:50000/b1s/v1)");
		}
		return new ConnInput(mode, url, db, user, pw, verify);
	}

	@Transactional(rollbackFor = Exception.class)
	public UUID create(CreateCompanyRequest req, UUID createdBy) {
		String name = Json.reqText(req.name, "name", 200);
		String adminName = Json.reqText(req.adminName, "adminName", 150);
		String adminEmail = Validators.email(Json.reqText(req.adminEmail, "adminEmail"));
		
		// ik_onboarding_company sheet columns: all NOT NULL except the logo
		String phone = Json.reqText(req.companyPhoneNo, "companyPhoneNo", 15);
		String street = Json.reqText(req.companyStreet, "companyStreet", 255);
		String city = Json.reqText(req.companyCity, "companyCity", 255);
		String state = Json.reqText(req.companyState, "companyState", 255);
		String zip = Json.reqText(req.companyZipcode, "companyZipcode", 6);
		String website = Json.reqText(req.companyWebsite, "companyWebsite", 255);
		String headOffice = Json.reqText(req.headofficeLocation, "headofficeLocation", 255);
		String gst = Json.reqText(req.companyGst, "companyGst", 50);
		String regNo = Json.reqText(req.registrationNumber, "registrationNumber", 50);
		String userPhone = Json.reqText(req.userPhoneNo, "userPhoneNo", 15);
		String emailId = Json.reqText(req.emailId, "emailId", 100);
		String emailPwd = Json.reqText(req.emailPwd, "emailPwd", 40);
		String smtpServer = Json.reqText(req.smtpServer, "smtpServer", 100);
		String smtpPort = Json.reqText(req.smtpPort, "smtpPort", 4);
		String mode = mode(req.integrationMode, null);
		if ("SERVICE_LAYER".equals(mode) && (req.sapConnections == null || req.sapConnections.isEmpty()))
			throw ApiException.badRequest("Select at least one SAP B1 company DB (sapConnections)");
		if (users.existsByEmailIgnoreCase(adminEmail))
			throw ApiException.conflict("A user with e-mail " + adminEmail + " already exists");

		Company company = new Company();
		company.setName(name);
		company.setIndustry(Json.optText(req.industry));
		
		company.setCompanyPhoneNo(phone);
		company.setCompanyStreet(street);
		company.setCompanyCity(city);
		company.setCompanyState(state);
		company.setCompanyZipcode(zip);
		company.setCompanyWebsite(website);
		company.setHeadofficeLocation(headOffice);
		company.setCompanyGst(gst);
		company.setRegistrationNumber(regNo);
	
		company.setUserPhoneNo(userPhone);
		company.setEmailId(emailId);
		company.setEmailPwd(Crypto.encrypt(emailPwd));
		company.setSmtpServer(smtpServer);
		company.setSmtpPort(smtpPort);
		company.setCompanyLogo(logoBytes(req.companyLogo));
		company.setUserName(adminName);
		company.setEmail(adminEmail);
		company.setIntegrationMode(mode);
		company.setSlVerifyTls(req.slVerifyTls == null || req.slVerifyTls);
		// The company's own schema and tables are created in this same transaction: if
		// anything
		// below fails, the schema is rolled back together with the company row.
		String schema = Tenants.createSchema();
		company.setSchemaId(schema);
		companies.saveAndFlush(company);
		companySettings.saveAndFlush(new CompanySettings(company.getId()));

		User admin = new User();
		admin.setCompanyId(company.getId());
		admin.setName(adminName);
		admin.setEmail(adminEmail);
		admin.setRole("ADMIN");
		admin.setStatus("INVITED");
		// Saved in the company's users table; a trigger copies the row to global_users
		// for login.
		TenantContext.run(schema, () -> users.saveAndFlush(admin));

		// SAP B1 company DBs picked during onboarding. created_by is the Super Admin doing the onboarding
		// (FK → ik_vendor.global_users, so it works although they have no row in the tenant's users).
		List<SapConnection> picked = connections(req.sapConnections, mode, schema, createdBy);
		// A company on the built-in simulator has no real SAP DB; give it one simulated DB so every tenant row
		// (master data, documents) still has a connection_id to belong to.
		List<SapConnection> conns = !picked.isEmpty() ? picked : List.of(new SapConnection(schema, "Built-in simulator",
				"MOCK_" + company.getId().toString().substring(0, 8), "http://127.0.0.1/b1s/v1", "manager",
				Crypto.encrypt("manager"), true, 1, createdBy));
		TenantContext.run(schema, () -> sapConnections.saveAllAndFlush(conns));

		// ik_vendor.ik_config: mail (SMTP) settings for the new schema; SAP details live in ik_sap_connection.
		ikConfigs.saveAndFlush(new IkConfig(schema, emailId, company.getEmailPwd(), smtpServer, smtpPort, createdBy));

		return company.getId();
	}

	/**
	 * Runs after the create transaction commits — invites the admin, tests the
	 * connection, and (if it's live) kicks off the initial master-data sync.
	 * Mirrors the original sequencing.
	 */
	public Row afterCreate(UUID companyId) {
		List<User> admins = Tenants.call(companyId,
				() -> users.findByCompanyIdAndRoleOrderByCreatedAt(companyId, "ADMIN"));
		if (admins.isEmpty())
			throw new IllegalStateException("admin not found for company " + companyId);
		AuthService.issueInvite(admins.get(0).getId());
		Row conn = testStored(companyId);
	System.out.println("-----------------conn-------");
			System.out.println(conn);
		if ("OK".equals(conn.str("connectionStatus"))) {
			System.out.println("---------------------------exexyte backround0------------------------");
			Background.run("initial-sync", () -> MasterSyncService.sync(companyId, null, "MANUAL"));
		}
		return detail(companyId);
	}

	@Transactional(rollbackFor = Exception.class)
	public Row update(UUID companyId, UpdateCompanyRequest req) {
		Company company = companies.findById(companyId).orElseThrow(() -> ApiException.notFound("Company"));
		company.setName(Json.optText(req.name));
		company.setIndustry(Json.optText(req.industry));
		company.setIntegrationMode(mode(req.integrationMode, company));
		if (req.slVerifyTls != null)
			company.setSlVerifyTls(req.slVerifyTls);
		company.setConnectionStatus("NOT_TESTED");
		applyOnboardingFields(company, req);

		companies.save(company);
		SapGatewayFactory.evict(companyId);
		testStored(companyId);
		return detail(companyId);
	}

	/** Update: each onboarding-sheet field is changed only when the request carries a value. */
	private static void applyOnboardingFields(Company c, UpdateCompanyRequest r) {
		String v;
		if ((v = Json.optText(r.companyPhoneNo)) != null) c.setCompanyPhoneNo(limit(v, "companyPhoneNo", 15));
		if ((v = Json.optText(r.companyStreet)) != null) c.setCompanyStreet(limit(v, "companyStreet", 255));
		if ((v = Json.optText(r.companyCity)) != null) c.setCompanyCity(limit(v, "companyCity", 255));
		if ((v = Json.optText(r.companyState)) != null) c.setCompanyState(limit(v, "companyState", 255));
		if ((v = Json.optText(r.companyZipcode)) != null) c.setCompanyZipcode(limit(v, "companyZipcode", 6));
		if ((v = Json.optText(r.companyWebsite)) != null) c.setCompanyWebsite(limit(v, "companyWebsite", 255));
		if ((v = Json.optText(r.headofficeLocation)) != null) c.setHeadofficeLocation(limit(v, "headofficeLocation", 255));
		if ((v = Json.optText(r.companyGst)) != null) c.setCompanyGst(limit(v, "companyGst", 50));
		if ((v = Json.optText(r.registrationNumber)) != null) c.setRegistrationNumber(limit(v, "registrationNumber", 50));
		if ((v = Json.optText(r.userPhoneNo)) != null) c.setUserPhoneNo(limit(v, "userPhoneNo", 15));
		if ((v = Json.optText(r.emailId)) != null) c.setEmailId(limit(v, "emailId", 100));
		if ((v = Json.optText(r.emailPwd)) != null) c.setEmailPwd(Crypto.encrypt(limit(v, "emailPwd", 40)));
		if ((v = Json.optText(r.smtpServer)) != null) c.setSmtpServer(limit(v, "smtpServer", 100));
		if ((v = Json.optText(r.smtpPort)) != null) c.setSmtpPort(limit(v, "smtpPort", 4));
		if (Json.optText(r.companyLogo) != null) c.setCompanyLogo(logoBytes(r.companyLogo));
	}
	
	/** The API carries the logo as base64 text (a "data:image/png;base64," prefix is accepted); stored as bytes. */
	private static byte[] logoBytes(String base64) {
		String s = Json.optText(base64);
		if (s == null)
			return null;
		int comma = s.indexOf(',');
		if (s.startsWith("data:") && comma > 0)
			s = s.substring(comma + 1);
		try {
			return java.util.Base64.getMimeDecoder().decode(s);
		} catch (IllegalArgumentException e) {
			throw ApiException.badRequest("'companyLogo' must be base64-encoded image data");
		}
	}

	private static String limit(String v, String field, int max) {
		if (v.length() > max)
			throw ApiException.badRequest("'" + field + "' must be at most " + max + " characters");
		return v;
	}
	
	public Row testUnsaved(TestConnectionRequest req) {
		ConnInput ci = conn(req.integrationMode, req.serviceLayerBaseUrl, req.sapCompanyDb, req.slUsername,
				req.slPassword, req.slVerifyTls);

		System.out.println("----------------1----------------");
		System.out.println(ci);
		SapB1Gateway g = SapGatewayFactory.create(
				new SapGatewayFactory.Conn(ci.mode(), ci.url(), ci.db(), ci.user(), ci.password(), ci.verifyTls()),
				UUID.randomUUID().toString());
		System.out.println("-----------g---------------");
		System.out.println(g);
		SapB1Gateway.ConnectionInfo info = g.testConnection();
		return new Row().with("ok", info.ok()).with("message", info.message()).with("version", info.version());
	}

	/** Tests the saved connection and records the outcome on the company. */
	@Transactional(rollbackFor = Exception.class)
	public Row testStored(UUID companyId) {
		Company company = companies.findById(companyId).orElseThrow(() -> ApiException.notFound("Company"));
		System.out.println("----------------company-------------");
		System.out.println(company);
		
		SapB1Gateway.ConnectionInfo info;
		try {
			System.out.println("---------before-----------------");
			info = SapGatewayFactory.forCompany(companyId).testConnection();
			System.out.println("-------------after---------------");
		} catch (ApiException e) {
			info = new SapB1Gateway.ConnectionInfo(false, e.getMessage(), null);
		} catch (Exception e) {
			info = new SapB1Gateway.ConnectionInfo(false, e.getMessage(), null);
		}
		company.setConnectionStatus(info.ok() ? "OK" : "FAILED");
		company.setConnectionMessage(info.message());
		if (info.version() != null)
			company.setSapB1Version(info.version());
		company.setLastTestedAt(Instant.now());
		companies.save(company);
		return new Row().with("connectionStatus", company.getConnectionStatus())
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
		List<User> admins = Tenants.call(companyId,
				() -> users.findByCompanyIdAndRoleOrderByCreatedAt(companyId, "ADMIN"));
		if (admins.isEmpty())
			throw ApiException.notFound("Company admin");
		User admin = admins.get(0);
		if (!"INVITED".equals(admin.getStatus()))
			throw ApiException.conflict("The admin has already activated their account");
		return AuthService.issueInvite(admin.getId());
	}

	public boolean isSyncRunning(UUID companyId) {
		return MasterSyncService.isRunning(companyId);
	}

	public void triggerSync(UUID companyId) {
		Background.run("sa-sync", () -> MasterSyncService.sync(companyId, null, "MANUAL"));
	}
}
