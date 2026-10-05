CREATE TABLE fionas.inquiry_communications (
    id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL REFERENCES fionas.inquiries(id),
    kind text NOT NULL CHECK (kind IN ('CUSTOMER_EMAIL_RECEIVED', 'STAFF_EMAIL_SENT', 'STAFF_ACKNOWLEDGED')),
    occurred_at timestamptz(6) NOT NULL,
    principal_kind text CHECK (principal_kind IN ('USER', 'SERVICE')),
    principal_id uuid,
    CHECK (
        (kind = 'CUSTOMER_EMAIL_RECEIVED' AND principal_kind IS NULL AND principal_id IS NULL)
        OR (kind IN ('STAFF_EMAIL_SENT', 'STAFF_ACKNOWLEDGED') AND principal_kind IS NOT NULL AND principal_id IS NOT NULL)
    )
);

CREATE INDEX inquiry_communications_inquiry_at_idx ON fionas.inquiry_communications (inquiry_id, occurred_at);
