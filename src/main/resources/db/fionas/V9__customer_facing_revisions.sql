-- Pre-production cutover: old Invoice histories cannot identify service-only amendments
-- reliably. Require an operator reset rather than manufacture approval facts or read
-- runtime-owned tables. Initial Invoice issuance always copies line authorship.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM fionas.inquiry_proposals p
        JOIN fionas.financial_document_authorship a ON a.document_id = p.document_id
        WHERE a.document_version > (
            SELECT max(q.document_version) FROM fionas.inquiry_proposals q WHERE q.inquiry_id = p.inquiry_id
        )
    ) THEN
        RAISE EXCEPTION 'V9 requires an operator reset of pre-production databases containing booked inquiries; no data is deleted automatically';
    END IF;
END;
$$;

ALTER TABLE fionas.inquiry_proposals ADD COLUMN quote_revision integer;
WITH ordered AS (
    SELECT id,
           first_value(document_version) OVER w + (row_number() OVER w)::integer - 1 AS revision
    FROM fionas.inquiry_proposals
    WINDOW w AS (PARTITION BY inquiry_id ORDER BY recorded_order)
)
UPDATE fionas.inquiry_proposals p SET quote_revision = o.revision FROM ordered o WHERE p.id = o.id;
ALTER TABLE fionas.inquiry_proposals
    ALTER COLUMN quote_revision SET NOT NULL,
    ADD CHECK (quote_revision > 0),
    ADD UNIQUE (inquiry_id, quote_revision);

-- INITIAL records the automatic booking transition; AMENDED requires a verified staff USER.
-- Snapshot/service references are identities, never copies of money or service content.
CREATE TABLE fionas.inquiry_invoice_amendments (
    id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL,
    invoice_revision integer NOT NULL CHECK (invoice_revision > 0),
    document_id uuid NOT NULL,
    document_version integer NOT NULL CHECK (document_version >= 3),
    service_plan_revision integer,
    kind text NOT NULL CHECK (kind IN ('INITIAL', 'AMENDED')),
    approved_at timestamptz(6) NOT NULL,
    approved_by uuid REFERENCES commerce.users (principal_id),
    financial_change boolean NOT NULL,
    service_plan_change boolean NOT NULL,
    UNIQUE (inquiry_id, invoice_revision),
    FOREIGN KEY (inquiry_id, document_id) REFERENCES fionas.inquiry_financial_documents (inquiry_id, document_id),
    FOREIGN KEY (document_id, document_version) REFERENCES commerce.financial_document_snapshots (document_id, version),
    FOREIGN KEY (inquiry_id, service_plan_revision) REFERENCES fionas.inquiry_service_plans (inquiry_id, revision),
    CHECK (
        (kind = 'INITIAL' AND approved_by IS NULL AND NOT financial_change AND NOT service_plan_change AND invoice_revision = document_version)
        OR (kind = 'AMENDED' AND approved_by IS NOT NULL AND (financial_change OR service_plan_change))
    )
);
CREATE UNIQUE INDEX inquiry_invoice_initial_idx ON fionas.inquiry_invoice_amendments (inquiry_id) WHERE kind = 'INITIAL';

-- Booking is invalidated by its payment; each subsequent amendment has one source.
CREATE TRIGGER invoice_amendment_timeline_revision
AFTER INSERT ON fionas.inquiry_invoice_amendments
FOR EACH ROW WHEN (NEW.kind = 'AMENDED') EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();
