ALTER TABLE fionas.stripe_payment_attempts
    DROP CONSTRAINT stripe_payment_attempts_checkout_mode_check,
    ADD CONSTRAINT stripe_payment_attempts_checkout_mode_check
        CHECK (checkout_mode IN ('HOSTED', 'EMBEDDED', 'ELEMENTS'));
