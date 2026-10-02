-- Required event details for the pre-release inquiry form. No fabricated defaults or backfill.
ALTER TABLE fionas.inquiries
    ADD COLUMN event_date date NOT NULL,
    ADD COLUMN event_type text NOT NULL,
    ADD CONSTRAINT inquiries_event_date_check CHECK (event_date BETWEEN DATE '0001-01-01' AND DATE '9999-12-31'),
    ADD CONSTRAINT inquiries_event_type_check CHECK (
        event_type IN ('BIRTHDAY', 'WEDDING', 'CORPORATE', 'SCHOOL_EVENT', 'NEIGHBORHOOD_EVENT', 'OTHER')
    );
