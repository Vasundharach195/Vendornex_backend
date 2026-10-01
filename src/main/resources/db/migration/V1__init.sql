-- =====================================================================
-- Ikyam VendorNex for SAP Business One — PostgreSQL schema (V1)
-- Source of truth for the physical data model. Readable companion:
-- docs/DATA_MODEL.md
--
-- Provenance:
--   App-owned        : real persistence, CRUD via this app
--   Synced (read)    : cached from SAP B1 Service Layer, never edited here
--   Mirrored         : app row that points at a B1 document (DocEntry/DocNum);
--                      B1 is authoritative for the document, the app owns the
--                      portal-only workflow around it (ack, ASN, GRPO confirm)
--
-- Enumerations are varchar + CHECK (easier to evolve than PG enum types).
-- Every tenant table carries company_id; the data-access layer filters on it.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- 1. companies — App-owned (tenant root)
-- ---------------------------------------------------------------------
CREATE TABLE companies (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                        VARCHAR(200) NOT NULL,
    industry                    VARCHAR(100),
    integration_mode            VARCHAR(20)  NOT NULL DEFAULT 'MOCK'
                                CHECK (integration_mode IN ('MOCK','SERVICE_LAYER')),
    service_layer_base_url      VARCHAR(500),
    sap_company_db              VARCHAR(100),
    sl_username                 VARCHAR(100),
    sl_password_enc             TEXT,           -- AES-256-GCM, never plaintext
    sl_verify_tls               BOOLEAN NOT NULL DEFAULT TRUE,
    connection_status           VARCHAR(20)  NOT NULL DEFAULT 'NOT_TESTED'
                                CHECK (connection_status IN ('NOT_TESTED','OK','FAILED')),
    connection_message          VARCHAR(1000),
    sap_b1_version              VARCHAR(50),
    last_tested_at              TIMESTAMPTZ,
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    onboarded_on                DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 2. company_settings — App-owned, 1:1 with companies
-- ---------------------------------------------------------------------
CREATE TABLE company_settings (
    company_id                  UUID PRIMARY KEY REFERENCES companies(id) ON DELETE CASCADE,
    pr_mode                     VARCHAR(20) NOT NULL DEFAULT 'APP'
                                CHECK (pr_mode IN ('APP','B1_SYNC')),
    push_pr_to_b1               BOOLEAN NOT NULL DEFAULT FALSE,
    vendor_approval_stages      VARCHAR(20)[] NOT NULL DEFAULT ARRAY['FINANCE','PROCUREMENT']::VARCHAR(20)[],
    requester_approval_stages   VARCHAR(20)[] NOT NULL DEFAULT ARRAY['FINANCE']::VARCHAR(20)[],
    pr_approval_stages          VARCHAR(20)[] NOT NULL DEFAULT ARRAY['ADMIN']::VARCHAR(20)[],
    bp_series                   INTEGER,                 -- B1 numbering series for new vendors; NULL = manual code
    bp_code_prefix              VARCHAR(10) NOT NULL DEFAULT 'V',
    bp_code_next                INTEGER NOT NULL DEFAULT 20001,
    default_warehouse_code      VARCHAR(20),
    default_tax_code            VARCHAR(20),
    india_localization          BOOLEAN NOT NULL DEFAULT TRUE,  -- GSTIN on BPAddresses, PAN in BPFiscalTaxIDCollection
    currency                    VARCHAR(3) NOT NULL DEFAULT 'INR',
    master_sync_interval_min    INTEGER NOT NULL DEFAULT 30 CHECK (master_sync_interval_min >= 5),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 3. vendors — App-owned; becomes / links to a B1 Business Partner (OCRD, CardType = S)
--    (declared before users because users.vendor_id references it)
-- ---------------------------------------------------------------------
CREATE TABLE vendors (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    legal_name                  VARCHAR(200) NOT NULL,
    contact_name                VARCHAR(150),
    email                       VARCHAR(200),
    phone                       VARCHAR(40),
    link_type                   VARCHAR(10) NOT NULL DEFAULT 'NEW' CHECK (link_type IN ('NEW','EXISTING')),
    sap_card_code               VARCHAR(15),             -- OCRD.CardCode once created / linked
    sap_bp_status               VARCHAR(10) CHECK (sap_bp_status IN ('ACTIVE','INACTIVE')),
    vendor_group_code           INTEGER,                 -- OCRG.GroupCode (synced list)
    vendor_group_name           VARCHAR(100),
    street                      VARCHAR(200),
    city                        VARCHAR(100),
    state                       VARCHAR(100),
    zip_code                    VARCHAR(20),
    country                     VARCHAR(3),
    gstin                       VARCHAR(15),
    pan                         VARCHAR(10),
    bank_name                   VARCHAR(100),
    bank_account_no             VARCHAR(40),
    bank_ifsc                   VARCHAR(11),
    sap_bank_code               VARCHAR(30),             -- ODSC.BankCode — needed to push bank details to B1
    status                      VARCHAR(25) NOT NULL DEFAULT 'DRAFT'
                                CHECK (status IN ('DRAFT','PENDING_APPROVAL','SAP_SYNC_PENDING','SAP_SYNC_FAILED','ACTIVE','REJECTED','INACTIVE')),
    wizard_step                 SMALLINT NOT NULL DEFAULT 1,
    rejection_reason            VARCHAR(1000),
    created_by_user_id          UUID,
    invited_on                  DATE NOT NULL DEFAULT CURRENT_DATE,
    activated_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_vendors_company_cardcode ON vendors(company_id, sap_card_code) WHERE sap_card_code IS NOT NULL;
CREATE INDEX ix_vendors_company_status ON vendors(company_id, status);

-- ---------------------------------------------------------------------
-- 4. users — App-owned. Consumes zero SAP B1 named-user licences.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID REFERENCES companies(id),   -- NULL only for SUPER_ADMIN
    name                        VARCHAR(150) NOT NULL,
    email                       VARCHAR(200) NOT NULL,
    password_hash               VARCHAR(100),                     -- bcrypt; NULL until invite accepted
    role                        VARCHAR(20) NOT NULL
                                CHECK (role IN ('SUPER_ADMIN','ADMIN','APPROVER','REQUESTER','VENDOR')),
    status                      VARCHAR(20) NOT NULL DEFAULT 'INVITED'
                                CHECK (status IN ('INVITED','PENDING_APPROVAL','ACTIVE','REJECTED','DISABLED')),
    vendor_id                   UUID REFERENCES vendors(id),      -- set when role = VENDOR
    department                  VARCHAR(100),                     -- requesters
    sap_employee_id             INTEGER,                          -- OHEM.empID, optional mapping for PR push
    invite_token                VARCHAR(80),
    invite_expires_at           TIMESTAMPTZ,
    rejection_reason            VARCHAR(1000),
    last_login_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_company CHECK ((role = 'SUPER_ADMIN') = (company_id IS NULL)),
    CONSTRAINT ck_users_vendor  CHECK ((role = 'VENDOR') = (vendor_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_users_email ON users(lower(email));
CREATE UNIQUE INDEX ux_users_invite ON users(invite_token) WHERE invite_token IS NOT NULL;
CREATE INDEX ix_users_company_role ON users(company_id, role);

-- ---------------------------------------------------------------------
-- 5. user_approval_stages — App-owned. Which approval stages an APPROVER may act on.
-- ---------------------------------------------------------------------
CREATE TABLE user_approval_stages (
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stage                       VARCHAR(20) NOT NULL CHECK (stage IN ('FINANCE','PROCUREMENT','COMPLIANCE')),
    PRIMARY KEY (user_id, stage)
);

-- ---------------------------------------------------------------------
-- 6. login_activity — App-owned (audit)
-- ---------------------------------------------------------------------
CREATE TABLE login_activity (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID REFERENCES users(id),
    company_id                  UUID REFERENCES companies(id),
    email                       VARCHAR(200),
    event                       VARCHAR(20) NOT NULL CHECK (event IN ('LOGIN','LOGIN_FAILED','LOGOUT','INVITE_ACCEPTED')),
    ip_address                  VARCHAR(64),
    user_agent                  VARCHAR(400),
    occurred_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_activity_company ON login_activity(company_id, occurred_at DESC);

-- ---------------------------------------------------------------------
-- 7. doc_sequences — App-owned. Human-readable numbers for app documents.
-- ---------------------------------------------------------------------
CREATE TABLE doc_sequences (
    company_id                  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    doc_type                    VARCHAR(10) NOT NULL CHECK (doc_type IN ('PR','RFQ','ASN')),
    prefix                      VARCHAR(20) NOT NULL,
    next_value                  INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (company_id, doc_type)
);

-- ---------------------------------------------------------------------
-- 8–13. SAP B1 master data — Synced (read). Refreshed by the master-sync job.
-- ---------------------------------------------------------------------
CREATE TABLE sap_warehouses (                               -- OWHS
    company_id                  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    warehouse_code              VARCHAR(8) NOT NULL,
    warehouse_name              VARCHAR(100) NOT NULL,
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, warehouse_code)
);

CREATE TABLE sap_items (                                    -- OITM (purchase items)
    company_id                  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    item_code                   VARCHAR(50) NOT NULL,
    item_name                   VARCHAR(200) NOT NULL,
    item_group_code             INTEGER,
    item_group_name             VARCHAR(100),
    purchase_uom                VARCHAR(20),
    inventory_uom               VARCHAR(20),
    avg_price                   NUMERIC(19,6),           -- OITM.AvgPrice (Service Layer: AvgStdPrice), baseline for price variance
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,
    last_synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, item_code)
);
CREATE INDEX ix_sap_items_name ON sap_items(company_id, lower(item_name));

CREATE TABLE sap_item_stock (                               -- OITW
    company_id                  UUID NOT NULL,
    item_code                   VARCHAR(50) NOT NULL,
    warehouse_code              VARCHAR(8) NOT NULL,
    in_stock                    NUMERIC(19,6) NOT NULL DEFAULT 0,
    committed                   NUMERIC(19,6) NOT NULL DEFAULT 0,
    ordered                     NUMERIC(19,6) NOT NULL DEFAULT 0,
    last_synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, item_code, warehouse_code),
    FOREIGN KEY (company_id, item_code) REFERENCES sap_items(company_id, item_code) ON DELETE CASCADE
);

CREATE TABLE sap_vendor_groups (                            -- OCRG, Type = vendor
    company_id                  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    group_code                  INTEGER NOT NULL,
    group_name                  VARCHAR(100) NOT NULL,
    last_synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, group_code)
);

CREATE TABLE sap_business_partners (                        -- OCRD, CardType = S
    company_id                  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    card_code                   VARCHAR(15) NOT NULL,
    card_name                   VARCHAR(200) NOT NULL,
    group_code                  INTEGER,
    is_active                   BOOLEAN NOT NULL DEFAULT TRUE,  -- Valid = tYES and Frozen = tNO
    federal_tax_id              VARCHAR(32),
    email                       VARCHAR(200),
    last_synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, card_code)
);

CREATE TABLE sap_employees (                                -- OHEM (requester identity in B1_SYNC mode)
    company_id                  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    employee_id                 INTEGER NOT NULL,
    full_name                   VARCHAR(150) NOT NULL,
    department                  VARCHAR(100),
    email                       VARCHAR(200),
    last_synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, employee_id)
);

