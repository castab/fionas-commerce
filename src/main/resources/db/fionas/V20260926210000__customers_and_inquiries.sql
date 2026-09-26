-- Fiona-owned tables for the inquiry slice. They live in the application schema (public),
-- tracked by Fiona's own history table (public.flyway_schema_history), never in the
-- commerce schema that commerce-runtime owns.

-- The person who makes an inquiry. Emails are stored normalized (trimmed, lowercase) and
-- are unique, because CreateInquiry reuses the customer who owns a submitted email. The
-- customer's identity is its id; the email is a lookup key (see AGENTS.md).
CREATE TABLE public.customers (
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    email      text        NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT customers_pkey PRIMARY KEY (id),
    CONSTRAINT customers_email_key UNIQUE (email)
);

-- A prospective customer's initial request, before any booking exists.
CREATE TABLE public.inquiries (
    id          uuid        NOT NULL,
    customer_id uuid        NOT NULL,
    message     text,
    created_at  timestamptz NOT NULL,
    CONSTRAINT inquiries_pkey PRIMARY KEY (id),
    CONSTRAINT inquiries_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES public.customers (id)
);

CREATE INDEX inquiries_customer_id_idx ON public.inquiries (customer_id);
