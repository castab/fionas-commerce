-- A service approval is an independent Fiona fact, even at an unchanged financial version.
ALTER TABLE fionas.inquiry_service_plans ADD COLUMN revision integer;
WITH ordered AS (
    SELECT document_id, document_version,
           row_number() OVER (PARTITION BY inquiry_id ORDER BY document_version)::integer AS revision
    FROM fionas.inquiry_service_plans
)
UPDATE fionas.inquiry_service_plans p SET revision = o.revision
FROM ordered o WHERE p.document_id = o.document_id AND p.document_version = o.document_version;
ALTER TABLE fionas.inquiry_service_plans
    ALTER COLUMN revision SET NOT NULL,
    ALTER COLUMN plan DROP NOT NULL,
    DROP CONSTRAINT inquiry_service_plans_pkey,
    DROP CONSTRAINT inquiry_service_plans_reviewed_check,
    ADD PRIMARY KEY (inquiry_id, revision),
    ADD CHECK (revision > 0),
    ADD CHECK (reviewed_document_version >= 1 AND reviewed_document_version <= document_version);
CREATE INDEX inquiry_service_plans_snapshot_idx ON fionas.inquiry_service_plans (document_id, document_version, revision DESC);

ALTER TABLE fionas.inquiry_proposals
    DROP CONSTRAINT inquiry_proposals_pair_key,
    ADD COLUMN service_plan_revision integer,
    ADD FOREIGN KEY (inquiry_id, service_plan_revision) REFERENCES fionas.inquiry_service_plans (inquiry_id, revision);
UPDATE fionas.inquiry_proposals p SET service_plan_revision = s.revision
FROM fionas.inquiry_service_plans s
WHERE p.document_id = s.document_id AND p.document_version = s.document_version;
