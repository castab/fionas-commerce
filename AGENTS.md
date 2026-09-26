# AGENTS.md

The architectural contract for contributors and coding agents working in this repository.
Read it before changing anything under `src/main`, before adding a dependency, and before
introducing a new concept. [`README.md`](README.md) explains how to build, run, and
configure the application; this file explains the rules and why seemingly reasonable
changes can be architecturally wrong.

These rules are non-negotiable without an explicit decision from the maintainer.

## Application identity

`fionas-commerce` is the concrete commerce backend of Fiona's Ice Cream and its catering
business. It is an **application**, not a framework and not a library:

- It owns Fiona-specific concepts, Fiona's HTTP API, Fiona's persistence and migrations,
  its configuration, its logging backend, `main()`, the process lifecycle, and its
  deployable artifact.
- It is never published as a Maven artifact. There is no `maven-publish`.
- It is not the place to invent generic commerce abstractions for hypothetical
  businesses. If behavior could belong to every commerce application (catering, mobile
  detailing, repair, pet services, point of sale), it may belong in `commerce-domain` or
  `commerce-runtime` instead. Do not move it automatically: identify and document the
  requirement first (see [Commerce-runtime gap rule](#commerce-runtime-gap-rule)).

## Dependency direction

```text
fionas-commerce          Fiona's application: entities, relationships, policy, HTTP, process
      │
      ▼
commerce-runtime         reusable runtime: PostgreSQL, JDBI, Flyway, Transactor, http4k, errors
      │
      ▼
commerce-domain          reusable vocabulary and invariants
```

Never create a dependency in the opposite direction. The application depends on
`io.github.castab:commerce-runtime` only; `commerce-domain` arrives transitively at the
matching version. Do not declare `commerce-domain` separately unless a concrete build
reason appears, and never at a different version.

Upstream sources, READMEs, and `AGENTS.md` live in
[castab/commerce-domain](https://github.com/castab/commerce-domain) (being renamed to
`commerce`). Read them at the tag matching the version in `gradle/libs.versions.toml`
before relying on an upstream API. Never copy, fork, or vendor their code.

## Application-owned concepts

Fiona owns, and persists in its own tables:

- customers;
- inquiries;
- Fiona bookings (future);
- contacts (future);
- event and service details, and service locations (future);
- relationships between these records and generic commerce facts (future), for example
  which `FinancialDocument` lineage an inquiry or booking produced.

Fiona types live under `io.github.castab.fionas.commerce`. Never place a Fiona type under
`io.github.castab.commerce`, which belongs to the reusable artifacts.

### Customers versus event contacts

A `Customer` is the durable person entering into the commercial relationship: identity,
name, email. Booking or event contacts are different:

- an event may have one or more contacts;
- an event contact may be someone other than the customer;
- contact details are contextual to one booking and may be purged once it reaches a
  terminal phase.

So the customer record is **never** the storage location for event-specific contact
details, and contact details are never merged into the customer to "save a table". When
contacts are implemented, they belong to the inquiry or booking they describe, in their
own table, purgeable independently of the customer.

### Customer matching (current, deliberately simple policy)

`CreateInquiry` establishes the customer by normalized email:

- The submitted email is normalized (trimmed, lowercased) by `Email.of`.
- If a customer already has that email, it is **reused as is**. A differing submitted
  name does **not** overwrite the stored name, and the differing name is not retained
  anywhere.
- Otherwise a new customer is created, in the same transaction as the inquiry.
- `fionas.customers.email` is unique, because this policy needs "the customer with this
  email" to be unambiguous. A concurrent request that loses the race for a new email
  gets `409 conflict` and may retry.

The customer's identity is `CustomerId`, not the email. The email is a lookup key. There
is no customer update, merge, or deduplication. **Unresolved:** whether a returning
customer's new name should be recorded or confirmed, whether two customers may ever share
an email (for example a household), and whether email matching is acceptable without
verifying ownership of the address. Decide these explicitly before changing the policy.

## Generic commerce concepts

Do not duplicate or re-model anything `commerce-domain` defines:

- financial documents (`Estimate`, `Quote`, `Invoice`), versions, money, line items;
- payments, allocations, reversals, refunds, reconciliation;
- the payment-adapter contract;
- principals, roles, permissions;
- the booking lifecycle phase interfaces.

Use the upstream types. A Fiona booking implements the upstream lifecycle phase
interfaces directly; it does not reinvent phases. Fiona tables may later *reference*
commerce facts (for example by a financial document's `id`), but generic types never
acquire Fiona fields: no `bookingId`, `customerId`, or Fiona data on `FinancialDocument`
or any other upstream type.

`Inquiry` and `Customer` are Fiona concepts. Do not generalize them, and do not propose
them upstream as generic types; upstream deliberately removed customers in `0.0.5`.

## Runtime-owned infrastructure

`commerce-runtime` owns, and this application must not replace, fork, or duplicate:

- `Transactor` and `Transaction`;
- the JDBI root and the HikariCP connection pool;
- migration orchestration (`MigrationLifecycle`, run by `commerceRuntime(...)`), the
  runtime's own migrations, and the `commerce` schema;
- the error model (`CommerceFailure`, `validating`, `CommerceErrorHandling`,
  `ErrorResponse`);
- http4k/Jetty composition (`commerceRuntime(...)`), `CommerceJson`, `jsonBody`;
- configuration loading (`CommerceRuntimeConfiguration.load()`);
- `/health` and `/ready`.

Never create another connection pool, another `Jdbi` instance, another transaction
manager, Spring transactions, a nested transaction abstraction, a second configuration
loader, or a second error/response framework. Tests may build their own infrastructure
only to *observe* the database from outside the runtime (see `TestDatabase`).

## Composition

`FionaApplication.kt` is the composition root. `fionaApplication()` returns the
`ApplicationContributions` (Fiona's migration location and route factory); the route
factory builds repositories and operations from the `CommerceRuntimeContext` with
ordinary Kotlin, hands the operations to the API as `FionaOperations`, and contributes
exactly two route handlers: `fionaApi(...)` (the API contract) and `apiDocs()` (Swagger UI). `Main.kt` loads configuration, calls `commerceRuntime(...)` (which runs
the migration phase before composing anything), starts it, installs the shutdown hook,
and blocks. Keep `main()` thin: no schema, Flyway, or migration decisions belong in it. There is no DI framework, no annotation scanning,
no service locator. Keep wiring visible.

## Transaction rule

- **Operations own the transaction boundary**, through `context.transactor.inTransaction`.
- **Repositories receive the caller's `Transaction`** as their first parameter and use
  `transaction.handle` for JDBI access.
- **Repositories never begin, commit, or roll back**, never call `inTransaction`, and hold
  no `Jdbi`, `Handle`, `DataSource`, or `Transactor`.
- An operation that coordinates several writes does all of them inside one
  `inTransaction` call. Never nest `inTransaction`; it opens a separate transaction. Pass
  the `Transaction` down instead.

The shared runtime transaction is the seam that will let one Fiona operation atomically
write Fiona-owned rows and runtime-owned commerce facts (for example an inquiry, its
estimate, and the relationship between them). Preserve it. `ArchitectureSpec` and
`RuntimeTransactionSpec` guard this rule; never loosen them to make a change pass.

## HTTP rule

Routes translate transport. Operations orchestrate. Repositories persist.

- Route: a `ContractRoute` (see [API contract and OpenAPI](#api-contract-and-openapi)):
  request DTO → application values (inside `validating { }`) → one operation → response
  DTO.
- Routes receive operations as plain functions (`(CreateInquiry.Command) -> InquiryDetails`),
  never repositories or the `Transactor`.
- No SQL in routes. No HTTP in repositories or operations. No persistence in routes.
- Transport DTOs are `@Serializable` classes in the `http` package. Never put
  serialization annotations on application types, and never let DTOs leak into
  operations or repositories.
- Expected failures are `CommerceFailure`s with caller-safe messages. SQL text,
  constraint names, stack traces, and exception details never reach a response.
  Repositories translate a unique violation into `CommerceFailure.Conflict`
  (`isUniqueViolation()`); everything unexpected becomes `internal_failure`.

## API contract and OpenAPI

Fiona's HTTP API is **one http4k contract**, and its OpenAPI document is an **output** of
that contract, never a second source of truth:

```text
new Fiona endpoint
      │
      ▼
ContractRoute in the feature's routes file (http/InquiryRoutes.kt)
      ├── path, method, request and response lenses
      ├── operationId, summary, description, tag
      ├── every status it answers, errors through returningError(ErrorCategory…)
      └── the handler
      │
      ▼
listed in fionaApiRoutes (http/FionaApi.kt)
      ├──► the running API                              fionaApi(...)
      ├──► GET /openapi.json                            rendered by that same contract
      └──► build/openapi/fionas-commerce-openapi.json   generateOpenApi, same fionaApi(...)
```

There is no later step called "update the spec". The rules:

1. **OpenAPI is rendered from executable contract routes.** Never add or maintain an
   `openapi.json`, `openapi.yaml`, `swagger.*`, or any document describing routes
   separately, and never describe a route anywhere but on its own `ContractRoute`.
   `ArchitectureSpec` rejects such files.
2. **Every externally supported Fiona endpoint is a `ContractRoute` in `fionaApiRoutes`.**
   Never add an ordinary http4k route (`bind`, `routes(`) for an endpoint because it is
   quicker. The only ordinary routing is plumbing in `http/FionaApi.kt`, each piece listed
   in `ArchitectureSpec`: Swagger UI at `/docs`, and the `405` answer for methods a
   contract path does not declare (derived from the contract's own paths). A new exception
   needs a stated reason and a deliberate change to that guard.
3. **Every operation has a deliberate, stable `operationId`** (`createInquiry`,
   `getInquiry`), chosen by hand, never derived from class or function names. Code
   generators name client methods after it, so **changing one is a breaking API change**.
4. **Metadata changes with the implementation, in the same change**: statuses, request
   and response bodies, examples, and property facts (`@ApiProperty`). A route documents
   every status it can answer; errors use `returningError(ErrorCategory.…)`, which takes
   status and code from commerce-runtime and the body from its `ErrorResponse`. Never
   define a Fiona error model.
5. **The served and the generated document are one rendering** of `fionaApi(...)`. The
   generator (`src/openapi`, `generateOpenApi`) calls the contract with `FionaOperations`
   that are never invoked; it must never need a database, Docker, a server, or the
   network. Adding an operation to `FionaOperations` forces the generator's stub to name
   it.
6. **Runtime-owned routes are not Fiona routes.** Never redeclare `/health` or `/ready` as
   contract routes to make them appear in the document: that would be a second
   implementation. They join the document only if commerce-runtime publishes metadata for
   them.
7. **kotlinx.serialization stays the wire format.** Never switch to Jackson, or add a
   second JSON representation, for documentation. Schemas are derived from the DTOs'
   serial descriptors (`KotlinxSchemas` in `http/OpenApi.kt`); what a type cannot say goes
   in `@ApiProperty` on the transport DTO property, referencing the domain's constants
   (for example `maxLength = CustomerName.MAX_LENGTH`). `@ApiProperty` is for
   `@Serializable` DTOs in `http` only, never for application or domain types.
8. **Document only what the server enforces or guarantees.** No `format` the server does
   not produce or check (the request email is shallowly validated text, so it has no
   `email` format); limits the server applies after normalizing say so. `KotlinxSchemas`
   fails loudly on a property kind it does not support rather than emit a vague schema;
   extend it when a DTO needs it.
9. **An API contract change includes OpenAPI tests** (`OpenApiDocumentSpec`) alongside the
   behavior tests (`InquiryRoutesSpec`).
10. **Breaking changes are intentional.** Removing or renaming a path, method, property,
    `operationId`, or status, making an optional property required, or changing a type or
    format breaks clients; it is never a side effect of refactoring. There is no URL
    versioning (`/v1`); introducing it is a separate decision.

Also:

- **Contract routing must not change error semantics.** The contract's own failures go to
  commerce-runtime (`RuntimeErrorHandling`): unreadable input is rethrown as its
  `LensFailure` (`400 malformed_request`), and an unmatched request is an empty `404`
  (`not_found`). A contract treats a path value its lens rejects as an unmatched route
  (`404`), so contract path lenses are plain strings; identifiers are parsed in the
  handler, where a bad one is a `LensFailure` (`400`), and documented with
  `format: uuid`. Request bodies are read once, by the handler (no pre-flight extraction).
- **The document names no server host.** It is identical in every environment; Swagger UI
  calls the origin that served it.
- **`info.version` is the Gradle project version**, written by the build into
  `fionas-commerce.properties` and read by `fionaVersion()`: `0.0.0-SNAPSHOT` in
  `gradle.properties` until a release sets `-Pversion`.
- **Swagger UI is self-contained**: its WebJar is packaged in the fat jar, and `/docs`
  never loads assets from a CDN. No authentication options are configured, because the API
  has none.
- **http4k modules stay at commerce-runtime's http4k version** (`http4k` in
  `libs.versions.toml`), never a newer BOM; `ArchitectureSpec` fails when two http4k
  versions meet on the classpath.

## Application migrations

**Database migrations in this repository are application-owned migrations only.**
`commerce-runtime` owns migration orchestration and its own persistence migrations. Do not
instantiate an independent Flyway startup lifecycle, copy runtime migrations into this
repository, modify `commerce`-owned objects from Fiona's migrations, or coordinate
application migration version numbers with runtime migration versions. Application
migrations live in Fiona's migration location and schema, and are executed by the runtime
after runtime-owned migrations.

- **The runtime orchestrates; Fiona owns only the contents.** Fiona contributes one
  location, `classpath:db/fionas` (`FIONA_MIGRATION_LOCATION`), through
  `ApplicationContributions.migrationLocations`. The runtime discovers its own migrations
  inside its jar; Fiona never lists, copies, or depends on their files or versions.
- **Runtime first.** `commerceRuntime(...)` applies the runtime's migrations, then Fiona's,
  before anything is composed or served. A Fiona migration may therefore depend on
  runtime-owned structures, never the reverse.
- **Independent version space.** Fiona's migrations are `V1__…`, `V2__…`, the next integer
  in Fiona's own history, unrelated to the runtime's numbering (both have a `V1`). If two
  branches add the same `V<n>`, the one merged second renumbers before merging.
- **Fiona's objects live in the `fionas` schema**, created by Fiona's `V1`. SQL names it
  explicitly (`fionas.customers`); never rely on `search_path`. `public` holds only the
  history table the runtime keeps for Fiona's stream.
- **Never create, alter, or drop anything in `commerce`**, and never add files under
  `db/commerce`. If Fiona needs a runtime-owned structure to change, stop and raise it as
  a runtime requirement (see [Commerce-runtime gap rule](#commerce-runtime-gap-rule)).
- **Reference runtime structures only when they are a published contract.** A foreign key
  to a runtime table is legitimate when commerce-runtime publishes that table for
  applications; never depend on incidental runtime tables, indexes, or Flyway metadata.
  As of 0.0.6 the runtime publishes no table. `ArchitectureSpec` rejects any `commerce.`
  reference in Fiona migrations; the first sanctioned one updates that guard deliberately.
- **History is immutable.** Never edit a migration that has run outside a disposable
  database; correct it with a new migration. (One pre-release exception, before any
  deployment: the original `V20260926210000` migration was rewritten as `V1`, moving the
  tables from `public` to `fionas`, alongside commerce 0.0.6's own history reset.)
- **Expand → migrate → contract** for any change to a deployed schema: add the new
  structure, move code and data to it in a later release, remove the old one only after
  no running version uses it. Old and new instances overlap during rolling deploys.
- **Migration failure prevents startup.** `commerceRuntime(...)` throws and no runtime is
  returned; `main()` logs `event=startup_failed` and exits. Never catch a migration failure
  to keep booting.
- **Startup mode is the runtime's setting.** Fiona's `application.conf` sets
  `migrations.onStartup = MIGRATE` (migrate, then serve). A deployment that migrates in a
  separate release step sets `MIGRATIONS_ON_STARTUP=validate`. Never add Fiona-specific
  migration switches. A future migration-only entry point would call the runtime's
  `MigrationLifecycle`; none exists yet, deliberately.

## Kotlin conventions

- Do not write redundant explicit `public`. Write `data class Inquiry(...)` and
  `fun createInquiry(...)`, never `public data class ...` or `public fun ...`. Use
  explicit visibility only when it carries information: `private`, `internal`,
  `protected`.
- Prefer imports over fully qualified identifiers in declarations and code. Do not write
  `io.github.castab.commerce.runtime.persistence.Transaction` inline to avoid an import.
- Identifiers are distinct UUID-backed value classes (`CustomerId`, `InquiryId`), never
  raw `UUID`s passed around, and never one generic `EntityId`.
- Value objects validate in `init` with `require`; their constructors accept only the
  canonical form, and a factory (`Email.of`, `CustomerName.of`,
  `InquiryMessage.ofOptional`) normalizes submitted text.
- Operations take a `java.time.Clock` and ID-producing functions rather than calling
  `Instant.now()` or `UUID.randomUUID()` deep inside code. Timestamps are truncated to
  microseconds, PostgreSQL's precision.
- Formatting: ktlint (`ktlint_official`, `.editorconfig`). Run `./gradlew ktlintFormat`
  before committing; `compileKotlin` formats main sources as well.

## Packages

Organize by cohesive feature, not by layer. Current packages:

| Package | Contents |
|---|---|
| `io.github.castab.fionas.commerce` | `Main.kt`, `FionaApplication.kt` (composition root) |
| `...customer` | `Customer` and its values, `CustomerRepository`, `JdbiCustomerRepository` |
| `...inquiry` | `Inquiry` and its values, `InquiryRepository`, `JdbiInquiryRepository`, the `CreateInquiry` and `GetInquiry` operations |
| `...http` | The API contract (`FionaApi.kt`: `fionaApiRoutes`, `fionaApi`, `apiDocs`), its OpenAPI renderer and schemas (`OpenApi.kt`), and each feature's contract routes and transport DTOs (`InquiryRoutes.kt`) |
| `...openapi` (source set `src/openapi`) | The `generateOpenApi` entry point; not in the deployable jar |

Do not create empty packages or layers for future work. Avoid `service`, `manager`,
`handler`, `util`, `common`, `base`, or `framework` packages and classes unless they
acquire a concrete, well-defined responsibility.

## Premature abstraction rule

Do not create generic frameworks, `Repository<T, ID>`, `CrudRepository`,
`BaseRepository`, generic service layers, plugin systems, or extension APIs for possible
future requirements. Repositories are narrow and intention-revealing. Extract only after
real repetition or a real requirement appears.

Not in scope until a dedicated slice decides otherwise: quotes, estimates, invoices or
any financial-document persistence, payments, refunds, allocations, reconciliation,
Stripe or any payment provider, authentication, authorization, role persistence, event
publishing, outbox, NATS, projections, CQRS, booking conversion, lifecycle transitions,
pricing, deposits, and customer merge or deduplication. Do not add placeholders for them.

Also never introduce Spring or Spring Boot, Hibernate/JPA, a DI framework, event
sourcing, H2, or Testcontainers.

## Commerce-runtime gap rule

If a Fiona feature needs generic commerce persistence or orchestration that
`commerce-runtime` does not expose (for example persisting a `FinancialDocument`), stop
at the architectural boundary and document:

1. the concrete Fiona use case;
2. the missing runtime capability;
3. the minimal reusable API that appears necessary;
4. why implementing it here would duplicate generic commerce behavior.

Then the capability is added upstream, released, and consumed here. Never quietly build a
parallel generic commerce subsystem in this application. The intended progression is:

```text
real Fiona requirement → Fiona implementation → missing reusable seam becomes evident
  → minimal upstream change → new commerce release → Fiona consumes it
```

### Known upstream gaps (as of commerce 0.0.6)

- **Application history schema is fixed.** Fiona's tables live in `fionas`, but the
  runtime keeps every application's migration history in `public.flyway_schema_history`
  (`ApplicationMigrations.SCHEMA`), with no way to choose another schema.
- **No published runtime table.** The runtime owns no table yet, so nothing Fiona could
  legitimately reference exists; `MigrationLifecycleSpec` proves runtime-first ordering
  through the `commerce` schema itself.
- **Validation is not a public operation.** `MigrationLifecycle.migrate()` is public, but
  validate-only exists only through `commerceRuntime(...)` with `VALIDATE`.
- **Hoplite prints a deprecation notice to stdout** on every
  `CommerceRuntimeConfiguration.load()` (sealed-type inference), bypassing the
  application's logging. The fix belongs in the runtime's `ConfigLoaderBuilder`
  (`withExplicitSealedTypes()`).
- **No reusable test support.** The Docker-CLI PostgreSQL build service and
  `TestDatabase` follow upstream's pattern but are re-implemented here, because the
  runtime publishes no test fixtures.
- **Runtime routes carry no API metadata.** `/health` and `/ready` are plain http4k routes,
  so they cannot join Fiona's OpenAPI document without being redeclared here, which the
  API contract rules forbid. A combined document needs the runtime to publish contract
  metadata for them.
- **The error body lens is private.** `CommerceErrorHandling` renders `ErrorResponse`
  through a private lens, so Fiona builds its own `jsonBody(ErrorResponse.serializer())`
  to document error responses. It is the same runtime type, not a second error model; a
  public lens (or documented error responses) upstream would remove it.
- **`ErrorResponse` has no schema descriptions.** Its OpenAPI schema says `code` and
  `message` are required strings but cannot describe them, because the type is upstream.
- **Cross-boundary atomicity is unproven.** The runtime has no commerce repository yet,
  so no test writes a Fiona row and a commerce row in one transaction. When the first
  commerce repository ships, add that test here.

### Known http4k 6.58 limitations (not to be fixed by upgrading here)

- `OpenApi3(info, kotlinxJson)` selects the reflective `ApiRenderer.Auto`, which fails on
  kotlinx.serialization models (`Serializer for class 'JsonLiteral' is not found`), and the
  example-based renderer infers neither `required` nor formats. Hence `KotlinxSchemas`.
- Contract response metadata has no headers, so `201`'s `Location` is described in prose.
- A contract answers OPTIONS on a declared path with `200` and an undeclared method with
  `404`; `fionaApi` keeps the API's established `405`.

## Testing expectations

- Kotest `FunSpec` with Kotest assertions. No JUnit assertion APIs.
- Database specs use real PostgreSQL started by the build through the Docker CLI (or
  `TEST_DATABASE_JDBC_URL`). Each spec creates its own database; the real migrations are
  applied by commerce-runtime through `TestApplication`, which composes the application
  exactly as `main()` does and exposes the runtime's own `Transactor`.
- Keep: value-object tests, repository integration tests, operation tests (including
  atomic rollback), `RuntimeTransactionSpec` (Fiona writes roll back together and stay
  invisible until commit), HTTP tests through the complete handler, schema tests,
  `MigrationLifecycleSpec` (consumer-level migration contract only; the runtime's suite owns
  the lifecycle internals), `OpenApiDocumentSpec` (the document's paths, operationIds,
  statuses, and schemas), `OpenApiRoutesSpec` (`/openapi.json` and `/docs` through the
  complete handler, and parity with the generator), `GenerateOpenApiSpec` (the build
  artifact, byte-deterministic), and `ArchitectureSpec`.
- Run `./gradlew ktlintCheck test build` before considering work complete.

## Documentation synchronization

Changes to endpoints, the API contract, configuration, migrations, packages, the
customer-matching policy, the version convention, or the upstream version must update
`README.md` and this file in the same change.
