-- Every Fiona inquiry is a request for configured ice cream service: it is recorded with the
-- pricing inputs the customer configured, in the same transaction. The reverse reference makes
-- that relationship mandatory. It is deferred because the inquiry is written before its inputs
-- (which reference it), so the check runs at commit and no inquiry without inputs can commit.
-- Pre-release inquiry data is disposable: no inputs are invented for an existing inquiry
-- recorded without them, so such a database fails this migration and must be recreated.
ALTER TABLE fionas.inquiries
    ADD CONSTRAINT inquiries_pricing_fkey FOREIGN KEY (id)
        REFERENCES fionas.inquiry_pricing (inquiry_id) DEFERRABLE INITIALLY DEFERRED;
