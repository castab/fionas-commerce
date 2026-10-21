-- Additive: terminal staff decisions not to pursue a REQUESTED inquiry. Existing inquiries, their
-- canonical Estimates and every other row are untouched; an inquiry without a row here is undeclined.
--
-- One immutable row per inquiry (the primary key), naming the exact canonical Estimate snapshot the
-- staff user reviewed and the verified staff USER who decided. It is an operational fact beside the
-- financial lineage, never a lifecycle status column: the lifecycle stays a projection, and nothing
-- here stores an amount, total, stage or status. The reason is an internal staff note.
CREATE TABLE fionas.inquiry_declines (
    inquiry_id       uuid           NOT NULL,
    document_id      uuid           NOT NULL,
    document_version integer        NOT NULL,
    declined_at      timestamptz(6) NOT NULL,
    declined_by      uuid           NOT NULL,
    reason           text           NOT NULL,
    CONSTRAINT inquiry_declines_pkey PRIMARY KEY (inquiry_id),
    CONSTRAINT inquiry_declines_reason_check CHECK (char_length(reason) BETWEEN 1 AND 500 AND reason = btrim(reason)),
    CONSTRAINT inquiry_declines_inquiry_id_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiries (id),
    CONSTRAINT inquiry_declines_association_fkey FOREIGN KEY (inquiry_id, document_id)
        REFERENCES fionas.inquiry_financial_documents (inquiry_id, document_id),
    CONSTRAINT inquiry_declines_document_fkey FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version),
    CONSTRAINT inquiry_declines_declined_by_fkey FOREIGN KEY (declined_by) REFERENCES commerce.users (principal_id)
);
