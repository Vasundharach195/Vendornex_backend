# VendorNex Backend — Technical Document (Java / Spring Boot)

> Scope: the Java Spring Boot backend in `backend/` only. Frontend, infrastructure and SAP configuration are out of scope.

---

## 1. What this application does

VendorNex is a **vendor management portal for SAP Business One (B1)**. The backend is a REST API that:

- onboards **vendors** (a 4-step wizard plus document upload) and puts them through an approval chain;
- lets **requesters** raise **Purchase Requests (PRs)** that get approved;
- turns approved PR lines into **RFQs** (request for quotation), collects vendor **quotations**, compares them and **awards** lines;
- creates **Purchase Orders (POs)** in SAP B1; vendors **acknowledge** them and send **ASNs** (shipment notices);
- turns ASNs into **GRPO** (Goods Receipt PO) drafts in B1, which a buyer confirms and posts;
- scores vendors on a **scorecard** (on-time delivery, quality, price variance);
- keeps a local copy of SAP master data (items, warehouses, tax codes and so on) in sync.

It is **multi-tenant**: one deployment serves many customer companies. Each company connects to its own SAP B1 Service Layer.

---

## 2. Technology stack

| Area | Technology | Where |
|---|---|---|
| Language | Java 21 (records, pattern matching, virtual threads) | `pom.xml` |
| Framework | Spring Boot 3.3.4 (`spring-boot-starter-web`) | `pom.xml` |
| Web server | Embedded Tomcat, virtual threads on | `application.properties` |
| Persistence | Spring Data JPA / Hibernate, plus `JdbcTemplate` and a small custom JDBC helper (`Db`) | `db/`, `*/repository/` |
| Database | PostgreSQL, reached through **pgbouncer** (transaction pooling) | `AppConfig`, `DataSourceConfig` |
| Connection pool | HikariCP | `DataSourceConfig`, `Db` |
| DB migrations | Custom runner (Flyway-style `V1__init.sql`) | `db/Migrator.java` |
| JSON | Jackson (one shared, customised `ObjectMapper`) | `http/Json.java`, `JacksonConfig` |
| Auth | Custom HS256 JWT + BCrypt passwords | `security/` |
| Encryption | AES-256-GCM for stored SAP passwords | `security/Crypto.java` |
| SAP integration | Java `HttpClient` against the SAP B1 Service Layer (OData) | `sap/` |
| Build / package | Maven, Spring Boot repackage → `vendornex-api.jar` | `pom.xml` |
| Container | Multi-stage Docker (Maven build → Temurin 21 JRE) | `Dockerfile` |

Spring Security is **not** used. Authentication and role checks are done by a custom interceptor (see section 6).

---

## 3. Project structure

All code is under `src/main/java/com/ikyam/vendornex/`.

The code is organised **by layer**: one package per layer, shared by all features.

```
VendornexApplication.java     ← main class (@SpringBootApplication)

controller/    ALL @RestController classes — HTTP only
               ApprovalController, AuthController, CompanyController, DashboardController,
               HealthController, MasterDataController, PurchaseOrderController,
               PurchaseRequestController, RfqController, ScorecardController,
               SettingsController, UserController, VendorController
service/       ALL business logic
               ApprovalService, AuthService, CompanyService, MasterDataService,
               PurchaseRequestService, SettingsService, UserService, VendorService,
               SyncService (SAP outbox), MasterSyncService (SAP pull), PoStatus,
               DocumentStore (file storage), Fields (vendor wizard fields),
               VendorLifecycle (legacy static vendor activation, used by SyncService)
repository/    ALL database access
               *Repository = Spring Data JPA interfaces, *Queries = JdbcTemplate SQL
dto/           ALL request bodies (Create…Request, Update…Request, Set…Request)
entity/        ALL JPA @Entity classes (+ *Id composite-key classes)

config/        App settings, DataSource, Jackson, startup sequence, scheduled jobs
web/           Auth interceptor, @Roles annotation, CORS, global exception handler, helpers
security/      JWT, BCrypt, AES crypto, Role enum, CurrentUser
http/          ApiException (error → HTTP status), Json helpers
db/            Db (legacy JDBC helper), Migrator, Row, row mapper, parameter binding
common/        Settings lookup, document numbers, validators (GSTIN/PAN/IFSC), background tasks
sap/           SAP B1 gateway interface, Service Layer client, payload builders
sap/mock/      Embedded SAP Service Layer simulator (for dev/demo)
seed/          Demo data loader
```

Features and their main classes:

| Feature | Controller | Service | Repository / Entity |
|---|---|---|---|
| Companies (Super Admin) | `CompanyController` | `CompanyService` | `CompanyRepository`, `CompanyQueries` / `Company`, `CompanySettings` |
| Settings | `SettingsController` | `SettingsService` | `SettingsQueries` |
| Users & requesters | `UserController` | `UserService` | `UserRepository`, `UserQueries` / `User`, `LoginActivity` |
| Vendors | `VendorController` | `VendorService` | `VendorRepository`, `VendorQueries` / `Vendor`, `VendorDocument` |
| Approvals | `ApprovalController` | `ApprovalService` | `ApprovalStepRepository`, `ApprovalQueries` / `ApprovalStep` |
| SAP master data | `MasterDataController` | `MasterDataService` | `Sap*Repository`, `MasterDataQueries` / `Sap*` |
| Purchase requests | `PurchaseRequestController` | `PurchaseRequestService` | `PurchaseRequestRepository`, `PurchaseRequestQueries` / `PurchaseRequest`, `PurchaseRequestLine` |
| RFQs | `RfqController` | — (logic still in controller) | — |
| POs, ASNs, GRPOs | `PurchaseOrderController` | `PoStatus` (rest still in controller) | — |
| Scorecard, dashboards | `ScorecardController`, `DashboardController` | — (logic in controller) | `ScorecardQueries`, `DashboardQueries` |
| Login | `AuthController` | `AuthService` | `AuthQueries` |

Resources:

```
src/main/resources/application.properties
src/main/resources/db/migration/V1__init.sql     ← full database schema
```

### 3.1 What goes in each layer

| Package | Rule | Example |
|---|---|---|
| `controller` | HTTP only: route, `@Roles`, read params/body, call one service method, return the result. No SQL, no business rules. | `PurchaseRequestController` |
| `service` | Business rules, validation, `@Transactional`, calls to SAP sync. | `PurchaseRequestService` |
| `repository` | Every query. JPA interface for simple CRUD/locks, `*Queries` class for SQL. | `PurchaseRequestRepository`, `PurchaseRequestQueries` |
| `dto` | Request bodies, plain fields (Lombok `@Data`). | `CreatePurchaseRequestRequest` |
| `entity` | Table mappings (Lombok `@Getter @Setter @Builder`). | `PurchaseRequest` |

The code is **still being migrated** from a hand-written style (static `Db` calls inside controllers) to this layering. `RfqController`, `PurchaseOrderController`, `ScorecardController`, `DashboardController` and `AuthController` still contain SQL and logic; `SyncService`, `MasterSyncService`, `AuthService`, `PoStatus` and `VendorLifecycle` are static classes that use `Db`. Everything else follows the rules above.

**Lombok** is used in the newer code (the purchase-request feature first): `@Getter @Setter @Builder @NoArgsConstructor(access = PROTECTED) @AllArgsConstructor` on entities (never `@Data` on entities), `@Data` on DTOs, `@RequiredArgsConstructor` for constructor injection in controllers, services and query classes. VS Code (Java extension) supports Lombok out of the box; Eclipse/IntelliJ need the Lombok plugin.

### 3.2 When to use JPA vs JdbcTemplate

The code follows a clear rule (documented in `VendorQueries` and `Vendor`):

- **JPA entity + `JpaRepository`** for simple creates, status changes and row locks. Example: `VendorRepository.lockByIdAndCompanyId(...)` uses `@Lock(PESSIMISTIC_WRITE)`.
- **`JdbcTemplate` query classes (`*Queries`)** for wide "give me the whole row for the screen" reads, joins, reports and SQL that JPA cannot express (for example correlated subqueries). Results are mapped by `GenericRowMapper` into a `Row`.

`Row` is a `LinkedHashMap<String,Object>` whose keys are the column names in camelCase (`legal_name` → `legalName`). Controllers return it directly as JSON, so API responses keep the same shape whichever path loaded the data.

---

## 4. Application startup

Startup order matters. Spring guarantees it through bean dependencies:

```
1. DataSourceConfig.dataSource()
      └─ Migrator.migrate()          runs SQL migrations over a DIRECT Postgres connection
      └─ builds HikariDataSource     (points at pgbouncer)
2. Hibernate starts                   ddl-auto=validate → checks entities match the schema
3. StartupConfig.@PostConstruct       (depends on DataSource, so runs after migrations)
      ├─ Crypto.init                  loads AES key
      ├─ AuthService.init             creates JWT signer
      ├─ Db.init                      second Hikari pool for the legacy Db helper
      ├─ starts MockServiceLayer      if MOCK_SL_ENABLED=true (default)
      └─ DemoSeeder.seedIfEmpty       if SEED_DEMO=true (default)
4. Tomcat starts accepting requests
5. SchedulingConfig jobs begin (after their initial delays)
```

