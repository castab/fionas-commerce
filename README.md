# fionas-commerce

The commerce backend of Fiona's Ice Cream and its catering business: a concrete Kotlin/JVM
application built on the reusable
[`commerce-runtime`](https://github.com/castab/commerce-domain/tree/v0.0.11/runtime) and
[`commerce-domain`](https://github.com/castab/commerce-domain/tree/v0.0.11/domain)
artifacts.

> **Status: early slices.** The application implements inquiries (a prospective customer
> submits an inquiry, and it can be read back), serves Fiona's Offerings catalog through
> commerce-runtime's reusable Offerings capability, and prices selections from it with
> Fiona's own pricing (`POST /estimate-preview`, which records nothing). There are no stored
> estimates, quotes, bookings, invoices, or payments yet. Staff authentication protects catalog administration.

## How it fits together

```text
commerce-domain       reusable commerce vocabulary and invariants
      │                (financial documents, payments, booking lifecycle, principals)
      ▼
commerce-runtime      reusable runtime: PostgreSQL/HikariCP, JDBI, Flyway, Transactor,
      │                http4k on Jetty, configuration, error contract, /health, /ready,
      │                Offerings snapshots (commerce.offering*) and the Offerings
      │                catalog capability: operations, HTTP contract routes, DTOs, schemas
      ▼
fionas-commerce       Fiona's application: customers, inquiries, Fiona's HTTP API and
                       tables, Fiona's catalog id and where its catalog is served,
                       Fiona's pricing (FionasOfferingsEngine) and estimate previews,
                       application.conf, Logback, main(), deployable jar
```

`fionas-commerce` depends on `io.github.castab:commerce-runtime:0.0.11`, which brings
`commerce-domain:0.0.11` with it. It contributes its migrations, permissions, and routes to the runtime
through `ApplicationContributions`, and every write goes through the runtime's shared
`Transactor`:

```text
HTTP request
   │
   ▼
commerce-runtime error handling (one {"code","message"} error contract)
   │
   ▼
Fiona contract route http/InquiryRoutes.kt     JSON DTO → application values
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

The Offerings catalog takes the same path, except that every piece below the error
handling is commerce-runtime's; Fiona supplies only the binding:

```text
commerce-runtime Offerings contract route      bound by Fiona at /offering-catalog
   │
   ▼
commerce-runtime Offerings operation           one runtime transaction, derives rN+1
   │
   ▼
OfferingsSnapshotRepository (runtime)  ──────  commerce.offerings_snapshots,
                                               commerce.offering_categories, commerce.offerings
```

The rules behind this structure are in [`AGENTS.md`](AGENTS.md).

## Endpoints

**Inquiry API**, implemented by Fiona:

| Endpoint | Behavior |
|---|---|
| `POST /inquiries` | Records an inquiry, establishing its customer. `201` with the inquiry and a `Location` header. |
| `GET /inquiries/{inquiryId}` | The persisted inquiry. `404` when unknown, `400` when the id is not a UUID. |

**Offerings Catalog API**, exposed by Fiona and implemented by commerce-runtime's Offerings
capability (see [Offerings catalog](#offerings-catalog)):

| Endpoint | Behavior |
|---|---|
| `GET /offering-catalog` | The latest revision of the whole catalog: categories in order, each with its offerings in order. |
| `POST /offering-catalog` | Initializes the empty catalog as revision 1. `409` if it exists. |
| `GET /offering-catalog/revisions/{revision}` | Exactly the catalog as that revision recorded it. |
| `GET /offering-catalog/categories` | The latest revision's categories, in order. |
| `POST /offering-catalog/categories` | Appends a category in a new revision. |
| `GET /offering-catalog/categories/{categoryKey}` | One category of the latest revision. |
| `GET /offering-catalog/categories/{categoryKey}/offerings` | That category and its offerings, in order. |
| `GET /offering-catalog/offerings` | The latest revision's offerings, in order. |
| `POST /offering-catalog/offerings` | Appends an offering to an existing category in a new revision. |
| `GET /offering-catalog/offerings/{offeringKey}` | One offering of the latest revision. |

**Estimate preview API**, implemented by Fiona (see [Estimate preview](#estimate-preview)):

| Endpoint | Behavior |
|---|---|
| `POST /estimate-preview` | Prices a selection from an exact catalog revision for a guest count and service duration. `200` with the lines and totals; records nothing. |

**Staff authentication API**, implemented by Fiona (see [Staff authentication](#staff-authentication)):

| Endpoint | Behavior |
|---|---|
| `POST /auth/login` | Verifies a staff password and sets Fiona's secure session cookie. Requires a trusted browser origin. |
| `POST /auth/logout` | Revokes the runtime session and clears the cookie, including on repeated logout. |
| `GET /auth/me` | Returns the active human staff profile and current role keys. |
| `PUT /admin/users/{userId}/credentials/password` | Sets a runtime user's Fiona password; requires `fionas.credentials.manage` and trusted Origin. |

The runtime administration capability is mounted at `/admin/access`: it exposes users,
services, roles, permission catalog, role grants, and principal role assignments in the
same OpenAPI document. Its routes use Fiona's session cookie and trusted Origin policy.

**Runtime infrastructure**, served by commerce-runtime and not in the OpenAPI document:

| Endpoint | Behavior |
|---|---|
| `GET /health` | Liveness: `200 {"status":"ok"}`. |
| `GET /ready` | Readiness: `200 {"status":"ready"}` when the database is reachable, `503` otherwise. |

**API documentation**:

| Endpoint | Behavior |
|---|---|
| `GET /openapi.json` | The OpenAPI 3.1 document of the Inquiry, Offerings Catalog, and Estimate preview APIs, rendered from the running contract. |
| `GET /docs` | Swagger UI for that document (redirects to `/docs/index.html`). |

A method an API path does not declare is `405` with an empty body; in particular, no
catalog route replaces or deletes anything.

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

## Offerings catalog

Fiona's catalog of what it sells (flavors, toppings, services) is a commerce-runtime
Offerings catalog. The split is deliberate:

- **Fiona chooses the catalog identity and where it is served.** Its primary catalog is
  `FIONA_OFFERINGS_CATALOG_ID`, `0cde8e0b-aa9c-4129-9853-8db2cbbb909b`, a constant in
  [`offering/FionaOfferings.kt`](src/main/kotlin/io/github/castab/fionas/commerce/offering/FionaOfferings.kt).
  It is never generated, configured, or stored in a Fiona table; every environment has its
  own database, so each uses this same id. `fionaOfferingsBinding(accessControl)` mounts the catalog at
  `/offering-catalog` with the operationId prefix `fionasOfferings` and `ReadWrite(accessControl)` access.
- **commerce-runtime implements the catalog machinery**: the operations, the contract routes
  and their DTOs and schemas, validation, and the append-only snapshot tables in its
  `commerce` schema. Fiona has no Offerings tables, repositories, DTOs, or SQL of its own.

The catalog is **immutable and revisioned**. Every change appends a complete new snapshot,
`r1`, `r2`, …; nothing is updated or deleted, and any earlier revision can be read back
exactly with `GET /offering-catalog/revisions/{revision}`.

On a fresh database **the catalog does not exist**: startup applies migrations and nothing
else, so `GET /offering-catalog` is `404 not_found` until it is initialized, once:

```bash
curl -i -X POST localhost:8080/offering-catalog
```

That creates the empty revision 1 (a second initialization is `409 conflict`). Categories
and offerings are then appended, each in its own new revision:

```bash
curl -i -X POST localhost:8080/offering-catalog/categories -H 'Content-Type: application/json' \
  -d '{"key":"soft-serve-flavor","displayName":"Soft Serve","minimumSelections":2,"maximumSelections":2}'
```

```bash
curl -i -X POST localhost:8080/offering-catalog/offerings -H 'Content-Type: application/json' \
  -d '{"key":"vanilla","category":"soft-serve-flavor","displayName":"Vanilla","description":"Classic vanilla soft serve"}'
```

`GET /offering-catalog` returns the latest revision in one request, offerings grouped under
their categories in order:

```json
{
  "catalogId": "0cde8e0b-aa9c-4129-9853-8db2cbbb909b",
  "revision": 3,
  "previousRevision": 2,
  "categories": [
    {
      "key": "soft-serve-flavor",
      "displayName": "Soft Serve",
      "minimumSelections": 2,
      "maximumSelections": 2,
      "offerings": [
        { "key": "vanilla", "category": "soft-serve-flavor", "displayName": "Vanilla", "description": "Classic vanilla soft serve" }
      ]
    }
  ]
}
```

An offering's `price` is optional descriptive metadata in one of three forms, discriminated
by `kind`:

| `kind` | Fields | Example |
|---|---|---|
| `FIXED` | `amount`, `currency` | `{"kind":"FIXED","amount":"120.00","currency":"USD"}` |
| `PER_QUANTITY` | `amount`, `currency`, `dimension` | `{"kind":"PER_QUANTITY","amount":"0.75","currency":"USD","dimension":"guest"}` |
| `PER_DURATION` | `amount`, `currency`, `interval` (ISO-8601) | `{"kind":"PER_DURATION","amount":"50.00","currency":"USD","interval":"PT1H"}` |

Amounts are exact decimal strings. A price describes an offering; it is not a pricing rule.
Fiona's pricing of a whole event (base fee, duration, guests, included toppings) is
[Fiona's engine](#estimate-preview), which applies these prices without knowing any
offering by name.

Production catalog contents are administrative data, entered through the API (for example
from Swagger UI at `/docs`) after deployment. Neither startup nor a migration seeds them.

The three catalog `POST` routes require an active staff session with
`commerce.offerings.manage`. The runtime enforces this through Fiona's `AccessControl`;
catalog reads remain public.

## Staff authentication

Fiona verifies staff passwords. `commerce-runtime` owns human and service identities,
roles, role permissions, assignments, live permission resolution, and sessions.
`commerce-domain` defines the principal and permission vocabulary. A session holds only
identity, so changing a role or disabling a user takes effect immediately.

```text
POST /auth/login → PasswordAuthenticator → UserId → context.sessions.create(...)
                 → __Host-fionas_session cookie
next request     → sessionAuthentication(...) → authenticatedPrincipal
                 → AccessControl → PermissionResolver → handler
```

`POST /auth/login` accepts `{"username":"...","password":"..."}` and answers `204`
with a `Secure`, `HttpOnly`, host-only, `Path=/`, `SameSite=Lax` cookie. Invalid credentials
or a disabled user receive the same `401` response. `GET /auth/me` returns the active
human staff profile and role keys without secrets. `POST /auth/logout` revokes the runtime
session and clears the cookie; repeating it is safe. No raw session token is sent in JSON.

The runtime directory normalizes usernames and stores the profile and status in
`commerce.users`. Fiona's `fionas.user_credentials` holds only the Argon2id hash and
change time, with a foreign key to that runtime user. Fiona contributes
`fionas.credentials.manage` to the runtime permission catalog. The bootstrap
Administrator role explicitly grants OfferingsManage, PrincipalRead, PrincipalManage,
RoleRead, RoleManage, RoleAssign, and CredentialsManage. Future permissions are not
granted automatically.

To provision the first administrator, set `FIONAS_BOOTSTRAP_ADMIN_USERNAME`,
`FIONAS_BOOTSTRAP_ADMIN_PASSWORD`, and `FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME` for one startup.
With none of these required variables, bootstrap is disabled. All three must be present
together; a partial set fails startup. The username and display name must not be blank
after trimming, and the password must be nonblank and at least 12 characters. The password
is not trimmed or changed. First and last names are optional. See [`.env.example`](.env.example)
for local development values; the application does not load that file automatically.
After both migration streams and permission validation, the app creates the runtime role,
runtime user, Fiona credential, and role assignment in one transaction only when no user
exists. Remove the bootstrap password from the environment after provisioning. Later
startups never overwrite a credential. No default password exists.

An administrator can create a user with `POST /admin/access/users`, then set its password
with `PUT /admin/users/{userId}/credentials/password`. The password must have at least 12
characters. The response contains no credential material. Setting a password does not
revoke existing sessions; there is no self-service password change in this API.

Set `FIONAS_TRUSTED_ORIGINS` to the exact frontend origin (or a comma-separated list),
for example `https://shop.example.com`. Login and every unsafe request carrying Fiona's
session cookie require a matching `Origin`; a missing or different origin receives `403`.
When no origin is configured, browser login fails closed. Public inquiry and estimate
preview requests remain public. `/health`, `/ready`, and Offerings reads remain public;
Offerings writes require `commerce.offerings.manage`.

Future service credentials will be authenticated by a separate Fiona-specific mechanism
to a `ServiceId`, then use this same `AccessControl` and `PermissionResolver` path. This
change does not add service credentials or service login endpoints.

## Estimate preview

`POST /estimate-preview` prices a selection the way Fiona's booking page does, with the
server as the authority. It is **not a quote, is never recorded, and creates no financial
document**: it creates no customer, inquiry, or anything else, and the same request prices
the same every time.

```text
catalog revision + guest count + duration + selections
        │
        ▼
commerce-runtime: that exact catalog revision (GetOfferingsCatalogRevision)
        │
        ▼
commerce-domain OfferingsEngine: is the selection structurally valid for that revision?
        │
        ▼
FionasOfferingsEngine: Fiona's pricing → LineItems
        │
        ▼
estimated lines + totals
```

The work is split three ways:

- **The catalog** (commerce-runtime) says what can be selected, how many selections each
  category allows, and what single offerings cost: a premium flavor at `+$0.50` per guest, a
  waffle cone at `+$0.75` per guest.
- **commerce-domain's `OfferingsEngine`** rejects a structurally invalid selection: an
  unknown category or offering, one in the wrong category, too few or too many selections,
  duplicates.
- **`FionasOfferingsEngine`** (Fiona) prices the event: the base fee and hourly rate, the
  per-guest ice cream service, each selected offering's catalog price, and the extra-topping
  rule. It knows no offering by name, so a new premium flavor added through
  `POST /offering-catalog/offerings` with a `PER_QUANTITY` `guest` price is priced at once,
  with no deployment.

Fiona's pricing policy (`FIONAS_PRICING_POLICY` in
[`offering/FionasPricingPolicy.kt`](src/main/kotlin/io/github/castab/fionas/commerce/offering/FionasPricingPolicy.kt)):

| Rule | Current value |
|---|---|
| Base service | `$150.00` per event plus `$50.00` per hour of service, one line |
| Service durations | 90, 120, 150, or 180 minutes |
| Ice cream service | `$4.00` per guest |
| Toppings | the first 4 selections of the `topping` category included; each further selection `$0.25` per guest, as one "Extra toppings" line |
| Tax | none yet: every line's tax is `0` |

Catalog prices are applied as follows: `FIXED` is one flat line; `PER_QUANTITY` with the
dimension `guest` is charged per guest (another dimension is rejected); `PER_DURATION` is
charged per interval of the service duration, only when the interval divides it exactly.
Every amount is in USD; an offering priced in another currency is rejected. Unpriced
selections add no line. A premium topping's own price and the extra-topping charge are
different facts, and both apply.

Lines come in a stable order: base service, ice cream service, priced selections in the
order submitted, extra toppings. Totals are the sums of the lines.

**The catalog revision is required and exact.** A page renders its choices from one revision
(`GET /offering-catalog` returns it) and submits that revision; the preview is priced from
it even after the catalog has changed, and a selection naming something the revision lacks
is rejected. A preview is never silently repriced from a later revision.

For example, with a catalog at revision 15 holding soft-serve flavors (Vanilla, Chocolate,
Horchata at `$0.50` per guest), six toppings, and cones (Cups, Waffle cones at `$0.75` per
guest):

```bash
curl -s -X POST localhost:8080/estimate-preview -H 'Content-Type: application/json' -d '{
  "catalogRevision": 15, "guestCount": 75, "durationMinutes": 120,
  "selections": [
    {"category": "soft-serve-flavor", "offerings": ["vanilla", "horchata"]},
    {"category": "topping", "offerings": ["sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough"]},
    {"category": "cone-option", "offerings": ["waffle-cone"]}
  ]}'
```

```text
Base service         2 hours · setup, staff & local travel     250.00   ($150 + 2 × $50)
Ice cream service    75 × 4.00                                  300.00
Horchata             75 × 0.50                                   37.50   (catalog price)
Waffle cones         75 × 0.75                                   56.25   (catalog price)
Extra toppings (2)   150 × 0.25                                  37.50   (2 extra × 75 guests)
                                                    total       681.25
```

The response lists each line's `description`, `subDescription`, `quantity` (absent for a
flat line), `unitPrice`, `subtotal`, `taxAmount`, `total`, and `currency`, then `subtotal`,
`taxAmount`, `total`, and `currency` for the estimate, with the `catalogRevision` it was
priced from. Every amount and quantity is an exact decimal string, never a JSON number.
`"guestCountIsMinimum": true` marks a guest count like "100+": the price uses exactly the
stated count, and the response echoes the flag so the page can say "from $X".

