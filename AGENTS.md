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
commerce-runtime         reusable runtime: PostgreSQL, JDBI, Flyway, Transactor, http4k, errors,
      │                  Offerings snapshots and the Offerings catalog capability, the
      │                  financial ledger (documents, payments, allocations, reconciliation)
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
- inquiries, the Fiona pricing inputs a customer configured with one (see
  [Inquiries and the staff inbox](#inquiries-and-the-staff-inbox)), and the staff inquiry
  list;
- password credentials, verification, login, browser Origin policy, and first-admin
  bootstrap policy; commerce-runtime owns staff users and authorization;
- the identity of Fiona's primary Offerings catalog and where it is served (its contents
  are persisted by commerce-runtime; see [Offerings catalog](#offerings-catalog));
- Fiona's pricing: `FionasPricingInputs`, `FionasOfferingsContext`, `FionasPricingPolicy`,
  `FionasOfferingsEngine`, `FionasPricing`, and estimate previews (see
  [Fiona's pricing](#fionas-pricing));
- which inquiry owns each commerce-runtime `FinancialDocument` lineage, the pricing inputs
  of every financial snapshot, change-order intent, and payment acceptance policy (see
  [Financial documents and payments](#financial-documents-and-payments));
- Fiona bookings (future);
- contacts (future);
- event and service details, and service locations (future);
- further relationships between these records and generic commerce facts (future), for
  example which lineage a booking produced.

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
- **The public response never discloses a stored customer.** `POST /inquiries` answers a
  receipt of the new inquiry (`id`, `createdAt`, and `Location`), never the customer's
  id, stored name, or email, so submitting an address reveals nothing about whoever owns
  it. `CreateInquiry` returns the `Inquiry` alone, so no route can leak what it never
  receives. Customers are read only by staff holding `fionas.inquiries.read`.

The customer's identity is `CustomerId`, not the email. The email is a lookup key. There
is no customer update, merge, or deduplication. **Unresolved:** whether a returning
customer's new name should be recorded or confirmed, whether two customers may ever share
an email (for example a household), and whether email matching is acceptable without
verifying ownership of the address. Decide these explicitly before changing the policy.

### Inquiries and the staff inbox

- **Submission is public; reading is staff-only.** `POST /inquiries` is public.
  `GET /inquiries` and `GET /inquiries/{inquiryId}` require Fiona's
  `fionas.inquiries.read`. There is no public confirmation lookup: a confirmation renders
  what the customer just submitted.
- **The inbox is a primitive, not a query API.** `ListInquiries` returns the newest
  inquiries first, ordered by `created_at DESC, id DESC`, `limit` 1–100 (default 25) per
  page, continuing strictly after the previous page's last `(created_at, id)` (keyset, never
  offsets). The HTTP cursor is that position, base64url-encoded, and is opaque to clients.
  The `inquiries_created_at_id_idx` index serves every page. Never add search,
  filters, inquiry status, offsets, or a generic pagination framework here without a
  dedicated slice.
- **Requested pricing inputs are the customer's request, never amounts.** An inquiry may
  carry the `FionasPricingInputs` the customer configured (catalog revision, selections,
  guest count, duration), in `fionas.inquiry_pricing` and its ordered categories and
  selections. `CreateInquiry` checks them with `FionasPricing` from exactly the revision they
  name, inside its one transaction, and discards the lines: rejections are the preview's
  own `404`/`422`, the revision is pinned as submitted, and no `FinancialDocument` is
  created. Staff read them back unchanged and may submit them to
  `POST /inquiries/{inquiryId}/estimates`. They are never rewritten later.
- **Event facts are not modeled.** Date, location, and occasion stay in the free-form
  `message` until a slice introduces event details; never encode structured facts in
  `message` or bolt them onto the pricing inputs.

## Fiona's customer inquiry form

- `GET /inquiry-form` is public, a ContractRoute with operationId `getInquiryForm`.
  `GetInquiryForm` reads one current snapshot through the runtime's `GetOfferingsCatalog`,
  wired in the composition root, and adapts it into `InquiryForm`. No persistence, seeding,
  second catalog, generic form DSL, or shared commerce form concept is introduced.
- Fiona owns the ordered sections, stable question keys, labels, submission bindings, and
  small rendering hints. Semantic inputs are distinct from hints: TEXT, EMAIL, INTEGER,
  BOOLEAN, INTEGER_CHOICE, and OFFERING_CHOICE. No frontend component names.
- Public offering questions explicitly reference stable category keys (soft serve,
  toppings from the pricing policy, cones/cups), ordered by Fiona's question definition.
  Missing/retired categories are omitted; restoration restores the configured question.
  Other active categories never become public questions automatically. Catalog categories
  and public question exposure are separate concerns.
- Offering choices project catalog-owned limits and active options from that single
  snapshot. HTTP reuses the runtime's `dto()`, `OfferingDto`, and `OfferingPriceDto`;
  never restate offering identity, price forms, or selection validation. Allowed durations
  come from `FionasPricingPolicy`; text limits come from Fiona's value-object constants.
- The response exposes `definitionVersion` (2 for the current code-owned definition) and
  `catalogId`/`catalogRevision`. Clients submit the latter revision as
  `pricingInputs.catalogRevision`; the existing exact-revision pricing remains authoritative.
  Change the definition version deliberately when code-owned questions/bindings change.
- `pricingPreview` is advisory data derived from that same snapshot and
  `FIONAS_PRICING_POLICY`, not a second pricing engine or expression DSL. It projects exact
  decimal amounts: base service and resolved public per-duration offering contributions
  for each allowed duration, per-guest rate/dimension, and topping included count/rate.
  Catalog fixed and per-quantity prices retain the runtime's representation. Local totals
  add base, guest charges, selected offering charges, and excess topping charges; the
  latter apply in addition to catalog prices. No price contributes zero. The minimum-guest
  flag labels the estimate and does not change arithmetic.
- Public-form compatibility checks and authoritative pricing share the policy's currency,
  guest-dimension, and exact-duration decisions. Preserve finite fractional multipliers
  (90 minutes at a one-hour price is 1.5 units). A public price incompatible with any
  advertised duration, insufficient public options for a minimum, or a required hidden
  category fails with diagnostic server detail and the runtime's generic `500`; never
  silently filter an invalid public offering. Optional hidden offerings need not satisfy
  public-form pricing constraints. Conditional availability is a separate slice.
- Browser amounts are advisory only. Preview, inquiry submission, and persisted documents
  remain authoritative through `FionasOfferingsEngine`. Request DTOs accept pricing inputs,
  never trusted totals or line items; the form introduces no alternate validation path.
- Each field's `submissionPointer` is a JSON Pointer into the existing request; offering
  inputs append `{category, offerings}` at `/pricingInputs/selections`.
  The service section remains optional, and field requirements apply when it is used.
  The form advertises only supported answers. Phone, structured dates/locations, event
  contacts, definition administration/history, and a reusable UI abstraction remain deferred.
- Wire inputs are sealed serializable DTOs in `http`, with a `type` discriminator.
  `KotlinxSchemas` derives explicit oneOf variants, discriminator mappings, required const
  tags, and string enum values from descriptors. Offering options nested inside Fiona DTOs
  still use `offeringsOpenApiRenderer`. Test the union and its runtime price references in
  `OpenApiDocumentSpec`, behavior through the complete handler in `InquiryFormRoutesSpec`,
  and policy/snapshot projection in `GetInquiryFormSpec`. Response-only local estimates
  must match authoritative previews, including captured historical catalog revisions.

## Generic commerce concepts

Do not duplicate or re-model anything `commerce-domain` defines:

- financial documents (`Estimate`, `Quote`, `Invoice`), versions, money, line items;
- offerings: `OfferingsSnapshot`, `OfferingCategory`, `Offering`, `OfferingPrice`, catalog
  ids and revisions, and the `OfferingsEngine` contract;
- payments, allocations, reversals, refunds, reconciliation;
- the payment-adapter contract;
- principals, roles, permissions;
- the booking lifecycle phase interfaces.

Use the upstream types. A Fiona booking implements the upstream lifecycle phase
interfaces directly; it does not reinvent phases. Fiona tables *reference* commerce facts
(an inquiry's financial-document lineage, a snapshot's pricing source, both by
`(document_id, version)`), but generic types never acquire Fiona fields: no `inquiryId`,
`bookingId`, `customerId`, pricing inputs, or other Fiona data on `FinancialDocument`,
`PaymentRecord`, or any other upstream type.

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
- `/health` and `/ready`;
- Offerings persistence (`OfferingsSnapshotRepository`, the `commerce.offerings_snapshots`,
  `commerce.offering_categories`, and `commerce.offerings` tables), the Offerings
  operations (`CreateOfferingsCatalog`, `AddOfferingCategory`, `AddOffering`,
  `GetOfferingsCatalog`, `GetOfferingsCatalogRevision`, update/retire/restore and retired
  identity discovery, expected-revision concurrency, lifetime key reservation, and the list
  and get reads), and
  the Offerings HTTP capability (`offeringsHttpCapability`, `OfferingsHttpBinding`,
  `OfferingsHttpAccess`, its contract routes and DTOs, and `offeringsOpenApiRenderer`).
- principal session lifecycle and persistence (`context.sessions`, `SessionManager`,
  `commerce.principal_sessions`, `SessionCookie`, `sessionAuthentication`), the
  `authenticatedPrincipal` request lens, and `AccessControl` permission enforcement.
- human users, service identities, principal status, roles, role permissions, role
  assignments, live permission resolution, and the permission catalog
  (`context.authorization`); the authorization administration HTTP capability.
- the financial ledger: `FinancialLedger` (`context.financialLedger`), the
  `FinancialDocumentRepository` and `PaymentRepository`, their tables
  (`commerce.financial_document_snapshots`, `commerce.financial_document_lines`,
  `commerce.payment_records`, `commerce.payment_allocations`, `commerce.refund_records`,
  `commerce.refund_allocations`), financial-document lifecycle
  orchestration (`create`, `changeOrder`, `issueQuote`, `issueInvoice`), payment recording
  and allocation, refund recording and explicit allocation unwinds, and derived reconciliation.

Never create another connection pool, another `Jdbi` instance, another transaction
manager, Spring transactions, a nested transaction abstraction, a second configuration
loader, or a second error/response framework. Tests may build their own infrastructure
only to *observe* the database from outside the runtime (see `TestDatabase`).

## Composition

`FionaApplication.kt` is the composition root. `fionaApplication()` returns the
`ApplicationContributions` (Fiona's migration schema and location, and route factory); the route
factory builds repositories and operations from the `CommerceRuntimeContext` with
ordinary Kotlin, hands the operations to the API as `FionaOperations`, binds the runtime's
Offerings capability to Fiona's catalog (`offeringsHttpCapability(context,
fionaOfferingsBinding(accessControl))`), mounts the runtime's authorization administration
capability at `/admin/access` with that same `AccessControl`, builds Fiona's financial
operations on `context.financialLedger` with Fiona's own association and pricing-source
repositories and one `FionasPricing` (the engine plus the runtime's transaction-bound
catalog read), and contributes exactly two route handlers: `fionaApi(operations, offerings, authorizationAdmin, version, auth)`
(the API contract) and `apiDocs()` (Swagger UI). `Main.kt` loads configuration, calls `commerceRuntime(...)` (which runs
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
  `inTransaction` call. Prefer caller-owned `Transaction` composition over nested
  `inTransaction` calls; pass the `Transaction` down instead.

The shared runtime transaction is the seam that lets one Fiona operation atomically write
Fiona-owned rows and runtime-owned commerce facts. Fiona's financial operations do: a
first-snapshot document writes the runtime's snapshot, Fiona's inquiry association, and
Fiona's pricing source in one transaction; a transition or change order writes the runtime's
successor and its pricing source; standalone receipt and allocation are separate ledger
facts, while the combined payment operation writes both in one transaction.

- **Ledger calls take the operation's `Transaction`.** Inside `inTransaction`, call only the
  `FinancialLedger` overloads that take a `Transaction`
  (`context.financialLedger.issueQuote(transaction, id)`), never the convenience overloads
  (`issueQuote(id)`), which open a second transaction that commits on its own.
- **Reads that decide a write happen in that transaction too**: the exact catalog revision
  (through `FionasPricing`, never `GetOfferingsCatalogRevision`, which opens its own), the
  latest snapshot, and the association.

Preserve the seam. `ArchitectureSpec`, `RuntimeTransactionSpec`, and
`FinancialDocumentAtomicitySpec` guard this rule; never loosen them to make a change pass.

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
listed in fionaApiRoutes (http/FionaApi.kt), composed by fionaApi(...) with the
contract routes of runtime capabilities Fiona binds (offerings.contractRoutes)
      ├──► the running API                              fionaApi(...)
      ├──► GET /openapi.json                            rendered by that same contract
      └──► build/openapi/fionas-commerce-openapi.json   generateOpenApi, same fionaApi(...)
```

There is no later step called "update the spec". The rules:

1. **OpenAPI is rendered from executable contract routes.** Never add or maintain an
   `openapi.json`, `openapi.yaml`, `swagger.*`, or any document describing routes
   separately, and never describe a route anywhere but on its own `ContractRoute`.
   `ArchitectureSpec` rejects such files.
2. **Every externally supported Fiona endpoint is a `ContractRoute` in `fionaApiRoutes`**,
   or a contract route of a runtime capability Fiona binds (the Offerings catalog), which
   joins the same contract unchanged.
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
   status and code from commerce-runtime and the body from its `ErrorResponse` (or
   `ValidationErrorResponse` for optional structured validation violations). Never
   define a Fiona error model.
5. **The served and the generated document are one rendering** of `fionaApi(...)`. The
   generator (`src/openapi`, `generateOpenApi`) calls the contract with `FionaOperations`
   that are never invoked, and the Offerings capability built with the same
   `fionaOfferingsBinding` from a rendering-only context; it must never need a database,
   Docker, a server, or the network. Adding an operation to `FionaOperations` forces the
   generator's stub to name it.
6. **Runtime infrastructure routes are not Fiona routes.** Never redeclare `/health` or
   `/ready` as contract routes to make them appear in the document: that would be a second
   implementation. They join the document only if commerce-runtime publishes metadata for
   them. Runtime *capability* routes are different: the Offerings capability publishes
   contract routes for the host to mount, so they are part of Fiona's API and document.
7. **kotlinx.serialization stays the wire format.** Never switch to Jackson, or add a
   second JSON representation, for documentation. The one sanctioned use of
   `http4k-format-jackson` is rendering the Offerings schemas through commerce-runtime's
   `offeringsOpenApiRenderer`, which only works on a reflective JSON (`OfferingsSchemas` in
   `http/OpenApi.kt`); `ArchitectureSpec` confines Jackson there. Fiona's own schemas are
   derived from the DTOs' serial descriptors (`KotlinxSchemas`: strings, `Int`s as `int32`
   integers, booleans, lists, enums, sealed input unions with string discriminators, and
   nested `@Serializable` objects as components; anything
   else fails loudly until a DTO needs it); what a type cannot say goes
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
  never loads assets from a CDN. No authentication options are configured in Swagger UI;
  browser staff sessions use Fiona's cookie.
- **http4k modules stay at commerce-runtime's http4k version** (`http4k` in
  `libs.versions.toml`), never a newer BOM; `ArchitectureSpec` fails when two http4k
  versions meet on the classpath.

## Application migrations

- **Commerce 0.0.17's V7 deliberately rejects existing financial snapshots.** PostgreSQL
  assigns each new snapshot's `created_at` with `clock_timestamp()`. Pre-V7 snapshots have
  no authoritative creation time. Existing databases are ephemeral: recreate an affected
  database/volume. Never backfill, manufacture timestamps, compensate in Fiona migrations,
  alter runtime migrations, or bypass Flyway validation. Fresh/empty databases migrate normally.

**Database migrations in this repository are application-owned migrations only.**
`commerce-runtime` owns migration orchestration and its own persistence migrations. Do not
instantiate an independent Flyway startup lifecycle, copy runtime migrations into this
repository, modify `commerce`-owned objects from Fiona's migrations, or coordinate
application migration version numbers with runtime migration versions. Application
migrations live in Fiona's migration location and schema, and are executed by the runtime
after runtime-owned migrations.

- **The runtime orchestrates; Fiona owns only the contents and the schema.** Fiona
  contributes `ApplicationMigrations(schema = FIONA_MIGRATION_SCHEMA, locations =
  listOf(FIONA_MIGRATION_LOCATION))` (`fionas` and `classpath:db/fionas`) through
  `ApplicationContributions.migrations`. The declared schema is created by the runtime's
  Flyway when missing, is the default schema of Fiona's stream, is the only schema that
  stream manages, and holds Fiona's `flyway_schema_history`. The runtime discovers its own
  migrations inside its jar; Fiona never lists, copies, or depends on their files or versions.
- **Runtime first.** `commerceRuntime(...)` applies the runtime's migrations, then Fiona's,
  before anything is composed or served. A Fiona migration may therefore depend on
  runtime-owned structures, never the reverse.
- **Independent version space.** Fiona's migrations are `V1__…`, `V2__…`, the next integer
  in Fiona's own history, unrelated to the runtime's numbering (both have a `V1`). If two
  branches add the same `V<n>`, the one merged second renumbers before merging.
- **Fiona's objects live in the `fionas` schema**, created by the runtime's Flyway before
  `V1` runs, so no Fiona migration says `CREATE SCHEMA fionas` (it would collide). SQL still
  names the schema explicitly (`fionas.customers`); never rely on `search_path` or on the
  default schema. `public` holds nothing of Fiona's, neither objects nor migration history:
  `commerce.flyway_schema_history` and `fionas.flyway_schema_history` are the two histories.
  Development databases created before commerce 0.0.15 keep Fiona's history in
  `public.flyway_schema_history`; the runtime does not reinterpret it, and Fiona adds no
  move, baseline, copy, `baselineOnMigrate`, or startup SQL for it. Recreate such databases.
- **Never create, alter, or drop anything in `commerce`**, and never add files under
  `db/commerce`. If Fiona needs a runtime-owned structure to change, stop and raise it as
  a runtime requirement (see [Commerce-runtime gap rule](#commerce-runtime-gap-rule)).
- **Reference runtime structures only when they are a published contract.** A foreign key
  to a runtime table is legitimate when commerce-runtime publishes that table for
  applications; never depend on incidental runtime tables, indexes, or Flyway metadata.
  The runtime publishes the Offerings snapshot tables
  (`commerce.offerings_snapshots`, `commerce.offering_categories`, `commerce.offerings`),
  but no Fiona migration references them, and Fiona reaches the catalog only through the
  runtime's Offerings operations and snapshot read. Fiona's `V2` references the published
  `commerce.users(principal_id)` for the credential foreign key, and `V3` references the
  published `commerce.financial_document_snapshots(document_id, version)`: the association
  names each lineage's first snapshot, and each pricing source its exact snapshot. `V4`
  (the inquiry list index and an inquiry's requested pricing inputs) references only
  Fiona's own tables.
  `ArchitectureSpec` confines runtime schema references to these purposes.
- **History is immutable.** Never edit a migration that has run outside a disposable
  database; correct it with a new migration. (One pre-release exception, before any
  deployment: the original `V20260926210000` migration was rewritten as `V1`, moving the
  tables from `public` to `fionas`, alongside commerce 0.0.6's own history reset.
  The unreleased staff `V2` was also rewritten for the commerce 0.0.11 integration
  because all development data and volumes are intentionally reset.)
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

## Offerings catalog

Fiona's catalog of what it sells is a commerce-runtime Offerings catalog. Fiona chooses the
catalog and where it is served; commerce-runtime implements everything else.

1. **The catalog id is Fiona's, stable, and checked in.** `FIONA_OFFERINGS_CATALOG_ID`
   (`offering/FionaOfferings.kt`, `0cde8e0b-aa9c-4129-9853-8db2cbbb909b`) identifies
   Fiona's primary Offerings catalog. It is never generated at startup, configured through
   the environment, or stored in a Fiona table. Every environment has its own database and
   the same logical id. Changing it orphans every recorded revision; `ArchitectureSpec`
   pins it.
2. **Fiona mounts the runtime capability; it never reimplements it.**
   `fionaOfferingsBinding(accessControl)` binds the catalog at `/offering-catalog` with the operationId
   prefix `fionasOfferings` (operationIds are API contract, rule 3 above),
   `ReadWrite(accessControl)` access, and the `Offerings catalog` OpenAPI tag. The composition root calls `offeringsHttpCapability` once, with no
   wrapper.
3. **Generic Offerings behavior stays upstream**: operations, DTOs, routes, validation,
   revision derivation, and persistence. Never add Fiona Offering DTOs, repositories,
   operations, or commands (update, delete, replace, retire) of Fiona's own.
4. **Fiona never writes SQL against `commerce.offering*`**, reading or writing, and never
   calls `OfferingsSnapshotRepository` itself: the composition root hands it to the
   runtime's own `GetOfferingsCatalogRevision` (previews) and `GetOfferingsCatalog`
   (inquiry form), and hands its transaction-bound
   `retrieveVersion` to `FionasPricing` (persisted documents, which must read the exact
   revision in the transaction that writes them).
5. **Fiona creates no Offerings tables or migrations.** The runtime's own migration stream
   creates its tables; Fiona knows neither their migration file names nor versions.
6. **The runtime's contract routes join Fiona's one contract** (`fionaApi`), so the
   aggregate OpenAPI document, `/docs`, and `generateOpenApi` include them with no second
   document, Swagger UI, or generation task.
7. **The Offerings schemas come from `offeringsOpenApiRenderer`.** Fiona's renderer hands
   every Offerings body to it (`OfferingsSchemas`), so `OfferingPriceDto` stays a
   `kind`-discriminated `oneOf`. A default renderer would silently degrade that contract;
   `OpenApiDocumentSpec` guards it. Never restate an Offerings schema.
8. **`ReadWrite(accessControl)` protects administration.** Create/add/update/retire/restore
   and dedicated retired identity discovery require the runtime's
   `CommercePermissions.OfferingsManage` (`commerce.offerings.manage`). Fiona supplies one
   `AccessControl` with cookie session authentication and live role resolution. Ordinary
   active and exact historical reads remain public.
9. **Pricing and selection policy is not the catalog.** A price is descriptive metadata.
   Fiona's rules (base fee, duration, guests, included toppings) are
   `FionasOfferingsEngine`'s; see [Fiona's pricing](#fionas-pricing).
10. **Catalog contents are administrative data.** Startup mutates nothing beyond
    migrations: it never creates or seeds the catalog. `POST /offering-catalog` initializes
    it (revision 1), and production contents are entered through the API after deployment.
    No seeding or import mechanism exists; adding one is a separate decision.
11. **A missing capability is a runtime requirement.** If Fiona needs Offerings behavior
    the runtime does not expose, apply the
    [commerce-runtime gap rule](#commerce-runtime-gap-rule); never copy generic code here.
12. **Management creates successors, never changes history.** Commerce-runtime 0.0.18 adds
    no migration. Catalog persistence/history is append-only and immutable: edit replaces
    properties in a successor revision; DELETE retires from the successor. Natural keys
    remain reserved for life and must be restored, never re-added as unrelated identities.
    Update and restore keep the path-owned key. Historical revisions remain valid pricing
    sources after edits and retirement.
13. **Clients own the observed revision.** Every mutation after creation, including add,
    requires integer `expectedRevision` (JSON for add/update/restore, query for DELETE).
    Stale requests receive `409 conflict` and must reload; Fiona never injects latest or
    retries them. Local bootstrap and sequential test fixtures thread the revision each
    successful response returns. Test helpers accept explicit revisions so stale behavior
    remains testable. Fiona adds no lifecycle DTOs, state, persistence, or mechanics.

## Staff authentication and authorization

Fiona verifies passwords and owns login, cookie choice, browser Origin policy, and
bootstrap policy. Commerce-runtime owns the human and service principal directory,
persisted roles and grants, live permission resolution, administration HTTP capability,
and sessions. Commerce-domain defines the principal and permission vocabulary.

```text
POST /auth/login → context.authorization.findUserByUsername → Fiona credential verification
                 → UserId → context.sessions.create(...)
                 → __Host-fionas_session cookie
later request    → sessionAuthentication(...) → authenticatedPrincipal
                 → AccessControl → context.authorization.permissionResolver → handler
```

- Fiona's unreleased `V2` was rewritten for disposable development data. It owns only
  `fionas.user_credentials`: `user_id` references `commerce.users(principal_id)`, plus
  an Argon2id hash and change timestamp. It owns no user, role, assignment, or session table.
- The runtime normalizes usernames and enforces uniqueness in its directory.
- Passwords are Argon2id hashes encoded by `argon2-jvm`. Raw passwords enter only the
  login/bootstrap credential path and are never logged or persisted. Wrong username,
  wrong password, and disabled status receive the same `401` response.
- Bootstrap is disabled when none of `FIONAS_BOOTSTRAP_ADMIN_USERNAME`,
  `FIONAS_BOOTSTRAP_ADMIN_PASSWORD`, and `FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME` is present.
  All three enable bootstrap; a partial set fails startup. Username and display name must
  be nonblank after trimming; the password is never trimmed and must be nonblank and at
  least 12 characters. First and last names are optional.
- The first administrator is provisioned only when complete bootstrap environment
  credentials are provided and no runtime users exist. Runtime role creation, runtime
  user creation, Fiona credential insertion, and role assignment share one transaction
  after both migration streams and permission validation. There is no default password;
  later startup never overwrites an existing credential.
- The Administrator role grants exactly OfferingsManage, FinancialDocumentRead,
  FinancialDocumentCreate, PaymentRecord, RefundRecord, PrincipalRead, PrincipalManage, RoleRead,
  RoleManage, RoleAssign, and Fiona's `fionas.credentials.manage` and `fionas.inquiries.read`. It has no wildcard or
  automatic future grants. Grants are fixed when bootstrap creates the role; startup never
  mutates an existing Administrator role, whose grants are managed through `/admin/access`:
  an Administrator created by an earlier release gains `fionas.inquiries.read` (or
  `commerce.refund.record`) only through the documented read-modify-replace grant.
- Generic commerce actions use commerce-domain's `CommercePermissions`
  (`FinancialDocumentRead`, `FinancialDocumentCreate`, `PaymentRecord`, `RefundRecord`); never define a Fiona
  duplicate. Only Fiona-specific actions get a Fiona permission.
- Fiona contributes `fionas.credentials.manage` and `fionas.inquiries.read` through
  `ApplicationContributions.permissionDefinitions`. The runtime permission catalog and
  live resolver remain the only authorization source.
- Fiona composes one `AccessControl` from cookie `sessionAuthentication(context.sessions,
  SessionCookie("__Host-fionas_session"))` and the permission resolver. The runtime's
  `OfferingsHttpAccess.ReadWrite(accessControl)` protects its mutations and retired discovery. The same
  control guards runtime administration at `/admin/access` and Fiona's
  `PUT /admin/users/{userId}/credentials/password`. The credential endpoint verifies the
  runtime user, stores a new hash, returns no secret material, and does not revoke existing
  sessions. Every financial-document, payment, and refund route requires its commerce permission
  through the same control. A document's payment histories are a child read of the document
  and require `FinancialDocumentRead`. `GET /payments/unapplied` uses `PaymentRecord` for
  its operational queue; `RefundRecord` alone grants neither read. There is no new
  payment-read permission. Listing and reading inquiries require
  `fionas.inquiries.read`. Inquiry submission, estimate previews, ordinary Offerings reads, health,
  and readiness remain public.
- `FIONAS_TRUSTED_ORIGINS` names exact permitted browser origins. Login and unsafe
  cookie-authenticated methods require a matching `Origin` and fail closed if none is
  configured. Fiona's CSRF policy is separate from the reusable runtime session filter.
- Future service credentials map to `ServiceId` and reuse this authorization path;
  service credential authentication is not part of this slice.
- `GET /auth/me` returns sorted effective `permissions` directly from
  `context.authorization.permissionResolver.permissionsFor`, alongside profile and roles.
  It requires only an active human session, never `commerce.role.read`. Grants and role
  assignments are resolved live; never derive them from role keys, hard-code an
  Administrator mapping, or store permissions in sessions.

## Fiona's pricing

`FionasOfferingsEngine` is the first Fiona-specific commerce policy: given one exact,
immutable catalog revision, a structurally valid selection, and a `FionasOfferingsContext`,
it produces the commerce `LineItem`s of Fiona's estimate. The split:

```text
commerce-domain     Offering vocabulary, OfferingsEngine and its structural validation,
                    LineItem, Money
commerce-runtime    catalog persistence, catalog operations (GetOfferingsCatalogRevision),
                    catalog HTTP and OpenAPI
fionas-commerce     FionasPricingInputs, FionasOfferingsContext, FionasPricingPolicy,
                    FionasOfferingsEngine, FionasPricing, PreviewEstimate and
                    POST /estimate-preview; persisted estimates and change orders
```

**The catalog says what is selectable and carries simple per-offering prices. Fiona's engine
owns the relationships between selections and the event context**: base-event pricing, the
service duration, per-guest pricing, and the first-four-toppings-included rule.

1. **The engine knows no offering by name.** Never write `if (offering == "waffle-cone")`
   or give a premium flavor a rule: its price is a catalog `PER_QUANTITY` `guest` price,
   entered through `POST /offering-catalog/offerings`, and priced with no deployment. The
   engine understands the event context, Fiona's base pricing, the topping category's
   aggregate rule, and the generic `OfferingPrice` forms; `ArchitectureSpec` rejects an
   `OfferingKey` built in its sources.
2. **Structural rules stay commerce-domain's.** Unknown categories or offerings, wrong
   categories, selection counts, and duplicates are `OfferingsEngine`'s checks, run before
   `evaluateValid`; never repeat them. Category cardinalities (how many flavors, at most six
   toppings, one cone option) are catalog data, never engine constants.
   `includedToppingCount` is different: how many selections the price covers, not how many
   are legal.
3. **Policy values live in one immutable `FionasPricingPolicy`** (`FIONAS_PRICING_POLICY`):
   one currency (USD), base fee, hourly rate, per-guest rate, included toppings, extra
   topping rate, offered durations, the topping category key (`topping`), and the per-guest
   quantity dimension (`guest`). It is not a pricing framework; never add a rules DSL, and
   never push Fiona's rules upstream.
4. **The engine is pure**: no persistence, HTTP, runtime, serialization, or clock, and its
   sources import only commerce-domain and the JDK (`ArchitectureSpec`). Line ids come from
   an injected `() -> UUID`, so tests are deterministic.
5. **Expected rejections are results, not exceptions.** Fiona's violations are
   `FionasOfferingsViolation`s with stable codes: `INVALID_GUEST_COUNT`,
   `UNSUPPORTED_DURATION`, `UNSUPPORTED_CURRENCY`, `UNSUPPORTED_QUANTITY_DIMENSION`,
   `INCOMPATIBLE_DURATION_PRICE`. Never rename a code; clients may match on them. A broken
   policy or snapshot invariant still fails loudly.
6. **Money is exact.** `BigDecimal` and `Money` only, never `Double` or `Float`; nothing is
   rounded (a duration price that does not divide the service duration exactly is
   rejected); no currency is converted or mixed; tax is zero until a tax slice decides
   otherwise. Totals are always derived from the lines.
7. **Lines have a stable order**: base service, ice cream service, priced selections in
   submitted order, extra toppings.
8. **The catalog revision is exact.** Pricing evaluates the revision the request names,
   loaded with `OfferingsSnapshotReference(FIONA_OFFERINGS_CATALOG_ID, revision)`, never the
   latest. A preview records nothing and performs no Fiona write, so it reads the snapshot
   through the runtime's `GetOfferingsCatalogRevision`, which opens its own transaction. A
   persisted estimate or change order runs the same engine through `FionasPricing` inside
   the one transaction that writes it.
9. **One input model, one pricing path.** `FionasPricingInputs` (catalog revision,
   `OfferingSelections`, `FionasOfferingsContext`) is what previews, persisted estimates,
   change orders, pricing-source history, and an inquiry's requested inputs share; `FionasPricing` turns it into lines and
   reports rejections identically everywhere. It is strongly typed Fiona data: never a
   metadata map or an opaque context.
   Rejections use runtime `offeringsValidationFailed(message, violations)`, preserving
   Fiona's explanations and stable structural/policy codes. HTTP `validation_failed` may
   carry optional `violations: [{code: ...}]`; ordinary validation can omit it. Clients
   match codes directly and never parse the diagnostic message. OpenAPI uses runtime
   `ValidationErrorResponse` and `ValidationViolationResponse`, never Fiona copies.
10. **A preview is not an estimate document.** `POST /estimate-preview` stays public and
    stateless and creates no `FinancialDocument`; persisted documents are a separate,
    staff-only API.

## Financial documents and payments

Financial documents (`Estimate`, `Quote`, `Invoice`) and payments are commerce-runtime's
financial ledger. A Fiona inquiry may begin a lineage at Estimate v1, Quote v1, or Invoice
v1. Starting at Quote or Invoice is a legitimate new lineage with no predecessor, not a
skipped-history transition. An Estimate may then follow the usual change-order, quote,
deposit, invoice, and final-payment workflow. The split:

```text
commerce-domain     FinancialDocument / ChangeOrder / LineItem / PaymentRecord /
                    PaymentAllocation / RefundRecord / RefundAllocation models and invariants
commerce-runtime    financial snapshot persistence, lifecycle orchestration, payment,
                    allocation and refund persistence, reconciliation, transaction-aware
                    FinancialLedger operations
fionas-commerce     inquiry → document relationship, Fiona pricing inputs and history,
                    server-authoritative starting-stage choice and pricing, change-order
                    intent, payment acceptance and allocation policy, HTTP and auth
```

1. **Never persist a ledger fact in Fiona.** No Fiona table holds a document, a line, an
   amount, a total, a stage, a payment, an allocation, a balance, or a payment status;
   `DatabaseSchemaSpec` and `ArchitectureSpec` reject them. Fiona reaches documents and
   payments only through `context.financialLedger`, never its repositories or tables.
2. **Fiona owns the relationship.** `fionas.inquiry_financial_documents`: one inquiry owns
   any number of lineages (alternative or restarted proposals), a lineage belongs to exactly
   one inquiry, revisions are versions inside a lineage. A lineage no inquiry owns does not
   exist in Fiona's API (`404`), even when the ledger holds it; every document route resolves
   the association first.
3. **Every snapshot records why it was priced so.** `fionas.financial_document_pricing` (and
   its ordered categories and selections) stores the `FionasPricingInputs` of each exact
   `(document_id, version)`, written in the same transaction as the snapshot. A change order
   records its revised inputs; a transition copies its source's inputs unchanged.
   `PricedSnapshot` retains runtime `FinancialDocumentVersion`, including its authoritative
   PostgreSQL-owned `createdAt`. Current reads use `latestVersion(transaction, id)`;
   history uses `versionHistory(transaction, id)`, within existing REPEATABLE READ boundaries.
   Document mutation responses read back the persisted version through `describeLocked`.
   Every `FinancialDocumentResponse` carries its RFC 3339 `createdAt`. Never use Fiona's
   clock, lineage-association timestamp, or inferred ordering to manufacture this fact.
4. **The server prices; the browser never supplies financial values.** First-snapshot
   Estimate, Quote, and Invoice documents and change orders accept commercial inputs only,
   never lines, prices, tax, or totals, and price them with `FionasPricing` from exactly
   the catalog revision the request names. The existing estimate endpoint delegates to the
   same creation choreography with starting stage Estimate.
5. **Transitions never reprice**; they go through `issueQuote` and `issueInvoice`, and the
   runtime reports a transition the stage does not have (`IllegalTransition`). There is no
   estimate-to-invoice shortcut.
6. **A change order replaces the line set.** It removes every current line and adds every
   repriced line in the engine's order (`repricing`); it never matches lines by
   description or position, and keeps the stage. Inputs that reproduce the current charges
   (ignoring line ids) are rejected as no financial change. A change to non-financial
   details is not a change order. A generic line-source identity is future work, decided
   from real need.
7. **Every document-lineage mutation names the version it acts on** (`expectedVersion`, an
   allocation's `documentVersion`); a lineage that has moved on is `Conflict`. Mutations lock the
   lineage's association row, and the runtime's `(document_id, previous_version)` uniqueness
   is the final guard.
   `fionas.inquiry_financial_documents` is the serialization point for mutations of one
   Fiona-owned financial lineage: `expectLatest` locks it before reading the latest version
   and writing. Multi-query financial reads (current, history, inquiry list) use
   PostgreSQL REPEATABLE READ and normal ownership lookups, so their document, pricing,
   and settlement queries share one point-in-time snapshot without blocking writers.
   The current view reconciles the exact snapshot it returns.
8. **Receipt and allocation are separate immutable facts.** Standalone `RecordPayment`
   records money received through `ledger.recordPayment` with an explicit currency and no
   destination; it may remain unapplied. `AllocatePayment` assigns part or all of an
   existing payment to an exact, currently latest Quote or Invoice snapshot through
   `ledger.allocatePayment`. Fiona locks the document lineage and checks its version and
   stage; the runtime locks payment history and enforces existence, currency agreement,
   and allocation limits. Separate allocations can apply one payment to several eligible
   documents. The combined `RecordDocumentPayment` operation remains atomic: it records
   and allocates the entire payment to one latest Quote or Invoice in one transaction.
   Amounts are exact decimals limited by currency minor units. External-reference
   uniqueness is the runtime's conflict policy. Allocations stay attached to their exact
   snapshot, while the latest lineage reconciliation counts them all.
9. **Refunds explicitly unwind allocations.** `POST /payments/{paymentId}/refunds` requires
   `commerce.refund.record`. The caller names each payment allocation and amount to unwind;
   an empty list refunds unapplied value. Fiona generates refund ids and timestamps and calls
   `ledger.recordRefund(transaction, ...)`, then `reconcilePayment(transaction, ...)` in one
   runtime transaction. Runtime validates the complete history. Refunded money is never
   reusable. No Fiona migration or refund table exists. Fresh Administrators receive the
   permission; existing roles retain their grants. To enable an existing Administrator,
   read its current permissions with `GET /admin/access/roles/commerce.administrator`, add
   `commerce.refund.record`, then submit the complete desired set with
   `PUT /admin/access/roles/commerce.administrator/permissions`. That endpoint replaces all
   grants; it does not add one permission. Allocation reversals remain unsupported by runtime
   persistence.
10. **Settlement is derived.** Only the latest view carries reconciliation (`grossAllocated`,
   `netApplied`, `balance`); history shows historical facts and pricing sources, never a
   reconciliation of an older snapshot. Unapplied amount is net received minus net
   allocated value after refunds and refund unwinds, never mutable state. Payment status is presentation, derived by
   clients. Allocation responses reconcile the exact reference they changed.
11. **Payment facts are read back from the ledger, document-first.**
    `GET /financial-documents/{documentId}/payments` (`ListFinancialDocumentPaymentHistories`)
    proves Fiona owns the lineage (`inquiryOf`) and calls
    `ledger.paymentHistoriesForLineage(transaction, documentId)` in one REPEATABLE READ
    transaction; a lineage only the ledger holds is `404`, never the runtime's `[]`. Each
    `PaymentHistory` is returned whole, in the runtime's order: allocations to other lineages
    stay, because its reconciliation depends on them, and discovery stays historical after
    refunds unwind every allocation here. Never filter a history to the requested document,
    re-sort it, recompute its reconciliation, or cache a mutation response in its place. The
    history DTOs are facts (an allocation carries no document settlement; a refund allocation
    names its `refundId` and `paymentAllocationId`), distinct from the mutation receipts.
    `GET /payments/unapplied` delegates directly to runtime `unappliedPayments()` through
    a narrow `FionaOperations` callback. Its runtime-owned REPEATABLE READ boundary returns
    complete histories with positive derived `unallocated`, ordered by receipt time then
    payment id, requiring `commerce.payment.record` and no inquiry association. Never
    filter/re-sort those results, calculate availability, or query commerce payment tables.
    No pagination, stored balance/status, general payment search, or single-payment resource.
    Allocation body `documentId` is parsed before domain validation: unreadable UUID text
    is a body `LensFailure` (`400 malformed_request`), while readable invalid values retain
    domain errors.
12. **One transaction per operation** (see [Transaction rule](#transaction-rule)).
13. **No `Booking` yet.** Inquiry → financial-document lineage → payments is the model until
    a slice decides when an inquiry becomes a booking.

## Kotlin conventions

- Do not write redundant explicit `public`. Write `data class Inquiry(...)` and
  `fun createInquiry(...)`, never `public data class ...` or `public fun ...`. Use
  explicit visibility only when it carries information: `private`, `internal`,
  `protected`.
- Prefer imports over fully qualified identifiers in declarations and code. Do not write
  `io.github.castab.commerce.runtime.persistence.Transaction` inline to avoid an import.
- Identifiers are distinct UUID-backed value classes (`CustomerId`, `InquiryId`), never
  raw `UUID`s passed around, and never one generic `EntityId`. A financial-document
  lineage keeps commerce-domain's own identity (`FinancialDocument.id`, a `UUID`) and
  versions are its `Version`; Fiona adds no wrapper around upstream identities.
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
| `...inquiry` | `Inquiry` and its values, `InquiryRepository`, `JdbiInquiryRepository`, the requested pricing inputs' `InquiryPricingRepository` and `JdbiInquiryPricingRepository`, the `CreateInquiry`, `GetInquiry`, and `ListInquiries` operations, and the customer form's `InquiryForm` values and `GetInquiryForm` adapter |
| `...offering` | `FionaOfferings.kt` (Fiona's catalog id and its binding to commerce-runtime's Offerings capability), Fiona's pricing (`FionasPricingInputs`, `FionasOfferingsContext` and its violations, `FionasPricingPolicy`, `FionasOfferingsEngine`, `FionasPricing`), and the `PreviewEstimate` operation with its `EstimatePreview` result |
| `...financial` | Fiona's context for the runtime's financial ledger: the inquiry association and pricing-source repositories, the read models, and the `CreateInquiryFinancialDocument`, `CreateInquiryEstimate`, `CreateChangeOrder`, `IssueQuote`, `IssueInvoice`, `RecordPayment`, `AllocatePayment`, `RecordDocumentPayment`, `RecordRefund`, `GetFinancialDocument`, `GetFinancialDocumentHistory`, `ListInquiryFinancialDocuments`, and `ListFinancialDocumentPaymentHistories` operations |
| `...staff` | Fiona's credential persistence, password verification, permission definition, and first-admin bootstrap |
| `...http` | The API contract (`FionaApi.kt`: `fionaApiRoutes`, `fionaApi`, `apiDocs`), its OpenAPI renderer and schemas (`OpenApi.kt`), browser origin policy, and feature contract routes and transport DTOs (`InquiryRoutes.kt`, `InquiryFormRoutes.kt`, `EstimatePreviewRoutes.kt`, `FinancialDocumentRoutes.kt`, `AuthRoutes.kt`) |
| `...openapi` (source set `src/openapi`) | The `generateOpenApi` entry point; not in the deployable jar |

Do not create empty packages or layers for future work. Avoid `service`, `manager`,
`handler`, `util`, `common`, `base`, or `framework` packages and classes unless they
acquire a concrete, well-defined responsibility.

## Premature abstraction rule

Do not create generic frameworks, `Repository<T, ID>`, `CrudRepository`,
`BaseRepository`, generic service layers, plugin systems, or extension APIs for possible
future requirements. Repositories are narrow and intention-revealing. Extract only after
real repetition or a real requirement appears.

Not in scope until a dedicated slice decides otherwise: allocation reversals,
Stripe or any payment provider or SDK, payment
webhooks, service credentials, OAuth/OIDC, self-service password resets, event publishing,
outbox, NATS, projections, CQRS, bookings and booking conversion, booking lifecycle
transitions, a generic line-source identity, stored balances or payment statuses, tax,
travel fees, minimum orders, inventory, availability, catalog seeding or import, deposit
requirements or schedules, customer merge or deduplication, inquiry search, filters, or
status, and event details (date, location, occasion). Do not add placeholders for
them.

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

### Known upstream gaps (last audited at commerce 0.0.14)

The application consumes commerce-runtime 0.0.18, with matching commerce-domain transitively.
The application history schema gap is closed by commerce 0.0.15 (applications declare their
own migration schema). The payment read gap is closed by commerce 0.0.16:
`FinancialLedger.paymentHistory` and `paymentHistoriesForLineage` (each with a
`Transaction` overload) return whole `PaymentHistory` values, which Fiona serves per document
(see [Financial documents and payments](#financial-documents-and-payments), rule 11). The
unapplied discovery gap is closed by 0.0.17 (`unappliedPayments`), as are persisted financial
version creation timestamps, structured validation errors, and invalid Offerings schema
formats. Commerce 0.0.18 supplies the managed Offerings lifecycle, retired discovery,
lifetime key reservation, and expected-revision concurrency without a new migration.
The remaining gaps below have not been re-audited.

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
- **Offerings routes need a composed runtime.** `offeringsHttpCapability` takes a
  `CommerceRuntimeContext`, whose constructor is `internal`, and only `commerceRuntime(...)`,
  which needs a database, creates one. Rendering the document offline needs only route
  metadata, so the generator (`src/openapi`, never the deployable jar) builds a
  rendering-only context reflectively, with a transactor that opens no connection and
  repositories that refuse every call. The authorization admin capability
  also needs this context; the rendering-only authorization directory and, since 0.0.12,
  the context's `FinancialLedger` (whose constructor is `internal` too) are assembled
  reflectively. This is provisional: commerce-runtime may eventually need a first-class
  contract/OpenAPI composition seam that does not require runtime persistence infrastructure.
  The runtime's current internal ledger constructor requires concrete PostgreSQL repositories;
  the renderer reflects those constructors, which open no connection, behind its refusing
  transactor. This remains confined to `src/openapi`.
- **`offeringsOpenApiRenderer` needs Jackson.** It builds schemas through http4k's
  reflective schema generator, which fails on `CommerceJson`
  (`Serializer for class 'JsonLiteral' is not found`), so Fiona renders the Offerings
  schemas with `http4k-format-jackson` and carries them into its kotlinx-rendered document
  (`OfferingsSchemas`). Upstream fix: a schema hook that works on `CommerceJson`, or
  descriptor-derived Offerings schemas.
- **Offerings route metadata is minimal.** Commerce lets Fiona supply the
  `Offerings catalog` tag through its binding, but routes still have no descriptions;
  their error examples all say `Request failed`, and no route documents `500`.
- **The ledger has no expected-version write.** `FinancialLedger.changeOrder`,
  `issueQuote`, `issueInvoice`, and `recordPaymentAgainstDocument` act on whatever is
  latest; only a competing successor is rejected. Fiona checks the caller's version itself
  and serializes its own mutations by locking its association row. Minimal upstream API:
  overloads taking the expected `FinancialDocumentReference` that fail with `Conflict` when
  it is not the latest.
- **Stage names are not published.** The runtime persists `ESTIMATE`/`QUOTE`/`INVOICE` but
  keeps the mapping private, so Fiona's HTTP layer maps the sealed stages to the same names
  itself. A public stage name (or enum) in commerce-domain would remove it.
- **Violations carry only a code.** `OfferingsViolation` has no message or details, so
  `FionasPricing` describes commerce-domain's structural violations itself, with a
  fallback for any it does not know. Upstream fix: a caller-safe description on each
  violation.
- **No line-sum helper for evaluations.** `FinancialDocument` derives its totals, but an
  `OfferingsEvaluation` has none, so a preview's totals are summed in Fiona
  (`EstimatePreview`). A shared helper (or evaluation totals) belongs in commerce-domain.

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
  invisible until commit), `FinancialDocumentAtomicitySpec` (a Fiona failure after a ledger
  write rolls back the runtime's snapshot, payment, and allocation with Fiona's rows),
  `FinancialDocumentRepositoriesSpec`, `RepricingSpec`, `FinancialDocumentReadConsistencySpec`
  (a paused REPEATABLE READ reader retains its snapshot while a concurrent writer commits),
  `FinancialDocumentRoutesSpec` (the
  whole inquiry → estimate → quote → deposit → invoice → change order → payment workflow
  through the complete handler, with its conflicts, transitions, payment policy, and
  permissions), `FinancialDocumentPaymentsSpec` (payment, allocation, refund, and
  refund-allocation ids rediscovered after their responses are gone; Fiona ownership over the
  runtime's lineage; whole split-payment histories; historical discovery; runtime ordering;
  `FinancialDocumentRead` only), `InquiryRoutesSpec` (the public receipt never reveals a stored customer;
  inquiry list and detail require `fionas.inquiries.read`; keyset pages with timestamp ties;
  requested pricing inputs pinned, rejected as a preview rejects them, and handed to an
  estimate unchanged), HTTP tests through the complete handler, schema tests,
  `MigrationLifecycleSpec` (consumer-level migration contract only; the runtime's suite owns
  the lifecycle internals), `OfferingsCatalogSpec` (Fiona's catalog through the complete
  handler: initialization, revisions, historical reads, price forms, offering and category
  lifecycle smoke tests, stale-client rejection, and historical pricing after retirement;
  retired-discovery authorization uses the existing live-permission spec; integration only, the
  runtime's suite owns the capability), `FionasOfferingsEngineSpec` (Fiona's pricing,
  purely, with exact `BigDecimal` amounts), `EstimatePreviewRoutesSpec` (the preview through
  the complete handler over a catalog built with the Offerings API, including revision
  pinning), `OpenApiDocumentSpec` (the document's paths, operationIds,
  statuses, and schemas), `OpenApiRoutesSpec` (`/openapi.json` and `/docs` through the
  complete handler, and parity with the generator), `GenerateOpenApiSpec` (the build
  artifact, byte-deterministic), and `ArchitectureSpec`.
- Run `./gradlew ktlintCheck test build` before considering work complete.

## Documentation synchronization

Changes to endpoints, the API contract, configuration, migrations, packages, the
customer-matching policy, the Offerings binding, the financial-document or payment policy,
the bootstrap grants, the version convention, or the upstream version must update
`README.md` and this file in the same change.
