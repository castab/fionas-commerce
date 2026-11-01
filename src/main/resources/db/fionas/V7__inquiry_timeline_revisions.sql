-- Advisory invalidation only: no email state, timeline content, or delivery log.
-- Triggers also cover older application instances during a rolling deployment.
CREATE TABLE fionas.inquiry_change_revisions (
    inquiry_id UUID PRIMARY KEY REFERENCES fionas.inquiries(id),
    revision BIGINT NOT NULL CHECK (revision > 0)
);

CREATE FUNCTION fionas.advance_inquiry_timeline_revision() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO fionas.inquiry_change_revisions (inquiry_id, revision)
    VALUES (NEW.inquiry_id, 1)
    ON CONFLICT (inquiry_id) DO UPDATE
        SET revision = fionas.inquiry_change_revisions.revision + 1;
    RETURN NEW;
END;
$$;

-- AFTER INSERT does not fire for ON CONFLICT DO NOTHING. Both the counter and
-- its source fact roll back together. Same-inquiry updates serialize until commit.
CREATE TRIGGER communication_timeline_revision
AFTER INSERT ON fionas.inquiry_communications
FOR EACH ROW EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();

-- Includes outbound/delivery events even when no communication fact is appended.
CREATE TRIGGER conversation_event_timeline_revision
AFTER INSERT ON fionas.inquiry_conversation_events
FOR EACH ROW EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();
