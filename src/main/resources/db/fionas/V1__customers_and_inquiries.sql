-- Fiona-owned objects for the inquiry slice. They live in the fionas schema, which this
-- application owns; the commerce schema belongs to commerce-runtime and is never touched
-- here. This is Fiona's own migration stream, versioned independently of the runtime's and
-- applied by commerce-runtime after the runtime's migrations.
CREATE SCHEMA fionas;

-- The person who makes an inquiry. Emails are stored normalized (trimmed, lowercase) and
-- are unique, because CreateInquiry reuses the customer who owns a submitted email. The
-- customer's identity is its id; the email is a lookup key (see AGENTS.md).
CREATE TABLE fionas.customers (
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    email      text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT customers_pkey PRIMARY KEY (id),
    CONSTRAINT customers_email_key UNIQUE (email)
);

-- A prospective customer's initial request, before any booking exists.
CREATE TABLE fionas.inquiries (
    id          uuid        NOT NULL,
    customer_id uuid        NOT NULL,
    message     text,
    created_at  timestamptz NOT NULL,
    CONSTRAINT inquiries_pkey PRIMARY KEY (id),
    CONSTRAINT inquiries_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES fionas.customers (id)
);

CREATE INDEX inquiries_customer_id_idx ON fionas.inquiries (customer_id);
