ALTER TABLE fionas.stripe_payment_attempts
    ADD COLUMN checkout_mode text NOT NULL DEFAULT 'HOSTED'
        CHECK (checkout_mode IN ('HOSTED', 'EMBEDDED'));
