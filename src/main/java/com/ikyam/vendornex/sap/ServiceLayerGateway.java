package com.ikyam.vendornex.sap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikyam.vendornex.db.Db;
import com.ikyam.vendornex.http.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SAP Business One Service Layer client (OData v3 endpoint /b1s/v1).
 *
 * <p>Session handling: POST /Login returns B1SESSION and ROUTEID cookies (ROUTEID matters when the
 * Service Layer runs behind its load balancer). The session is reused until shortly before its
 * SessionTimeout and transparently renewed on HTTP 401. One instance exists per company
 * (see {@link SapGatewayFactory}), so every company has its own B1 session.
 *
 * <p>Reads page through results with {@code Prefer: odata.maxpagesize} and follow
 * {@code odata.nextLink}.
 */
public class ServiceLayerGateway implements SapB1Gateway {

    private static final Logger log = LoggerFactory.getLogger(ServiceLayerGateway.class);
    private static final int PAGE_SIZE = Integer.parseInt(System.getenv().getOrDefault("SL_PAGE_SIZE", "100"));
    private static final int CONNECT_ATTEMPTS = 3;
    private static final String GRPO_DRAFT_OBJECT = "oPurchaseDeliveryNotes";

    private final String baseUrl;
    private final String companyDb;
    private final String username;
    private final String password;
    private final HttpClient http;

    private final ReentrantLock loginLock = new ReentrantLock();
    private volatile String cookieHeader;
    private volatile Instant sessionExpiresAt = Instant.EPOCH;
    private volatile String version;
    /** Set by SapGatewayFactory for real companies; null for throw-away gateways (connection tests). */
    private volatile UUID trackedCompanyId;
    private volatile String trackedSchemaId;

    public ServiceLayerGateway(String baseUrl, String companyDb, String username, String password, boolean verifyTls) {
        if (baseUrl == null || companyDb == null || username == null || password == null) {
            throw SapException.business("Service Layer connection is not fully configured for this company");
        }
        this.baseUrl = normalise(baseUrl);
        this.companyDb = companyDb;
        this.username = username;
        this.password = password;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .version(HttpClient.Version.HTTP_1_1);
        System.out.println("--------------2---------------");
        System.out.println(b);
        System.out.println("verifyTls"+ verifyTls);
        if (!verifyTls) {
            b.sslContext(trustAllContext());
            SSLParameters sslParams = new SSLParameters();
            sslParams.setEndpointIdentificationAlgorithm(null);
            b.sslParameters(sslParams);
        }
        this.http = b.build();
    }

    /** Accepts "https://host:50000", ".../b1s/v1" or ".../b1s/v2" and returns the v1 root. */
    static String normalise(String url) {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/b1s/v1") || u.endsWith("/b1s/v2")) return u;
        return u + "/b1s/v1";
    }

    // ================================================================ connection

    @Override
    public ConnectionInfo testConnection() {
        try {
            logout();
            invalidateSession();
            login();
            get("Warehouses", Map.of("$select", "WarehouseCode", "$top", "1"));
            return new ConnectionInfo(true, "Connected to " + companyDb, version);
        } catch (SapException e) {
            return new ConnectionInfo(false, e.getMessage(), null);
        }
    }

    private void login() {
        loginLock.lock();
        try {
            if (cookieHeader != null && Instant.now().isBefore(sessionExpiresAt)) return;
            ObjectNode body = Json.obj();
            body.put("CompanyDB", companyDb);
            body.put("UserName", username);
            body.put("Password", password);
            System.out.println(URI.create(baseUrl + "/Login"));
            
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/Login"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();
            System.out.println(req);
           try {
        	   HttpResponse<String> res = send(req);
               System.out.println("----------res-----------");
               System.out.println(res.statusCode());
               System.out.println(res.body());
               if (res.statusCode() != 200) {
                   throw new SapException("Service Layer login failed: " + errorMessage(res.body()), res.statusCode(),
                           res.statusCode() >= 500, null);
               }
               List<String> cookies = new ArrayList<>();
               for (String sc : res.headers().allValues("set-cookie")) {
                   String kv = sc.split(";", 2)[0];
                   if (kv.startsWith("B1SESSION=") || kv.startsWith("ROUTEID=")) cookies.add(kv);
               }
               JsonNode j = Json.parse(res.body());
               if (cookies.stream().noneMatch(c -> c.startsWith("B1SESSION="))) {
                   cookies.add("B1SESSION=" + j.path("SessionId").asText());
               }
               cookieHeader = String.join("; ", cookies);
               int timeoutMin = j.path("SessionTimeout").asInt(30);
               sessionExpiresAt = Instant.now().plusSeconds(Math.max(60, timeoutMin * 60L - 60));
               version = j.path("Version").asText(null);
               saveSession(j.path("SessionId").asText(null));
               log.info("Service Layer session opened for {} at {}", companyDb, baseUrl);
           }catch (Exception e) {
        	   System.out.println(e);
        	   throw e; // a failed login must reach the caller, or the real error is hidden
           }
        } finally {
            loginLock.unlock();
        }
    }

