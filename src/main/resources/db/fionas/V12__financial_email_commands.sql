-- Fiona command identities and communication purpose only. Financial truth remains in the runtime ledger.
CREATE TABLE fionas.inquiry_financial_emails (
    id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL REFERENCES fionas.inquiries(id),
    idempotency_key text NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,128}$'),
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    sent_by uuid NOT NULL REFERENCES commerce.users (principal_id),
    kind text NOT NULL CHECK (kind IN ('QUOTE', 'INVOICE')),
    document_id uuid NOT NULL,
    document_version integer NOT NULL CHECK (document_version > 0),
    business_revision integer NOT NULL CHECK (business_revision > 0),
    proposal_id uuid REFERENCES fionas.inquiry_proposals(id),
    recovery_digest text NOT NULL CHECK (recovery_digest ~ '^[a-f0-9]{64}$'),
    provider_request text NOT NULL CHECK (provider_request ~ '^[a-f0-9]{64}$'),
    signature_name text NOT NULL,
    opening boolean NOT NULL,
    requested_at timestamptz(6) NOT NULL,
    observed_through bigint NOT NULL CHECK (observed_through >= 0),
    dispatch_count integer NOT NULL DEFAULT 0 CHECK (dispatch_count >= 0),
    conversation_id uuid,
    outcome text CHECK (outcome IN ('ACCEPTED', 'REJECTED')),
    provider_message_id uuid UNIQUE,
    provider_state text CHECK (provider_state IN ('pending', 'accepted', 'indeterminate')),
    communication_id uuid UNIQUE REFERENCES fionas.inquiry_communications(id),
    UNIQUE (inquiry_id, idempotency_key),
    FOREIGN KEY (inquiry_id, document_id) REFERENCES fionas.inquiry_financial_documents(inquiry_id, document_id),
    FOREIGN KEY (document_id, document_version) REFERENCES commerce.financial_document_snapshots (document_id, version),
    CHECK ((kind = 'QUOTE') = (proposal_id IS NOT NULL)),
    CHECK (opening OR conversation_id IS NOT NULL),
    CHECK (
      (outcome IS NOT DISTINCT FROM 'ACCEPTED' AND provider_message_id IS NOT NULL
        AND provider_state IS NOT NULL AND provider_state IN ('pending', 'accepted')
        AND communication_id IS NOT NULL AND conversation_id IS NOT NULL)
      OR (outcome IS NOT DISTINCT FROM 'REJECTED' AND provider_message_id IS NULL AND provider_state IS NULL AND communication_id IS NULL)
      OR (outcome IS NULL AND communication_id IS NULL AND (
         (provider_message_id IS NULL AND provider_state IS NULL)
         OR (provider_message_id IS NOT NULL AND provider_state IS NOT NULL
             AND provider_state = 'indeterminate' AND conversation_id IS NOT NULL)))
    )
);
-- An ambiguous intent reserves the inquiry. A new key must never become an accidental replacement send.
CREATE UNIQUE INDEX inquiry_financial_emails_unresolved_idx
    ON fionas.inquiry_financial_emails(inquiry_id) WHERE outcome IS NULL;
CREATE INDEX inquiry_financial_emails_history_idx ON fionas.inquiry_financial_emails(inquiry_id, requested_at, id);

-- Invalidate also on durable UNKNOWN / definitive rejection, which have no communication fact.
CREATE TRIGGER financial_email_timeline_revision
    AFTER INSERT OR UPDATE ON fionas.inquiry_financial_emails
    FOR EACH ROW EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();
