-- A clearing fact (staff reply or acknowledgement) may name the greatest recorded_order its staff member had observed.
-- Inbound recorded after that boundary stays outstanding even when the clearing fact is recorded later, as when a
-- provider accepts a reply after new customer email arrived. NULL keeps the original meaning: clears through itself.
ALTER TABLE fionas.inquiry_communications
    ADD COLUMN clears_through bigint,
    ADD CONSTRAINT inquiry_communications_clears_through_check CHECK (
        clears_through IS NULL OR (
            kind IN ('STAFF_EMAIL_SENT', 'STAFF_ACKNOWLEDGED') AND clears_through >= 0 AND clears_through < recorded_order
        )
    );

-- The attention aggregate reads the boundary with the rest of the covered columns.
DROP INDEX fionas.inquiry_communications_inquiry_order_idx;
CREATE INDEX inquiry_communications_inquiry_order_idx ON fionas.inquiry_communications (inquiry_id, recorded_order)
    INCLUDE (kind, occurred_at, clears_through);

-- The boundary a staff reply command observed before its provider call, applied when the reply is accepted, however
-- late. NULL only for commands recorded before this migration, which clear through their own acceptance as before.
ALTER TABLE fionas.inquiry_staff_replies
    ADD COLUMN observed_through bigint CHECK (observed_through >= 0);