CREATE TABLE sap_tax_codes (                                -- VatGroups (input) / OSTC
    company_id                  UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    tax_code                    VARCHAR(20) NOT NULL,
    tax_name                    VARCHAR(100),
    rate                        NUMERIC(9,4),
    last_synced_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, tax_code)
);

-- ---------------------------------------------------------------------
-- 14. vendor_documents — App-owned (onboarding uploads)
-- ---------------------------------------------------------------------
CREATE TABLE vendor_documents (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    vendor_id                   UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    doc_type                    VARCHAR(20) NOT NULL CHECK (doc_type IN ('GST','PAN','MSME','INSURANCE','CANCELLED_CHEQUE','OTHER')),
    file_name                   VARCHAR(255) NOT NULL,
    content_type                VARCHAR(100),
    size_bytes                  BIGINT NOT NULL,
    storage_key                 VARCHAR(300) NOT NULL,   -- path under the document store
    expiry_date                 DATE,
    uploaded_by_user_id         UUID REFERENCES users(id),
    uploaded_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_vendor_documents_type ON vendor_documents(vendor_id, doc_type) WHERE doc_type <> 'OTHER';

-- ---------------------------------------------------------------------
-- 15. approval_steps — App-owned. One row per (entity, stage) in the configured chain.
-- ---------------------------------------------------------------------
CREATE TABLE approval_steps (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    entity_type                 VARCHAR(20) NOT NULL CHECK (entity_type IN ('VENDOR','REQUESTER','PURCHASE_REQUEST')),
    entity_id                   UUID NOT NULL,
    stage                       VARCHAR(20) NOT NULL CHECK (stage IN ('ADMIN','FINANCE','PROCUREMENT','COMPLIANCE')),
    seq                         SMALLINT NOT NULL,
    status                      VARCHAR(15) NOT NULL DEFAULT 'NOT_STARTED'
                                CHECK (status IN ('NOT_STARTED','PENDING','APPROVED','REJECTED','SKIPPED')),
    round                       SMALLINT NOT NULL DEFAULT 1,     -- increments on resubmission
    acted_by_user_id            UUID REFERENCES users(id),
    acted_at                    TIMESTAMPTZ,
    remarks                     VARCHAR(1000),
    UNIQUE (entity_type, entity_id, round, stage)
);
CREATE INDEX ix_approval_queue ON approval_steps(company_id, stage, status);

-- ---------------------------------------------------------------------
-- 16–17. purchase_requests / lines — App-owned (APP mode) or Mirrored (B1_SYNC, OPRQ/PRQ1)
-- ---------------------------------------------------------------------
CREATE TABLE purchase_requests (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    pr_no                       VARCHAR(30) NOT NULL,
    source                      VARCHAR(5) NOT NULL CHECK (source IN ('APP','B1')),
    sap_doc_entry               INTEGER,
    sap_doc_num                 INTEGER,
    requester_user_id           UUID REFERENCES users(id),
    requester_name              VARCHAR(150) NOT NULL,
    department                  VARCHAR(100),
    required_date               DATE,
    justification               VARCHAR(2000),
    status                      VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL'
                                CHECK (status IN ('DRAFT','PENDING_APPROVAL','APPROVED','REJECTED','PARTIALLY_SOURCED','SOURCED','CLOSED','CANCELLED')),
    rejection_reason            VARCHAR(1000),
    sap_push_status             VARCHAR(15) NOT NULL DEFAULT 'NOT_REQUIRED'
                                CHECK (sap_push_status IN ('NOT_REQUIRED','QUEUED','POSTED','FAILED')),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, pr_no)
);
CREATE UNIQUE INDEX ux_pr_sap_entry ON purchase_requests(company_id, sap_doc_entry) WHERE sap_doc_entry IS NOT NULL;
CREATE INDEX ix_pr_company_status ON purchase_requests(company_id, status);

