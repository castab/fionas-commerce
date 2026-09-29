-- Test-only application migration. It depends on the commerce schema, which only
-- commerce-runtime's migration phase creates, so it can succeed only if the runtime's
-- migrations ran first. It references only the schema, never a runtime table, so it stays
-- valid whichever tables a runtime release owns. It reads the schema and never modifies it.
-- Its own schema, fionas_test, already exists: the runtime's Flyway creates the schema the
-- stream declares.

CREATE TABLE fionas_test.runtime_dependency AS
SELECT 'commerce'::regnamespace::text AS runtime_schema;
