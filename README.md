# fionas-commerce

The commerce backend of Fiona's Ice Cream and its catering business: a concrete Kotlin/JVM
application built on the reusable
[`commerce-runtime`](https://github.com/castab/commerce-domain/tree/v0.0.6/runtime) and
[`commerce-domain`](https://github.com/castab/commerce-domain/tree/v0.0.6/domain)
artifacts.

> **Status: first vertical slice.** The application currently implements only
> inquiries: a prospective customer submits an inquiry, and it can be read back. There
> are no quotes, bookings, invoices, payments, or authentication yet.

## How it fits together

```text
commerce-domain       reusable commerce vocabulary and invariants
      │                (financial documents, payments, booking lifecycle, principals)
      ▼
commerce-runtime      reusable runtime: PostgreSQL/HikariCP, JDBI, Flyway, Transactor,
      │                http4k on Jetty, configuration, error contract, /health, /ready
      ▼
fionas-commerce       Fiona's application: customers, inquiries, Fiona's HTTP API and
                       tables, application.conf, Logback, main(), deployable jar
```

`fionas-commerce` depends on `io.github.castab:commerce-runtime:0.0.6`, which brings
`commerce-domain:0.0.6` with it. It contributes its migrations and routes to the runtime
through `ApplicationContributions`, and every write goes through the runtime's shared
`Transactor`:

```text
HTTP request
   │
   ▼
commerce-runtime error handling (one {"code","message"} error contract)
   │
   ▼
Fiona route          http/InquiryRoutes.kt     JSON DTO → application values
   │
   ▼
Fiona operation      inquiry/CreateInquiry.kt  opens one runtime transaction
   │
   ├── CustomerRepository  ─┐
   └── InquiryRepository   ─┴── SQL on transaction.handle, same Transaction
                                   │
                                   ▼
                     PostgreSQL: fionas.customers, fionas.inquiries (Fiona)
                                 commerce.*                          (runtime)
```

The rules behind this structure are in [`AGENTS.md`](AGENTS.md).

## Endpoints

| Endpoint | Behavior |
|---|---|
| `POST /inquiries` | Records an inquiry, establishing its customer. `201` with the inquiry and a `Location` header. |
| `GET /inquiries/{inquiryId}` | The persisted inquiry. `404` when unknown, `400` when the id is not a UUID. |
| `GET /health` | Liveness, served by commerce-runtime: `200 {"status":"ok"}`. |
| `GET /ready` | Readiness, served by commerce-runtime: `200 {"status":"ready"}` when the database is reachable, `503` otherwise. |

```bash
curl -i -X POST localhost:8080/inquiries -H 'Content-Type: application/json' \
  -d '{"name":"Jane Doe","email":"jane@example.com","message":"Ice cream for a birthday."}'
```

```json
{
  "id": "c755f7cd-1e28-4c75-a85f-d066ede7387d",
  "customerId": "602df298-8d54-45b6-a40c-bbf80949a3b8",
  "name": "Jane Doe",
  "email": "jane@example.com",
  "message": "Ice cream for a birthday.",
  "createdAt": "2026-09-26T21:19:39.321012Z"
}
```

`name` (at most 200 characters) and `email` are required; `message` is optional (at most
4000 characters). Values are trimmed, and the email is lowercased. If a customer already
has that email, the inquiry is attached to that customer and the response shows the
customer's stored name, which a later inquiry does not change (see
[Customer matching](AGENTS.md#customer-matching-current-deliberately-simple-policy)).

Errors use commerce-runtime's contract, `{"code": "...", "message": "..."}`:
`malformed_request` (400), `validation_failed` (422), `not_found` (404), `conflict` (409),
`internal_failure` (500, never describing the cause).

## Requirements

- **Java 25.** The build fails without a local Java 25 toolchain; it never downloads one.
- **Docker**, for the tests (they start a throwaway PostgreSQL 18) and for the local
  database in `compose.yaml`.
- **GitHub Packages credentials.** The commerce artifacts are published to GitHub
  Packages, which requires a token with `read:packages` even for public packages.
- No Gradle installation: use the wrapper (`./gradlew`, Gradle 9.7.0).

### GitHub Packages credentials

Either put these in `~/.gradle/gradle.properties` (never in this repository):

```properties
GitHubPackagesUsername=<your GitHub username>
GitHubPackagesPassword=<a token with read:packages>
```

or export them for the build:

```bash
export GITHUB_ACTOR=<your GitHub username>
export GITHUB_TOKEN=$(gh auth token)
```

The Gradle properties take precedence. Only `io.github.castab` artifacts are resolved
from GitHub Packages (`https://maven.pkg.github.com/castab/commerce-domain`); everything
else comes from Maven Central. In CI the workflow's `GITHUB_TOKEN` is used, or a
`PACKAGES_READ_TOKEN` repository secret if the commerce packages have not granted this
repository read access.

## Configuration

The application's configuration is [`src/main/resources/application.conf`](src/main/resources/application.conf),
loaded by commerce-runtime's `CommerceRuntimeConfiguration.load()` and overridden by the
environment. Credentials come only from the environment.

| Environment variable | Meaning | Default |
|---|---|---|
| `PORT` | HTTP port | `8080` |
| `DATABASE_JDBC_URL` | PostgreSQL JDBC URL | required |
| `DATABASE_USERNAME` | Database user | required |
| `DATABASE_PASSWORD` | Database password | required |
| `DATABASE_MAXIMUM_POOL_SIZE` | Connection pool size | `4` |
| `DATABASE_MINIMUM_IDLE` | Idle connections kept | `1` |
| `DATABASE_CONNECTION_TIMEOUT_MS` | Connection timeout | `500` |
| `DATABASE_VALIDATION_TIMEOUT_MS` | Validation timeout | `1000` |
| `MIGRATIONS_ON_STARTUP` | `migrate`: apply pending migrations, then serve. `validate`: only check that they are applied | `migrate` (Fiona's `application.conf`) |
| `LOG_LEVEL` | Level of the application's and runtime's own logs | `INFO` |

Logging is Logback ([`logback.xml`](src/main/resources/logback.xml)): `key=value` lines
on stdout, with library logging at `WARN`/`INFO`. The database password is never logged.

## Database and migrations

commerce-runtime owns migration orchestration; Fiona owns only its own migrations.

```text
commerce-runtime migrations    commerce schema    commerce.flyway_schema_history   first
Fiona migrations               fionas schema      public.flyway_schema_history     second
```

- Fiona's migrations live in [`src/main/resources/db/fionas`](src/main/resources/db/fionas)
  and are contributed as `classpath:db/fionas`. The runtime's migrations come inside the
  `commerce-runtime` jar; Fiona never lists or copies them.
- The two streams have independent version spaces: Fiona's migrations are `V1`, `V2`, …
  regardless of the runtime's numbering.
- Fiona's tables are in the `fionas` schema: `customers` and `inquiries`
  (`inquiries.customer_id → customers.id`, `customers.email` unique). Fiona never creates or
  changes anything in `commerce`.
- Composing the runtime runs the migration phase before anything is served. By default
  (`MIGRATIONS_ON_STARTUP=migrate`) it applies the runtime's pending migrations, then
  Fiona's; re-running against a current database applies nothing. A deployment that
  migrates in a separate release step sets `MIGRATIONS_ON_STARTUP=validate` on its
  instances. There is no separate migration command yet.
- If any migration fails, or validation finds the database behind, the process logs
  `event=startup_failed` and exits without serving.

> **Upgrading from commerce 0.0.5.** commerce-runtime 0.0.6 reset its own migration history,
> and Fiona's first migration moved its tables to `fionas`. Databases created before this
> change fail validation and must be recreated, for example `docker compose down -v`.

The rules for writing migrations are in [`AGENTS.md`](AGENTS.md#application-migrations).

## Running locally

```bash
docker compose up -d db
```

```bash
export DATABASE_JDBC_URL=jdbc:postgresql://localhost:5432/fionas DATABASE_USERNAME=fionas DATABASE_PASSWORD=fionas
```

```bash
./gradlew run
```

Or build and run the executable jar, as a deployment would:

```bash
./gradlew shadowJar
```

```bash
java -jar build/libs/fionas-commerce-all.jar
```

On Windows use `.\gradlew.bat` and set the variables with `$env:NAME = "value"`.

The process runs until it receives SIGTERM or SIGINT; its shutdown hook stops the server
and closes the connection pool.

### Packaging

`./gradlew shadowJar` produces `build/libs/fionas-commerce-all.jar`: one executable jar
with the application, its dependencies, `application.conf`, `logback.xml`, and the Fiona
migrations (Flyway's service files are merged). This is the deployable artifact. The
Gradle `application` plugin also provides `./gradlew run` and `installDist` for local
use. No Spring Boot or container image is involved yet.

## Testing

```bash
./gradlew test
```

The tests need Docker: Gradle starts a throwaway `postgres:18-alpine` container through
the Docker CLI and removes it when the build ends. To use an existing server instead, set
`TEST_DATABASE_JDBC_URL`, `TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` (the user
must be allowed to `CREATE DATABASE`). Each spec creates and drops its own database, and
commerce-runtime applies the real migrations. There is no H2 and no test schema.

| Spec | Proves |
|---|---|
| `CustomerValuesSpec`, `InquiryValuesSpec` | Value-object validation and normalization |
| `DatabaseSchemaSpec` | Fiona's tables and keys are in `fionas`, nothing Fiona-owned in `commerce` or `public` |
| `MigrationLifecycleSpec` | Fiona as a consumer of the runtime's migration phase: runtime migrations first (an application migration depending on them succeeds), independent version spaces, repeat startup applies nothing, failures prevent composition |
| `JdbiCustomerRepositorySpec`, `JdbiInquiryRepositorySpec` | Insert/read, email lookup, unique email conflict, foreign key |
| `RuntimeTransactionSpec` | Fiona repositories write through the runtime `Transaction`: both writes roll back together, and nothing is visible before commit |
| `InquiryOperationsSpec` | New customer + inquiry together, customer reuse, atomic failure, not found |
| `InquiryRoutesSpec` | The HTTP API through the complete runtime handler, including errors |
| `FionaApplicationSpec` | `application.conf` loads, `/health` and `/ready`, a real server on a port |
| `ArchitectureSpec` | Repositories take a `Transaction` and build no transaction infrastructure; no SQL in routes; no HTTP in persistence |

Full verification, as CI runs it:

```bash
./gradlew ktlintCheck test build
```

`./gradlew ktlintFormat` fixes formatting.
