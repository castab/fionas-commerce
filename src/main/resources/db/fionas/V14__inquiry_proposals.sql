-- Fiona's durable publication event; immutable commerce facts remain in the ledger.
ALTER TABLE fionas.inquiry_financial_documents
    ADD CONSTRAINT inquiry_financial_documents_inquiry_document_key UNIQUE (inquiry_id, document_id);

CREATE TABLE fionas.inquiry_proposals (
    id uuid PRIMARY KEY,
    recorded_order bigint GENERATED ALWAYS AS IDENTITY NOT NULL UNIQUE CHECK (recorded_order > 0),
    inquiry_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version integer NOT NULL CHECK (document_version >= 2),
    deposit_requirement_revision integer NOT NULL CHECK (deposit_requirement_revision >= 1),
    kind text NOT NULL CHECK (kind IN ('INITIAL', 'QUOTE_REVISED', 'DEPOSIT_REVISED')),
    issued_at timestamptz(6) NOT NULL,
    principal_kind text NOT NULL CHECK (principal_kind IN ('USER', 'SERVICE')),
    principal_id uuid NOT NULL,
    CONSTRAINT inquiry_proposals_association_fkey FOREIGN KEY (inquiry_id, document_id)
        REFERENCES fionas.inquiry_financial_documents (inquiry_id, document_id),
    CONSTRAINT inquiry_proposals_pair_key UNIQUE (document_id, document_version, deposit_requirement_revision)
);

CREATE INDEX inquiry_proposals_inquiry_order_idx ON fionas.inquiry_proposals (inquiry_id, recorded_order DESC);
CREATE UNIQUE INDEX inquiry_proposals_initial_idx ON fionas.inquiry_proposals (inquiry_id) WHERE kind = 'INITIAL';
