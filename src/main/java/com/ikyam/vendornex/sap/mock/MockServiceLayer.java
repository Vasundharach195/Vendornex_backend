package com.ikyam.vendornex.sap.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ikyam.vendornex.http.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded SAP Business One Service Layer SIMULATOR (HTTP, /b1s/v1).
 *
 * <p>Companies in integration_mode = MOCK point their Service Layer URL at this server, so the real
 * {@link com.ikyam.vendornex.sap.ServiceLayerGateway} is used end to end — login cookies, paging,
 * error bodies and business-rule rejections behave like the real Service Layer. Each CompanyDB gets
 * its own dataset (seeded with sample masters) persisted as JSON under the data directory.
 *
 * <p>It implements only what VendorNex calls. It is a development/demo tool, not a B1 replacement.
 * Run standalone with: {@code java -cp app.jar com.ikyam.vendornex.sap.mock.MockServiceLayer 50000}
 */
public final class MockServiceLayer {

    private static final Logger log = LoggerFactory.getLogger(MockServiceLayer.class);
    public static final String USER = "manager";
    public static final String PASSWORD = "manager";

    private final Path storeDir;
    private final Map<String, ObjectNode> dbs = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private int port;

    private record Session(String companyDb, Instant expires) {}

    private static final class SlError extends RuntimeException {
        final int status;
        final int code;

        SlError(int status, int code, String msg) {
            super(msg);
            this.status = status;
            this.code = code;
        }
    }

    public MockServiceLayer(Path storeDir) {
        this.storeDir = storeDir;
    }

    public static void main(String[] args) throws Exception {
        int p = args.length > 0 ? Integer.parseInt(args[0]) : 50000;
        new MockServiceLayer(Path.of("./data/mock-service-layer")).start("0.0.0.0", p);
        log.info("Mock Service Layer listening on http://localhost:{}/b1s/v1 (user {}/{})", p, USER, PASSWORD);
    }

    public void start(String host, int port) throws IOException {
        Files.createDirectories(storeDir);
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/b1s/v1/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        this.port = server.getAddress().getPort();
    }

