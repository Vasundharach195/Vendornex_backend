-- =====================================================================
-- Ikyam VendorNex — PostgreSQL schema (V1, initial)
--
--   ik_vendor           control schema: companies, company_settings, global_users (login /
--                       invite / activation), login_activity, jwt_tokens (SUPER_ADMIN tokens
--                       only), b1_sessions, schema_migrations
--   <tenant schema>     every other table, created per company at onboarding from
--                       db/tenant/T1__init.sql (vnx_c00001, vnx_c00002, ...). Holds the
--                       company's users and their jwt_tokens.
--   public              no application tables
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS ik_vendor;

-- ---------------------------------------------------------------------
-- 1. companies — App-owned (tenant root)
-- ---------------------------------------------------------------------
CREATE TABLE ik_vendor.companies (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_id                   VARCHAR(30) UNIQUE,           -- this company's Postgres schema
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

-- Numbering for tenant schema names.
CREATE SEQUENCE ik_vendor.tenant_schema_seq;

-- ---------------------------------------------------------------------
-- 2. company_settings — App-owned, 1:1 with companies
-- ---------------------------------------------------------------------
CREATE TABLE ik_vendor.company_settings (
    company_id                  UUID PRIMARY KEY REFERENCES ik_vendor.companies(id) ON DELETE CASCADE,
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
-- 3. global_users — the only table authentication reads. SUPER_ADMIN lives here only;
--    company users are kept identical to each tenant's users table by the triggers below.
-- ---------------------------------------------------------------------
CREATE TABLE ik_vendor.global_users (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id                  UUID REFERENCES ik_vendor.companies(id),   -- NULL only for SUPER_ADMIN
    name                        VARCHAR(150) NOT NULL,
    email                       VARCHAR(200) NOT NULL,
    password_hash               VARCHAR(100),                     -- bcrypt; NULL until invite accepted
    role                        VARCHAR(20) NOT NULL
                                CHECK (role IN ('SUPER_ADMIN','ADMIN','APPROVER','REQUESTER','VENDOR')),
    status                      VARCHAR(20) NOT NULL DEFAULT 'INVITED'
                                CHECK (status IN ('INVITED','PENDING_APPROVAL','ACTIVE','REJECTED','DISABLED')),
    vendor_id                   UUID,                             -- set when role = VENDOR (row lives in the tenant schema)
    department                  VARCHAR(100),                     -- requesters
    sap_employee_id             INTEGER,                          -- OHEM.empID, optional mapping for PR push
    invite_token                VARCHAR(80),
    invite_expires_at           TIMESTAMPTZ,
    rejection_reason            VARCHAR(1000),
    last_login_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_global_users_company CHECK ((role = 'SUPER_ADMIN') = (company_id IS NULL)),
    CONSTRAINT ck_global_users_vendor  CHECK ((role = 'VENDOR') = (vendor_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_global_users_email ON ik_vendor.global_users(lower(email));
CREATE UNIQUE INDEX ux_global_users_invite ON ik_vendor.global_users(invite_token) WHERE invite_token IS NOT NULL;
CREATE INDEX ix_global_users_company_role ON ik_vendor.global_users(company_id, role);

-- ---------------------------------------------------------------------
-- 4. login_activity — App-owned (audit)
-- ---------------------------------------------------------------------
CREATE TABLE ik_vendor.login_activity (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID REFERENCES ik_vendor.global_users(id),
    company_id                  UUID REFERENCES ik_vendor.companies(id),
    email                       VARCHAR(200),
    event                       VARCHAR(20) NOT NULL CHECK (event IN ('LOGIN','LOGIN_FAILED','LOGOUT','INVITE_ACCEPTED')),
    ip_address                  VARCHAR(64),
    user_agent                  VARCHAR(400),
    occurred_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_login_activity_company ON ik_vendor.login_activity(company_id, occurred_at DESC);

-- ---------------------------------------------------------------------
-- 5. jwt_tokens — SUPER_ADMIN session tokens only (hash only, never the raw JWT).
--    Every company user's tokens go to jwt_tokens in that company's tenant schema.
-- ---------------------------------------------------------------------
CREATE TABLE ik_vendor.jwt_tokens (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID NOT NULL REFERENCES ik_vendor.global_users(id) ON DELETE CASCADE,
    role                        VARCHAR(20),
    token_hash                  VARCHAR(64) NOT NULL,
    issued_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at                  TIMESTAMPTZ
);
CREATE INDEX ix_jwt_tokens_user ON ik_vendor.jwt_tokens(user_id);

-- ---------------------------------------------------------------------
-- 6. b1_sessions — App-owned; SAP B1 Service Layer session per company.
--    Table only for now: SapGatewayFactory still keeps sessions in memory.
-- ---------------------------------------------------------------------
CREATE TABLE ik_vendor.b1_sessions (
    company_id                  UUID PRIMARY KEY REFERENCES ik_vendor.companies(id) ON DELETE CASCADE,
    schema_id                   VARCHAR(30),
    sap_username                VARCHAR(100),
    session_id                  TEXT,
    sap_db                      VARCHAR(100),
    expires_at                  TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 7. Keep global_users and each tenant's users table identical.
--    Application code keeps writing the tenant users table (user management) and
--    global_users (login, invite, activation, password); these triggers copy every
--    change to the other side. pg_trigger_depth() stops the copy from echoing back.
-- ---------------------------------------------------------------------
CREATE FUNCTION ik_vendor.sync_user_to_global() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF pg_trigger_depth() > 1 THEN RETURN NULL; END IF;
    IF TG_OP = 'DELETE' THEN
        DELETE FROM ik_vendor.global_users WHERE id = OLD.id;
        RETURN NULL;
    END IF;
    INSERT INTO ik_vendor.global_users
           (id, company_id, name, email, password_hash, role, status, vendor_id, department, sap_employee_id,
            invite_token, invite_expires_at, rejection_reason, last_login_at, created_at, updated_at)
    VALUES (NEW.id, NEW.company_id, NEW.name, NEW.email, NEW.password_hash, NEW.role, NEW.status, NEW.vendor_id,
            NEW.department, NEW.sap_employee_id, NEW.invite_token, NEW.invite_expires_at, NEW.rejection_reason,
            NEW.last_login_at, NEW.created_at, NEW.updated_at)
    ON CONFLICT (id) DO UPDATE SET
            company_id = EXCLUDED.company_id, name = EXCLUDED.name, email = EXCLUDED.email,
            password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, status = EXCLUDED.status,
            vendor_id = EXCLUDED.vendor_id, department = EXCLUDED.department, sap_employee_id = EXCLUDED.sap_employee_id,
            invite_token = EXCLUDED.invite_token, invite_expires_at = EXCLUDED.invite_expires_at,
            rejection_reason = EXCLUDED.rejection_reason, last_login_at = EXCLUDED.last_login_at,
            updated_at = EXCLUDED.updated_at;
    RETURN NULL;
END $$;

CREATE FUNCTION ik_vendor.sync_user_to_tenant() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    tenant TEXT;
BEGIN
    IF pg_trigger_depth() > 1 THEN RETURN NULL; END IF;
    IF TG_OP = 'DELETE' THEN
        SELECT schema_id INTO tenant FROM ik_vendor.companies WHERE id = OLD.company_id;
        IF tenant IS NOT NULL THEN
            EXECUTE format('DELETE FROM %I.users WHERE id = $1', tenant) USING OLD.id;
        END IF;
        RETURN NULL;
    END IF;
    SELECT schema_id INTO tenant FROM ik_vendor.companies WHERE id = NEW.company_id;
    IF tenant IS NULL THEN RETURN NULL; END IF;   -- SUPER_ADMIN: lives in global_users only
    EXECUTE format($sql$
        INSERT INTO %I.users
               (id, company_id, name, email, password_hash, role, status, vendor_id, department, sap_employee_id,
                invite_token, invite_expires_at, rejection_reason, last_login_at, created_at, updated_at)
        VALUES (($1).id, ($1).company_id, ($1).name, ($1).email, ($1).password_hash, ($1).role, ($1).status, ($1).vendor_id,
                ($1).department, ($1).sap_employee_id, ($1).invite_token, ($1).invite_expires_at, ($1).rejection_reason,
                ($1).last_login_at, ($1).created_at, ($1).updated_at)
        ON CONFLICT (id) DO UPDATE SET
                company_id = EXCLUDED.company_id, name = EXCLUDED.name, email = EXCLUDED.email,
                password_hash = EXCLUDED.password_hash, role = EXCLUDED.role, status = EXCLUDED.status,
                vendor_id = EXCLUDED.vendor_id, department = EXCLUDED.department, sap_employee_id = EXCLUDED.sap_employee_id,
                invite_token = EXCLUDED.invite_token, invite_expires_at = EXCLUDED.invite_expires_at,
                rejection_reason = EXCLUDED.rejection_reason, last_login_at = EXCLUDED.last_login_at,
                updated_at = EXCLUDED.updated_at
        $sql$, tenant) USING NEW;
    RETURN NULL;
END $$;

CREATE TRIGGER trg_global_users_sync_tenant AFTER INSERT OR UPDATE OR DELETE ON ik_vendor.global_users
    FOR EACH ROW EXECUTE FUNCTION ik_vendor.sync_user_to_tenant();
