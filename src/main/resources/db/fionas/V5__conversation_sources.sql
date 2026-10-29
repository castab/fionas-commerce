-- Provider conversation events already processed for a Fiona booking topic: durable at-least-once deduplication.
-- No provider content is retained; the fingerprint detects a reused event identity carrying a different payload.
CREATE TABLE fionas.inquiry_conversation_events (
    event_id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL REFERENCES fionas.inquiries(id),
    conversation_id uuid NOT NULL,
    provider_sequence bigint NOT NULL CHECK (provider_sequence > 0),
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    UNIQUE (conversation_id, provider_sequence)
);
CREATE INDEX inquiry_conversation_events_inquiry_idx ON fionas.inquiry_conversation_events(inquiry_id);

-- Message identity is distinct from transport event identity. No provider content is retained.
CREATE TABLE fionas.inquiry_customer_email_sources (
    message_id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL REFERENCES fionas.inquiries(id),
    occurred_at timestamptz(6) NOT NULL,
    communication_id uuid NOT NULL UNIQUE REFERENCES fionas.inquiry_communications(id)
);

-- One staff reply command per Idempotency-Key. It is committed before the provider call so an ambiguous outcome can be
-- replayed with the exact provider request and key. The body is never stored: replays must resubmit it (fingerprinted).
CREATE TABLE fionas.inquiry_staff_replies (
    idempotency_key text PRIMARY KEY CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,128}$'),
    inquiry_id uuid NOT NULL REFERENCES fionas.inquiries(id),
    fingerprint text NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    sent_by uuid NOT NULL CONSTRAINT inquiry_staff_replies_sent_by_fkey REFERENCES commerce.users (principal_id),
    mode text NOT NULL CHECK (mode IN ('OPEN', 'REPLY')),
    provider_request text NOT NULL CHECK (provider_request ~ '^[0-9a-f]{64}$'),
    signature_name text NOT NULL CHECK (length(signature_name) BETWEEN 1 AND 256),
    requested_at timestamptz(6) NOT NULL,
    outcome text CHECK (outcome IN ('ACCEPTED', 'REJECTED')),
    conversation_id uuid,
    provider_message_id uuid UNIQUE,
    provider_state text CHECK (provider_state IN ('pending', 'accepted')),
    communication_id uuid UNIQUE REFERENCES fionas.inquiry_communications(id),
    CHECK (mode = 'OPEN' OR conversation_id IS NOT NULL),
    CHECK (
        (outcome IS NULL AND provider_message_id IS NULL AND provider_state IS NULL AND communication_id IS NULL) OR
        (outcome = 'ACCEPTED' AND conversation_id IS NOT NULL AND provider_message_id IS NOT NULL
            AND provider_state IS NOT NULL AND communication_id IS NOT NULL) OR
        (outcome = 'REJECTED' AND provider_message_id IS NULL AND provider_state IS NULL AND communication_id IS NULL)
    )
);
CREATE INDEX inquiry_staff_replies_inquiry_idx ON fionas.inquiry_staff_replies(inquiry_id);