CREATE TABLE purchase_request_lines (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_request_id         UUID NOT NULL REFERENCES purchase_requests(id) ON DELETE CASCADE,
    line_num                    INTEGER NOT NULL,                 -- = PRQ1.LineNum when source = B1
    item_code                   VARCHAR(50) NOT NULL,
    item_name                   VARCHAR(200) NOT NULL,
    uom                         VARCHAR(20),
    quantity                    NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    sourced_qty                 NUMERIC(19,6) NOT NULL DEFAULT 0, -- moved into an RFQ or PO
    warehouse_code              VARCHAR(8),
    required_date               DATE,
    UNIQUE (purchase_request_id, line_num),
    CHECK (sourced_qty >= 0 AND sourced_qty <= quantity)
);

-- ---------------------------------------------------------------------
-- 18–23. RFQ, invitations, quotations, awards — App-owned (no B1 equivalent in the flow)
-- ---------------------------------------------------------------------
CREATE TABLE rfqs (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    rfq_no                      VARCHAR(30) NOT NULL,
    title                       VARCHAR(200) NOT NULL,
    due_date                    DATE NOT NULL,
    status                      VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                                CHECK (status IN ('OPEN','CLOSED','PARTIALLY_AWARDED','AWARDED','CANCELLED')),
    notes                       VARCHAR(2000),
    created_by_user_id          UUID REFERENCES users(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, rfq_no)
);

