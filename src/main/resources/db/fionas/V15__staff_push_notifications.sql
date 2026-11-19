-- Staff Web Push: device registrations, notification jobs and per-device delivery outcomes.
-- Delivery work is scheduled through commerce-runtime's outbox (its own producer); these tables
-- hold only Fiona's delivery state. Endpoint URLs and key material never leave this table, and
-- are cleared when a registration is retired.
CREATE TABLE fionas.staff_push_subscriptions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES commerce.users (principal_id),
    endpoint text CHECK (endpoint ~ '^https://' AND char_length(endpoint) <= 2048),
    endpoint_sha256 text NOT NULL CHECK (endpoint_sha256 ~ '^[a-f0-9]{64}$'),
    push_service text NOT NULL CHECK (push_service IN ('FCM', 'MOZILLA', 'WINDOWS', 'APPLE', 'OTHER')),
    p256dh text CHECK (p256dh ~ '^[A-Za-z0-9_-]{87}$'),
    auth text CHECK (auth ~ '^[A-Za-z0-9_-]{22}$'),
    expiration_time timestamptz(6),
    label text CHECK (label = btrim(label) AND char_length(label) BETWEEN 1 AND 64),
    registration_version bigint NOT NULL CHECK (registration_version > 0),
    enrolled_at timestamptz(6) NOT NULL,
    refreshed_at timestamptz(6) NOT NULL CHECK (refreshed_at >= enrolled_at),
    retired_at timestamptz(6),
    retirement text CHECK (retirement IN ('REVOKED', 'PROVIDER_EXPIRED')),
    CHECK (
      (retired_at IS NULL AND retirement IS NULL AND endpoint IS NOT NULL AND p256dh IS NOT NULL AND auth IS NOT NULL)
      OR (retired_at IS NOT NULL AND retirement IS NOT NULL AND endpoint IS NULL AND p256dh IS NULL AND auth IS NULL)
    )
);
-- One active owner per push endpoint: concurrent enrollments serialize on this index.
CREATE UNIQUE INDEX staff_push_subscriptions_active_endpoint_idx
    ON fionas.staff_push_subscriptions (endpoint_sha256) WHERE retired_at IS NULL;
CREATE INDEX staff_push_subscriptions_user_idx ON fionas.staff_push_subscriptions (user_id, enrolled_at, id);

CREATE TABLE fionas.staff_notifications (
    id uuid PRIMARY KEY,
    kind text NOT NULL CHECK (kind IN ('TEST', 'NEW_INQUIRY')),
    requested_by uuid REFERENCES commerce.users (principal_id),
    idempotency_key text CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,128}$'),
    target_subscription_id uuid REFERENCES fionas.staff_push_subscriptions (id),
    inquiry_id uuid UNIQUE REFERENCES fionas.inquiries (id),
    created_at timestamptz(6) NOT NULL,
    give_up_at timestamptz(6) NOT NULL CHECK (give_up_at > created_at),
    fanned_out_at timestamptz(6) CHECK (fanned_out_at >= created_at),
    UNIQUE (requested_by, idempotency_key),
    CHECK (
      (kind = 'TEST' AND requested_by IS NOT NULL AND idempotency_key IS NOT NULL
        AND target_subscription_id IS NOT NULL AND inquiry_id IS NULL AND fanned_out_at IS NOT NULL)
      OR (kind = 'NEW_INQUIRY' AND requested_by IS NULL AND idempotency_key IS NULL
        AND target_subscription_id IS NULL AND inquiry_id IS NOT NULL)
    )
);

CREATE TABLE fionas.staff_notification_deliveries (
    id uuid PRIMARY KEY,
    notification_id uuid NOT NULL REFERENCES fionas.staff_notifications (id),
    subscription_id uuid NOT NULL REFERENCES fionas.staff_push_subscriptions (id),
    state text NOT NULL CHECK (state IN ('QUEUED', 'RETRYING', 'PUSH_ACCEPTED', 'PUSH_FAILED', 'SKIPPED')),
    outcome_reason text CHECK (outcome_reason IN (
      'subscription_expired', 'subscription_revoked', 'recipient_not_eligible', 'provider_rejected',
      'provider_unavailable', 'provider_throttled', 'transport_failure', 'endpoint_not_allowed',
      'provider_outcome_unknown', 'delivery_expired'
    )),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ever_uncertain boolean NOT NULL DEFAULT false,
    provider_code integer CHECK (provider_code BETWEEN 100 AND 599),
    attempt_token uuid,
    attempt_started_at timestamptz(6),
    not_before timestamptz(6),
    give_up_at timestamptz(6) NOT NULL,
    created_at timestamptz(6) NOT NULL,
    last_attempt_at timestamptz(6),
    completed_at timestamptz(6),
    UNIQUE (notification_id, subscription_id),
    CHECK ((attempt_token IS NULL) = (attempt_started_at IS NULL)),
    CHECK (
      (state IN ('QUEUED', 'RETRYING') AND completed_at IS NULL)
      OR (state = 'PUSH_ACCEPTED' AND completed_at IS NOT NULL AND attempt_token IS NULL AND outcome_reason IS NULL)
      OR (state IN ('PUSH_FAILED', 'SKIPPED') AND completed_at IS NOT NULL AND attempt_token IS NULL AND outcome_reason IS NOT NULL)
    ),
    CHECK (state <> 'QUEUED' OR outcome_reason IS NULL),
    CHECK (state <> 'RETRYING' OR outcome_reason IS NOT NULL)
);
CREATE INDEX staff_notification_deliveries_subscription_idx ON fionas.staff_notification_deliveries (subscription_id);