Key points:

- **Hibernate never creates or changes tables.** `spring.jpa.hibernate.ddl-auto=validate`. The schema is owned by `V1__init.sql`.
- **Migrations** are listed in `Migrator.MIGRATIONS`. Each file's SHA-256 checksum is stored in `schema_migrations`. If an applied file is edited, startup fails. A Postgres advisory lock stops two instances migrating at once. **To change the schema, add a new file (e.g. `V2__add_x.sql`) and add it to the list. Never edit `V1__init.sql`.**
- There are currently **two connection pools** (`vendornex-jpa` for Spring/JPA, `vendornex-pgbouncer` for `Db`). The second one goes away when the migration to Spring layering is finished. **When a Spring transaction is active, `Db` calls join it** (`Db.bindSpringDataSource`), so legacy helpers such as `DocNumbers`, `Settings` and `SyncService.enqueue` called from a `@Transactional` service see its uncommitted rows and commit or roll back with it. Flush JPA changes (`saveAndFlush`) before calling a `Db` helper that reads them.

---

## 5. Configuration

All settings come from **environment variables**, read once by `config/AppConfig.java`. The defaults are for local development only.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:6432/vendornex?prepareThreshold=0` | App connection (via pgbouncer) |
| `MIGRATION_DB_URL` | `jdbc:postgresql://localhost:5432/vendornex` | Direct Postgres, used only for migrations |
| `DB_USER` / `DB_PASSWORD` | `vendornex` / `vendornex` | DB credentials |
| `DB_POOL_SIZE` | `20` | Max connections per pool |
| `HTTP_PORT` | `8080` | Server port |
| `JWT_SECRET` | dev value | JWT signing key, **at least 32 chars** |
| `JWT_TTL_MINUTES` | `480` | Login session length (8 h) |
| `APP_SECRET_KEY` | empty → dev key | Base64 32-byte AES key for SAP passwords. **Must be set in production.** |
| `DOCUMENT_STORE_DIR` | `./data/documents` | Where uploaded vendor documents are saved |
| `PUBLIC_APP_URL` | `http://localhost:5173` | Frontend URL, used to build invite links |
| `CORS_ORIGIN` | `*` | Allowed browser origin |
| `SEED_DEMO` | `true` | Load demo data into an empty DB |
| `SCHEDULER_ENABLED` | `true` | Turn background jobs on/off |
| `MOCK_SL_ENABLED` | `true` | Start the embedded SAP simulator |
| `MOCK_SL_PORT` | `0` (random) | Simulator port |

`application.properties` holds only Spring settings: port, virtual threads, JPA `validate`, `open-in-view=false`, UTC time zone, and "404 for unknown URLs".

> **Why `prepareThreshold=0`?** pgbouncer runs in *transaction pooling* mode, so a connection is shared between clients after each transaction. Server-side prepared statements and session state (`SET`, advisory locks, temp tables) would break. That is also why migrations use a direct connection.

---

## 6. Security

### 6.1 Roles

Defined in `security/Role.java`:

| Role | Who | Company? |
|---|---|---|
| `SUPER_ADMIN` | Platform operator; creates customer companies | None |
| `ADMIN` | Customer's procurement admin | Yes |
| `APPROVER` | Approves at one or more stages: `FINANCE`, `PROCUREMENT`, `COMPLIANCE` | Yes |
| `REQUESTER` | Raises purchase requests | Yes |
| `VENDOR` | Supplier portal user, linked to one vendor | Yes |

The database enforces this: a `SUPER_ADMIN` has no company, and a `VENDOR` user must have a `vendor_id`.

### 6.2 How a request is authenticated

```
Client ──► Authorization: Bearer <JWT>
             │
             ▼
   AuthInterceptor.preHandle()         (web/AuthInterceptor.java, runs on /api/**)
     1. Does the controller method have @Roles?  No → public endpoint, continue.
     2. AuthService.authenticate(header)
          - verify JWT signature (HS256) and expiry
          - reload the user from DB on EVERY request
          - reject if user / company / vendor is not active
     3. Is the user's role in @Roles(...)?   No → 403
     4. Store CurrentUser on the request
             │
             ▼
   Controller method receives CurrentUser as a parameter
   (resolved by CurrentUserArgumentResolver)
```

Example:

```java
@GetMapping("/api/vendors")
@Roles(Role.ADMIN)                          // only ADMIN may call this
public Object list(@RequestParam(required = false) String status,
                   @RequestParam(required = false) String q,
                   CurrentUser u) {         // injected automatically
    return service.list(u.company(), QueryParams.orNull(status), QueryParams.orNull(q));
}
```

Rules to remember:

