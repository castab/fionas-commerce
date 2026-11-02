-- Explicit, operator-invoked reset of a disposable pre-production fionas-commerce database.
--
-- Deletes ALL commerce-runtime and Fiona persistence (the `commerce` and `fionas` schemas,
-- including both Flyway histories) so the next normal application start lets commerce-runtime
-- recreate its schema and then applies Fiona's migrations from V1. It never drops the database
-- itself and is never run by the application. See docs/preproduction-database-reset.md.
--
--   psql "<connection URL of the target database>" -X -v ON_ERROR_STOP=1 \
--     -v database=<exact database name> \
--     -v environment=<designated non-production environment> \
--     -v confirm='RESET <exact database name>' \
--     -f scripts/reset-preproduction-database.sql
--
-- Every check and both drops run in one transaction: a refusal or any error (including an
-- undefined psql variable, which psql leaves as invalid SQL) rolls back and changes nothing.
-- The database must first be designated disposable by its owner, once:
--
--   COMMENT ON DATABASE <name> IS 'fionas-commerce disposable environment=<environment>';

BEGIN;

SET LOCAL lock_timeout = '5s';

-- psql interpolates variables only outside dollar quotes, so the request is passed in here.
SELECT set_config('fionas.reset_database', :'database', true),
       set_config('fionas.reset_environment', :'environment', true),
       set_config('fionas.reset_confirmation', :'confirm', true);

DO $$
DECLARE
    target text := current_setting('fionas.reset_database');
    environment text := current_setting('fionas.reset_environment');
    designation text := shobj_description((SELECT oid FROM pg_database WHERE datname = current_database()), 'pg_database');
    unexpected text;
BEGIN
    IF target IS DISTINCT FROM current_database() THEN
        RAISE EXCEPTION 'fionas reset refused: the connection is to database "%", not the named target', current_database();
    END IF;
    IF current_setting('fionas.reset_confirmation') IS DISTINCT FROM 'RESET ' || current_database() THEN
        RAISE EXCEPTION 'fionas reset refused: confirm must be exactly ''RESET %''', current_database();
    END IF;
    IF environment !~ '^[a-z][a-z0-9-]{0,62}$' OR environment ~ 'prod' OR environment = 'live' THEN
        RAISE EXCEPTION 'fionas reset refused: "%" is not a non-production environment name', environment;
    END IF;
    IF designation IS DISTINCT FROM 'fionas-commerce disposable environment=' || environment THEN
        RAISE EXCEPTION 'fionas reset refused: database "%" is not designated disposable for environment "%"',
            current_database(), environment;
    END IF;

    -- A dedicated database holds only commerce-runtime and Fiona: anything else means it is shared.
    SELECT string_agg(nspname, ', ' ORDER BY nspname) INTO unexpected FROM pg_namespace
    WHERE nspname NOT IN ('commerce', 'fionas', 'public', 'information_schema')
      AND nspname NOT LIKE 'pg\_%';
    IF unexpected IS NOT NULL THEN
        RAISE EXCEPTION 'fionas reset refused: shared database, unexpected schemas: %', unexpected;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE relnamespace = 'public'::regnamespace)
        OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace = 'public'::regnamespace)
        OR EXISTS (SELECT 1 FROM pg_type WHERE typnamespace = 'public'::regnamespace)
        OR EXISTS (SELECT 1 FROM pg_extension WHERE extname <> 'plpgsql') THEN
        RAISE EXCEPTION 'fionas reset refused: shared database, public schema or extensions hold other objects';
    END IF;

    -- Every application instance, worker and console must be stopped first.
    IF EXISTS (
        SELECT 1 FROM pg_stat_activity
        WHERE datname = current_database() AND pid <> pg_backend_pid() AND backend_type = 'client backend'
    ) THEN
        RAISE EXCEPTION 'fionas reset refused: other sessions are connected to "%"; stop every service first', current_database();
    END IF;

    DROP SCHEMA IF EXISTS fionas CASCADE;
    DROP SCHEMA IF EXISTS commerce CASCADE;
    RAISE NOTICE 'fionas reset: dropped schemas commerce and fionas in "%" (%)', current_database(), environment;
END;
$$;

COMMIT;
