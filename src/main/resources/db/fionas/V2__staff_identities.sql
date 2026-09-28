-- Human identity, credentials, and authorization belong to Fiona, not commerce-runtime.
CREATE TABLE fionas.users (
    id uuid PRIMARY KEY,
    username text NOT NULL UNIQUE,
    first_name text,
    last_name text,
    display_name text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT users_username_normalized CHECK (username = lower(btrim(username)) AND username <> '')
);

CREATE TABLE fionas.user_credentials (
    user_id uuid PRIMARY KEY REFERENCES fionas.users(id),
    password_hash text NOT NULL,
    password_changed_at timestamptz NOT NULL
);

-- Role assignments address a PrincipalId by kind and id. No session or permission is stored here.
CREATE TABLE fionas.principal_role_assignments (
    principal_kind text NOT NULL CHECK (principal_kind IN ('USER', 'SERVICE')),
    principal_id uuid NOT NULL,
    role_key text NOT NULL,
    PRIMARY KEY (principal_kind, principal_id, role_key)
);

CREATE TABLE fionas.service_identities (
    id uuid PRIMARY KEY,
    name text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED'))
);