CREATE TABLE rfq_lines (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rfq_id                      UUID NOT NULL REFERENCES rfqs(id) ON DELETE CASCADE,
    line_num                    INTEGER NOT NULL,
    item_code                   VARCHAR(50) NOT NULL,
    item_name                   VARCHAR(200) NOT NULL,
    uom                         VARCHAR(20),
    quantity                    NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    awarded_qty                 NUMERIC(19,6) NOT NULL DEFAULT 0,
    warehouse_code              VARCHAR(8),
    required_date               DATE,
    UNIQUE (rfq_id, line_num),
    CHECK (awarded_qty >= 0 AND awarded_qty <= quantity)
);

-- Which PR lines fed which RFQ line (PRs can be merged into one RFQ)
CREATE TABLE rfq_line_sources (
    rfq_line_id                 UUID NOT NULL REFERENCES rfq_lines(id) ON DELETE CASCADE,
    pr_line_id                  UUID NOT NULL REFERENCES purchase_request_lines(id),
    quantity                    NUMERIC(19,6) NOT NULL,
    PRIMARY KEY (rfq_line_id, pr_line_id)
);

CREATE TABLE rfq_vendors (
    rfq_id                      UUID NOT NULL REFERENCES rfqs(id) ON DELETE CASCADE,
    vendor_id                   UUID NOT NULL REFERENCES vendors(id),
    invited_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    viewed_at                   TIMESTAMPTZ,
    PRIMARY KEY (rfq_id, vendor_id)
);