- **No `@Roles` = public.** Only login, invite and health endpoints are public.
- Because the user is reloaded each time, **disabling a user or company takes effect immediately**, even if they still hold a valid token.
- **Tenant isolation:** always get the company from `u.company()`, never from the request body or URL. `u.company()` throws 403 for a Super Admin, and `u.vendor()` throws 403 for non-vendor users.

### 6.3 Other security pieces

| Piece | Detail |
|---|---|
| Passwords | BCrypt (cost 11). Policy: 8+ characters, at least one letter and one digit (`Passwords.validate`). |
| JWT | Hand-written HS256 (`Jwt.java`). Claims: `sub` (user id), `iat`, `exp`, `iss=vendornex`. |
| Invitations | Nobody sets another person's password. New users are `INVITED` with a random 7-day token; they open `PUBLIC_APP_URL/accept-invite/<token>` and choose a password. No email is sent — the admin shares the link. |
| Login audit | Every login, failed login, logout and invite acceptance goes to `login_activity` with IP and user agent. |
| SAP passwords | Stored encrypted with AES-256-GCM (`Crypto.encrypt/decrypt`). Changing `APP_SECRET_KEY` makes old values unreadable. |
| Separation of duties | `CurrentUser.canActOnStage()` — only holders of a stage can act on it. A requester cannot approve their own PR. |
| Document storage | Files are stored under `company/vendor/uuid` and paths are checked, so one tenant cannot reach another tenant's files. |

---

## 7. Error handling

Business code throws `ApiException` with an HTTP status:

```java
throw ApiException.notFound("Vendor");            // 404 {"error":"Vendor not found"}
throw ApiException.badRequest("'email' is required");
throw ApiException.conflict("This step has already been actioned");
```

`web/GlobalExceptionHandler.java` (`@RestControllerAdvice`) turns every exception into the same JSON shape:

```json
{ "error": "human-readable message" }
```

| Exception | Status |
|---|---|
| `ApiException` | its own status (400/401/403/404/409/410/413/502…) |
| DB unique violation (`23505`) | 409 |
| DB check / foreign key / bad value (`23514`, `23503`, `22P02`) | 400 |
| Malformed JSON body | 400 |
| Unknown URL | 404 |
| Wrong HTTP method | 405 |
| Anything else | 500 (logged with stack trace) |

Small helpers in `web/`:

- `Ids.uuid(id)` — a malformed id in the URL gives **404**, not 400.
- `QueryParams.orNull / clampInt` — trims blanks, clamps paging values.
- `Bodies.readBytes(req, max)` — reads raw upload bodies with a size limit (413 if too big).
- `Json.reqText / optDec / reqDate ...` — validate request fields with consistent error messages.

---

## 8. Database

The full schema is in `src/main/resources/db/migration/V1__init.sql`. Status values are `VARCHAR` + `CHECK` constraints (not Postgres enums), so they are easy to extend. Almost every table has a `company_id` column for tenant isolation.

### 8.1 Tables by area

| Area | Tables |
|---|---|
| Tenants & config | `companies`, `company_settings`, `doc_sequences` |
| Users | `users`, `user_approval_stages`, `login_activity` |
| Vendors | `vendors`, `vendor_documents` |
| Approvals | `approval_steps` |
| Purchase requests | `purchase_requests`, `purchase_request_lines` |
| RFQ | `rfqs`, `rfq_lines`, `rfq_line_sources`, `rfq_vendors`, `quotations`, `quotation_lines`, `rfq_awards` |
| Purchase orders | `purchase_orders`, `purchase_order_lines` |
| Shipments & receipts | `asns`, `asn_lines`, `grpos`, `grpo_lines` |
| Scorecard | `vendor_score_snapshots` |
| SAP master-data cache (read-only) | `sap_warehouses`, `sap_items`, `sap_item_stock`, `sap_vendor_groups`, `sap_business_partners`, `sap_employees`, `sap_tax_codes` |
| SAP sync | `sync_runs` (pulls), `sync_transactions` (outbox of writes) |
| Migrations | `schema_migrations` |

### 8.2 Important company settings (`company_settings`)

| Column | Meaning |
|---|---|
| `pr_mode` | `APP` = PRs are raised in VendorNex. `B1_SYNC` = PRs are copied read-only from SAP. |
| `push_pr_to_b1` | In APP mode, also create approved PRs in SAP. |
| `vendor_approval_stages` | Ordered stages for vendors. Default `[FINANCE, PROCUREMENT]`. |
| `requester_approval_stages` | Default `[FINANCE]`. |
| `pr_approval_stages` | Default `[ADMIN]`. |
| `master_sync_interval_min` | How often master data is pulled (minimum 5). |

