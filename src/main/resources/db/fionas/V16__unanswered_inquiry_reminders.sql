-- Scheduled unanswered-inquiry reminders replace the per-inquiry push trigger (expand step).
-- A reminder job is keyed by its schedule slot and carries the positive count it announced.
-- NEW_INQUIRY stays valid only so a pre-V16 binary still running during a deploy can write it;
-- current binaries never write or read it, and a later contract migration removes it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM fionas.staff_notifications WHERE kind = 'NEW_INQUIRY') THEN
        RAISE EXCEPTION 'NEW_INQUIRY staff notifications exist; the per-inquiry trigger was retired before any were expected';
    END IF;
END
$$;

ALTER TABLE fionas.staff_notifications
    ADD COLUMN scheduled_for timestamptz(6) UNIQUE,
    ADD COLUMN inquiry_count integer CHECK (inquiry_count > 0),
    DROP CONSTRAINT staff_notifications_kind_check,
    DROP CONSTRAINT staff_notifications_check2;

ALTER TABLE fionas.staff_notifications
    ADD CONSTRAINT staff_notifications_kind_check CHECK (kind IN ('TEST', 'NEW_INQUIRY', 'UNANSWERED_INQUIRIES')),
    ADD CONSTRAINT staff_notifications_shape_check CHECK (
      (kind = 'TEST' AND requested_by IS NOT NULL AND idempotency_key IS NOT NULL
        AND target_subscription_id IS NOT NULL AND inquiry_id IS NULL AND fanned_out_at IS NOT NULL
        AND scheduled_for IS NULL AND inquiry_count IS NULL)
      OR (kind = 'NEW_INQUIRY' AND requested_by IS NULL AND idempotency_key IS NULL
        AND target_subscription_id IS NULL AND inquiry_id IS NOT NULL
        AND scheduled_for IS NULL AND inquiry_count IS NULL)
      OR (kind = 'UNANSWERED_INQUIRIES' AND requested_by IS NULL AND idempotency_key IS NULL
        AND target_subscription_id IS NULL AND inquiry_id IS NULL
        AND scheduled_for IS NOT NULL AND inquiry_count IS NOT NULL)
    );
