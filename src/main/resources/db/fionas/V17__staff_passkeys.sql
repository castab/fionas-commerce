-- Staff passkeys (WebAuthn discoverable credentials) and their one-time ceremonies.
-- Public keys are not secrets, but nothing here is ever returned except a passkey's id,
-- name, provider AAGUID, flags and timestamps. Challenges are single-use and short-lived.

-- The WebAuthn user handle of each staff user: random opaque bytes, never the principal id
-- or username, so authenticators and password managers learn nothing about the account.
CREATE TABLE fionas.staff_webauthn_user_handles (
    user_id uuid PRIMARY KEY REFERENCES commerce.users (principal_id),
    user_handle bytea NOT NULL UNIQUE CHECK (octet_length(user_handle) = 64),
    created_at timestamptz(6) NOT NULL
);

CREATE TABLE fionas.staff_passkeys (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES fionas.staff_webauthn_user_handles (user_id),
    credential_id bytea NOT NULL UNIQUE CHECK (octet_length(credential_id) BETWEEN 1 AND 1023),
    public_key_cose bytea NOT NULL CHECK (octet_length(public_key_cose) BETWEEN 1 AND 2048),
    signature_count bigint NOT NULL CHECK (signature_count BETWEEN 0 AND 4294967295),
    aaguid uuid NOT NULL,
    transports text[] NOT NULL DEFAULT '{}',
    backup_eligible boolean NOT NULL,
    backed_up boolean NOT NULL,
    name text CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 64),
    created_at timestamptz(6) NOT NULL,
    last_used_at timestamptz(6) CHECK (last_used_at >= created_at),
    -- A credential that is not backup eligible can never become backed up.
    CHECK (backup_eligible OR NOT backed_up)
);
CREATE INDEX staff_passkeys_user_idx ON fionas.staff_passkeys (user_id, created_at, id);

-- One challenge per sign-in or registration attempt. A registration is bound to the user who
-- started it; a sign-in is not bound to any user until a passkey answers it.
CREATE TABLE fionas.webauthn_ceremonies (
    id uuid PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('REGISTRATION', 'AUTHENTICATION')),
    challenge bytea NOT NULL UNIQUE CHECK (octet_length(challenge) = 32),
    user_id uuid REFERENCES commerce.users (principal_id),
    created_at timestamptz(6) NOT NULL,
    expires_at timestamptz(6) NOT NULL CHECK (expires_at > created_at),
    consumed_at timestamptz(6) CHECK (consumed_at >= created_at),
    CHECK ((kind = 'REGISTRATION') = (user_id IS NOT NULL))
);
CREATE INDEX webauthn_ceremonies_expiry_idx ON fionas.webauthn_ceremonies (expires_at);