An empty stage list means **auto-approve**.

### 8.3 Transactions

- **Converted code:** use Spring `@Transactional(rollbackFor = Exception.class)` on service methods.
- **Legacy code:** uses `Db.tx(() -> {...})`. It keeps the connection in a `ThreadLocal`, so nested `Db` calls join the same transaction.
- Document numbers such as `PR-2026-0001` come from `DocNumbers.next()`. It uses `INSERT … ON CONFLICT … RETURNING`, which is safe under concurrency.

---

## 9. Business flows and status lifecycles

### 9.1 Vendor onboarding

```
DRAFT ──submit──► PENDING_APPROVAL ──all stages approve──┬─► (linked to existing SAP BP) ──► ACTIVE
   ▲                     │                               └─► SAP_SYNC_PENDING ──SAP OK──► ACTIVE
   │                     └──any stage rejects──► REJECTED          │
   └──── edit & resubmit ◄──────────────────────────────┘          └─SAP error─► SAP_SYNC_FAILED (admin retries)
ACTIVE ◄──► INACTIVE  (admin toggle)
```

- The wizard has 4 steps: Basic → Tax → Bank → Uploads. GSTIN, PAN and IFSC are format-checked, and the GSTIN must contain the PAN (`common/Validators.java`).
- GST and PAN documents are required before submit. Uploads accept PDF or images, up to 10 MB.
- When a vendor becomes `ACTIVE`, a vendor portal user is created and an invite link is returned.

### 9.2 Approval engine (`service/ApprovalService`)

Shared by **VENDOR**, **REQUESTER** and **PURCHASE_REQUEST**.

1. `start()` reads the configured stages and creates one `approval_steps` row per stage. The first is `PENDING`, the rest `NOT_STARTED`.
2. An approver calls `POST /api/approvals/{id}/approve` or `/reject`. The step row is locked first.
3. **Approve:** the next stage becomes `PENDING`. After the last stage, `onApproved()` runs the entity-specific action (activate vendor, invite requester, or push PR to SAP).
4. **Reject:** a reason is required. Remaining stages are skipped and the entity becomes `REJECTED`.
5. **Resubmit** starts a new `round`, so earlier history is kept.

Who can act: the `ADMIN` stage needs the ADMIN role. Other stages need an APPROVER who holds that stage in `user_approval_stages`.

### 9.3 Procure-to-receive

```
Purchase Request ──approve──► APPROVED
      │ (lines)
      ├──► RFQ (OPEN) ──vendors quote──► compare ──award per line──► one PO per winning vendor
      └──► PO directly (no RFQ)
                                  │
PO:  SAP_PENDING ──SAP OK──► PENDING_ACK ──vendor acknowledges──► ACKNOWLEDGED
                 └─error─► SAP_FAILED                 (or DECLINED)
                                  │
     vendor submits ASN ──► GRPO draft created in SAP
                                  │
     buyer confirms received / rejected qty + warehouse ──► GRPO posted in SAP
                                  │
PO status recalculated from line quantities (PoStatus.recompute):
     PARTIALLY_SHIPPED → SHIPPED → PARTIALLY_RECEIVED → COMPLETED
```

- **PR statuses:** `DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, PARTIALLY_SOURCED, SOURCED, CLOSED, CANCELLED`.
- **RFQ statuses:** `OPEN, CLOSED, PARTIALLY_AWARDED, AWARDED, CANCELLED`. A scheduled job closes OPEN RFQs past their due date.
- **ASN statuses:** `SUBMITTED, GRPO_DRAFTED, RECEIVED, CANCELLED`.
- **GRPO statuses:** `DRAFT_PENDING, DRAFT_CREATED, DRAFT_FAILED, POSTING, POSTED, POST_FAILED`.
- SAP is the master for POs: every PO is created in SAP and carries SAP's DocNum. Only POs that never reached SAP can be cancelled in VendorNex.

### 9.4 Vendor scorecard

Calculated in `ScorecardController`:

- **OTIF** — % of due PO lines received in full on or before the delivery date.
- **Quality** — accepted ÷ (accepted + rejected) quantity on posted GRPOs.
- **Price variance** — average % of PO unit price above the item's SAP average cost (negative = cheaper).

Monthly snapshots in `vendor_score_snapshots` give the trend.

---

## 10. SAP Business One integration

### 10.1 Gateway

- `sap/SapB1Gateway.java` — interface with every SAP operation: test connection, read master data, read open PRs/POs, create BP/PR/PO/GRPO draft, post GRPO.
- `sap/ServiceLayerGateway.java` — real implementation. Logs in to `/b1s/v1/Login`, reuses the `B1SESSION`/`ROUTEID` cookies, re-logs in on 401, and follows OData paging.
- `sap/SapGatewayFactory.java` — one cached gateway **per company**. The cache is replaced automatically when the connection settings change.
- `sap/B1Payloads.java` — builds the JSON bodies sent to SAP.

