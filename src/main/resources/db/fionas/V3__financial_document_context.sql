-- Fiona's context for commerce-runtime's financial documents. The documents themselves
-- (immutable snapshots, their lines and derived totals), payments, allocations, and
-- reconciliation belong to commerce-runtime, in its commerce schema, and are never copied
-- here. Fiona records only which inquiry a document lineage belongs to, and the Fiona
-- pricing inputs each snapshot was priced from. Both reference commerce-runtime's published
-- commerce.financial_document_snapshots (document_id, version) key and change nothing in it.

-- Which inquiry each financial-document lineage belongs to. An inquiry may have several
-- lineages (an alternative or restarted proposal); a lineage belongs to exactly one inquiry.
-- The runtime identifies a lineage by its document id and keys snapshots by
-- (document_id, version), so the reference names the lineage's first snapshot explicitly:
-- every lineage has a version 1, and it is never removed.
CREATE TABLE fionas.inquiry_financial_documents (
    document_id     uuid        NOT NULL,
    initial_version integer     NOT NULL DEFAULT 1,
    inquiry_id      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT inquiry_financial_documents_pkey PRIMARY KEY (document_id),
    CONSTRAINT inquiry_financial_documents_initial_version_check CHECK (initial_version = 1),
    CONSTRAINT inquiry_financial_documents_inquiry_id_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiries (id),
    CONSTRAINT inquiry_financial_documents_snapshot_fkey FOREIGN KEY (document_id, initial_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

CREATE INDEX inquiry_financial_documents_inquiry_id_idx ON fionas.inquiry_financial_documents (inquiry_id, created_at);

-- Why Fiona priced each snapshot the way it did: one row per exact (document_id, version),
-- written with the snapshot and never changed. A change order records its revised inputs;
-- a lifecycle transition (quote, invoice) records its source's inputs again.
CREATE TABLE fionas.financial_document_pricing (
    document_id            uuid    NOT NULL,
    document_version       integer NOT NULL,
    catalog_revision       integer NOT NULL,
    guest_count            integer NOT NULL,
    guest_count_is_minimum boolean NOT NULL,
    duration_minutes       integer NOT NULL,
    CONSTRAINT financial_document_pricing_pkey PRIMARY KEY (document_id, document_version),
    CONSTRAINT financial_document_pricing_catalog_revision_check CHECK (catalog_revision >= 1),
    CONSTRAINT financial_document_pricing_guest_count_check CHECK (guest_count >= 1),
    CONSTRAINT financial_document_pricing_duration_minutes_check CHECK (duration_minutes > 0),
    CONSTRAINT financial_document_pricing_document_id_fkey FOREIGN KEY (document_id)
        REFERENCES fionas.inquiry_financial_documents (document_id),
    CONSTRAINT financial_document_pricing_snapshot_fkey FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);

-- The priced category blocks, in submitted order. A block chosen with nothing in it is a row
-- without selections.
CREATE TABLE fionas.financial_document_pricing_categories (
    document_id      uuid    NOT NULL,
    document_version integer NOT NULL,
    position         integer NOT NULL,
    category_key     text    NOT NULL,
    CONSTRAINT financial_document_pricing_categories_pkey PRIMARY KEY (document_id, document_version, position),
    CONSTRAINT financial_document_pricing_categories_category_key_key UNIQUE (document_id, document_version, category_key),
    CONSTRAINT financial_document_pricing_categories_position_check CHECK (position >= 0),
    CONSTRAINT financial_document_pricing_categories_pricing_fkey FOREIGN KEY (document_id, document_version)
        REFERENCES fionas.financial_document_pricing (document_id, document_version)
);

-- The chosen catalog keys of each block, in the order chosen.
CREATE TABLE fionas.financial_document_pricing_selections (
    document_id       uuid    NOT NULL,
    document_version  integer NOT NULL,
    category_position integer NOT NULL,
    position          integer NOT NULL,
    offering_key      text    NOT NULL,
    CONSTRAINT financial_document_pricing_selections_pkey
        PRIMARY KEY (document_id, document_version, category_position, position),
    CONSTRAINT financial_document_pricing_selections_offering_key_key
        UNIQUE (document_id, document_version, category_position, offering_key),
    CONSTRAINT financial_document_pricing_selections_position_check CHECK (position >= 0),
    CONSTRAINT financial_document_pricing_selections_category_fkey FOREIGN KEY (document_id, document_version, category_position)
        REFERENCES fionas.financial_document_pricing_categories (document_id, document_version, position)
);
