-- Staff discover inquiries newest first, a page at a time: ORDER BY created_at DESC, id DESC,
-- continuing strictly after the last row of the previous page. The id breaks ties between
-- inquiries recorded at the same instant. PostgreSQL scans this index backwards for both the
-- first page and every later one.
CREATE INDEX inquiries_created_at_id_idx ON fionas.inquiries (created_at, id);

-- The Fiona pricing inputs a customer configured with an inquiry, if any: at most one row per
-- inquiry, written in the same transaction as the inquiry and never changed. They are the
-- customer's request, pinned to the catalog revision it names, so staff can start an estimate
-- from them; never lines, amounts, or totals, which the server alone derives when staff price
-- a financial document.
CREATE TABLE fionas.inquiry_pricing (
    inquiry_id             uuid    NOT NULL,
    catalog_revision       integer NOT NULL,
    guest_count            integer NOT NULL,
    guest_count_is_minimum boolean NOT NULL,
    duration_minutes       integer NOT NULL,
    CONSTRAINT inquiry_pricing_pkey PRIMARY KEY (inquiry_id),
    CONSTRAINT inquiry_pricing_catalog_revision_check CHECK (catalog_revision >= 1),
    CONSTRAINT inquiry_pricing_guest_count_check CHECK (guest_count >= 1),
    CONSTRAINT inquiry_pricing_duration_minutes_check CHECK (duration_minutes > 0),
    CONSTRAINT inquiry_pricing_inquiry_id_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiries (id)
);

-- The requested category blocks, in submitted order. A block chosen with nothing in it is a
-- row without selections.
CREATE TABLE fionas.inquiry_pricing_categories (
    inquiry_id   uuid    NOT NULL,
    position     integer NOT NULL,
    category_key text    NOT NULL,
    CONSTRAINT inquiry_pricing_categories_pkey PRIMARY KEY (inquiry_id, position),
    CONSTRAINT inquiry_pricing_categories_category_key_key UNIQUE (inquiry_id, category_key),
    CONSTRAINT inquiry_pricing_categories_position_check CHECK (position >= 0),
    CONSTRAINT inquiry_pricing_categories_pricing_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiry_pricing (inquiry_id)
);

-- The chosen catalog keys of each block, in the order chosen.
CREATE TABLE fionas.inquiry_pricing_selections (
    inquiry_id        uuid    NOT NULL,
    category_position integer NOT NULL,
    position          integer NOT NULL,
    offering_key      text    NOT NULL,
    CONSTRAINT inquiry_pricing_selections_pkey PRIMARY KEY (inquiry_id, category_position, position),
    CONSTRAINT inquiry_pricing_selections_offering_key_key UNIQUE (inquiry_id, category_position, offering_key),
    CONSTRAINT inquiry_pricing_selections_position_check CHECK (position >= 0),
    CONSTRAINT inquiry_pricing_selections_category_fkey FOREIGN KEY (inquiry_id, category_position)
        REFERENCES fionas.inquiry_pricing_categories (inquiry_id, position)
);