Each company has an `integration_mode`:

- `SERVICE_LAYER` — a real SAP B1 server.
- `MOCK` — the embedded simulator (`sap/mock/MockServiceLayer.java`), started at boot on `127.0.0.1`. Its data is kept in `data/mock-service-layer/*.json`. Use it for local development and demos without SAP.

### 10.2 Writing to SAP: the outbox (`service/SyncService`)

Every write to SAP goes through the `sync_transactions` table. This means **an SAP outage never loses a business action**.

```
1. Business code, inside its DB transaction:   SyncService.enqueue(...)  → row QUEUED
2. Transaction commits
3. Same request:                               SyncService.processNow(txId)
      ├─ success          → POSTED, SAP DocEntry/DocNum saved on the source record
      ├─ SAP down (5xx/network) → stays QUEUED, retried by background worker
      │                           backoff: 30s, 60s, 120s … up to 32 min, until max attempts
      └─ SAP rejects data (4xx) → FAILED, source record flagged; admin fixes data and clicks Retry
```

Operations: `CREATE_VENDOR`, `CREATE_PURCHASE_REQUEST`, `CREATE_PURCHASE_ORDER`, `CREATE_GRPO_DRAFT`, `POST_GRPO`.

- Retry rebuilds the payload from current data, so an admin's fix is picked up.
- The worker claims rows with `FOR UPDATE SKIP LOCKED`, so several instances can run safely.
- Rows stuck in `RUNNING` for over 10 minutes (e.g. a crashed instance) go back to `QUEUED`.
- If `CREATE_VENDOR` finds that the card code already exists with the same name, it treats the call as already done (the earlier response was lost).

### 10.3 Reading from SAP: master sync (`service/MasterSyncService`)

Pulls from SAP into the `sap_*` cache tables: `WAREHOUSES, ITEMS, VENDOR_GROUPS, BUSINESS_PARTNERS, EMPLOYEES, TAX_CODES, PURCHASE_REQUESTS, PURCHASE_ORDERS`.

- Each entity runs in its own transaction and writes a `sync_runs` row, so one failure does not block the others.
- Only one sync runs per company at a time.
- Triggers: `SCHEDULED` (every `master_sync_interval_min`), `MANUAL` (`POST /api/datahub/sync`), and on company creation.

### 10.4 Scheduled jobs (`config/SchedulingConfig`)

| Job | Every | Does |
|---|---|---|
| `outboxWorker` | 5 s (starts after 10 s) | Retries up to 20 due SAP writes |
| `masterSync` | 60 s (starts after 60 s) | Closes overdue RFQs; runs master sync for companies that are due |

Both are skipped when `SCHEDULER_ENABLED=false`. Errors are logged and never stop the scheduler.

---

## 11. REST API reference

All endpoints are under `/api`. Send `Authorization: Bearer <token>` except on public ones. Bodies are JSON unless noted.

Role key: **SA** = SUPER_ADMIN, **AD** = ADMIN, **AP** = APPROVER, **RQ** = REQUESTER, **VN** = VENDOR, **All** = any signed-in user.

### Auth & health
| Method | Path | Roles |
|---|---|---|
| GET | `/api/health` | Public |
| POST | `/api/auth/login` → `{token, user}` | Public |
| GET | `/api/auth/invite/{token}` | Public |
| POST | `/api/auth/invite/{token}` (set password) | Public |
| GET | `/api/auth/me` | All |
| POST | `/api/auth/change-password` | All |
| POST | `/api/auth/logout` | All |

### Super Admin — companies
| Method | Path | Roles |
|---|---|---|
| GET / POST | `/api/sa/companies` | SA |
| GET / PUT | `/api/sa/companies/{id}` | SA |
| POST | `/api/sa/companies/test-connection` | SA |
| POST | `/api/sa/companies/{id}/test-connection` | SA |
| POST | `/api/sa/companies/{id}/status` | SA |
| POST | `/api/sa/companies/{id}/resend-admin-invite` | SA |
| POST | `/api/sa/companies/{id}/sync` | SA |

### Settings & users
| Method | Path | Roles |
|---|---|---|
| GET / PUT | `/api/settings` | AD |
| POST | `/api/settings/test-connection` | AD |
| GET / POST | `/api/users/internal` | AD |
| PUT | `/api/users/internal/{id}` | AD |
| POST | `/api/users/{id}/invite` | AD |
| POST | `/api/users/{id}/status` | AD |
| GET | `/api/users/login-activity` | AD |
| GET / POST | `/api/requesters` | AD |
| GET | `/api/requesters/{id}` | AD, AP |
| PUT | `/api/requesters/{id}` | AD |
| POST | `/api/requesters/{id}/resubmit` | AD |