CREATE TABLE quotations (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rfq_id                      UUID NOT NULL REFERENCES rfqs(id) ON DELETE CASCADE,
    vendor_id                   UUID NOT NULL REFERENCES vendors(id),
    delivery_date               DATE NOT NULL,
    payment_terms               VARCHAR(200),
    notes                       VARCHAR(2000),
    revision                    SMALLINT NOT NULL DEFAULT 1,
    status                      VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED'
                                CHECK (status IN ('SUBMITTED','WITHDRAWN','AWARDED','PARTIALLY_AWARDED','NOT_AWARDED')),
    submitted_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rfq_id, vendor_id)
);

CREATE TABLE quotation_lines (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quotation_id                UUID NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    rfq_line_id                 UUID NOT NULL REFERENCES rfq_lines(id) ON DELETE CASCADE,
    unit_price                  NUMERIC(19,6) NOT NULL CHECK (unit_price >= 0),
    fulfil_qty                  NUMERIC(19,6) NOT NULL CHECK (fulfil_qty >= 0),
    tax_percent                 NUMERIC(9,4),
    UNIQUE (quotation_id, rfq_line_id)
);

-- Line-level award: an RFQ line can be split across vendors
CREATE TABLE rfq_awards (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rfq_id                      UUID NOT NULL REFERENCES rfqs(id) ON DELETE CASCADE,
    rfq_line_id                 UUID NOT NULL REFERENCES rfq_lines(id) ON DELETE CASCADE,
    quotation_id                UUID NOT NULL REFERENCES quotations(id),
    vendor_id                   UUID NOT NULL REFERENCES vendors(id),
    awarded_qty                 NUMERIC(19,6) NOT NULL CHECK (awarded_qty > 0),
    unit_price                  NUMERIC(19,6) NOT NULL,
    purchase_order_id           UUID,                         -- FK added after purchase_orders
    awarded_by_user_id          UUID REFERENCES users(id),
    awarded_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 24–25. purchase_orders / lines — Mirrored (OPOR/POR1). B1 DocNum is authoritative.
-- ---------------------------------------------------------------------
CREATE TABLE purchase_orders (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    vendor_id                   UUID NOT NULL REFERENCES vendors(id),
    card_code                   VARCHAR(15) NOT NULL,
    source                      VARCHAR(10) NOT NULL CHECK (source IN ('RFQ','PR','DIRECT','B1')),
    rfq_id                      UUID REFERENCES rfqs(id),
    sap_doc_entry               INTEGER,
    sap_doc_num                 INTEGER,
    doc_date                    DATE NOT NULL DEFAULT CURRENT_DATE,
    doc_due_date                DATE NOT NULL,
    currency                    VARCHAR(3) NOT NULL DEFAULT 'INR',
    remarks                     VARCHAR(1000),
    doc_total                   NUMERIC(19,6) NOT NULL DEFAULT 0,
    status                      VARCHAR(25) NOT NULL DEFAULT 'SAP_PENDING'
                                CHECK (status IN ('SAP_PENDING','SAP_FAILED','PENDING_ACK','ACKNOWLEDGED','DECLINED',
                                                  'PARTIALLY_SHIPPED','SHIPPED','PARTIALLY_RECEIVED','COMPLETED','CANCELLED')),
    ack_remarks                 VARCHAR(1000),
    acknowledged_at             TIMESTAMPTZ,
    acknowledged_by_user_id     UUID REFERENCES users(id),
    created_by_user_id          UUID REFERENCES users(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_po_sap_entry ON purchase_orders(company_id, sap_doc_entry) WHERE sap_doc_entry IS NOT NULL;
CREATE INDEX ix_po_company_vendor ON purchase_orders(company_id, vendor_id, status);
ALTER TABLE rfq_awards ADD CONSTRAINT fk_rfq_awards_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id);

CREATE TABLE purchase_order_lines (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_order_id           UUID NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    line_num                    INTEGER NOT NULL,                 -- = POR1.LineNum
    item_code                   VARCHAR(50) NOT NULL,
    item_name                   VARCHAR(200) NOT NULL,
    uom                         VARCHAR(20),
    quantity                    NUMERIC(19,6) NOT NULL CHECK (quantity > 0),
    unit_price                  NUMERIC(19,6) NOT NULL CHECK (unit_price >= 0),
    warehouse_code              VARCHAR(8) NOT NULL,
    tax_code                    VARCHAR(20),
    ship_date                   DATE,                             -- line delivery date (POR1.ShipDate)
    shipped_qty                 NUMERIC(19,6) NOT NULL DEFAULT 0, -- sum of ASN lines
    received_qty                NUMERIC(19,6) NOT NULL DEFAULT 0, -- sum of posted GRPO lines
    base_pr_line_id             UUID REFERENCES purchase_request_lines(id),
    UNIQUE (purchase_order_id, line_num),
    CHECK (shipped_qty >= 0 AND received_qty >= 0)
);

-- ---------------------------------------------------------------------
-- 26–29. ASN and GRPO — App-owned workflow; GRPO posts to B1 (Drafts → OPDN)
-- ---------------------------------------------------------------------
CREATE TABLE asns (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    purchase_order_id           UUID NOT NULL REFERENCES purchase_orders(id),
    asn_no                      VARCHAR(30) NOT NULL,
    ship_date                   DATE NOT NULL,
    expected_delivery           DATE NOT NULL,
    carrier                     VARCHAR(100) NOT NULL,
    tracking_no                 VARCHAR(100) NOT NULL,
    vendor_invoice_no           VARCHAR(50),
    cartons                     INTEGER,
    pallets                     INTEGER,
    total_weight                VARCHAR(40),
    status                      VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED'
                                CHECK (status IN ('SUBMITTED','GRPO_DRAFTED','RECEIVED','CANCELLED')),
    submitted_by_user_id        UUID REFERENCES users(id),
    submitted_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (company_id, asn_no)
);

CREATE TABLE asn_lines (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asn_id                      UUID NOT NULL REFERENCES asns(id) ON DELETE CASCADE,
    po_line_id                  UUID NOT NULL REFERENCES purchase_order_lines(id),
    shipped_qty                 NUMERIC(19,6) NOT NULL CHECK (shipped_qty > 0),
    batch_no                    VARCHAR(50),
    UNIQUE (asn_id, po_line_id)
);

CREATE TABLE grpos (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    asn_id                      UUID NOT NULL UNIQUE REFERENCES asns(id),
    purchase_order_id           UUID NOT NULL REFERENCES purchase_orders(id),
    sap_draft_entry             INTEGER,                         -- ODRF.DocEntry
    sap_doc_entry               INTEGER,                         -- OPDN.DocEntry once posted
    sap_doc_num                 INTEGER,
    status                      VARCHAR(20) NOT NULL DEFAULT 'DRAFT_PENDING'
                                CHECK (status IN ('DRAFT_PENDING','DRAFT_CREATED','DRAFT_FAILED','POSTING','POSTED','POST_FAILED')),
    posting_date                DATE,
    confirmed_by_user_id        UUID REFERENCES users(id),
    confirmed_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE grpo_lines (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grpo_id                     UUID NOT NULL REFERENCES grpos(id) ON DELETE CASCADE,
    po_line_id                  UUID NOT NULL REFERENCES purchase_order_lines(id),
    asn_qty                     NUMERIC(19,6) NOT NULL,
    received_qty                NUMERIC(19,6) NOT NULL CHECK (received_qty >= 0),
    rejected_qty                NUMERIC(19,6) NOT NULL DEFAULT 0 CHECK (rejected_qty >= 0),
    warehouse_code              VARCHAR(8) NOT NULL,
    UNIQUE (grpo_id, po_line_id)
);

-- ---------------------------------------------------------------------
-- 30. vendor_score_snapshots — Derived. Monthly roll-up for the scorecard trend.
-- ---------------------------------------------------------------------
CREATE TABLE vendor_score_snapshots (
    company_id                  UUID NOT NULL REFERENCES companies(id),
    vendor_id                   UUID NOT NULL REFERENCES vendors(id) ON DELETE CASCADE,
    period                      DATE NOT NULL,                  -- first day of month
    otif_pct                    NUMERIC(5,2),
    quality_pct                 NUMERIC(5,2),
    price_variance_pct          NUMERIC(7,2),
    po_count                    INTEGER NOT NULL DEFAULT 0,
    computed_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (vendor_id, period)
);

-- ---------------------------------------------------------------------
-- 31. sync_runs — App-owned (log). One row per master-data pull.
-- ---------------------------------------------------------------------
CREATE TABLE sync_runs (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    entity                      VARCHAR(30) NOT NULL,   -- ITEMS / WAREHOUSES / VENDOR_GROUPS / BUSINESS_PARTNERS / EMPLOYEES / TAX_CODES / PURCHASE_REQUESTS / PURCHASE_ORDERS
    trigger_type                VARCHAR(10) NOT NULL CHECK (trigger_type IN ('SCHEDULED','MANUAL','LOGIN')),
    status                      VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    records                     INTEGER NOT NULL DEFAULT 0,
    error_detail                TEXT,
    started_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at                 TIMESTAMPTZ
);
CREATE INDEX ix_sync_runs_company ON sync_runs(company_id, started_at DESC);

-- ---------------------------------------------------------------------
-- 32. sync_transactions — App-owned (outbox). One row per Service Layer write.
--     Worker claims rows with FOR UPDATE SKIP LOCKED (safe under pgbouncer
--     transaction pooling and with several backend instances).
-- ---------------------------------------------------------------------
CREATE TABLE sync_transactions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID NOT NULL REFERENCES companies(id),
    operation                   VARCHAR(30) NOT NULL
                                CHECK (operation IN ('CREATE_VENDOR','CREATE_PURCHASE_REQUEST','CREATE_PURCHASE_ORDER','CREATE_GRPO_DRAFT','POST_GRPO')),
    source_type                 VARCHAR(30) NOT NULL,   -- VENDOR / PURCHASE_REQUEST / PURCHASE_ORDER / GRPO
    source_id                   UUID NOT NULL,
    target_b1_object            VARCHAR(60) NOT NULL,   -- BusinessPartners, PurchaseOrders, Drafts, PurchaseDeliveryNotes …
    payload_snapshot            JSONB,                  -- exact request body sent (enables audit + retry)
    response_snapshot           JSONB,
    status                      VARCHAR(10) NOT NULL DEFAULT 'QUEUED'
                                CHECK (status IN ('QUEUED','RUNNING','POSTED','FAILED')),
    attempts                    INTEGER NOT NULL DEFAULT 0,
    max_attempts                INTEGER NOT NULL DEFAULT 5,
    next_attempt_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    sap_doc_entry               INTEGER,
    sap_key                     VARCHAR(50),            -- CardCode / DocNum returned by B1
    error_detail                TEXT,
    requested_by_user_id        UUID REFERENCES users(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at                 TIMESTAMPTZ
);
CREATE INDEX ix_sync_tx_pick ON sync_transactions(status, next_attempt_at) WHERE status = 'QUEUED';
CREATE INDEX ix_sync_tx_source ON sync_transactions(source_type, source_id);