Errors use commerce-runtime's contract: `400 malformed_request` for a body that cannot be
read, `404 not_found` for a catalog revision that does not exist, and `422 validation_failed`
for a selection that does not fit the revision or Fiona's pricing. The message names each
violation's stable code, for example `TOO_MANY_SELECTIONS`, `UNKNOWN_OFFERING`,
`INVALID_GUEST_COUNT`, `UNSUPPORTED_DURATION`, `UNSUPPORTED_CURRENCY`,
`UNSUPPORTED_QUANTITY_DIMENSION`, or `INCOMPATIBLE_DURATION_PRICE`.

## API contract and OpenAPI

The Fiona API describes itself. Each endpoint is an http4k contract route that carries its
own OpenAPI metadata next to its handler: path, method, `operationId`, summary, tag,
request and response bodies with examples, and every status it answers. The OpenAPI
document is rendered from those routes; there is no hand-maintained `openapi.json` or YAML,
so changing an endpoint changes its documentation in the same place.

- **`GET /openapi.json`** is the machine-readable contract, served live by the application.
  It needs no database and describes Fiona's API, `/inquiries` and `/offering-catalog`,
  not the runtime's `/health` and `/ready` or the documentation routes.

  ```bash
  curl http://localhost:8080/openapi.json
  ```

