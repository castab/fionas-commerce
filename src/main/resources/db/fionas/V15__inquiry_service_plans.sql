-- Fiona's approved service plan for one exact canonical Quote snapshot: what Fiona's promises
-- to serve (guest count, duration, selected offerings with the catalog names staff reviewed)
-- and why each ledger line of that Quote exists (generated source, carried Estimate line, staff
-- adjustment, negotiated override, with reasons). The plan holds no money: amounts stay on the
-- commerce-runtime snapshot, which the plan names by (document_id, document_version) and whose
-- lines it names by id inside the jsonb content. Plans are immutable and written once, in the
-- transaction that issues the Quote. Quotes issued before this migration have no plan; none is
-- invented.
CREATE TABLE fionas.inquiry_service_plans (
    document_id uuid NOT NULL,
    document_version integer NOT NULL CHECK (document_version >= 2),
    inquiry_id uuid NOT NULL,
    reviewed_document_version integer NOT NULL
        CHECK (reviewed_document_version >= 1 AND reviewed_document_version < document_version),
    pricing_basis text NOT NULL CHECK (pricing_basis IN ('KEEP_ESTIMATE', 'REVISE_SERVICE_SELECTIONS', 'REPRICE_CONFIGURATION')),
    catalog_revision integer NOT NULL CHECK (catalog_revision >= 1),
    plan jsonb NOT NULL CHECK (jsonb_typeof(plan) = 'object'),
    approved_at timestamptz(6) NOT NULL,
    principal_kind text NOT NULL CHECK (principal_kind IN ('USER', 'SERVICE')),
    principal_id uuid NOT NULL,
    PRIMARY KEY (document_id, document_version),
    CONSTRAINT inquiry_service_plans_association_fkey FOREIGN KEY (inquiry_id, document_id)
        REFERENCES fionas.inquiry_financial_documents (inquiry_id, document_id),
    CONSTRAINT inquiry_service_plans_document_fkey FOREIGN KEY (document_id, document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version),
    CONSTRAINT inquiry_service_plans_reviewed_fkey FOREIGN KEY (document_id, reviewed_document_version)
        REFERENCES commerce.financial_document_snapshots (document_id, version)
);
