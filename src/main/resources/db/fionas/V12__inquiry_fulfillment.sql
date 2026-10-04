CREATE TABLE fionas.inquiry_fulfillment (
    inquiry_id uuid PRIMARY KEY REFERENCES fionas.inquiries(id),
    served_at timestamptz NOT NULL,
    served_by_kind text NOT NULL CHECK (served_by_kind IN ('USER', 'SERVICE')),
    served_by_id uuid NOT NULL,
    closed_at timestamptz,
    closed_by_kind text CHECK (closed_by_kind IN ('USER', 'SERVICE')),
    closed_by_id uuid,
    CHECK (
        (closed_at IS NULL AND closed_by_kind IS NULL AND closed_by_id IS NULL)
        OR (closed_at IS NOT NULL AND closed_by_kind IS NOT NULL AND closed_by_id IS NOT NULL)
    )
);