### Vendors
| Method | Path | Roles |
|---|---|---|
| GET | `/api/vendors?status=&q=` | AD |
| GET | `/api/vendors-lookup` | AD |
| GET | `/api/vendors/{id}` | AD, AP |
| POST | `/api/vendors` | AD |
| PUT / DELETE | `/api/vendors/{id}` (delete = drafts only) | AD |
| POST | `/api/vendors/{id}/submit` | AD |
| POST | `/api/vendors/{id}/retry-sap` | AD |
| POST | `/api/vendors/{id}/status` `{active}` | AD |
| POST | `/api/vendors/{id}/invite` | AD |
| POST | `/api/vendors/{id}/documents/{type}` — **raw file body**, `Content-Type` PDF/image, file name in `X-File-Name` or `?fileName=`, optional `?expiryDate=` | AD |
| GET | `/api/vendors/{id}/documents/{docId}/file` | AD, AP |
| DELETE | `/api/vendors/{id}/documents/{docId}` | AD |

Document types: `GST, PAN, MSME, INSURANCE, CANCELLED_CHEQUE, OTHER`.

### Approvals
| Method | Path | Roles |
|---|---|---|
| GET | `/api/approvals/queue` | AD, AP |
| GET | `/api/approvals/history` | AD, AP |
| POST | `/api/approvals/{stepId}/approve` `{remarks?}` | AD, AP |
| POST | `/api/approvals/{stepId}/reject` `{remarks}` (required) | AD, AP |

### Master data & Data Hub
| Method | Path | Roles |
|---|---|---|
| GET | `/api/master/items`, `/api/master/warehouses` | AD, AP, RQ |
| GET | `/api/master/vendor-groups`, `/tax-codes`, `/employees`, `/business-partners` | AD |
| GET | `/api/datahub/items`, `/summary`, `/sync-runs`, `/sync-transactions` | AD |
| POST | `/api/datahub/sync-transactions/{id}/retry` | AD |
| POST | `/api/datahub/sync` | AD |

### Purchase requests
| Method | Path | Roles |
|---|---|---|
| GET | `/api/purchase-requests` | AD, AP, RQ |
| GET | `/api/purchase-requests/{id}` | AD, AP, RQ |
| GET | `/api/purchase-requests/sourceable-lines` | AD |
| POST | `/api/purchase-requests` | AD, RQ |
| POST | `/api/purchase-requests/{id}/cancel` | AD, RQ |
| POST | `/api/purchase-requests/{id}/resubmit` | AD, RQ |
| POST | `/api/purchase-requests/{id}/retry-b1` | AD |

### RFQs
| Method | Path | Roles |
|---|---|---|
| GET / POST | `/api/rfqs` | AD |
| GET | `/api/rfqs/{id}` | AD |
| GET | `/api/rfqs/{id}/comparison` | AD |
| POST | `/api/rfqs/{id}/vendors`, `/extend`, `/close`, `/cancel` | AD |
| POST | `/api/rfqs/{id}/award` `{docDueDate?, taxCode?, awards:[{rfqLineId, quotationId, quantity}]}` | AD |
| GET | `/api/vendor/rfqs`, `/api/vendor/rfqs/{id}` | VN |
| POST | `/api/vendor/rfqs/{id}/quote`, `/withdraw` | VN |

### Purchase orders, ASNs, GRPOs
| Method | Path | Roles |
|---|---|---|
| GET / POST | `/api/purchase-orders` | AD |
| GET | `/api/purchase-orders/{id}` | AD |
| POST | `/api/purchase-orders/from-prs` `{lines:[{prLineId, quantity, unitPrice, taxCode?}]}` | AD |
| POST | `/api/purchase-orders/{id}/retry-sap`, `/cancel` | AD |
| GET | `/api/vendor/purchase-orders`, `/api/vendor/purchase-orders/{id}` | VN |
| POST | `/api/vendor/purchase-orders/{id}/acknowledge` | VN |
| POST | `/api/vendor/purchase-orders/{id}/asns` | VN |
| GET | `/api/grpos` | AD |
| POST | `/api/grpos/{id}/confirm` `{postingDate?, lines:[{poLineId, receivedQty, rejectedQty, warehouseCode}]}` | AD |
| POST | `/api/grpos/{id}/retry` | AD |