- **`GET /docs`** is Swagger UI reading `/openapi.json`, with "Try it out" against the
  same origin. Its assets come from the Swagger UI WebJar inside the application jar,
  never from a CDN. For `http://localhost:8080/docs`, set
  `FIONAS_TRUSTED_ORIGINS=http://localhost:8080` to use login and unsafe
  cookie-authenticated methods.
- **`./gradlew generateOpenApi`** writes the same document, pretty-printed, to
  `build/openapi/fionas-commerce-openapi.json`, without a database, Docker, a server, or
  network access. `./gradlew build` runs it, and CI keeps the file as the
  `fionas-commerce-openapi` workflow artifact of every successful build, so other
  applications can use the contract without a deployed instance.

The document is OpenAPI 3.1.0. `info.version` is the Gradle project version
(`0.0.0-SNAPSHOT` by default in `gradle.properties`; a release build sets
`-Pversion=<version>`). It declares no server host, so it is the same in every
environment. The stable `operationId`s are `createInquiry`, `getInquiry`,
`previewEstimate`, `login`, `logout`, and `getCurrentUser`, and for the catalog
`fionasOfferingsGetCatalog`, `fionasOfferingsCreateCatalog`,
`fionasOfferingsGetCatalogRevision`, `fionasOfferingsListCategories`,
`fionasOfferingsAddCategory`, `fionasOfferingsGetCategory`,
`fionasOfferingsListCategoryOfferings`, `fionasOfferingsListOfferings`,
`fionasOfferingsAddOffering`, and `fionasOfferingsGetOffering`.

