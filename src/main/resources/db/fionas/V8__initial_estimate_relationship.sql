-- Existing staff-created lineages remain related; never infer an initial estimate from time.
ALTER TABLE fionas.inquiry_financial_documents
    ADD COLUMN purpose text NOT NULL DEFAULT 'RELATED',
    ADD CONSTRAINT inquiry_financial_documents_purpose_check
        CHECK (purpose IN ('INITIAL_ESTIMATE', 'RELATED'));

CREATE UNIQUE INDEX inquiry_financial_documents_initial_estimate_idx
    ON fionas.inquiry_financial_documents (inquiry_id)
    WHERE purpose = 'INITIAL_ESTIMATE';
