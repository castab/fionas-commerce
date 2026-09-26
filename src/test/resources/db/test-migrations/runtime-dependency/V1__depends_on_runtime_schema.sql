-- Test-only application migration. It depends on the commerce schema, which only
-- commerce-runtime's migration phase creates, so it can succeed only if the runtime's
-- migrations ran first. (0.0.6 publishes no runtime table to reference; the schema is the
-- one runtime-created structure available.) It reads the schema and never modifies it.
CREATE SCHEMA fionas_test;

CREATE TABLE fionas_test.runtime_dependency AS
SELECT 'commerce'::regnamespace::text AS runtime_schema;
