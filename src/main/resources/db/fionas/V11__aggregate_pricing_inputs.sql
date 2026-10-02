-- Fiona pricing inputs have no identity or lifecycle outside the row that owns them: an
-- inquiry (what the customer requested) or the pricing source of one exact financial-document
-- version (what staff priced it from). Store each complete value in its owner's row, as one
-- jsonb in Fiona's persisted representation, instead of normalizing its categories and
-- selections into child rows. The application owns that representation and restores it
-- strictly; the database checks only that it is a JSON object.
--
-- No existing pricing inputs are converted: pre-release data is disposable, so a populated
-- database is rejected and must be recreated rather than pretend its rows were ever written
-- in the new representation. Released migrations remain unchanged.

-- Hold the owners against concurrent inserts until the check and DDL commit.
LOCK TABLE fionas.inquiries, fionas.inquiry_pricing, fionas.financial_document_pricing IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM fionas.inquiries) THEN
        RAISE EXCEPTION 'V11 cannot move preexisting inquiry pricing inputs into their inquiries; recreate the ephemeral database';
    END IF;
    IF EXISTS (SELECT 1 FROM fionas.financial_document_pricing) THEN
        RAISE EXCEPTION 'V11 cannot move preexisting financial document pricing sources into their rows; recreate the ephemeral database';
    END IF;
END
$$;

-- An inquiry's requested pricing inputs are part of the inquiry row itself, so the deferred
-- reverse reference V10 needed to make them mandatory is replaced by NOT NULL: one insert
-- writes the complete inquiry.
ALTER TABLE fionas.inquiries DROP CONSTRAINT inquiries_pricing_fkey;
DROP TABLE fionas.inquiry_pricing_selections;
DROP TABLE fionas.inquiry_pricing_categories;
DROP TABLE fionas.inquiry_pricing;

ALTER TABLE fionas.inquiries
    ADD COLUMN pricing_inputs jsonb NOT NULL,
    ADD CONSTRAINT inquiries_pricing_inputs_check CHECK (jsonb_typeof(pricing_inputs) = 'object');

-- A pricing source remains one row per exact (document_id, document_version), keyed to the
-- runtime's snapshot and to the lineage Fiona owns; its complete inputs replace both the scalar
-- columns and the child tables, so there is one source of truth.
DROP TABLE fionas.financial_document_pricing_selections;
DROP TABLE fionas.financial_document_pricing_categories;

ALTER TABLE fionas.financial_document_pricing
    DROP COLUMN catalog_revision,
    DROP COLUMN guest_count,
    DROP COLUMN guest_count_is_minimum,
    DROP COLUMN duration_minutes,
    ADD COLUMN pricing_inputs jsonb NOT NULL,
    ADD CONSTRAINT financial_document_pricing_pricing_inputs_check CHECK (jsonb_typeof(pricing_inputs) = 'object');
