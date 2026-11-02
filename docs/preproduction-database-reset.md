# Pre-production database reset

`V9__customer_facing_revisions` refuses a database that already holds booked inquiries,
because earlier service-only Invoice decisions cannot be reconstructed. For a **disposable
pre-production** database, [`scripts/reset-preproduction-database.sql`](../scripts/reset-preproduction-database.sql)
deletes all fionas-commerce persistence so the next normal start migrates from scratch.
It is an operator tool only: the application never runs it, never packages it and never
resets anything on startup. Without an authorized reset, V9 keeps failing startup and
changes nothing.

## What it deletes and what it keeps

In one transaction it drops the `commerce` schema (commerce-runtime: users, roles, sessions,
service credentials, financial documents, deposits, payments, refunds, outbox, and the
runtime Flyway history) and the `fionas` schema (customers, inquiries, credentials,
proposals, service approvals, amendments, communications, and Fiona's Flyway history).
Nothing is left half-reset: both schemas go together or neither does. It keeps the database
itself, its owner, its grants and the designation comment, so no `CREATE DATABASE`
privilege is needed and nothing drops the database the operator is connected to. On the
next start commerce-runtime recreates `commerce` (V1–V3) and then applies Fiona's V1–V9.

## Safeguards

Every check runs before any drop, in the same transaction; any refusal or error, including a
missing psql variable, rolls back and changes nothing.

| Check | Refuses when |
|---|---|
| Authorization | `confirm` is not exactly `RESET <database>` |
| Target identity | `database` is not the database the connection actually reached |
| Non-production | `environment` contains `prod`, is `live`, or is not a simple lowercase name |
| Designation | the database comment is not exactly `fionas-commerce disposable environment=<environment>` |
| Dedicated | any schema other than `commerce`, `fionas` and `public` exists, `public` holds any object, or any extension other than `plpgsql` is installed |
| Stopped services | any other client session is connected to the database |

A production database must never carry the designation comment. Setting it is a deliberate,
separate act by the database owner, so pending migrations alone can never authorize a reset.

## Requirements

- A `psql` client (PostgreSQL 18 recommended) on the operator's machine. The application
  image contains no `psql` and no script.
- A role that owns both schemas (the role in `DATABASE_USERNAME`, which created them through
  Flyway, or the database owner/superuser). Setting the designation needs the database owner.
  On Railway the Postgres service's `postgres` user has both.
- Network access to the database. On Railway, use the Postgres service's `DATABASE_PUBLIC_URL`
  (TCP proxy), not the private `*.railway.internal` address.

## Procedure (Railway)

The commands assume the Railway PostgreSQL database named `railway` and a staging environment;
substitute your database name (`PGDATABASE`) and environment.

1. **Stop every client of the database.** Remove the fionas-commerce service's active
   deployment (service → Deployments → Remove, or `railway down` with that service linked) and
   close any open `psql` or database console sessions. fionas-web only calls the HTTP API and
   needs no change at this point, but its requests fail until the backend is back. The script
   refuses while any other session is connected.
2. **Designate the database once** (database owner):

   ```bash
   psql "$DATABASE_PUBLIC_URL" -X -c "COMMENT ON DATABASE railway IS 'fionas-commerce disposable environment=staging'"
   ```

3. **Run the reset** from the repository root, at the commit you are about to deploy:

   ```bash
   psql "$DATABASE_PUBLIC_URL" -X -v ON_ERROR_STOP=1 -v database=railway -v environment=staging -v confirm='RESET railway' -f scripts/reset-preproduction-database.sql
   ```

   Success ends with `NOTICE: fionas reset: dropped schemas commerce and fionas in "railway"
   (staging)` and `COMMIT`. Any `ERROR: fionas reset refused: …` means nothing changed.
4. **Configure bootstrap.** Every user, role, session and service credential is gone. Set
   `FIONAS_BOOTSTRAP_ADMIN_USERNAME`, `FIONAS_BOOTSTRAP_ADMIN_PASSWORD` (at least 12 characters)
   and `FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME` on the service, together. Keep
   `MIGRATIONS_ON_STARTUP` unset or `migrate`, and keep `DATABASE_*`, `SERVICE_TOKENS_*` and
   `FIONAS_TRUSTED_ORIGINS` as described in [Deploying on Railway](../README.md#deploying-on-railway).
   The existing signing key may stay; tokens issued before the reset name services that no
   longer exist and are rejected.
5. **Deploy** the release containing V9. Startup migrates both streams (runtime first), then
   creates the Administrator with its fixed grants. Wait for `/ready`.
6. **Remove `FIONAS_BOOTSTRAP_ADMIN_PASSWORD`** from the service variables (bootstrap never runs
   again once a user exists).
7. **Re-provision `fionas-web`** through `/admin/access` exactly as in
   [Service authentication](../README.md#service-authentication): create the service, the
   `fionas.web` role granting only `fionas.inquiries.create`, assign it, issue a credential, and
   store the new serviceId and secret in fionas-web's server-side configuration. Redeploy
   fionas-web.
8. **Recreate staff.** Sign in as the bootstrap Administrator, create other staff users and
   their passwords, and assign roles. Everyone signs in again; all earlier sessions are gone.

For a local Compose database, recreating the volume (`docker compose down -v`) remains the
simplest alternative; the script works there too once the database is designated.

## External state (not touched)

The script changes only PostgreSQL. Nothing external is deleted automatically; decide
separately, by your own retention policy, whether to purge anything.

- **NATS `FIONAS_EVENTS`** keeps already-published `InquiryPersisted`/`InquiryDeclined` events for
  inquiries that no longer exist. Unpublished outbox rows are deleted with `commerce` and are
  never published. New inquiries receive new UUIDs and new event IDs, so there are no
  deduplication or aggregate-sequence collisions, but downstream consumers holding state for old
  inquiries should be reset or told to ignore unknown aggregates.
- **NATS `FIONAS_INQUIRIES` / `inquiry-to-commerce`** keeps its acknowledgement floor; earlier
  submissions are not redelivered. Messages still pending when the backend stopped are processed
  into the fresh database when intake resumes. Replaying old submissions would require the
  exact-sequence recovery tool and is not done automatically.
- **NATS `RESEND_CONVERSATIONS` / `fionas-commerce-conversations`** keeps its position. Later
  provider events for old inquiry topics name unknown inquiries and are ignored and acknowledged.
- **Resend Conversation Service** keeps the `booking` conversations, messages and idempotency
  records for old inquiry UUIDs. They no longer appear in any staff timeline and do not collide
  with new inquiries.
- **fionas-web** must receive the new service credential (step 7) and should discard any cached
  inquiry IDs, review tokens or staff state from before the reset.
