-- Independent access and provider integration facts; commerce remains the financial authority.
CREATE TABLE fionas.inquiry_payment_access (
    inquiry_id uuid PRIMARY KEY REFERENCES fionas.inquiries (id),
    public_id uuid NOT NULL UNIQUE,
    generation text NOT NULL,
    token_hash text NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    revoked boolean NOT NULL DEFAULT false,
    issued_at timestamptz(6) NOT NULL,
    issued_by uuid NOT NULL REFERENCES commerce.users (principal_id)
);

CREATE TABLE fionas.stripe_payment_attempts (
    id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL REFERENCES fionas.inquiries (id),
    public_id uuid NOT NULL,
    request_key text NOT NULL CHECK (request_key ~ '^[A-Za-z0-9_-]{1,128}$'),
    request_fingerprint text NOT NULL,
    document_id uuid NOT NULL,
    document_version integer NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('DEPOSIT', 'INVOICE')),
    business_revision integer NOT NULL CHECK (business_revision > 0),
    proposal_id uuid REFERENCES fionas.inquiry_proposals (id),
    deposit_revision integer,
    authorized_minor bigint NOT NULL CHECK (authorized_minor BETWEEN 50 AND 99999999),
    currency text NOT NULL CHECK (currency = 'USD'),
    stripe_account text NOT NULL,
    live_mode boolean NOT NULL,
    provider_key text NOT NULL UNIQUE,
    created_at timestamptz(6) NOT NULL,
    expires_at timestamptz(6) NOT NULL CHECK (expires_at > created_at),
    session_id text UNIQUE,
    payment_intent_id text UNIQUE,
    provider_state text NOT NULL CHECK (provider_state IN ('CREATING', 'OPEN', 'PROCESSING', 'EXPIRED', 'FAILED', 'SUCCEEDED')),
    outcome text NOT NULL CHECK (outcome IN ('PENDING', 'APPLIED', 'RECONCILIATION_REQUIRED')),
    ledger_payment_id uuid UNIQUE,
    diagnostic text,
    UNIQUE (inquiry_id, request_key),
    FOREIGN KEY (inquiry_id, document_id) REFERENCES fionas.inquiry_financial_documents (inquiry_id, document_id),
    CHECK ((ledger_payment_id IS NOT NULL) = (provider_state = 'SUCCEEDED')),
    CHECK ((outcome = 'PENDING') = (ledger_payment_id IS NULL))
);
-- Unknown creation outcomes remain active even after wall-clock expiry until Stripe confirms.
CREATE UNIQUE INDEX stripe_one_active_inquiry ON fionas.stripe_payment_attempts (inquiry_id)
    WHERE provider_state IN ('CREATING', 'OPEN', 'PROCESSING');

-- Only non-sensitive event identity and disposition, never a raw Stripe payload.
CREATE TABLE fionas.stripe_webhook_events (
    event_id text PRIMARY KEY,
    event_type text NOT NULL,
    object_id text NOT NULL,
    payment_intent_id text,
    attempt_id uuid REFERENCES fionas.stripe_payment_attempts (id),
    received_at timestamptz(6) NOT NULL,
    disposition text NOT NULL CHECK (disposition IN ('PROCESSED', 'IGNORED', 'EXTERNAL_ADJUSTMENT'))
);
CREATE INDEX stripe_external_adjustments ON fionas.stripe_webhook_events (received_at)
    WHERE disposition = 'EXTERNAL_ADJUSTMENT';
