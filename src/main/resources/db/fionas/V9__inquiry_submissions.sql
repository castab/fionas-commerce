-- A key represents one committed inquiry command, retained for the inquiry's lifetime.
-- INSERT ON CONFLICT is the serialization point; contenders wait for commit or rollback.
-- Reserve the resulting inquiry id in the claim. The deferred FK permits claim-before-write
-- but prevents any incomplete claim from committing, even if application wiring is wrong.
CREATE TABLE fionas.inquiry_submissions (
    idempotency_key     text        PRIMARY KEY,
    request_fingerprint text       NOT NULL,
    inquiry_id         uuid        NOT NULL UNIQUE,
    created_at         timestamptz NOT NULL,
    CONSTRAINT inquiry_submissions_key_check
        CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT inquiry_submissions_fingerprint_check
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT inquiry_submissions_inquiry_fkey FOREIGN KEY (inquiry_id)
        REFERENCES fionas.inquiries (id) DEFERRABLE INITIALLY DEFERRED
);
