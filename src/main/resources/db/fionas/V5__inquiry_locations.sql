-- The event ZIP code belongs to one inquiry, never to the durable customer. Location
-- data can be purged independently without removing the inquiry or customer.
CREATE TABLE fionas.inquiry_locations (
    inquiry_id uuid NOT NULL,
    zip_code text NOT NULL,
    CONSTRAINT inquiry_locations_pkey PRIMARY KEY (inquiry_id),
    CONSTRAINT inquiry_locations_zip_code_check CHECK (zip_code ~ '^[0-9]{5}$'),
    CONSTRAINT inquiry_locations_inquiry_id_fkey FOREIGN KEY (inquiry_id) REFERENCES fionas.inquiries (id)
);