### Dashboards & scorecard
| Method | Path | Roles |
|---|---|---|
| GET | `/api/dashboard` | AD |
| GET | `/api/vendor/dashboard` | VN |
| GET | `/api/scorecard` | AD |
| GET | `/api/vendor/scorecard` | VN |

---

## 12. Build, run and deploy

### Local

Prerequisites: JDK 21, Maven, PostgreSQL. pgbouncer is optional locally — point both URLs at Postgres directly:

```bash
# PowerShell
$env:DB_URL="jdbc:postgresql://localhost:5432/vendornex?prepareThreshold=0"
$env:MIGRATION_DB_URL="jdbc:postgresql://localhost:5432/vendornex"
mvn spring-boot:run
```

On first start: the schema is created, the SAP simulator starts, and demo data is loaded. Check with `GET http://localhost:8080/api/health` → `{"status":"UP"}`.

### Build a jar

```bash
mvn -DskipTests package        # → target/vendornex-api.jar
java -jar target/vendornex-api.jar
```

### Docker

```bash
docker build -t vendornex-api .
docker run -p 8080:8080 \
  -e DB_URL=... -e MIGRATION_DB_URL=... -e DB_USER=... -e DB_PASSWORD=... \
  -e JWT_SECRET=... -e APP_SECRET_KEY=... \
  -e SEED_DEMO=false -e MOCK_SL_ENABLED=false \
  -v vendornex-docs:/data/documents \
  vendornex-api
```

The image runs as a non-root user (`vendornex`), in UTC, with a TCP health check on port 8080. Uploaded documents go to `/data/documents`, so **mount a volume there**.

### Production checklist

- [ ] Set a strong `JWT_SECRET` (32+ characters) and a real `APP_SECRET_KEY` (base64 of 32 random bytes).
- [ ] `SEED_DEMO=false`, `MOCK_SL_ENABLED=false`.
- [ ] Set `CORS_ORIGIN` to the real frontend URL instead of `*`.
- [ ] Set `PUBLIC_APP_URL` so invite links are correct.
- [ ] Persistent volume for `DOCUMENT_STORE_DIR` (or replace `DocumentStore` with S3/Azure Blob).
- [ ] If running more than one instance, only the outbox worker is safe to run everywhere; the in-memory "one sync per company" guard is per instance.

---

## 13. How to add a new feature (converted style)

Follow the purchase-request feature as the template (`PurchaseRequestController` → `PurchaseRequestService` → `PurchaseRequestRepository` / `PurchaseRequestQueries`).

1. **Schema** — add `V2__your_change.sql` in `db/migration/` and add its name to `Migrator.MIGRATIONS`.
2. **Entity** — `entity/Thing.java` with `@Entity @Table(name="things")` and Lombok `@Getter @Setter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor`. Hibernate will validate it against the table at startup.
3. **Repository** — `repository/ThingRepository.java` (`extends JpaRepository<Thing, UUID>`) for simple access, and `repository/ThingQueries.java` (`JdbcTemplate` + `GenericRowMapper`) for wide or joined reads. All SQL lives here.
4. **DTO** — `dto/CreateThingRequest.java` with Lombok `@Data`. Validate in the service with the `Json.reqText(raw, "field")` style helpers so error messages stay consistent.
5. **Service** — `service/ThingService.java`: `@Service @RequiredArgsConstructor`, `@Transactional(rollbackFor = Exception.class)` (or `TransactionTemplate`) on writes. Always filter by `companyId`. Throw `ApiException` for business errors.
6. **Controller** — `controller/ThingController.java`: `@RestController @RequiredArgsConstructor`, `@Roles(...)` on every non-public method, take `CurrentUser u`, parse path ids with `Ids.uuid(id)`, and just call the service.
7. **SAP write needed?** — enqueue a `sync_transactions` row inside the transaction and call `SyncService.processNow(id)` **after** it commits (see `PurchaseRequestService.create`, which commits with `TransactionTemplate` and then calls SAP).

---

## 14. Known technical debt

- **Half-migrated code.** `RfqController`, `PurchaseOrderController`, `ScorecardController`, `DashboardController` and `AuthController` still hold SQL and logic; `SyncService`, `MasterSyncService`, `AuthService`, `PoStatus` and `VendorLifecycle` still use the static `Db` helper.
- **Two connection pools** until the migration is done (doubles DB connections).
- **`VendorLifecycle`** (`service/`, static, uses `Db`) duplicates `VendorService.onActivated`. Remove it once `SyncService` calls the `VendorService` bean.
- **Custom JWT and auth** instead of Spring Security. It works, but has no refresh tokens or token revocation (the per-request user reload partly covers revocation).
- **No email sending** — invite links must be shared manually.
- **No automated tests** in `src/test`.
- **Local file storage** for documents — not shared between instances.
