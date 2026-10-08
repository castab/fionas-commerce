-- Fiona's application baseline, applied by commerce-runtime after its own migrations, in the
-- `fionas` schema the runtime creates for this stream. It replaces the pre-production V1–V15
-- history (commerce 0.0.23 rebaselined its own stream at the same time): an existing
-- development database must be recreated, never migrated, repaired or baselined.
--
-- Fiona owns only these rows. References into `commerce` are foreign keys to structures the
-- runtime publishes for applications: `commerce.users(principal_id)` and
-- `commerce.financial_document_snapshots(document_id, version)`. Nothing here stores a product
-- catalog, pricing input, line, amount, total, stage, payment, balance, or payment status.

-- Durable customers, matched by normalized email (see AGENTS.md "Customer matching").
CREATE TABLE fionas.customers (
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    email      text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT customers_pkey PRIMARY KEY (id),
    CONSTRAINT customers_email_key UNIQUE (email)
);

-- Inquiries with their required event facts and the descriptive service the customer requested
-- (strict jsonb, restored by JdbiInquiryRepository). The request never prices anything.
CREATE TABLE fionas.inquiries (
    id                uuid        NOT NULL,
    customer_id       uuid        NOT NULL,
    message           text,
    created_at        timestamptz NOT NULL,
    zip_code          text        NOT NULL,
    event_date        date        NOT NULL,
    event_type        text        NOT NULL,
    requested_service jsonb       NOT NULL,
    CONSTRAINT inquiries_pkey PRIMARY KEY (id),
    CONSTRAINT inquiries_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES fionas.customers (id),
    CONSTRAINT inquiries_zip_code_check CHECK (zip_code ~ '^[0-9]{5}$'),
    CONSTRAINT inquiries_event_date_check CHECK (event_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    CONSTRAINT inquiries_event_type_check CHECK (
        event_type IN ('BIRTHDAY', 'WEDDING', 'CORPORATE', 'SCHOOL_EVENT', 'NEIGHBORHOOD_EVENT', 'OTHER')
    ),
    CONSTRAINT inquiries_requested_service_check CHECK (jsonb_typeof(requested_service) = 'object')
);

CREATE INDEX inquiries_customer_id_idx ON fionas.inquiries (customer_id);
CREATE INDEX inquiries_created_at_id_idx ON fionas.inquiries (created_at, id);

-- Staff password credentials for runtime-owned users.
CREATE TABLE fionas.user_credentials (
    user_id             uuid        NOT NULL,
    password_hash       text        NOT NULL,
    password_changed_at timestamptz NOT NULL,
    CONSTRAINT user_credentials_pkey PRIMARY KEY (user_id),
    CONSTRAINT user_credentials_user_id_fkey FOREIGN KEY (user_id) REFERENCES commerce.users (principal_id)
);

-- Which inquiry owns each commerce-runtime financial lineage, named by its first snapshot. At most
-- one INITIAL_ESTIMATE lineage per inquiry; every other lineage is RELATED.
CREATE TABLE fionas.inquiry_financial_documents (
    document_id     uuid        NOT NULL,
    initial_version integer     NOT NULL DEFAULT 1,
    inquiry_id      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    purpose         text        NOT NULL,
    CONSTRAINT inquiry_financial_documents_pkey PRIMARY KEY (document_id),
    CONSTRAINT inquiry_financial_documents_initial_version_check CHECK (initial_version = 1),
    CONSTRAINT inquiry_financial_documents_purpose_check CHECK (purpose IN ('INITIAL_ESTIMATE', 'RELATED')),
    CONSTRAINT inquiry_financial_documents_inquiry_document_key UNIQUE (inquiry_id, document_id),
    CONSTRAINT inquiry_financial_documents_inquiry_id_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiries (id),
    CONSTRAINT inquiry_financial_documents_snapshot_fkey FOREIGN KEY (document_id, initial_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

CREATE INDEX inquiry_financial_documents_inquiry_id_idx ON fionas.inquiry_financial_documents (inquiry_id, created_at);
CREATE UNIQUE INDEX inquiry_financial_documents_initial_estimate_idx
    ON fionas.inquiry_financial_documents (inquiry_id)
    WHERE purpose = 'INITIAL_ESTIMATE';

-- Who committed the lines of each exact snapshot whose lines an actor supplied (carried forward
-- unchanged by stage transitions): provenance only, never an amount.
CREATE TABLE fionas.financial_document_authorship (
    document_id      uuid           NOT NULL,
    document_version integer        NOT NULL,
    author_kind      text           NOT NULL,
    author_id        uuid           NOT NULL,
    recorded_at      timestamptz(6) NOT NULL,
    CONSTRAINT financial_document_authorship_pkey PRIMARY KEY (document_id, document_version),
    CONSTRAINT financial_document_authorship_author_kind_check CHECK (author_kind IN ('USER', 'SERVICE')),
    CONSTRAINT financial_document_authorship_document_id_fkey FOREIGN KEY (document_id)
        REFERENCES fionas.inquiry_financial_documents (document_id),
    CONSTRAINT financial_document_authorship_snapshot_fkey FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

-- Durable public submission identity: one key, one semantic command, one committed result. The
-- deferred inquiry reference lets a claim precede its inquiry inside one transaction.
CREATE TABLE fionas.inquiry_submissions (
    idempotency_key     text        NOT NULL,
    request_fingerprint text        NOT NULL,
    inquiry_id          uuid        NOT NULL,
    created_at          timestamptz NOT NULL,
    CONSTRAINT inquiry_submissions_pkey PRIMARY KEY (idempotency_key),
    CONSTRAINT inquiry_submissions_inquiry_id_key UNIQUE (inquiry_id),
    CONSTRAINT inquiry_submissions_key_check CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT inquiry_submissions_fingerprint_check CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT inquiry_submissions_inquiry_fkey FOREIGN KEY (inquiry_id)
        REFERENCES fionas.inquiries (id) DEFERRABLE INITIALLY DEFERRED
);

-- Served and closed operational facts with their authenticated provenance.
CREATE TABLE fionas.inquiry_fulfillment (
    inquiry_id     uuid        NOT NULL,
    served_at      timestamptz NOT NULL,
    served_by_kind text        NOT NULL,
    served_by_id   uuid        NOT NULL,
    closed_at      timestamptz,
    closed_by_kind text,
    closed_by_id   uuid,
    CONSTRAINT inquiry_fulfillment_pkey PRIMARY KEY (inquiry_id),
    CONSTRAINT inquiry_fulfillment_inquiry_id_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiries (id),
    CONSTRAINT inquiry_fulfillment_served_by_kind_check CHECK (served_by_kind IN ('USER', 'SERVICE')),
    CONSTRAINT inquiry_fulfillment_closed_by_kind_check CHECK (closed_by_kind IN ('USER', 'SERVICE')),
    CONSTRAINT inquiry_fulfillment_closed_check CHECK (
        (closed_at IS NULL AND closed_by_kind IS NULL AND closed_by_id IS NULL)
        OR (closed_at IS NOT NULL AND closed_by_kind IS NOT NULL AND closed_by_id IS NOT NULL)
    )
);

-- Append-only communication activity; recorded_order is durable ingestion order.
CREATE TABLE fionas.inquiry_communications (
    id             uuid           NOT NULL,
    recorded_order bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    inquiry_id     uuid           NOT NULL,
    kind           text           NOT NULL,
    occurred_at    timestamptz(6) NOT NULL,
    principal_kind text,
    principal_id   uuid,
    CONSTRAINT inquiry_communications_pkey PRIMARY KEY (id),
    CONSTRAINT inquiry_communications_recorded_order_key UNIQUE (recorded_order),
    CONSTRAINT inquiry_communications_recorded_order_check CHECK (recorded_order > 0),
    CONSTRAINT inquiry_communications_inquiry_id_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiries (id),
    CONSTRAINT inquiry_communications_kind_check CHECK (kind IN ('CUSTOMER_EMAIL_RECEIVED', 'STAFF_EMAIL_SENT', 'STAFF_ACKNOWLEDGED')),
    CONSTRAINT inquiry_communications_principal_kind_check CHECK (principal_kind IN ('USER', 'SERVICE')),
    CONSTRAINT inquiry_communications_principal_check CHECK (
        (kind = 'CUSTOMER_EMAIL_RECEIVED' AND principal_kind IS NULL AND principal_id IS NULL)
        OR (kind IN ('STAFF_EMAIL_SENT', 'STAFF_ACKNOWLEDGED') AND principal_kind IS NOT NULL AND principal_id IS NOT NULL)
    )
);

CREATE INDEX inquiry_communications_inquiry_order_idx ON fionas.inquiry_communications (inquiry_id, recorded_order)
    INCLUDE (kind, occurred_at);

-- Append-only publications of exact canonical Quote/deposit pairs, each by a verified staff user.
CREATE TABLE fionas.inquiry_proposals (
    id                           uuid           NOT NULL,
    recorded_order               bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    inquiry_id                   uuid           NOT NULL,
    document_id                  uuid           NOT NULL,
    document_version             integer        NOT NULL,
    deposit_requirement_revision integer        NOT NULL,
    kind                         text           NOT NULL,
    issued_at                    timestamptz(6) NOT NULL,
    issued_by                    uuid           NOT NULL,
    CONSTRAINT inquiry_proposals_pkey PRIMARY KEY (id),
    CONSTRAINT inquiry_proposals_recorded_order_key UNIQUE (recorded_order),
    CONSTRAINT inquiry_proposals_recorded_order_check CHECK (recorded_order > 0),
    CONSTRAINT inquiry_proposals_document_version_check CHECK (document_version >= 2),
    CONSTRAINT inquiry_proposals_deposit_requirement_revision_check CHECK (deposit_requirement_revision >= 1),
    CONSTRAINT inquiry_proposals_kind_check CHECK (kind IN ('INITIAL', 'QUOTE_REVISED', 'DEPOSIT_REVISED')),
    CONSTRAINT inquiry_proposals_association_fkey FOREIGN KEY (inquiry_id, document_id)
        REFERENCES fionas.inquiry_financial_documents (inquiry_id, document_id),
    CONSTRAINT inquiry_proposals_document_fkey FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version),
    CONSTRAINT inquiry_proposals_issued_by_fkey FOREIGN KEY (issued_by) REFERENCES commerce.users (principal_id),
    CONSTRAINT inquiry_proposals_pair_key UNIQUE (document_id, document_version, deposit_requirement_revision)
);

CREATE INDEX inquiry_proposals_inquiry_order_idx ON fionas.inquiry_proposals (inquiry_id, recorded_order DESC);
CREATE UNIQUE INDEX inquiry_proposals_initial_idx ON fionas.inquiry_proposals (inquiry_id) WHERE kind = 'INITIAL';

-- Immutable approved service plans, one per exact canonical Quote snapshot: the staff service
-- commitment and line notes (strict, money-free jsonb), with the approving staff user.
CREATE TABLE fionas.inquiry_service_plans (
    document_id               uuid           NOT NULL,
    document_version          integer        NOT NULL,
    inquiry_id                uuid           NOT NULL,
    reviewed_document_version integer        NOT NULL,
    plan                      jsonb          NOT NULL,
    approved_at               timestamptz(6) NOT NULL,
    approved_by               uuid           NOT NULL,
    CONSTRAINT inquiry_service_plans_pkey PRIMARY KEY (document_id, document_version),
    CONSTRAINT inquiry_service_plans_document_version_check CHECK (document_version >= 2),
    CONSTRAINT inquiry_service_plans_reviewed_check
        CHECK (reviewed_document_version >= 1 AND reviewed_document_version < document_version),
    CONSTRAINT inquiry_service_plans_plan_check CHECK (jsonb_typeof(plan) = 'object'),
    CONSTRAINT inquiry_service_plans_association_fkey FOREIGN KEY (inquiry_id, document_id)
        REFERENCES fionas.inquiry_financial_documents (inquiry_id, document_id),
    CONSTRAINT inquiry_service_plans_document_fkey FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version),
    CONSTRAINT inquiry_service_plans_reviewed_fkey FOREIGN KEY (document_id, reviewed_document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version),
    CONSTRAINT inquiry_service_plans_approved_by_fkey FOREIGN KEY (approved_by) REFERENCES commerce.users (principal_id)
);
