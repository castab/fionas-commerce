-- Fiona-owned facts only. Runtime-owned snapshots/payments are observed by Fiona orchestration.
CREATE TRIGGER proposal_timeline_revision
AFTER INSERT ON fionas.inquiry_proposals
FOR EACH ROW EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();

CREATE TRIGGER decline_timeline_revision
AFTER INSERT ON fionas.inquiry_declines
FOR EACH ROW EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();

CREATE TRIGGER served_timeline_revision
AFTER INSERT ON fionas.inquiry_fulfillment
FOR EACH ROW EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();

CREATE TRIGGER closed_timeline_revision
AFTER UPDATE ON fionas.inquiry_fulfillment
FOR EACH ROW WHEN (OLD.closed_at IS DISTINCT FROM NEW.closed_at)
EXECUTE FUNCTION fionas.advance_inquiry_timeline_revision();