    /** Makes this gateway record each B1 session it opens in ik_vendor.b1_sessions. */
    void trackSession(UUID companyId, String schemaId) {
        this.trackedCompanyId = companyId;
        this.trackedSchemaId = schemaId;
    }

    /** One row per company: the latest SessionId returned by POST /Login. Bookkeeping only, never blocks a login. */
    private void saveSession(String sessionId) {
        UUID id = trackedCompanyId;
        if (id == null) return;
        try {
            Db.exec("""
                    INSERT INTO ik_vendor.b1_sessions(company_id, schema_id, sap_username, session_id, sap_db, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (company_id) DO UPDATE SET schema_id = EXCLUDED.schema_id, sap_username = EXCLUDED.sap_username,
                        session_id = EXCLUDED.session_id, sap_db = EXCLUDED.sap_db, expires_at = EXCLUDED.expires_at,
                        updated_at = now()""",
                    id, trackedSchemaId, username, sessionId, companyDb, java.sql.Timestamp.from(sessionExpiresAt));
        } catch (Exception e) {
            log.warn("Could not store B1 session for company {}: {}", id, e.getMessage());
        }
    }

    /** Best-effort POST /Logout so a replaced session does not stay open on the Service Layer until it times out. */
    private void logout() {
        String cookie = cookieHeader;
        if (cookie == null) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/Logout"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Cookie", cookie)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Service Layer logout skipped: {}", e.getMessage());
        }
    }

    private void invalidateSession() {
        cookieHeader = null;
        sessionExpiresAt = Instant.EPOCH;
    }

    // ================================================================ HTTP plumbing

    /**
    /**
     * Sends a request, retrying when the Service Layer refuses or drops the connection (it is often
     * briefly unavailable while heavy reads such as Items are running). A connection that never opened
     * ({@link ConnectException}) is safe to retry for any method; other I/O failures are retried for
     * GET only, because a POST may already have reached SAP.
     */
    private HttpResponse<String> send(HttpRequest req) {
        for (int attempt = 1; ; attempt++) {
            try {
                return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // java.net.http wraps the real reason (refused / timed out / unreachable / unresolved host) in the cause chain.
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                boolean retry = attempt < CONNECT_ATTEMPTS
                        && (e instanceof ConnectException || e instanceof HttpConnectTimeoutException || "GET".equals(req.method()));
                log.warn("Service Layer request to {} failed (attempt {}/{}){}", req.uri(), attempt, CONNECT_ATTEMPTS,
                        retry ? ", retrying" : "", e);
                if (!retry) {
                    throw new SapException("Cannot reach SAP B1 Service Layer at " + baseUrl + " (" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage())
                            + (root != e ? " <- " + root.getClass().getSimpleName() + ": " + root.getMessage() : "") + ")", 0, true, null);
                }
                pause(attempt * 3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SapException("Interrupted while calling Service Layer", 0, true, null);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Executes a call with the session cookie; on 401 re-logs in once and retries. */
    private HttpResponse<String> call(String method, String pathAndQuery, JsonNode body, Map<String, String> extraHeaders) {
        for (int attempt = 0; attempt < 2; attempt++) {
            login();
            String url = pathAndQuery.startsWith("http") ? pathAndQuery : baseUrl + "/" + pathAndQuery;
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(90))
                    .header("Cookie", cookieHeader)
                    .header("Accept", "application/json");
            extraHeaders.forEach(b::header);
            HttpRequest.BodyPublisher pub = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(Json.write(body));
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, pub);
            HttpResponse<String> res = send(b.build());
            if (res.statusCode() == 401 && attempt == 0) {
                invalidateSession();
                continue;
            }
            if (res.statusCode() >= 400) {
                boolean retryable = res.statusCode() >= 500 || res.statusCode() == 401 || res.statusCode() == 408 || res.statusCode() == 429;
                throw new SapException(errorMessage(res.body()), res.statusCode(), retryable, errorCode(res.body()));
            }
            return res;
        }
        throw new SapException("Service Layer session could not be re-established", 401, true, null);
    }

    private JsonNode get(String entity, Map<String, String> query) {
        HttpResponse<String> res = call("GET", entity + qs(query), null, Map.of());
        return Json.parse(res.body());
    }

    /** GET with server-driven paging, returning every row of the "value" array. */
    private List<JsonNode> getAll(String entity, Map<String, String> query) {
        List<JsonNode> out = new ArrayList<>();
        String next = entity + qs(query);
        int guard = 0;
        while (next != null && guard++ < 10_000) {
            HttpResponse<String> res = call("GET", next, null, Map.of("Prefer", "odata.maxpagesize=" + PAGE_SIZE));
            JsonNode j = Json.parse(res.body());
            j.path("value").forEach(out::add);
            String link = j.hasNonNull("odata.nextLink") ? j.get("odata.nextLink").asText()
                    : j.hasNonNull("@odata.nextLink") ? j.get("@odata.nextLink").asText() : null;
            if (link == null || link.isBlank()) {
                next = null;
            } else if (link.startsWith("http")) {
                next = link;
            } else {
                // nextLink is relative to the service root, e.g. "Items?$skip=500" or "/b1s/v1/Items?..."
                next = link.startsWith("/b1s/") ? baseUrl.substring(0, baseUrl.indexOf("/b1s/")) + link : link;
            }
        }
        return out;
    }

    private JsonNode post(String entity, JsonNode body) {
        HttpResponse<String> res = call("POST", entity, body, Map.of());
        return res.body() == null || res.body().isBlank() ? Json.obj() : Json.parse(res.body());
    }

    private static String qs(Map<String, String> q) {
        if (q == null || q.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("&", "?", "");
        new TreeMap<>(q).forEach((k, v) -> sj.add(k + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20")));
        return sj.toString();
    }

    private static String errorMessage(String body) {
        try {
            JsonNode e = Json.parse(body).path("error");
            JsonNode m = e.path("message");
            String msg = m.isTextual() ? m.asText() : m.path("value").asText(null);
            if (msg != null && !msg.isBlank()) return msg;
        } catch (Exception ignored) {
            // not JSON
        }
        return body == null || body.isBlank() ? "Service Layer error" : body.substring(0, Math.min(body.length(), 500));
    }

    private static String errorCode(String body) {
        try {
            JsonNode c = Json.parse(body).path("error").path("code");
            return c.isMissingNode() ? null : c.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String odataStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    // ================================================================ master data

    @Override
    public List<Warehouse> warehouses() {
        List<Warehouse> out = new ArrayList<>();
        for (JsonNode w : getAll("Warehouses", Map.of("$select", "WarehouseCode,WarehouseName,Inactive"))) {
            out.add(new Warehouse(w.path("WarehouseCode").asText(), w.path("WarehouseName").asText(),
                    !"tYES".equals(w.path("Inactive").asText())));
        }
        return out;
    }

    @Override
    public List<Item> items() {
        Map<Integer, String> groups = new HashMap<>();
        for (JsonNode g : getAll("ItemGroups", Map.of("$select", "Number,GroupName"))) {
            groups.put(g.path("Number").asInt(), g.path("GroupName").asText());
        }
        List<Item> out = new ArrayList<>();
        Map<String, String> q = Map.of(
                "$select", "ItemCode,ItemName,ItemsGroupCode,PurchaseUnit,InventoryUOM,AvgStdPrice,Valid,Frozen,ItemWarehouseInfoCollection",
                "$filter", "PurchaseItem eq 'tYES'");
        for (JsonNode i : getAll("Items", q)) {
            List<Stock> stock = new ArrayList<>();
            for (JsonNode w : i.path("ItemWarehouseInfoCollection")) {
                stock.add(new Stock(w.path("WarehouseCode").asText(), dec(w, "InStock"), dec(w, "Committed"), dec(w, "Ordered")));
            }
            Integer grp = i.hasNonNull("ItemsGroupCode") ? i.get("ItemsGroupCode").asInt() : null;
            boolean active = !"tNO".equals(i.path("Valid").asText("tYES")) && !"tYES".equals(i.path("Frozen").asText("tNO"));
            out.add(new Item(i.path("ItemCode").asText(), i.path("ItemName").asText(), grp, grp == null ? null : groups.get(grp),
                    text(i, "PurchaseUnit"), text(i, "InventoryUOM"), dec(i, "AvgStdPrice"), active, stock));
        }
        return out;
    }

    @Override
    public List<VendorGroup> vendorGroups() {
        List<VendorGroup> out = new ArrayList<>();
        for (JsonNode g : getAll("BusinessPartnerGroups", Map.of("$select", "Code,Name,Type", "$filter", "Type eq 'bbpgt_VendorGroup'"))) {
            out.add(new VendorGroup(g.path("Code").asInt(), g.path("Name").asText()));
        }
        return out;
    }

    @Override
    public List<BusinessPartner> vendors() {
        List<BusinessPartner> out = new ArrayList<>();
        Map<String, String> q = Map.of("$select", "CardCode,CardName,GroupCode,Valid,Frozen,FederalTaxID,EmailAddress",
                "$filter", "CardType eq 'cSupplier'");
        for (JsonNode b : getAll("BusinessPartners", q)) {
            boolean active = !"tNO".equals(b.path("Valid").asText("tYES")) && !"tYES".equals(b.path("Frozen").asText("tNO"));
            out.add(new BusinessPartner(b.path("CardCode").asText(), b.path("CardName").asText(),
                    b.hasNonNull("GroupCode") ? b.get("GroupCode").asInt() : null, active, text(b, "FederalTaxID"), text(b, "EmailAddress")));
        }
        return out;
    }

    private Map<Integer, String> departments() {
        Map<Integer, String> depts = new HashMap<>();
        try {
            for (JsonNode d : getAll("Departments", Map.of("$select", "Code,Name"))) depts.put(d.path("Code").asInt(), d.path("Name").asText());
        } catch (SapException e) {
            log.warn("Could not read Departments: {}", e.getMessage());
        }
        return depts;
    }

    @Override
    public List<Employee> employees() {
        Map<Integer, String> depts = departments();
        List<Employee> out = new ArrayList<>();
        for (JsonNode e : getAll("EmployeesInfo", Map.of("$select", "EmployeeID,FirstName,LastName,Department,eMail", "$filter", "Active eq 'tYES'"))) {
            String name = (e.path("FirstName").asText("") + " " + e.path("LastName").asText("")).trim();
            Integer dep = e.hasNonNull("Department") ? e.get("Department").asInt() : null;
            out.add(new Employee(e.path("EmployeeID").asInt(), name, dep == null ? null : depts.get(dep), text(e, "eMail")));
        }
        return out;
    }

    /** India (GST) and US localisations use SalesTaxCodes; VAT localisations use VatGroups. */
    @Override
    public List<TaxCode> taxCodes() {
        List<TaxCode> out = new ArrayList<>();
        try {
            for (JsonNode t : getAll("SalesTaxCodes", Map.of("$select", "Code,Name,Rate"))) {
                out.add(new TaxCode(t.path("Code").asText(), t.path("Name").asText(), dec(t, "Rate")));
            }
            return out;
        } catch (SapException e) {
            log.info("SalesTaxCodes not available ({}), falling back to VatGroups", e.getMessage());
        }
        for (JsonNode t : getAll("VatGroups", Map.of("$select", "Code,Name,Category", "$filter", "Category eq 'bovcInputTax'"))) {
            out.add(new TaxCode(t.path("Code").asText(), t.path("Name").asText(), null));
        }
        return out;
    }

    // ================================================================ documents

    @Override
    public List<Document> openPurchaseRequests() {
        Map<String, String> q = Map.of(
                "$select", "DocEntry,DocNum,DocDate,RequriedDate,RequesterName,RequesterDepartment,Comments,DocumentStatus,DocumentLines",
                "$filter", "DocumentStatus eq 'bost_Open'");
        Map<Integer, String> depts = departments();
        List<Document> out = new ArrayList<>();
        for (JsonNode d : getAll("PurchaseRequests", q)) {
            Document doc = toDocument(d, date(d, "RequriedDate"));
            String dept = d.hasNonNull("RequesterDepartment") ? depts.getOrDefault(d.get("RequesterDepartment").asInt(), doc.department()) : null;
            out.add(new Document(doc.docEntry(), doc.docNum(), doc.cardCode(), doc.requesterName(), dept, doc.docDate(),
                    doc.dueDate(), doc.currency(), doc.comments(), doc.status(), doc.lines()));
        }
        return out;
    }

    @Override
    public List<Document> openPurchaseOrders(LocalDate since) {
        String filter = "DocumentStatus eq 'bost_Open'" + (since == null ? "" : " and UpdateDate ge '" + since + "'");
        Map<String, String> q = Map.of(
                "$select", "DocEntry,DocNum,DocDate,DocDueDate,CardCode,DocCurrency,Comments,DocumentStatus,DocumentLines",
                "$filter", filter);
        List<Document> out = new ArrayList<>();
        for (JsonNode d : getAll("PurchaseOrders", q)) out.add(toDocument(d, date(d, "DocDueDate")));
        return out;
    }

    @Override
    public Document purchaseOrder(int docEntry) {
        JsonNode d = get("PurchaseOrders(" + docEntry + ")",
                Map.of("$select", "DocEntry,DocNum,DocDate,DocDueDate,CardCode,DocCurrency,Comments,DocumentStatus,DocumentLines"));
        return toDocument(d, date(d, "DocDueDate"));
    }

    @Override
    public String businessPartnerName(String cardCode) {
        try {
            return get("BusinessPartners(" + odataStr(cardCode) + ")", Map.of("$select", "CardCode,CardName")).path("CardName").asText(null);
        } catch (SapException e) {
            if (e.httpStatus == 404) return null;
            throw e;
        }
    }

    private Document toDocument(JsonNode d, LocalDate due) {
        List<DocLine> lines = new ArrayList<>();
        for (JsonNode l : d.path("DocumentLines")) {
            lines.add(new DocLine(l.path("LineNum").asInt(), l.path("ItemCode").asText(), l.path("ItemDescription").asText(),
                    text(l, "MeasureUnit") != null ? text(l, "MeasureUnit") : text(l, "UoMCode"),
                    dec(l, "Quantity"), l.hasNonNull("RemainingOpenQuantity") ? dec(l, "RemainingOpenQuantity") : dec(l, "Quantity"),
                    l.hasNonNull("UnitPrice") ? dec(l, "UnitPrice") : dec(l, "Price"),
                    text(l, "WarehouseCode"), text(l, "TaxCode"),
                    l.hasNonNull("RequiredDate") ? date(l, "RequiredDate") : date(l, "ShipDate")));
        }
        String dept = d.hasNonNull("RequesterDepartment") ? d.get("RequesterDepartment").asText() : null;
        return new Document(d.path("DocEntry").asInt(), d.path("DocNum").asInt(), text(d, "CardCode"), text(d, "RequesterName"), dept,
                date(d, "DocDate"), due, text(d, "DocCurrency"), text(d, "Comments"), text(d, "DocumentStatus"), lines);
    }

    // ================================================================ writes

    @Override
    public Result createBusinessPartner(ObjectNode payload) {
        JsonNode r = post("BusinessPartners", payload);
        return new Result(null, null, r.path("CardCode").asText(null), r);
    }

    @Override
    public Result createPurchaseRequest(ObjectNode payload) {
        return docResult(post("PurchaseRequests", payload));
    }

    @Override
    public Result createPurchaseOrder(ObjectNode payload) {
        return docResult(post("PurchaseOrders", payload));
    }

    @Override
    public Result createDraft(ObjectNode payload) {
        if (!payload.has("DocObjectCode")) payload.put("DocObjectCode", GRPO_DRAFT_OBJECT);
        return docResult(post("Drafts", payload));
    }

    @Override
    public Result createGoodsReceiptPo(ObjectNode payload) {
        return docResult(post("PurchaseDeliveryNotes", payload));
    }

    @Override
    public void deleteDraft(int docEntry) {
        call("DELETE", "Drafts(" + docEntry + ")", null, Map.of());
    }

    private static Result docResult(JsonNode r) {
        Integer entry = r.hasNonNull("DocEntry") ? r.get("DocEntry").asInt() : null;
        Integer num = r.hasNonNull("DocNum") ? r.get("DocNum").asInt() : null;
        return new Result(entry, num, num == null ? null : String.valueOf(num), r);
    }

    // ================================================================ small helpers

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static BigDecimal dec(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? BigDecimal.ZERO : new BigDecimal(v.asText());
    }

    private static LocalDate date(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : LocalDate.parse(s.substring(0, 10));
    }

    /**
     * Many on-premise Service Layer installs use a self-signed certificate. Prefer importing it into
     * the JVM truststore; this switch exists for test systems only (per company: sl_verify_tls = false).
     * Hostname verification is disabled alongside this trust-all context (see the sslParameters set on
     * the HttpClient.Builder above), so no JVM-wide system property is required.
     */
    private static SSLContext trustAllContext() {
        try {
            TrustManager[] tm = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