The Offerings routes are commerce-runtime's own contract routes, mounted in the same
contract, so their documentation is the runtime's: the same routes serve requests and
describe themselves. Their schemas come from the runtime's `offeringsOpenApiRenderer`,
which keeps `OfferingPriceDto` a strict `oneOf` of `FixedOfferingPrice`,
`PerQuantityOfferingPrice`, and `PerDurationOfferingPrice`, discriminated by `kind`. That
renderer works only with http4k's Jackson, so `http4k-format-jackson` is a dependency, used
for nothing else: requests and responses stay kotlinx.serialization.

Fiona's own schemas are derived from the kotlinx.serialization descriptors of the transport
DTOs, the wire format itself, so `required` matches what the server reads and writes: strings,
`int32` integers, booleans, arrays, and nested objects, each its own component. Known
gaps: the `Location` header of `201` is described in prose only, because http4k 6.58's
contract metadata cannot declare response headers; and the catalog's schemas contain
`"format": null`. Commerce-runtime 0.0.11 accepts Fiona's OpenAPI tags: Swagger UI groups
catalog operations under **Offerings catalog** and runtime administration plus Fiona's
password route under **Staff administration**. Every Fiona endpoint must be part of the
contract; the rules are in [`AGENTS.md`](AGENTS.md#api-contract-and-openapi).

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
environment. Bootstrap staff credentials are supplied only through environment variables.

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
| `SESSIONS_LIFETIME_MINUTES` | Fixed runtime session lifetime | `720` |
| `FIONAS_TRUSTED_ORIGINS` | Comma-separated exact browser origins for login and cookie-authenticated mutations | none; browser login is denied until configured |
| `FIONAS_BOOTSTRAP_ADMIN_USERNAME` | First administrator's username; required with password and display name | none |
| `FIONAS_BOOTSTRAP_ADMIN_PASSWORD` | First administrator's password, at least 12 characters; remove after provisioning | none |
| `FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME` | First administrator's nonblank display name; required with username and password | none |
| `FIONAS_BOOTSTRAP_ADMIN_FIRST_NAME`, `FIONAS_BOOTSTRAP_ADMIN_LAST_NAME` | Optional profile fields | none |
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
- Fiona's tables are in the `fionas` schema: `customers`, `inquiries`, and
  `user_credentials` (`inquiries.customer_id → customers.id`, `customers.email` unique,
  `user_credentials.user_id → commerce.users.principal_id`). Fiona never creates or
  changes anything in `commerce`, where the runtime keeps its own tables, including the
  Offerings snapshot tables that hold Fiona's catalog. The catalog needs no Fiona
  migration.
- Composing the runtime runs the migration phase before anything is served. By default
  (`MIGRATIONS_ON_STARTUP=migrate`) it applies the runtime's pending migrations, then
  Fiona's; re-running against a current database applies nothing. A deployment that
  migrates in a separate release step sets `MIGRATIONS_ON_STARTUP=validate` on its
  instances. There is no separate migration command yet.
- If any migration fails, or validation finds the database behind, the process logs
  `event=startup_failed` and exits without serving.

> **Development reset for the commerce 0.0.11 integration.** Fiona's unreleased `V2`
> contains only password credentials. Disposable databases from before the authorization
> directory integration must be reset before starting this version; databases already
> reset for commerce 0.0.10 need no second reset. Commerce 0.0.10 introduced the runtime
> authorization directory. Both migration streams run before bootstrap or route composition.
>
> **Upgrading from commerce 0.0.6.** commerce-runtime 0.0.8 only adds a runtime migration
> (the Offerings tables), so a database created with 0.0.6 migrates forward on startup.
>
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
with the application, its dependencies, `application.conf`, `logback.xml`, the Fiona
migrations (Flyway's service files are merged), and the Swagger UI assets served at
`/docs`. This is the deployable artifact. The
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
| `DatabaseSchemaSpec` | Fiona's tables and keys are in `fionas`; `commerce` holds exactly what commerce-runtime creates on its own (its Offerings tables included); nothing Fiona-owned in `commerce` or `public` |
| `MigrationLifecycleSpec` | Fiona as a consumer of the runtime's migration phase: runtime migrations first (an application migration depending on them succeeds), independent version spaces, repeat startup applies nothing, failures prevent composition |
| `JdbiCustomerRepositorySpec`, `JdbiInquiryRepositorySpec` | Insert/read, email lookup, unique email conflict, foreign key |
| `RuntimeTransactionSpec` | Fiona repositories write through the runtime `Transaction`: both writes roll back together, and nothing is visible before commit |
| `InquiryOperationsSpec` | New customer + inquiry together, customer reuse, atomic failure, not found |
| `InquiryRoutesSpec` | The HTTP API through the complete runtime handler, including errors: the contract leaves every error body to commerce-runtime, and undeclared methods stay `405` |
| `AuthRoutesSpec` | Fresh bootstrap, generic login failures, session lifecycle, live Offerings grants, runtime administration, credential provisioning, and Origin checks |
| `FionasOfferingsEngineSpec` | Fiona's pricing, purely: the `$681.25` estimate, base and duration, per-guest service, each catalog price form, included and extra toppings, premium toppings, every policy violation, minimum guest counts, line order and injected ids, zero tax, exact totals, and structural validation left to commerce-domain |
| `EstimatePreviewRoutesSpec` | `POST /estimate-preview` through the complete handler over a catalog built with the Offerings API: the `$681.25` estimate, nothing recorded, minimum guest counts, pricing from the requested revision rather than a later one, and the `400`/`404`/`422` error contract |
| `OfferingsCatalogSpec` | Fiona's Offerings catalog through the complete handler: absent until initialized; revisions 1–4 from initialization, a category, and two offerings; ordered reads; exact historical revisions; every price form round-trips; no update or delete route |
| `OpenApiDocumentSpec` | The OpenAPI document: Fiona routes, runtime Offerings and administration routes, operationIds, statuses, schemas, and no host; the runtime's strict `OfferingPrice` `oneOf` |
| `OpenApiRoutesSpec` | `/openapi.json` and `/docs` through the complete handler; the served document equals the generated one; Swagger UI reads `/openapi.json`, which offers the Offerings operations, and loads nothing external |
| `GenerateOpenApiSpec` | `generateOpenApi` writes that document as UTF-8 JSON, byte-identical on every run |
| `FionaApplicationSpec` | `application.conf` loads, `/health` and `/ready`, a real server on a port |
| `ArchitectureSpec` | Repositories take a `Transaction` and build no transaction infrastructure; no SQL in routes; no HTTP in persistence; every endpoint is a contract route with an `operationId`; no hand-written OpenAPI file; one http4k version; the stable catalog id and binding; no Fiona Offerings types, repositories, or SQL; pricing depends only on commerce-domain and names no offering; previews record nothing; no financial-document persistence; Jackson only for the Offerings schemas |

Full verification, as CI runs it (`build` also generates the OpenAPI document):

```bash
./gradlew ktlintCheck test build
```

`./gradlew ktlintFormat` fixes formatting.