    public int port() { return port; }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ================================================================= request dispatch

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath().substring("/b1s/v1/".length());
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        try {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("Login") && method.equals("POST")) {
                login(ex, Json.parse(body));
                return;
            }
            Session s = session(ex);
            ObjectNode db = db(s.companyDb());
            if (path.equals("Logout")) {
                sessions.values().remove(s);
                respond(ex, 204, null);
                return;
            }
            Matcher key = Pattern.compile("^([A-Za-z]+)\\((.+)\\)$").matcher(path);
            String entity = key.matches() ? key.group(1) : path;
            String id = key.matches() ? key.group(2).replace("'", "") : null;
            Object result;
            synchronized (db) {
                result = switch (method) {
                    case "GET" -> id == null ? list(ex, db, entity, q) : getOne(db, entity, id);
                    case "POST" -> create(db, s.companyDb(), entity, (ObjectNode) Json.parse(body));
                    case "DELETE" -> { delete(db, s.companyDb(), entity, id); yield null; }
                    default -> throw new SlError(405, -1, "Method not supported");
                };
            }
            respond(ex, method.equals("POST") ? 201 : (result == null ? 204 : 200), result);
        } catch (SlError e) {
            ObjectNode err = Json.obj();
            ObjectNode inner = err.putObject("error");
            inner.put("code", e.code);
            inner.putObject("message").put("lang", "en-us").put("value", e.getMessage());
            respond(ex, e.status, err);
        } catch (Exception e) {
            log.error("Mock Service Layer failure", e);
            ObjectNode err = Json.obj();
            err.putObject("error").put("code", -1).putObject("message").put("lang", "en-us").put("value", "Internal error: " + e.getMessage());
            respond(ex, 500, err);
        }
    }

    private void login(HttpExchange ex, JsonNode b) throws IOException {
        String dbName = b.path("CompanyDB").asText("");
        if (!USER.equals(b.path("UserName").asText()) || !PASSWORD.equals(b.path("Password").asText())) {
            throw new SlError(401, -304, "Fail to get DB Credentials from SLD server");
        }
        if (dbName.isBlank()) throw new SlError(400, -1, "Invalid CompanyDB");
        db(dbName);
        String sid = UUID.randomUUID().toString();
        sessions.put(sid, new Session(dbName, Instant.now().plusSeconds(30 * 60)));
        ex.getResponseHeaders().add("Set-Cookie", "B1SESSION=" + sid + "; HttpOnly; Path=/b1s");
        ex.getResponseHeaders().add("Set-Cookie", "ROUTEID=.node1; Path=/b1s");
        ObjectNode r = Json.obj();
        r.put("odata.metadata", "/b1s/v1/$metadata#B1Sessions/@Element");
        r.put("SessionId", sid);
        r.put("Version", "1000230");
        r.put("SessionTimeout", 30);
        respond(ex, 200, r);
    }

    private Session session(HttpExchange ex) {
        String cookie = Optional.ofNullable(ex.getRequestHeaders().getFirst("Cookie")).orElse("");
        Matcher m = Pattern.compile("B1SESSION=([^;]+)").matcher(cookie);
        Session s = m.find() ? sessions.get(m.group(1)) : null;
        if (s == null || s.expires().isBefore(Instant.now())) throw new SlError(401, 301, "Invalid session or session already timeout.");
        return s;
    }

    // ================================================================= reads

    private Object list(HttpExchange ex, ObjectNode db, String entity, Map<String, String> q) {
        ArrayNode src = collection(db, entity);
        List<JsonNode> rows = new ArrayList<>();
        src.forEach(r -> { if (matches(r, q.get("$filter"))) rows.add(r); });
        int skip = q.containsKey("$skip") ? Integer.parseInt(q.get("$skip")) : 0;
        int top = q.containsKey("$top") ? Integer.parseInt(q.get("$top")) : Integer.MAX_VALUE;
        int page = 20;
        String prefer = ex.getRequestHeaders().getFirst("Prefer");
        if (prefer != null && prefer.contains("odata.maxpagesize=")) page = Integer.parseInt(prefer.replaceAll(".*odata.maxpagesize=(\\d+).*", "$1"));
        int end = Math.min(rows.size(), Math.min(skip + page, top == Integer.MAX_VALUE ? Integer.MAX_VALUE : skip + top));
        ObjectNode out = Json.obj();
        ArrayNode value = out.putArray("value");
        for (int i = skip; i < end; i++) value.add(rows.get(i));
        if (end < rows.size() && end - skip >= page && top == Integer.MAX_VALUE) {
            Map<String, String> nq = new TreeMap<>(q);
            nq.put("$skip", String.valueOf(end));
            StringJoiner sj = new StringJoiner("&");
            nq.forEach((k, v) -> sj.add(k + "=" + v.replace(" ", "%20").replace("'", "%27")));
            out.put("odata.nextLink", entity + "?" + sj);
        }
        return out;
    }

    private Object getOne(ObjectNode db, String entity, String id) {
        JsonNode r = find(collection(db, entity), keyField(entity), id);
        if (r == null) throw new SlError(404, -2028, "No matching records found (ODBC -2028)");
        return r;
    }

    /** Supports the filter shapes VendorNex sends: clauses joined by "and", ops eq / ne / ge / le. */
    private static boolean matches(JsonNode row, String filter) {
        if (filter == null || filter.isBlank()) return true;
        for (String clause : filter.split("\\s+and\\s+")) {
            Matcher m = Pattern.compile("^\\s*(\\w+)\\s+(eq|ne|ge|le)\\s+'?([^']*)'?\\s*$").matcher(clause);
            if (!m.matches()) continue;
            String actual = row.path(m.group(1)).asText("");
            String want = m.group(3);
            int cmp = actual.compareTo(want);
            boolean ok = switch (m.group(2)) {
                case "eq" -> actual.equals(want);
                case "ne" -> !actual.equals(want);
                case "ge" -> cmp >= 0;
                default -> cmp <= 0;
            };
            if (!ok) return false;
        }
        return true;
    }

    // ================================================================= writes

    private Object create(ObjectNode db, String dbName, String entity, ObjectNode p) {
        Object out = switch (entity) {
            case "BusinessPartners" -> createBp(db, p);
            case "PurchaseRequests" -> createDoc(db, "PurchaseRequests", p, false);
            case "PurchaseOrders" -> createDoc(db, "PurchaseOrders", p, true);
            case "Drafts" -> createDoc(db, "Drafts", p, true);
            case "PurchaseDeliveryNotes" -> createDoc(db, "PurchaseDeliveryNotes", p, true);
            default -> throw new SlError(400, -1, "Resource not found for the segment '" + entity + "'");
        };
        persist(dbName, db);
        return out;
    }

    private void delete(ObjectNode db, String dbName, String entity, String id) {
        if (!"Drafts".equals(entity)) throw new SlError(405, -1, "Delete not supported for " + entity);
        ArrayNode drafts = collection(db, "Drafts");
        for (int i = 0; i < drafts.size(); i++) {
            if (drafts.get(i).path("DocEntry").asText().equals(id)) {
                drafts.remove(i);
                persist(dbName, db);
                return;
            }
        }
        throw new SlError(404, -2028, "No matching records found (ODBC -2028)");
    }

    private ObjectNode createBp(ObjectNode db, ObjectNode p) {
        String name = p.path("CardName").asText("");
        if (name.isBlank()) throw new SlError(400, -5002, "Enter BP name");
        if (!"cSupplier".equals(p.path("CardType").asText())) throw new SlError(400, -5002, "Only supplier BPs are supported by the simulator");
        if (p.hasNonNull("GroupCode") && find(collection(db, "BusinessPartnerGroups"), "Code", p.get("GroupCode").asText()) == null) {
            throw new SlError(400, -5002, "Invalid BP group code " + p.get("GroupCode").asText());
        }
        String code;
        if (p.hasNonNull("Series") && !p.hasNonNull("CardCode")) {
            ObjectNode counters = (ObjectNode) db.get("counters");
            int next = counters.path("bpSeriesNext").asInt(30001);
            counters.put("bpSeriesNext", next + 1);
            code = "S" + next;
        } else {
            code = p.path("CardCode").asText("");
            if (code.isBlank()) throw new SlError(400, -5002, "Enter BP code or select a numbering series");
            if (code.length() > 15) throw new SlError(400, -5002, "BP code is limited to 15 characters");
        }
        if (find(collection(db, "BusinessPartners"), "CardCode", code) != null) {
            throw new SlError(400, -10, "1320000140 - Business partner code '" + code + "' already assigned to a business partner; enter a unique business partner code");
        }
        for (JsonNode a : p.path("BPAddresses")) {
            String gstin = a.path("GSTIN").asText("");
            if (!gstin.isEmpty() && !gstin.matches("\\d{2}[A-Z]{5}\\d{4}[A-Z][A-Z0-9]Z[A-Z0-9]")) {
                throw new SlError(400, -5002, "Invalid GSTIN '" + gstin + "' on address " + a.path("AddressName").asText());
            }
        }
        ObjectNode bp = p.deepCopy();
        bp.put("CardCode", code);
        bp.put("Valid", "tYES");
        bp.put("Frozen", "tNO");
        bp.put("CreateDate", LocalDate.now().toString());
        collection(db, "BusinessPartners").add(bp);
        return bp;
    }

    private ObjectNode createDoc(ObjectNode db, String entity, ObjectNode p, boolean needsVendor) {
        boolean draft = "Drafts".equals(entity);
        boolean grpo = "PurchaseDeliveryNotes".equals(entity) || (draft && "oPurchaseDeliveryNotes".equals(p.path("DocObjectCode").asText()));
        if (needsVendor) {
            JsonNode bp = find(collection(db, "BusinessPartners"), "CardCode", p.path("CardCode").asText(""));
            if (bp == null) throw new SlError(400, -5002, "Invalid BP code '" + p.path("CardCode").asText("") + "'");
            if ("tYES".equals(bp.path("Frozen").asText()) || "tNO".equals(bp.path("Valid").asText())) {
                throw new SlError(400, -10, "Business partner '" + bp.path("CardCode").asText() + "' is inactive");
            }
        }
        if ("PurchaseOrders".equals(entity) && !p.hasNonNull("DocDueDate")) throw new SlError(400, -5002, "Enter delivery date (DocDueDate)");
        JsonNode lines = p.path("DocumentLines");
        if (!lines.isArray() || lines.isEmpty()) throw new SlError(400, -5002, "Document must contain at least one row");
        ObjectNode doc = p.deepCopy();
        ArrayNode outLines = doc.putArray("DocumentLines");
        int n = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<Runnable> afterCommit = new ArrayList<>();
        for (JsonNode l : lines) {
            ObjectNode line = ((ObjectNode) l).deepCopy();
            BigDecimal qty = new BigDecimal(l.path("Quantity").asText("0"));
            if (qty.signum() <= 0) throw new SlError(400, -5002, "Row " + (n + 1) + ": quantity must be positive");
            JsonNode base = null;
            if (l.hasNonNull("BaseEntry")) {
                int baseType = l.path("BaseType").asInt();
                String baseEntity = baseType == 22 ? "PurchaseOrders" : baseType == 1470000113 ? "PurchaseRequests" : null;
                if (baseEntity == null) throw new SlError(400, -5002, "Unsupported BaseType " + baseType);
                JsonNode bdoc = find(collection(db, baseEntity), "DocEntry", l.path("BaseEntry").asText());
                if (bdoc == null) throw new SlError(400, -5002, "Base document " + l.path("BaseEntry").asText() + " not found");
                if (!"bost_Open".equals(bdoc.path("DocumentStatus").asText())) throw new SlError(400, -10, "Base document is already closed");
                base = find((ArrayNode) bdoc.path("DocumentLines"), "LineNum", l.path("BaseLine").asText());
                if (base == null) throw new SlError(400, -5002, "Base line " + l.path("BaseLine").asText() + " not found");
                BigDecimal open = new BigDecimal(base.path("RemainingOpenQuantity").asText("0"));
                if (grpo && qty.compareTo(open) > 0) {
                    throw new SlError(400, -10, "Row " + (n + 1) + ": quantity " + qty.toPlainString() + " exceeds open quantity " + open.toPlainString() + " on the base purchase order");
                }
                line.put("ItemCode", base.path("ItemCode").asText());
                if (!line.hasNonNull("UnitPrice")) line.put("UnitPrice", base.path("UnitPrice").asText("0"));
                if (!line.hasNonNull("WarehouseCode")) line.put("WarehouseCode", base.path("WarehouseCode").asText());
                if (grpo && !draft) {
                    ObjectNode baseLine = (ObjectNode) base;
                    ObjectNode baseDoc = (ObjectNode) bdoc;
                    afterCommit.add(() -> reduceOpen(baseDoc, baseLine, qty));
                }
            }
            JsonNode item = find(collection(db, "Items"), "ItemCode", line.path("ItemCode").asText(""));
            if (item == null) throw new SlError(400, -5002, "Row " + (n + 1) + ": invalid item code '" + line.path("ItemCode").asText("") + "'");
            String wh = line.path("WarehouseCode").asText("");
            if (wh.isEmpty() || find(collection(db, "Warehouses"), "WarehouseCode", wh) == null) {
                throw new SlError(400, -5002, "Row " + (n + 1) + ": invalid warehouse code '" + wh + "'");
            }
            if (line.hasNonNull("TaxCode") && find(collection(db, "SalesTaxCodes"), "Code", line.get("TaxCode").asText()) == null) {
                throw new SlError(400, -5002, "Row " + (n + 1) + ": invalid tax code '" + line.get("TaxCode").asText() + "'");
            }
            line.put("LineNum", n++);
            line.put("ItemDescription", item.path("ItemName").asText());
            line.put("MeasureUnit", item.path("PurchaseUnit").asText());
            line.put("RemainingOpenQuantity", qty);
            line.put("LineStatus", "bost_Open");
            BigDecimal price = new BigDecimal(line.path("UnitPrice").asText("0"));
            total = total.add(price.multiply(qty));
            outLines.add(line);
        }
        ObjectNode counters = (ObjectNode) db.get("counters");
        int entry = counters.path(entity + "Entry").asInt(1) ;
        int num = counters.path(entity + "Num").asInt(1);
        counters.put(entity + "Entry", entry + 1);
        counters.put(entity + "Num", num + 1);
        doc.put("DocEntry", entry);
        doc.put("DocNum", num);
        if (!doc.hasNonNull("DocDate")) doc.put("DocDate", LocalDate.now().toString());
        doc.put("UpdateDate", LocalDate.now().toString());
        doc.put("DocumentStatus", "bost_Open");
        doc.put("DocTotal", total);
        if (!doc.hasNonNull("DocCurrency")) doc.put("DocCurrency", "INR");
        afterCommit.forEach(Runnable::run);
        collection(db, entity).add(doc);
        return doc;
    }

    private static void reduceOpen(ObjectNode doc, ObjectNode line, BigDecimal qty) {
        BigDecimal open = new BigDecimal(line.path("RemainingOpenQuantity").asText("0")).subtract(qty).max(BigDecimal.ZERO);
        line.put("RemainingOpenQuantity", open);
        if (open.signum() == 0) line.put("LineStatus", "bost_Close");
        boolean allClosed = true;
        for (JsonNode l : doc.path("DocumentLines")) if (!"bost_Close".equals(l.path("LineStatus").asText())) allClosed = false;
        if (allClosed) doc.put("DocumentStatus", "bost_Close");
        doc.put("UpdateDate", LocalDate.now().toString());
    }

    // ================================================================= storage + helpers

    private static String keyField(String entity) {
        return switch (entity) {
            case "BusinessPartners" -> "CardCode";
            case "Items" -> "ItemCode";
            case "Warehouses" -> "WarehouseCode";
            default -> "DocEntry";
        };
    }

    private static ArrayNode collection(ObjectNode db, String entity) {
        JsonNode c = db.get(entity);
        if (c == null || !c.isArray()) throw new SlError(404, -1, "Resource not found for the segment '" + entity + "'");
        return (ArrayNode) c;
    }

    private static JsonNode find(ArrayNode arr, String field, String value) {
        for (JsonNode n : arr) if (n.path(field).asText().equals(value)) return n;
        return null;
    }

    private ObjectNode db(String name) {
        return dbs.computeIfAbsent(name, n -> {
            Path f = storeDir.resolve(safe(n) + ".json");
            try {
                if (Files.exists(f)) return (ObjectNode) Json.parse(Files.readString(f));
            } catch (IOException e) {
                log.warn("Could not read mock store {}, reseeding", f, e);
            }
            ObjectNode seeded = MockSeedData.build();
            persist(n, seeded);
            return seeded;
        });
    }

    private void persist(String name, ObjectNode db) {
        try {
            Files.writeString(storeDir.resolve(safe(name) + ".json"), Json.write(db));
        } catch (IOException e) {
            log.warn("Could not persist mock store for {}", name, e);
        }
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) return q;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            q.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static void respond(HttpExchange ex, int status, Object body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        byte[] b = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json;odata=minimalmetadata;charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
