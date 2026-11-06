ALTER TABLE fionas.stripe_payment_attempts
    ADD COLUMN checkout_branding_profile text NOT NULL DEFAULT 'NONE'
        CHECK (checkout_branding_profile IN ('NONE', 'FIONAS_V1'));
