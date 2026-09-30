-- ZIP code is required inquiry data. Existing inquiry data is empty for this pre-release
-- change; there is no backfill, synthetic default, or transfer from the optional location table.
ALTER TABLE fionas.inquiries
    ADD COLUMN zip_code text NOT NULL,
    ADD CONSTRAINT inquiries_zip_code_check CHECK (zip_code ~ '^[0-9]{5}$');

DROP TABLE fionas.inquiry_locations;
