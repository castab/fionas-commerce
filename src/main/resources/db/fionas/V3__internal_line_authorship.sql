-- Preserve historical principal attribution while admitting stable internal component names.
ALTER TABLE fionas.financial_document_authorship
    DROP CONSTRAINT financial_document_authorship_author_kind_check,
    ALTER COLUMN author_id DROP NOT NULL,
    ADD COLUMN author_component text,
    ADD CONSTRAINT financial_document_authorship_origin_check CHECK (
        (author_kind IN ('USER', 'SERVICE') AND author_id IS NOT NULL AND author_component IS NULL)
        OR
        (author_kind = 'INTERNAL' AND author_id IS NULL AND author_component IS NOT NULL
            AND length(author_component) BETWEEN 1 AND 128
            AND author_component ~ '^[a-z0-9]+([-_][a-z0-9]+)*(\.[a-z0-9]+([-_][a-z0-9]+)*)*$')
    );
