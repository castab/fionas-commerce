-- Commerce owns human identities and RBAC. Fiona stores only password credentials.
CREATE TABLE fionas.user_credentials (
    user_id uuid PRIMARY KEY REFERENCES commerce.users(principal_id),
    password_hash text NOT NULL,
    password_changed_at timestamptz NOT NULL
);
