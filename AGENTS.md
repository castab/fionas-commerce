# AGENTS.md

The architectural contract for contributors and coding agents working in this repository.
Read it before changing anything under `src/main`, before adding a dependency, and before
introducing a new concept. [`README.md`](README.md) explains how to build, run, and
configure the application; this file explains the rules and why seemingly reasonable
changes can be architecturally wrong.

## Architectural authority

[`ARCHITECTURE.md`](ARCHITECTURE.md) is the repository-wide high-level architectural
constitution for Fiona's application layer. This `AGENTS.md` is the detailed contributor
and implementation contract. Use them together: `ARCHITECTURE.md` defines Fiona's
responsibilities, its relationship to the shared commerce platform, and the principles for
deciding whether a concept belongs here or upstream; this file defines the concrete
repository invariants, contracts, workflows, and implementation rules that make those
principles specific.

`ARCHITECTURE.md` records durable architectural decisions; `AGENTS.md` records the current
concrete implementation contract. Do not duplicate implementation details across both.
If changing an endpoint, class, table, migration, permission, error mapping or locking
mechanism would make a statement stale, it normally belongs only in `AGENTS.md`. When
both documents need updating, state the architectural principle once in `ARCHITECTURE.md`
and keep the concrete mechanics here, cross-linking instead of copying prose.

Read `ARCHITECTURE.md` before making a change that affects any of the following:

- ownership between `fionas-commerce`, `commerce-runtime`, and `commerce-domain`;
- a new Fiona business concept, relationship, workflow state, or reusable abstraction;
- persistence structure, aggregate boundaries, duplicated/derived state, or schema ownership;
- inquiry, pricing, financial-document, payment, refund, allocation, deposit, offering, or
  reconciliation semantics;
- authentication, authorization, principals, roles, permissions, sessions, service identities,
  or credential ownership;
- transaction boundaries, isolation, locking, consistency, or cross-schema atomicity;
- reusable runtime capabilities, Fiona-specific HTTP behavior, API exposure boundaries, or
  OpenAPI contracts.

Treat `ARCHITECTURE.md` as an architectural constraint, not optional background reading.
The more specific rules in this file continue to apply simultaneously.

If a requested change appears to conflict with `ARCHITECTURE.md`, if these documents appear
to disagree, or if ownership between Fiona and the shared commerce platform is unclear,
surface the conflict explicitly before implementation. Do not silently resolve architectural
ambiguity by choosing the smallest local implementation.

In particular, if the needed capability is reusable commerce persistence, orchestration,
domain semantics, or another shared runtime concern, follow the
[Commerce-runtime gap rule](#commerce-runtime-gap-rule): identify the missing upstream seam,
add and release it upstream when appropriate, then consume the released capability here.
Never introduce a Fiona-local substitute merely to keep the work inside this repository.

Small implementation changes that preserve existing architectural boundaries do not require
rereading `ARCHITECTURE.md`.

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
      │                  principals, sessions, service tokens, the financial ledger (documents,
      │                  deposit requirements, payments, allocations, refunds, reconciliation)
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
- inquiries, the descriptive requested service a customer configured with one (see
  [Inquiries and the staff inbox](#inquiries-and-the-staff-inbox)), and the staff inquiry
  list;
- password credentials, verification, login, browser Origin policy, and first-admin
  bootstrap policy; commerce-runtime owns staff users and authorization;
- the trusted priced-line boundary: which principal may author financial lines, how a
  staff line proposal resolves against a reviewed snapshot, and who authored each exact
  snapshot's lines (see [Trusted priced lines](#trusted-priced-lines));
- which inquiry owns each commerce-runtime `FinancialDocument` lineage and its semantic purpose,
  change-order resolution, and payment acceptance policy (see
  [Financial documents and payments](#financial-documents-and-payments));
- the immutable, money-free service plan a staff user approved with a canonical Quote (see
  [Quote builder: staff-committed lines](#quote-builder-staff-committed-lines));
- inquiry served/closed operational facts with authenticated principal provenance;
- inquiry communication activity (inbound email, staff-sent email, acknowledgement) and dashboard attention policy;
- contacts (future);
- event and service details, and service locations (future);
- further relationships between these records and generic commerce facts (future), for
  example which lineage a booking produced.

Fiona owns **no product catalog and no pricing policy**. The customer-facing catalog, its
prices, and how a configured request becomes lines belong to `fionas-web`'s server-only
modules; negotiated amounts belong to verified staff. Never reintroduce a catalog, an
offerings binding, a pricing engine, an estimate preview, or price validation here.

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

- **Every Fiona inquiry arrives already priced.** `POST /inquiries` carries customer and event
  facts, a descriptive `requestedService`, and the exact `lines` the web pricing authority
  computed. Every accepted inquiry atomically materializes Estimate v1 with exactly those lines.
  There is one inquiry model and one successful creation path: no contact-only inquiry, no
  optional lines, no successful inquiry without its Estimate. `CreateInquiryRequest.requestedService`,
  `CreateInquiryRequest.lines`, `CreateInquiry.Command.requestedService`/`lines`,
  `InquiryDetails.requestedService` and `InquiryResponse.requestedService` are non-null; an
  omitted or `null` request value is a `LensFailure` (`400 malformed_request`) that writes
  nothing.
- **Submission is the web pricing authority's, as a SERVICE.** The route applies
  `access.requirePermission(FionaPermissions.InquiriesCreate).then(requireService)`: a USER
  session is `403` even when it holds the permission, so staff cannot inject customer-priced
  lines. The SERVICE becomes the lines' author (`CreateInquiry.Command.submittedBy`).
  `GET /inquiries` and `GET /inquiries/{inquiryId}` require `fionas.inquiries.read` from any
  principal. There is no public confirmation lookup.
- **The inbox is a primitive, not a query API.** `ListInquiries` returns the newest
  inquiries first, ordered by `created_at DESC, id DESC`, `limit` 1–100 (default 25) per
  page, continuing strictly after the previous page's last `(created_at, id)` (keyset, never
  offsets). The HTTP cursor is that position, base64url-encoded, and is opaque to clients.
  The `inquiries_created_at_id_idx` index serves every page. Never add search,
  filters, inquiry status, offsets, or a generic pagination framework here without a
  dedicated slice.
- **The requested service is descriptive only.** `RequestedService` (guest count and whether it
  is a minimum, optional duration, ordered items with label/group/key, optional
  `pricingReference`) is pinned customer intent in the inquiry row's `NOT NULL`
  `requested_service` jsonb. Fiona never prices from it, validates lines against it, or
  derives anything from its keys; only `guestCountIsMinimum` labels dashboard totals `FROM`.
  Lines that disagree with it are recorded exactly as the authority priced them.
- **Lines are validated, never priced.** `CreateInquiry.Command` runs `requireDocumentLines`
  and `requireNonnegativeTotal` (see [Trusted priced lines](#trusted-priced-lines)) before any
  business write; failures are `422 validation_failed`. Customer, inquiry, requested service,
  ledger snapshot/lines, `INITIAL_ESTIMATE` association and line authorship commit or roll back
  together through the transaction-taking `MaterializeInquiryFinancialDocument` core, shared
  with staff creation. All rejections commit nothing, including the key claim.
- **Public submission identity is explicit and durable.** Only `POST /inquiries` requires
  exactly one `Idempotency-Key`: 1–128 ASCII letters/digits/underscore/hyphen, case-sensitive,
  untrimmed, opaque and non-secret (UUIDs work). Invalid/missing/repeated keys are a
  `LensFailure` (`400 malformed_request`), after authorization and before the command.
  `CreateInquiry.Command` carries `InquirySubmissionKey`; the transport header never leaks
  into repositories as an HTTP concept. Other routes require no key.
- **Idempotency precedes business writes.** Canonical command fingerprint → claim/inspect
  key → replay or mismatch → only for new claims, customer/inquiry/Estimate materialization.
  A successful replay returns the stored Inquiry, hence the exact original 201
  receipt/Location, with no ledger write. A changed intent conflicts with
  `409 IDEMPOTENCY_KEY_REUSED` using runtime `ErrorResponse` and no-store; no previous
  request, fingerprint or submission state is exposed. Authentication and request-shape
  failures persist nothing.
- **The database serializes command identity.** `fionas.inquiry_submissions` is owned by
  Fiona. `JdbiInquirySubmissionRepository.claim` uses unique-key `INSERT ... ON CONFLICT DO NOTHING`
  in the existing READ COMMITTED operation transaction. A contender waits for the owner's
  commit/rollback; a separate statement then sees its committed result, or the contender
  becomes claimant after rollback. Never catch a unique SQL exception and continue an
  aborted transaction. The claim reserves a non-null, unique inquiry id with a deferred
  FK to `fionas.inquiries`, permitting early claim but preventing incomplete commits.
  Any failure rolls back the claim too; corrected retries may use that key. No independent
  finalization transaction, Redis, JVM mutex or TTL.
- **Fingerprint semantic intent, never raw bytes.** `InquiryRequestFingerprint` (encoding v2)
  hashes length-prefixed UTF-16 code units and binary canonical values with SHA-256: all
  customer/event values, the complete requested service with items in order, and every line's
  description, sub-description, quantity, unit price, tax and currency **in submitted order**.
  Decimals are canonicalized numerically (`4.5` equals `4.50`). Exclude the command key, the
  submitting service, generated identities, clocks and derived totals. Encoding changes
  require durable replay compatibility decisions; `InquiryRequestFingerprintSpec` pins a v2
  hash. One key plus one semantic request yields one committed result; never claim
  application code literally executes once. Different keys mean distinct commands, even for
  the same email/body. Never deduplicate by email or body similarity.
- **`fionas-web` responsibility.** Keep one non-secret token per logical visible submission,
  shared across duplicate browser requests and all backend retries/timeouts, together with
  the same priced lines. A UUID generated independently inside each backend attempt defeats
  idempotency. `IDEMPOTENCY_KEY_REUSED` means changed intent under a used key.
- **The event ZIP code is required inquiry-owned location data.** Non-null `zipCode` is
  trimmed five-digit US text (leading zeroes preserved), held in the non-null
  `fionas.inquiries.zip_code` column and read only by staff with the inquiry. Blank is
  invalid. It is never customer data. It supports staff travel review; no operating-area rule
  or automatic travel surcharge exists.
- **Event date and type are required inquiry facts.** `eventDate` is an actual calendar date
  (YYYY-MM-DD, years 0001–9999) with no time or time zone; `eventType` is one of BIRTHDAY,
  WEDDING, CORPORATE, SCHOOL_EVENT, NEIGHBORHOOD_EVENT, OTHER. Both are non-null Kotlin
  values and columns on `fionas.inquiries` (`date` and checked `text`), never customer data.
  No availability, future-date restriction, booking, or event-type pricing rule exists.
  Street address and further details remain customer-authored `message` text.

## Generic commerce concepts

Do not duplicate or re-model anything `commerce-domain` defines:

- financial documents (`Estimate`, `Quote`, `Invoice`), versions, money, line items, change
  orders and their application;
- deposit requirements and terms; payments, allocations, reversals, refunds, reconciliation;
- the payment-adapter contract;
- principals, roles, permissions;
- the booking lifecycle phase interfaces.

Use the upstream types: a priced line becomes commerce-domain's `LineItem` with `Money`, and a
staff edit becomes its `ChangeOrder`. Fiona's inquiry lifecycle is a projection of its
canonical financial lineage and operational facts, not a separate Booking aggregate. Fiona
tables *reference* commerce facts (an inquiry's financial-document lineage, a snapshot's line
authorship, a proposal's Quote, all by `(document_id, version)`), but generic types never
acquire Fiona fields: no `inquiryId`, `bookingId`, `customerId`, requested service, author, or
other Fiona data on `FinancialDocument`, `PaymentRecord`, or any other upstream type.

`Inquiry` and `Customer` are Fiona concepts. Do not generalize them, and do not propose
them upstream as generic types; upstream deliberately removed customers in `0.0.5`.

## Runtime-owned infrastructure

`commerce-runtime` owns, and this application must not replace, fork, or duplicate:

- `Transactor` and `Transaction`;
- the JDBI root and the HikariCP connection pool;
- migration orchestration (`MigrationLifecycle`, run by `commerceRuntime(...)`), the
  runtime's own migrations (a single `V1__commerce_baseline` since 0.0.23), and the
  `commerce` schema;
- the error model (`CommerceFailure`, `validating`, `CommerceErrorHandling`,
  `ErrorResponse`);
- http4k/Jetty composition (`commerceRuntime(...)`), `CommerceJson`, `jsonBody`;
- configuration loading (`CommerceRuntimeConfiguration.load()`);
- `/health` and `/ready`;
- principal session lifecycle and persistence (`context.sessions`, `SessionManager`,
  `commerce.principal_sessions`, `SessionCookie`, `sessionAuthentication`), the
  `authenticatedPrincipal` request lens, and `AccessControl` permission enforcement.
- human users, service identities, principal status, roles, role permissions, role
  assignments, live permission resolution, and the permission catalog
  (`context.authorization`); the authorization administration HTTP capability (which also
  serves the permission catalog), the current-principal HTTP capability, and service
  credentials, access tokens and the token endpoint capability.
- the financial ledger: `FinancialLedger` (`context.financialLedger`), the
  `FinancialDocumentRepository` and `PaymentRepository`, their tables
  (`commerce.financial_document_snapshots`, whose rows hold their lines,
  `commerce.payment_records`, `commerce.payment_allocations`, `commerce.refund_records`,
  `commerce.refund_allocations`), financial-document lifecycle orchestration (`create`, and
  the expected-version `changeOrder`, `issueQuote`, `issueInvoice`), payment recording
  and allocation, refund recording and explicit allocation unwinds, deposit requirements and
  revision history, coherent bulk financial lineage reads/activity, and derived
  reconciliation/satisfaction.

Commerce 0.0.23 has no Offerings catalog capability, and Fiona must not recreate one.

Never create another connection pool, another `Jdbi` instance, another transaction
manager, Spring transactions, a nested transaction abstraction, a second configuration
loader, or a second error/response framework. Tests may build their own infrastructure
only to *observe* the database from outside the runtime (see `TestDatabase`).

## Composition

`FionaApplication.kt` is the composition root. `fionaApplication()` returns the
`ApplicationContributions` (Fiona's migration schema and location, permission definitions,
and route factory); the route factory builds repositories and operations from the
`CommerceRuntimeContext` with ordinary Kotlin, hands the operations to the API as
`FionaOperations`, mounts the runtime's authorization administration capability at
`/admin/access` and the runtime's current-principal capability at `/authorization/me`
(`currentPrincipalHttpCapability(accessControl, "/authorization/me", setOf(authorizationTag))`)
with the same `AccessControl`, mounts the runtime's service token endpoint
(`fionaServiceAuthentication(context)`, `serviceAuthenticationHttpCapability` at
`/auth/service/token`), builds Fiona's financial operations on `context.financialLedger` with
Fiona's own association, authorship, proposal and service-plan repositories, and contributes
exactly two route handlers:
`fionaApi(operations, authorizationAdmin, currentPrincipal, serviceAuthentication, version, auth)`
(the API contract) and `apiDocs()` (Swagger UI). `Main.kt` loads configuration, calls
`commerceRuntime(...)` (which runs the migration phase before composing anything), starts it,
installs the shutdown hook, and blocks. Keep `main()` thin: no schema, Flyway, or migration
decisions belong in it. There is no DI framework, no annotation scanning, no service locator.
Keep wiring visible.

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
Fiona-owned rows and runtime-owned commerce facts. A priced inquiry claims its submission key
and writes its customer, inquiry, requested service, runtime Estimate, canonical association
and line authorship in one transaction. Staff creation and change orders write the ledger
snapshot and its authorship together; transitions copy authorship. Standalone receipt and
allocation are separate ledger facts, while the combined payment operation writes both in one
transaction.

- **Ledger calls take the operation's `Transaction` and the reviewed version.** Inside
  `inTransaction`, call only the `FinancialLedger` overloads that take a `Transaction`, and
  pass every mutation the version its caller reviewed
  (`context.financialLedger.changeOrder(transaction, id, changes, expectedVersion)`,
  `issueQuote(transaction, id, expectedVersion)`, `issueInvoice(transaction, id, expectedVersion)`).
  Never read the latest version just to manufacture an expected-version token; the token is
  what the caller saw, checked by Fiona under the association lock and again by the runtime.
  Test-only helpers that do read the latest live in `testing/LedgerFixtures.kt`.
- **Reads that decide a write happen in that transaction too**: the latest financial
  snapshot, the association, proposal history and fulfillment.

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
  operations or repositories. The only other `@Serializable` types are the private
  persistence DTOs of [Persisted JSON](#persisted-json), never wire DTOs.
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
contract routes of the runtime capabilities Fiona mounts (administration, current
principal, service token)
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
   or a contract route of a runtime capability Fiona mounts (authorization administration,
   current principal, service token), which joins the same contract unchanged.
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
   that are never invoked, and the runtime capabilities built from a rendering-only
   context; it must never need a database,
   Docker, a server, or the network. Adding an operation to `FionaOperations` forces the
   generator's stub to name it.
6. **Runtime infrastructure routes are not Fiona routes.** Never redeclare `/health` or
   `/ready` as contract routes to make them appear in the document: that would be a second
   implementation. They join the document only if commerce-runtime publishes metadata for
   them. Runtime *capability* routes are different: the administration, current-principal
   and service-token capabilities publish contract routes for the host to mount, so they
   are part of Fiona's API and document.
7. **kotlinx.serialization stays the wire format.** Never switch to Jackson, or add a
   second JSON representation, for documentation; Jackson is not a dependency
   (`ArchitectureSpec`). Fiona's schemas are derived from the DTOs' serial descriptors
   (`KotlinxSchemas`: strings, `Int`s as `int32` and `Long`s as `int64` integers, booleans,
   lists, enums, sealed unions with string discriminators, and nested `@Serializable`
   objects as components; anything else fails loudly until a DTO needs it); what a type
   cannot say goes in `@ApiProperty` on the transport DTO property, referencing the domain's
   constants (for example `maxLength = CustomerName.MAX_LENGTH`, or the `SIGNED_DECIMAL`
   pattern on every amount). `@ApiProperty` is for `@Serializable` DTOs in `http` only,
   never for application or domain types.
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
  `gradle.properties` until a release sets `-Pversion`. Releases are tag-driven (see
  [Releases](#releases)); `gradle.properties` is never edited for one.
- **Swagger UI is self-contained**: its WebJar is packaged in the fat jar, and `/docs`
  never loads assets from a CDN. No authentication options are configured in Swagger UI;
  browser staff sessions use Fiona's cookie.
- **http4k modules stay at commerce-runtime's http4k version** (`http4k` in
  `libs.versions.toml`), never a newer BOM; `ArchitectureSpec` fails when two http4k
  versions meet on the classpath.

## Service authentication, priced submission, staff terms, and login limiting

- **No static API key.** The former `UiApiKey`/`FIONAS_UI_API_KEY` mechanism is removed and
  must not return as a second credential. Software callers authenticate as SERVICE
  principals through commerce-runtime 0.0.23's service authentication; Fiona composes it and
  never recreates token issuing, verification, credential storage, or hashing.
- **One `AccessControl`, two runtime mechanisms, session first.** The composition root builds
  `authentication(SessionAuthenticator(context.sessions, cookie),
  ServiceAccessTokenAuthenticator(context.serviceAccessTokens))` behind `BrowserOrigin` and binds it
  with `AccessControl(..., context.authorization)`. A request is exactly one principal, USER or
  SERVICE; a valid session wins over a token on the same request, and identities are never
  merged. Authorization is the principal's current role grants, resolved live; tokens carry no
  permissions. Never assume a principal is human.
- **Permissions describe capabilities, never callers**: no `fionas.ui`, `fionas.frontend`, or
  `fionas.trusted`, no route allow-lists for services, no union of a session's and a token's
  grants, no role-name checks. Most routes accept whichever principal holds the permission.
- **Exactly two classes of routes also require a principal kind, explicitly and after the
  permission:**
  - **Priced inquiry submission is SERVICE-only.** `POST /inquiries` applies
    `access.requirePermission(FionaPermissions.InquiriesCreate).then(requireService)`. The
    web server is the customer pricing authority; a USER session, even an Administrator,
    is `403 forbidden`, so staff cannot inject customer-priced lines.
  - **Staff-authored amounts are USER-only.** Staff document creation, change orders, Quote
    preview, proposal issuance and both revisions, and standalone deposit approval and
    withdrawal (PUT/DELETE `/financial-documents/{documentId}/deposit-requirement`) apply the
    commerce permission(s), then
    `requirePermission(FionaPermissions.FinancialTermsManage)`, then `requireStaffUser`
    (`AccessControl.staffTerms(...)` in `FinancialDocumentRoutes.kt`,
    `AccessControl.staffComposition()` in `QuoteBuilderRoutes.kt`). A SERVICE holding every
    permission is `403 forbidden`: a SERVICE is never recorded as a staff
    approver or author. Handlers obtain the author with `staffUser(request)` /
    `servicePrincipal(request)`, never from the body. There is no on-behalf-of header,
    actor delegation or impersonation; a BFF proxying staff requests forwards the staff
    member's own session cookie with a trusted `Origin`.
  `ArchitectureSpec` pins both guards. `GET /auth/me` additionally requires a USER (`as? UserId`).
- **OpenAPI states the transports, separately from enforcement.** `http/OpenApi.kt` declares the
  `staffSession` scheme (API key in the `__Host-fionas_session` cookie) beside the runtime's
  `serviceAccessToken` bearer scheme, combined by Fiona's documentation-only `DocumentedSecurity`
  (one requirement object per alternative: OR). Never use http4k's `OrSecurity` for this: its
  filter re-runs the route per alternative and replaces a final `401` with a bodiless one.
  Routes open to both principals state `principalAuthentication()` or
  `principalAccess(permission, …)` (`http/AuthRoutes.kt`); `createInquiry` states
  `serviceTokenSecurity` alone and every staff-terms route `staffSessionSecurity` alone. The
  helpers never enforce anything. Public routes (login, the token endpoint) declare no
  security; logout declares `staffSession` OR anonymous (`{}`), never `serviceAccessToken`.
  The cookie name is one constant, `STAFF_SESSION_COOKIE`.
- **Logout is session-only.** `POST /auth/logout` applies `BrowserOrigin`, then the runtime's
  `sessionAuthentication(sessions, cookie)`: a valid session is revoked and the cookie cleared
  (`204`); a cookie whose session the runtime no longer accepts is still cleared (`204`);
  without a session cookie, a request that `AccessControl` authenticates (a SERVICE token) is
  `403`, never a pretend revocation; with neither, `204`. Logout never revokes a service access
  token. Never parse cookies beyond `SessionCookie`.
- **The token endpoint is the runtime's.** `fionaServiceAuthentication(context)` mounts
  `serviceAuthenticationHttpCapability` at `/auth/service/token` in Fiona's one contract. It is
  public and keeps the runtime's uniform `401`. Fiona builds no token endpoint and no limiter
  for it; deployments protect it with edge/reverse-proxy rate limiting and/or private
  reachability, because each valid-shaped attempt costs one Argon2id verification.
- **Configuration.** `application.conf` declares `serviceTokens { signingKey = "", issuer = "" }`,
  so `SERVICE_TOKENS_SIGNING_KEY` and `SERVICE_TOKENS_ISSUER` are required and startup fails
  without them. The signing key belongs only to fionas-commerce and is never a service
  credential or given to a frontend; the issuer is distinct per deployment. Never log a
  credential secret, verifier, access token, or signing key.
- **Provisioning is administration, never startup.** Startup never creates `fionas-web`, a
  role for it, or a credential. An administrator creates the service, a `fionas.web` role
  granting exactly `fionas.inquiries.create`, assigns it, and issues a credential through
  `/admin/access`; rotation is create B, deploy B, verify, revoke A.
- Anonymous `POST /auth/login` uses one synchronized in-memory limiter per process,
  capacity five, one token per five minutes, keyed by connection source IP. All attempts
  count before origin/body/password checks, including success; success never resets it.
  Monotonic time drives refill. Do not trust forwarded headers without an explicit proxy
  policy; a proxy currently shares its connection-IP bucket. Missing source shares a bucket.
- Retain at most 10,000 identities; prune fully replenished idle entries and use a depleted
  shared overflow bucket for new IPs at the bound, never evict depleted identities.
  Restart resets buckets; replicas do not share them. Exhaustion returns 429, Retry-After
  seconds rounded up, no-store, and runtime `ErrorResponse("rate_limited", "Too many requests")`.
  The runtime has no rate-limit ErrorCategory; reuse its envelope and document the local
  status/code on the login ContractRoute. No new error framework or upstream subsystem.

## Releases

CI verifies source; a release promotes source that is already verified. `ci.yml` runs on
`pull_request` to `main` only: no run for a feature-branch push by itself, one run per pull request
update, and none after the merge into `main`. `main` is expected to be protected (changes arrive
through pull requests whose required CI check passed); the workflows cannot enforce this and must not
try to configure it. Never re-add a `push` trigger to `ci.yml`, and never give a pull request
workflow Docker Hub credentials.
Pull request CI provisions Java 25 and Node.js 24 for the actual payment script smoke test
against throwaway PostgreSQL, without npm dependencies or caching. Release
artifact production does not run these tests or require Node.js.

A release is a pushed Git tag `vMAJOR.MINOR.PATCH` (no prerelease or build suffix), handled by
`.github/workflows/release.yml`, separate from `ci.yml`. The application is released as a Docker
image and an OpenAPI document; it is never published as a Maven artifact or package, and the fat jar
is not a release asset.

- **The tag is the only version source.** The workflow derives `VERSION` from the tag once and
  uses it for `-Pversion` (the jar's `fionas-commerce.properties`, `fionaVersion()`, and the OpenAPI
  `info.version` through the existing `generateOpenApi`), the Dockerfile's `APP_VERSION` argument,
  the image tag and labels, and the release assets. Never infer it from `gradle.properties`, a
  branch, a commit, the date, or Docker metadata, and never add a second version source.
- **Promote, never re-verify.** The release runs no `ktlintCheck`, `test`, or verification build,
  and never invokes `ci.yml`. It proves only that the tagged commit is reachable from `origin/main`
  (full-history checkout, `git merge-base --is-ancestor`), that the freshly generated OpenAPI
  document has `info.version == VERSION`, and that the built image's labels and jar report `VERSION`
  and hold no package credentials, all before Docker Hub login. Nothing is pushed before that.
  Compiling to produce the OpenAPI document and the image is artifact production, not verification.
- **One OpenAPI packaging script, dry-run in CI.** `scripts/package-openapi.sh <version> <dir>`
  (generate with `-Pversion`, assert `info.version`, write the versioned file and `.sha256`) is
  the only place that logic lives. `release.yml` runs it with the tag's version; `ci.yml` runs it
  with a throwaway version and publishes nothing, so release-path generation failures appear on
  the pull request. Do not duplicate its steps inline in a workflow. Likewise `ci.yml` dry-runs the
  Dockerfile build (`docker build` with a throwaway `APP_VERSION`, discarded): it never logs in to
  or pushes to Docker Hub, and receives no Docker Hub credentials.
- **Destinations are configuration**: variable `DOCKERHUB_IMAGE`, secrets `DOCKERHUB_USERNAME` and
  `DOCKERHUB_TOKEN`, and optionally `PACKAGES_READ_TOKEN`. No namespace is written into source.
- **Published**: `${DOCKERHUB_IMAGE}:<version>` only (no `latest` or moving aliases without a
  deliberate policy decision), and a GitHub Release for the existing tag with
  `fionas-commerce-openapi-<version>.json` and its `.sha256`. The workflow never creates, moves, or
  rewrites a tag, and reruns are safe (the image is rebuilt from the same commit; existing release
  assets are replaced).
- **Credentials stay in the Docker build stage.** `GITHUB_ACTOR` and `GITHUB_TOKEN` are build
  arguments declared in the build stage only; provenance is pinned to `mode=min` because `mode=max`
  records build arguments. Never copy them into the runtime stage, a file, or a log.
- A Dockerfile build without `APP_VERSION` stays `0.0.0-SNAPSHOT`, which keeps Railway builds working.
- Workflow changes, the Dockerfile's `APP_VERSION` handling, and the release configuration are
  documented in the README's Releasing section, which this section must agree with.

## Application migrations

- **Commerce 0.0.23 rebaselined both streams.** The runtime ships a single
  `V1__commerce_baseline`; Fiona ships a single `V1__fionas_baseline` written against it and
  deletes its former `V1`–`V15`. No conversion exists: a database migrated by an earlier
  release fails Flyway validation and must be recreated **manually** (stop instances, drop
  and recreate the database or `docker compose down -v`, start once with bootstrap
  variables, provision `fionas-web`). Never add `baselineOnMigrate`, `repair`, conversion,
  dual reads/writes, startup SQL, or any automatic destructive reset; the application never
  drops a database. This one-time rewrite of history was permitted only because every
  database was disposable pre-release data; after it, history is immutable again.

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
- **Independent version space.** Fiona's next migration is `V2__…`, the next integer in
  Fiona's own history, unrelated to the runtime's numbering (both have a `V1`). If two
  branches add the same `V<n>`, the one merged second renumbers before merging. Never use
  timestamps as versions.
- **Fiona's objects live in the `fionas` schema**, created by the runtime's Flyway before
  `V1` runs, so no Fiona migration says `CREATE SCHEMA fionas` (it would collide). SQL still
  names the schema explicitly (`fionas.customers`); never rely on `search_path` or on the
  default schema. `public` holds nothing of Fiona's, neither objects nor migration history.
- **Never create, alter, or drop anything in `commerce`**, and never add files under
  `db/commerce`. If Fiona needs a runtime-owned structure to change, stop and raise it as
  a runtime requirement (see [Commerce-runtime gap rule](#commerce-runtime-gap-rule)).
- **Reference runtime structures only when they are a published contract.** A foreign key
  to a runtime table is legitimate when commerce-runtime publishes that table for
  applications; never depend on incidental runtime tables, indexes, or Flyway metadata.
  `V1__fionas_baseline` has exactly eight such references: `user_credentials`,
  `inquiry_proposals.issued_by` and `inquiry_service_plans.approved_by` reference
  `commerce.users(principal_id)` (so only a real USER can approve); the lineage association,
  line authorship, the proposal's Quote, and the service plan's Quote and reviewed snapshot
  reference `commerce.financial_document_snapshots(document_id, version)`.
  `ArchitectureSpec` and `DatabaseSchemaSpec` confine runtime schema references to these.
- **`V1__fionas_baseline` contents**: `customers` (unique email); `inquiries` (customer FK,
  message, `zip_code`, `event_date`, checked `event_type`, `NOT NULL` JSON-object
  `requested_service`, and the `(created_at, id)` keyset index); `user_credentials`;
  `inquiry_financial_documents` (lineage ownership, `purpose` `INITIAL_ESTIMATE`/`RELATED`,
  a partial unique index for one initial estimate per inquiry); `financial_document_authorship`
  (one row per exact version, USER/SERVICE author, recorded time); `inquiry_submissions`
  (unique key and inquiry id, bounded key/SHA-256 checks, deferred inquiry FK);
  `inquiry_fulfillment` (served mandatory, closed all-or-nothing); append-only
  `inquiry_communications` with database-generated `recorded_order` and its covering index;
  append-only `inquiry_proposals` (exact pair uniqueness, one INITIAL per inquiry, USER
  `issued_by`); and immutable `inquiry_service_plans` (money-free `plan` jsonb, USER
  `approved_by`). No table holds an amount, total, stage, balance, catalog, offering or
  pricing input.
- **History is immutable.** Never edit a migration that has run outside a disposable
  database; correct it with a new migration.
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

- Fiona owns only `fionas.user_credentials`: `user_id` references
  `commerce.users(principal_id)`, plus an Argon2id hash and change timestamp. It owns no
  user, role, assignment, or session table.
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
- The Administrator role grants exactly FinancialDocumentRead, FinancialDocumentCreate,
  DepositRequirementManage, PaymentRecord, RefundRecord, PrincipalRead, PrincipalManage,
  RoleRead, RoleManage, RoleAssign, the runtime's `RuntimePermissions.ServiceCredentialManage`
  (without it the first administrator could create a service but never issue its credential),
  and Fiona's `fionas.credentials.manage`, `fionas.inquiries.read`, `fionas.inquiries.manage`,
  `fionas.communications.acknowledge`, and `fionas.financial-terms.manage`. It deliberately
  omits `fionas.inquiries.create` (a USER could not use it) and has no wildcard or automatic
  future grants. Grants are fixed when bootstrap creates the role; startup never mutates an
  existing Administrator role, whose grants are managed through `/admin/access` with the
  documented read-modify-replace flow (for example to add `fionas.financial-terms.manage`).
- Generic commerce actions use commerce-domain's `CommercePermissions`
  (`FinancialDocumentRead`, `FinancialDocumentCreate`, `DepositRequirementManage`,
  `PaymentRecord`, `RefundRecord`); never define a Fiona duplicate. Only Fiona-specific
  actions get a Fiona permission.
- Fiona contributes through `ApplicationContributions.permissionDefinitions`:
  `fionas.credentials.manage` (group `fionas.credentials`); `fionas.inquiries.read`,
  `fionas.inquiries.create` and `fionas.inquiries.manage` (group `fionas.inquiries`);
  `fionas.communications.acknowledge` (group `fionas.communications`); and
  `fionas.financial-terms.manage` (group `fionas.financial-terms`): a verified staff user may
  commit staff-authored lines, overrides, adjustments, proposal deposit terms and standalone
  deposit terms. The removed
  `fionas.inquiry-form.read`, `fionas.estimate-preview.create` and group `fionas.pricing` must
  not return. The runtime permission catalog and live resolver remain the only authorization
  source.
- Fiona composes one `AccessControl` from the cookie `SessionAuthenticator(context.sessions,
  SessionCookie("__Host-fionas_session"))`, then the `ServiceAccessTokenAuthenticator`, and the
  runtime's authorization directory. The same control guards runtime administration at
  `/admin/access` and Fiona's `PUT /admin/users/{userId}/credentials/password`. The credential
  endpoint verifies the runtime user, stores a new hash, returns no secret material, and does
  not revoke existing sessions. Every financial-document, payment, and refund route requires
  its commerce permission through the same control; the routes that author amounts also
  require `fionas.financial-terms.manage` and a USER (see
  [the principal-kind rules](#service-authentication-priced-submission-staff-terms-and-login-limiting)).
  A document's payment histories are a child read of the document and require
  `FinancialDocumentRead`. `GET /payments/unapplied` uses `PaymentRecord` for its operational
  queue; `RefundRecord` alone grants neither read. Listing and reading inquiries require
  `fionas.inquiries.read`. The service token endpoint, health, and readiness remain public.
- `FIONAS_TRUSTED_ORIGINS` names exact permitted browser origins. Login and unsafe
  cookie-authenticated methods require a matching `Origin` and fail closed if none is
  configured. Fiona's CSRF policy is separate from the reusable runtime session filter. A
  token-only request carries no cookie and is never rejected for lacking an `Origin`.
- Caller/actor delegation, user impersonation by a BFF, OAuth/OIDC, refresh tokens, and
  signing-key rotation are not part of this slice.
- `GET /auth/me` returns sorted effective `permissions` directly from
  `context.authorization.permissionResolver.permissionsFor`, alongside profile and roles.
  It requires only an active human session, never `commerce.role.read`. Grants and role
  assignments are resolved live; never derive them from role keys, hard-code an
  Administrator mapping, or store permissions in sessions.
- **Two different `/me` endpoints, deliberately.** `GET /auth/me` is Fiona's: "who is the
  current Fiona human staff user?", USER only (any other principal, a SERVICE included, is
  `403`), with Fiona's staff profile and role keys. `GET /authorization/me` is the runtime's
  `currentPrincipalHttpCapability`: "which principal authenticated this request?", USER or
  SERVICE, with its live effective permissions and `permissionCatalogRevision`, no profile
  or role keys, and no permission requirement beyond authentication. Never replace one with
  the other, widen `/auth/me` to services, or add Fiona DTOs, role resolution, or resolver
  calls for `/authorization/me`. A SERVICE-authenticated BFF calling it with its access token
  receives the BFF's own service identity, never the browser user's; there is no delegation.
- **One permission catalog route.** `GET /admin/access/permissions` (administration
  capability, `commerce.role.read`) is Fiona's only catalog endpoint: the complete runtime +
  Fiona vocabulary with the `revision` that `/authorization/me` reports. Do not also mount
  `permissionCatalogHttpCapability`: both capabilities use the same fixed
  `authorizationListPermissions` operationId (see the known upstream gaps). Catalog
  membership grants nothing; bootstrap never expands the Administrator role.

## Trusted priced lines

Fiona accepts money only as complete, already-priced lines from an authorized pricing
authority, and never computes, checks or adjusts a price:

```text
fionas-web (SERVICE)   customer catalog + pricing, server-only → POST /inquiries lines → Estimate v1
staff (USER)           negotiated final lines → staff documents, change orders, Quote proposals
commerce-domain        LineItem, Money, ChangeOrder application, derived subtotal/tax/total
fionas-commerce        line validation, proposal resolution, authorship, ownership, atomic writes
```

1. **`PricedLine` is the boundary value** (`financial/PricedLines.kt`): description (trimmed,
   nonblank, ≤200), optional sub-description (≤500), optional nonzero quantity (≤9 integer and
   ≤6 fraction digits; absent for a flat charge), unit price and line tax amount as `Money` in
   one currency (≤12 integer digits). A **unit rate** is distinct from a **settlement amount**:
   with a quantity, `unitPrice` may have up to `MAX_UNIT_PRICE_FRACTION_DIGITS` (12) decimal
   places and is kept exactly, but the extended subtotal `unitPrice × quantity` must be exact in
   the currency's minor units (USD `0.125 × 8 = 1.00` valid, `0.125 × 3` rejected); a flat
   `unitPrice` is the subtotal and `taxAmount` is the whole line's tax, both in minor units.
   Nothing is rounded, converted or defaulted: never `setScale(…, rounding)` an amount to make it
   fit, and never round a rate for storage, responses, tokens or fingerprints (`Money.decimal()`
   pads, never truncates).
   `PricedLine.of` trims submitted text; `withId` makes the commerce-domain `LineItem`.
2. **Document rules.** `requireDocumentLines`: 1 to `MAX_DOCUMENT_LINES` (100) lines, one
   currency. `requireNonnegativeTotal`: the derived total is not negative (negative lines,
   such as discounts and credits, are allowed; zero is allowed). Line ids are always Fiona's
   (generated, or derived for proposals), hence unique. A canonical Quote additionally needs a
   positive total (`QUOTE_TOTAL_NOT_POSITIVE`), so that a positive deposit can fit.
3. **No authoritative totals in requests.** Request DTOs carry lines only; any `total`-like
   property is ignored, and commerce-domain derives every subtotal, tax and total from the
   lines. HTTP amounts are exact decimal strings matching `SIGNED_DECIMAL`; never JSON numbers,
   `Double` or `Float`. Tax is the committed line amount, never a rate.
4. **Who may author.** `fionas.inquiries.create` + SERVICE for the canonical Estimate v1;
   commerce permission + `fionas.financial-terms.manage` + USER for every staff-authored
   snapshot. The author comes from authentication, never the body, and is recorded in
   `fionas.financial_document_authorship` for the exact snapshot. Transitions copy it.
5. **Staff edits are complete line sets** (`financial/LineProposal.kt`). A `LineProposal` lists
   every final line in order; each `ProposedLine` is either `Existing(lineItemId)` of the
   reviewed snapshot (carried unchanged, or replaced in place under the same id, a direct
   override) or `New(LineKey)` (`[A-Za-z0-9_-]{1,64}`, unique per proposal). Reviewed lines left
   out are removed. `resolveAgainst(reviewed)` derives ids (`proposedLineId`: a name-based UUID
   of document, reviewed version and key, so preview and approval agree), keeps stored lines
   for numerically equal values, and builds one domain `ChangeOrder`: the longest leading run
   of existing lines in increasing reviewed order stays in place (replaced when edited), every
   other reviewed line is removed, the rest are appended, so a moved line keeps its id. It then
   applies the change through the domain and checks the result equals the proposal exactly.
   Only **snapshot equivalence** (`sameSnapshot`: the same ordered line ids, each charging
   numerically the same) means no change: `changes == null`, and committing it is
   `NO_FINANCIAL_CHANGE`. Identity is part of the snapshot: reordering financially identical
   lines, or omitting a line and adding an identical one under a new key, is a real change order
   producing exactly the requested ids and order. `sameChargesIgnoringIds` compares charges only
   and must never decide whether a snapshot changed. Never match lines by description or
   position, and never add a generic patch language.
6. **Stable codes** (`LineProposalViolations`, in runtime `ValidationErrorResponse.violations`):
   `LINE_NOT_IN_REVIEWED_DOCUMENT`, `CURRENCY_MISMATCH`, `NO_FINANCIAL_CHANGE`,
   `NEGATIVE_DOCUMENT_TOTAL`; the Quote builder adds `QUOTE_TOTAL_NOT_POSITIVE` and
   `SERVICE_PLAN_LINE_NOT_FOUND`. Never rename a code; clients match on them. Value failures
   (malformed decimals, too many digits, empty sets) are `422 validation_failed` without codes;
   unreadable shapes (both or neither of `lineItemId`/`key`, an unreadable UUID) are `400`.
7. **`validateChangeOrder`** applies a change order to the locked current snapshot and rejects
   a negative resulting total before any ledger write. Every line-changing path calls it
   under the association lock.
8. **Nothing upstream of the lines exists here.** No catalog, offering, selection, pricing
   policy, preview of prices, catalog revision or pricing input; `ArchitectureSpec` rejects
   them. `RequestedService` is descriptive customer intent and never an input to lines.

### Persisted JSON

Two aggregate-owned values with no identity of their own are stored as strict jsonb in the
row that owns them: an inquiry's `requested_service` and a service plan's `plan`. Each has
Fiona's own private `@Serializable` representation (in `inquiry/RequestedService.kt` and
`JdbiInquiryServicePlanRepository`), separate from the HTTP DTOs, using the shared
`persistedJson` and strict primitive serializers (`PersistedJson.kt`).

- Every property is present (optional values are explicit `null`); array order is meaningful.
- Restoring is strict: unknown, missing or wrongly typed properties (`"75"` for an integer),
  and values the domain types reject, fail with an `IllegalStateException` naming the owner.
  Nothing is defaulted, coerced, or repaired.
- Only the repositories whose rows own the values encode or restore them (`ArchitectureSpec`).
  Domain types stay unaware of JSON. A shape change is a durable-data decision with its own
  migration. Neither value ever holds money.

## Financial documents and payments

Financial documents (`Estimate`, `Quote`, `Invoice`) and payments are commerce-runtime's
financial ledger. A Fiona inquiry's canonical lineage begins at Estimate v1 from its priced
submission; staff may also begin RELATED lineages at Estimate, Quote, or Invoice v1 from
staff-committed lines. Starting at Quote or Invoice is a legitimate new lineage with no
predecessor, not a skipped-history transition. The split:

```text
commerce-domain     FinancialDocument / ChangeOrder / LineItem / PaymentRecord /
                    PaymentAllocation / RefundRecord / RefundAllocation models and invariants
commerce-runtime    financial snapshot persistence, expected-version lifecycle orchestration,
                    payment, allocation and refund persistence, reconciliation,
                    transaction-aware FinancialLedger operations
fionas-commerce     inquiry → document relationship, line authorship, the trusted priced-line
                    boundary and proposal resolution, starting stage, payment acceptance and
                    allocation policy, HTTP and auth
```

1. **Never persist a ledger fact in Fiona.** No Fiona table holds a document, a line, an
   amount, a total, a stage, a payment, an allocation, a balance, or a payment status;
   `DatabaseSchemaSpec` and `ArchitectureSpec` reject them. Fiona reaches documents and
   payments only through `context.financialLedger`, never its repositories or tables.
2. **Fiona owns the relationship.** `fionas.inquiry_financial_documents`: one inquiry owns
   any number of lineages (alternative or restarted proposals), a lineage belongs to exactly
   one inquiry, revisions are versions inside a lineage. A lineage no inquiry owns does not
   exist in Fiona's API (`404`), even when the ledger holds it; every document route resolves
   the association first. Association purpose describes the relationship: `INITIAL_ESTIMATE`
   is the canonical inquiry-generated estimate, `RELATED` covers other lineages. A partial
   unique index enforces at most one initial estimate per inquiry; staff creation is `RELATED`.
3. **Snapshots stand alone.** Description, quantity, price, tax and currency on the immutable
   snapshot are authoritative. Never re-read a requested service, an author, or anything else
   to display or evolve its financial state. Versions evolve from the previous snapshot plus
   explicit line changes. `DocumentSnapshot` pairs the runtime `FinancialDocumentVersion`
   (including its PostgreSQL-owned `createdAt`) with optional `LineAuthorship`; responses
   expose it as `linesAuthoredBy`. Current reads use `latestVersion(transaction, id)`; history
   uses `versionHistory(transaction, id)`, within existing REPEATABLE READ boundaries.
   Mutation responses read back the persisted version through `describeLocked`. Never use
   Fiona's clock or an association timestamp to manufacture document creation time.
4. **Lines are authored, never priced here.** Staff first snapshots
   (`CreateInquiryFinancialDocument`, `FirstSnapshotStage`) and change orders accept complete
   lines from a verified USER (see [Trusted priced lines](#trusted-priced-lines)); the
   existing estimate endpoint is the same operation with stage `ESTIMATE`.
5. **Transitions never change lines**; canonical Invoice issuance is exclusively
   deposit-driven; they go through the expected-version `issueQuote` and `issueInvoice` and
   copy authorship. The runtime reports a transition the stage does not have
   (`IllegalTransition`). There is no estimate-to-invoice shortcut.
6. **Change orders commit staff lines.** Canonical Quotes use `ReviseInquiryQuoteProposal`;
   the standalone route permits canonical Estimates/Invoices and RELATED lineages.
   `CreateChangeOrder` takes `expectedVersion`, a `LineProposal` and the USER author, resolves
   it against the locked latest snapshot, runs `validateChangeOrder`, and commits through
   `FionaFinancialDocuments.commitLines` (ledger `changeOrder` with the expected version, then
   authorship), keeping the stage. Identical charges are `NO_FINANCIAL_CHANGE`. Negative lines
   are allowed; negative totals are `422 NEGATIVE_DOCUMENT_TOTAL`; zero document totals and
   negative reconciliation balances are distinct, valid facts. No payment or refund is implied.
   A zero canonical Quote still cannot publish a positive deposit. See
   [the foundation audit](docs/change-order-foundation-audit.md) for the domain semantics.
7. **Every document-snapshot mutation names the version it acts on** (`expectedVersion`, an
   allocation's `documentVersion`); a lineage that has moved on is `Conflict`. Mutations lock the
   lineage's association row (`expectLatest`) before reading the latest version and writing,
   then pass the same expected version to the runtime, whose NOWAIT lineage lock and
   `(document_id, previous_version)` uniqueness are the final guards.
   `fionas.inquiry_financial_documents` is the serialization point for mutations of one
   Fiona-owned financial lineage. Multi-query financial reads (current, history, inquiry list)
   use PostgreSQL REPEATABLE READ and normal ownership lookups, so their document, authorship,
   and settlement queries share one point-in-time snapshot without blocking writers.
   The current view reconciles the exact snapshot it returns.
8. **Receipt and allocation are separate immutable facts.** Standalone `RecordPayment`
   records money received through `ledger.recordPayment` with an explicit currency and no
   destination; it may remain unapplied. `AllocatePayment` assigns part or all of an
   existing payment to an exact, currently latest RELATED Quote or any Invoice snapshot through
   `ledger.allocatePayment`. Fiona locks the document lineage and checks its version and
   stage; the runtime locks payment history and enforces existence, currency agreement,
   and allocation limits. Separate allocations can apply one payment to several eligible
   documents. The combined `RecordDocumentPayment` operation remains atomic: it records
   and allocates the entire payment to one latest Quote or Invoice in one transaction. Canonical
   published Quotes require the exact current `InquiryProposalId` and one complete deposit
   payment; standalone allocation cannot fund them (see below).
   Amounts are exact decimals limited by currency minor units. External-reference
   uniqueness is the runtime's conflict policy. Allocations stay attached to their exact
   snapshot, while the latest lineage reconciliation counts them all.
9. **Refunds explicitly unwind allocations.** `POST /payments/{paymentId}/refunds` requires
   `commerce.refund.record`. The caller names each payment allocation and amount to unwind;
   an empty list refunds unapplied value. Fiona generates refund ids and timestamps and calls
   `ledger.recordRefund(transaction, ...)`, then `reconcilePayment(transaction, ...)` in one
   runtime transaction. Runtime validates the complete history. Refunded money is never
   reusable. No Fiona refund table exists. Allocation reversals remain unsupported by runtime
   persistence.
10. **Settlement is derived.** Only the latest view carries reconciliation (`grossAllocated`,
    `netApplied`, `balance`); history shows historical facts and authorship, never a
    reconciliation of an older snapshot. Unapplied amount is net received minus net
    allocated value after refunds and refund unwinds, never mutable state. Payment status is
    presentation, derived by clients. Allocation responses reconcile the exact reference they
    changed.
11. **Payment facts are read back from the ledger, document-first.**
    `GET /financial-documents/{documentId}/payments` (`ListFinancialDocumentPaymentHistories`)
    proves Fiona owns the lineage (`inquiryOf`) and calls
    `ledger.paymentHistoriesForLineage(transaction, documentId)` in one REPEATABLE READ
    transaction; a lineage only the ledger holds is `404`, never the runtime's `[]`. Each
    `PaymentHistory` is returned whole, in the runtime's order: allocations to other lineages
    stay, because its reconciliation depends on them, and discovery stays historical after
    refunds unwind every allocation here. Never filter a history to the requested document,
    re-sort it, recompute its reconciliation, or cache a mutation response in its place.
    `GET /payments/unapplied` delegates directly to runtime `unappliedPayments()` through
    a narrow `FionaOperations` callback, requiring `commerce.payment.record` and no inquiry
    association. Never filter/re-sort those results, calculate availability, or query commerce
    payment tables. No pagination, stored balance/status, general payment search, or
    single-payment resource. Allocation body `documentId` is parsed before domain validation:
    unreadable UUID text is a body `LensFailure` (`400 malformed_request`), while readable
    invalid values retain domain errors.
12. **One transaction per operation** (see [Transaction rule](#transaction-rule)).
13. **Lifecycle is projected, never duplicated.** The unique `INITIAL_ESTIMATE` lineage
    drives REQUESTED (Estimate), QUOTED (Quote), BOOKED (Invoice without served), SERVED
    (Invoice with served, without closed), CLOSED (Invoice with served and closed).
    RELATED lineages never drive it. No separate Booking aggregate or stored status exists.
    `RecordDocumentPayment` validates canonical deposit acceptance before any money write and
    uses transaction-taking `FionaFinancialDocuments.bookIfDepositSatisfied` after mutation.
    Standalone `AllocatePayment` rejects canonical Quotes; Invoice/RELATED behavior is unchanged.
    Only an active positive deposit satisfied according to runtime `FinancialLineageView`
    promotes the canonical Quote to Invoice, atomically with that mutation and the authorship
    copy. Failure rolls everything back. Manual `IssueInvoice` rejects canonical lineages and
    remains supported for RELATED lineages. Invoice never demotes after refunds; change
    orders preserve stage. Event date never advances lifecycle.
14. **Fiona owns fulfillment only.** `fionas.inquiry_fulfillment` stores mandatory served
    provenance and optional complete closed provenance (timestamp and USER/SERVICE identity).
    Closed cannot exist without served. `ManageInquiryFulfillment.markServed` requires BOOKED;
    `close` requires SERVED and authoritative current Invoice balance exactly zero. Positive
    and negative balances reject close. Repeated/stale actions are illegal transitions,
    never provenance rewrites. `CreateChangeOrder` rejects changes to a CLOSED canonical
    lineage with `409 illegal_transition`, reading fulfillment under the same association lock
    before writing. RELATED lineages remain eligible. No automatic close, unserve, reopen or
    blanket post-close ledger ban; payments/refunds keep their existing policy.
    Impossible fulfillment before Invoice fails internally. The injected Clock uses microseconds.
    POST `/inquiries/{inquiryId}/served` (`markInquiryServed`) and `/close` (`closeInquiry`)
    use `InquiriesManage` (`fionas.inquiries.manage`) through existing USER/SERVICE and Origin
    rules.
15. **Booking writes observe predecessors.** Triggering mutations and fulfillment actions
    hold the existing association row lock in READ COMMITTED. A waiter must observe the
    previous committed allocation; REPEATABLE READ would pin an earlier snapshot before
    waiting. Under that lock documents, allocations and deposit terms are stable against
    Fiona writers. No runtime-table SQL, nested transactions, JVM locks or automatic retry loops.

## Atomic canonical proposal publication

Fiona publishes its canonical `INITIAL_ESTIMATE` Quote only through `IssueInquiryProposal`:
Estimate -> immutable Quote + active deposit approved against that exact Quote + append-only
`InquiryProposal` issuance commit together. Staff explicitly supplies shared `DepositTerms`;
`FIONAS_DEFAULT_DEPOSIT_TERMS` proposes 20% and never writes an implicit approval. RELATED
lineages retain their standalone shared financial behavior.

`fionas.inquiry_proposals` stores only publication UUID, inquiry/lineage identities,
Quote version, deposit revision, issuance kind, microsecond Clock time and the approving staff
USER (`issued_by` references `commerce.users`; `InquiryProposal.issuedBy` is a `UserId`). Its
composite FKs reference Fiona's association and the exact published runtime snapshot.
An exact pair is unique; one INITIAL publication per inquiry is unique. Durable identity
ordering follows the association lock, independent of Clock timestamps. Re-sending a pair
is a future communication action, not another proposal. No amounts, terms, totals, stages,
mutable current/superseded flags, event bus or dispatcher are persisted in Fiona.

`recorded_order` orders proposal history within an inquiry. Sequence allocation does not
prove global commit order across concurrent inquiries. A future dispatcher must not treat
a high-water mark as proof every lower event committed and was observed; it needs explicit
delivery/claim semantics or another appropriate durable dispatch design.

`IssueInquiryProposal`, `ReviseInquiryQuoteProposal`, and `ReviseInquiryProposalDeposit`
own one READ COMMITTED transaction each. `InquiryProposals` composes transaction-taking
financial helpers and runtime ledger calls under the existing association row lock, acquired
before reviewed-token checks. Quote revision resolves staff-committed final lines against
the current Quote (`QuoteComposer`), applies the no-financial-change rule, appends a same-stage
Quote through `commitLines`, records an optional new service plan, then replaces the deposit
against the new Quote even if terms remain 20%. Deposit-only revision keeps the Quote
version and rejects numerically equivalent same-form terms; changing percentage to fixed
is meaningful even if the resolved amount is equal. Both require exact reviewed Quote and
deposit revision tokens. Any historical `grossAllocated > 0` blocks both revisions, even
when refunds unwind all applied value. Deposit satisfaction by the exact payment
operation atomically promotes Quote -> Invoice/BOOKED only after one exact canonical
deposit receipt; refunds never demote it.

Every publication is the exact `(documentId, documentVersion, depositRequirementRevision)`.
`IsCurrentPayableInquiryProposal` reads one REPEATABLE READ snapshot: only the latest
publication whose exact Quote and active approval/revision still match is payable. Every
reissue supersedes all earlier ids; Invoice makes all publication targets non-payable.
Business UUIDs are not bearer secrets. No public payment link, payment provider, delivery,
contact collection, cancellation, or post-payment adjustment workflow is introduced.

`IsCurrentPayableInquiryProposal` remains only a read/query seam. Canonical payment
acceptance uses `CanonicalInquiryDepositPaymentPolicy` inside `RecordDocumentPayment`'s
existing READ COMMITTED transaction after `expectLatest` locks the association. It reads
runtime `FinancialLineageView` and the latest publication, applies `requireCoherentProposal`,
and requires `Command.expectedProposalId` to name that exact current payable proposal.
Deposit-only republication invalidates old ids even at the same Quote version/amount.
Missing identity is `422 validation_failed`; stale identity/version is `409 conflict`.
Missing or incoherent canonical publication fails internally, with no compatibility repair.

A canonical deposit is indivisible: one distinct `PaymentRecord` must exactly equal the
runtime Active requirement's frozen `requiredAmount` (scale-independent decimal comparison)
and be fully allocated to its exact approved Quote snapshot. No Fiona deposit arithmetic,
terms negotiation, splitting, or new payment/deposit persistence exists. Partial/excess
amounts reject with `422 validation_failed` before writes. Any historical `grossAllocated > 0`
rejects with `409 illegal_transition`, including after a full refund unwind; deposits cannot
accumulate or top up. Staff must negotiate and publish new approved terms before accepting
a smaller full deposit. Extra money requires an exact deposit receipt followed by a distinct
ordinary Invoice payment. Invoice partial/full/overpayments and RELATED behavior stay supported.

`AllocatePayment` rejects canonical Quotes with `409 illegal_transition`; unapplied receipts
cannot fund their deposits. Acceptance invokes `bookIfDepositSatisfied` and verifies a
satisfied canonical Invoice with coherent historical proposal context before commit. Payment,
allocation, Invoice promotion and authorship copy roll back together. The
allocation remains on the accepted Quote; the returned application financial state is Invoice
and inquiry lifecycle is BOOKED. Refunds never demote/reopen booking. Proposal revision and
acceptance serialize on the same association row; no query-then-pay gap or new transaction.

POST `/financial-documents/{documentId}/payments` accepts optional UUID `expectedProposalId`
(required by policy only for canonical Quotes), parsed as body input (`400 malformed_request`
for unreadable UUID). Existing `commerce.payment.record`, USER/SERVICE and Origin policy,
operationId and receipt shape remain unchanged. No inquiry-scoped deposit endpoint or
frontend workflow is introduced in this slice.

The durable proposal row is the business event for future at-least-once integrations.
It commits with the financial facts, has a stable id, and never implies STAFF_EMAIL_SENT.
Actual delivery alone may append communication activity. A future generic durable dispatch
capability belongs upstream when needed; no after-commit crash-window publisher exists here.

POST `/staff/requests/{inquiryId}/proposals` (`issueInquiryProposal`), its `/quote-revisions`
child (`reviseInquiryQuoteProposal`) and `/deposit-revisions` child
(`reviseInquiryProposalDeposit`) derive the document from the inquiry. All apply
`AccessControl.staffComposition()`: `commerce.financial-document.create`,
`commerce.deposit-requirement.manage`, `fionas.financial-terms.manage`, and a staff USER
(`requireStaffUser`; a SERVICE is `403`), plus the unsafe-cookie Origin policy. The command's
`issuedBy` is the authenticated `UserId`. Responses are 200 with
`{proposal, financial, depositRequirement, servicePlan?}` and no-store.

Standalone canonical Quote issuance and Quote change orders reject with illegal_transition.
Standalone deposit set/replace/reactivate/withdraw also reject whenever the lineage is
canonical and proposal history exists, regardless of current financial stage. Both check
`InquiryProposalRepository.latest` in the caller's transaction after locking the association.
Unpaid Quote deposit changes use atomic proposal reissuance. After Invoice/BOOKED, the
accepted deposit requirement/history is immutable. Canonical Estimate and booked Invoice
change orders and RELATED deposit behavior remain supported; manual canonical Invoice
issuance remains forbidden.
`GET /staff/requests/{inquiryId}` keeps one unlocked REPEATABLE READ and its existing read
permissions, adding `suggestedDepositTerms`, optional latest `proposal`, and authoritative
`depositRequirement`. Missing/mismatched published Quote pairs fail internally. After booking,
the latest proposal is historical Quote/deposit context and its approval/revision must still
match the immutable accepted deposit.

## Quote builder: staff-committed lines

Staff compose the canonical Quote from final lines they negotiated, preview it without
writes, and publish it through the existing proposal operations.

- **Final lines, never pricing instructions.** Preview, initial issuance and Quote revision
  take a `LineProposal` (see [Trusted priced lines](#trusted-priced-lines)) against the exact
  reviewed version, an optional `ProposedServicePlan`, and `DepositTerms`. There are no modes,
  overrides-by-source, catalog revisions or selections. A verified staff USER holding
  `fionas.financial-terms.manage` is the pricing authority; Fiona checks structure and
  arithmetic only.
- **One pure core.** `QuoteComposer.compose(inquiryId, reviewed, proposal, servicePlan, terms)`
  (internal, pure; only derived ids vary by document/version/key): from an Estimate it yields
  the domain Quote successor (`changeOrder(...).toQuote()` when the lines change, else the
  Estimate's own `toQuote()`); from a Quote (revision) it requires a change and yields the
  same-stage successor. It rejects a nonpositive Quote (`QUOTE_TOTAL_NOT_POSITIVE`) and
  negative totals, resolves the deposit with shared `DepositTerms.resolve` (plus Fiona's
  minor-unit check for fixed terms), resolves plan notes to line ids
  (`SERVICE_PLAN_LINE_NOT_FOUND`), and computes a SHA-256 `QuoteReviewToken` (v2) over inquiry,
  document, reviewed version and stage, every final line (id, origin, key, values) in order,
  total, terms, required deposit and the plan. `ComposedQuote.financialChange` says whether an
  intermediate Estimate is appended.
- **Preview** `POST /staff/requests/{inquiryId}/quote-preview` (`previewInquiryQuote`) runs
  `PreviewInquiryQuote` in one unlocked REPEATABLE READ: canonical lineage at exactly
  `expectedDocumentVersion`, Estimate stage, coherent empty proposal/deposit history, then
  `QuoteComposer`. It writes nothing, even on failure. `200` no-store with lines (id, origin
  `CARRIED`/`REPLACED`/`NEW`, key, values, subtotal/tax/total), totals, deposit, plan, token.
- **Issuance** extends `IssueInquiryProposal` with an optional `ReviewedQuoteComposition(lines,
  servicePlan?, reviewToken)`; the HTTP body adds optional `lines`, `servicePlan` and
  `reviewToken`: lines and token both or neither, plan only with lines (`400`). Without them,
  the Estimate's own lines become the Quote, as before. With them, `InquiryProposals.issue`
  locks the association (`expectLatest`), checks Estimate stage and coherence, re-composes from
  authoritative state, requires the identical token (`409 QUOTE_REVIEW_STALE`, no-store), then
  `FionaFinancialDocuments.composedQuote` runs `validateChangeOrder` and the expected-version
  `ledger.changeOrder` (only when charges change, authored by the USER) and `ledger.issueQuote`,
  copies authorship, and checks the persisted Quote equals the candidate. The plan is inserted,
  the deposit is approved against that final Quote, and the INITIAL proposal is appended: one
  READ COMMITTED transaction; any failure rolls back every fact. Chains: `E1 → Q2` (no financial
  change) or `E1 → E2 → Q3`. Issued is not sent: no communication, payment or booking follows.
- **Quote revision** (`ReviseInquiryQuoteProposal`) composes against the current Quote with the
  same core, commits the lines, records an optional new plan for the new Quote, replaces the
  deposit with the expected revision, and appends QUOTE_REVISED.
- **Service plans** (`InquiryServicePlan`, `fionas.inquiry_service_plans`) are immutable, one per
  exact Quote snapshot, written only with staff-composed lines. They store the reviewed version,
  a `ServiceCommitment` (description ≤2000, optional guest count and duration, ≤50 items of
  ≤200), line notes by ledger id (≤500 each), approval Clock time and the approving USER
  (`approvedBy: UserId`, FK to `commerce.users`). No amounts, prices, catalog references,
  totals, stages or current flags. Only `JdbiInquiryServicePlanRepository` encodes and restores
  the strict JSON (`ArchitectureSpec`).
- **Read model.** `ReadStaffRequest` adds optional `servicePlan`: the plan of the latest
  proposal's exact Quote, checked against that ledger snapshot's line ids in the same REPEATABLE
  READ; contradictions or corrupt JSON fail internally. A deposit-only revision keeps the Quote,
  so its plan remains; Quotes published without lines have none (never invented). Booking keeps
  the accepted Quote's plan.
- Decline, notes beyond line notes, expiry, messages, delivery, payment links and post-close
  correction remain separate slices.

## Deposit requirements and bulk financial lineages

- `ReadStaffRequest` is a derived, read-only application projection in `staff`, not a new
  aggregate or persisted workspace. `GET /staff/requests/{inquiryId}` (`readStaffRequest`)
  returns inquiry, financial, suggestedDepositTerms, optional proposal, depositRequirement and
  required `payments` (empty array when none) through shared
  HTTP mappings. One unlocked REPEATABLE READ transaction composes the transaction-taking
  cores of `GetInquiry`, `GetFinancialDocument` and `ListFinancialDocumentPaymentHistories`.
  Payment histories use the same runtime capability and `PaymentHistoryResponse` mapping as
  the standalone document history route: whole payments, including other-lineage allocations,
  refund facts and fully unwound historical allocations, in runtime order with derived reconciliation.
  Accepted deposit allocations remain on the published Quote after Invoice promotion; later
  Invoice receipts appear separately. Unbooked canonical histories with allocations, or booked
  histories missing their unique exact accepted Quote receipt, fail internally. Refunds may
  reduce satisfaction after booking without invalidating the immutable receipt or lifecycle.
  The lifecycle's explicit INITIAL_ESTIMATE
  relationship selects the canonical lineage; RELATED lineages never participate. Financial
  ownership and lifecycle document identity must agree. Unknown inquiries are 404; missing
  canonical/customer data or disagreement fails internally, never as nullable financial state.
  The requested service remains pinned inquiry intent; current immutable lines, totals,
  version and current derived reconciliation come from the runtime ledger. `financial.reconciliation` is always present on this endpoint.
  BOTH `fionas.inquiries.read` and `commerce.financial-document.read` are required through the
  existing USER/SERVICE authentication. Safe GET requires no trusted Origin; success is no-store.
  `financial.version` supplies expectedDocumentVersion for atomic proposal issuance by inquiryId.
  Reads never issue/send Quotes or record communication. Existing standalone
  inquiry and financial reads and Quote concurrency semantics remain first-class.

- `ReadInquiryOperationalStates` derives the complete operational population in one unlocked
  REPEATABLE READ transaction: `InquiryRepository.ids` checks population completeness,
  `InquiryFinancialDocumentRepository.initialEstimates` reads only canonical relationships,
  one transaction-taking runtime `financialLineages` call supplies authoritative financial
  views, and `InquiryFulfillmentRepository.findAll` reads facts as a set. Missing/corrupt
  relationships fail internally; never skip an inquiry or use RELATED lineages. Every state
  delegates lifecycle to `InquiryLifecycle.project`. `InquiryOperationalSnapshot` retains
  those runtime views and derives pure counts: new = REQUESTED, quoted = QUOTED,
  booked = BOOKED or SERVED, needsClosing = SERVED with balance `signum() == 0`.
  These counts overlap, exclude CLOSED, and ignore event dates and deposit satisfaction.
  Neither states nor counts are persisted. Preserve bulk reads and caller-owned transactions;
  never replace them with per-inquiry reads. Its standalone invocation owns the transaction;
  the transaction-taking core supports `ReadStaffDashboard` without duplicating checks.

The dashboard exposes `asOf`, the unchanged four-count `summary`, and exactly three
`workQueue` members: `needsReply`, `needsQuote`, `needsResolution`, each `{items: [...]}`.
Items retain high-level customer/event and canonical financial facts, and add
`attentionSince`, stable `reasons`, and semantic `totalQualifier`. `FROM` applies only
to a current canonical Estimate with requested `guestCountIsMinimum=true`; exact-count
Estimates and all Quotes/Invoices are `EXACT`. Queue membership does not change this rule.
Clients format currency and the localized `from` label without another inquiry request.
Every queue sorts by onset ascending then lexical inquiry UUID. Queues may overlap; each inquiry appears only once within a queue. Clients
format durations from `asOf` and `attentionSince`; no pagination or preformatted age.

- `needsReply`: CUSTOMER_COMMUNICATION_UNACKNOWLEDGED when inbound customer email is
  durably recorded after the latest staff reply or acknowledgement's `recordedOrder`.
  Anchor: minimum actual `occurredAt` among outstanding inbound. Earlier-recorded inbound
  is cleared regardless of business timestamp; later-ingested backdated or equal-time
  inbound remains outstanding. Multiple emails produce one item.
- `needsQuote`: NEEDS_QUOTE for REQUESTED, anchored at inquiry creation. It shares the
  operational predicate with `summary.new`, so queue size equals that count.
- `needsResolution` aggregates every applicable reason, anchored at the earliest onset:
  QUOTE_STALE for QUOTED at or beyond three days since the maximum of current Quote
  version time, latest inbound email and latest staff-sent email; anchor is that maximum
  plus three days. STAFF_ACKNOWLEDGED never resets quote inactivity. EVENT_DATE_PASSED_UNSERVED
  for BOOKED after its event calendar day, anchored at the next day's start in Fiona's
  explicit event calendar zone. SERVED_WITH_BALANCE_DUE for SERVED positive canonical
  Invoice balance and READY_TO_CLOSE for SERVED exact-zero balance, both anchored at authoritative served time.
  Negative overpayment and CLOSED qualify for neither served reason. READY_TO_CLOSE shares
  the operational needsClosing predicate, so its item count equals `summary.needsClosing`.

`fionas.inquiry_communications` stores append-only source facts: activity UUID, inquiry
FK, kind (CUSTOMER_EMAIL_RECEIVED, STAFF_EMAIL_SENT, STAFF_ACKNOWLEDGED), microsecond
`occurredAt` timestamp, database-generated positive unique BIGINT identity `recorded_order`,
and required USER/SERVICE acting provenance for staff activity only. `occurredAt` records
when communication happened; `recordedOrder` records durable Fiona ingestion/observation order.
Repository appends acquire `SELECT id FROM fionas.inquiries ... FOR UPDATE` before inserting
and allocating the identity, in the caller's transaction. The locking lookup is the sole
inquiry existence check; a missing inquiry fails before insertion. Same-inquiry writes wait for the
preceding commit/rollback; unrelated inquiries remain independent. Sequence gaps are allowed.
Clearing follows record order; quote inactivity follows actual email `occurredAt`, excluding
acknowledgements. A backdated inbound recorded after acknowledgement needs reply even when
its actual email time leaves the Quote stale. The `(inquiry_id, recorded_order)` index
includes `kind` and `occurred_at` for the aggregate read.
Communication-kind clearing, quote-activity and principal-provenance semantics use exhaustive Kotlin `when`
expressions without `else`; every new kind requires explicit decisions for all three, with SQL
kind lists kept explicit and verified against the pure projection.
`RecordInquiryCommunication` supports inbound/outbound recording for future adapters.
No provider/public webhook, email bodies, attachments, delivery tracking, notifications,
mailboxes or generic conversation framework exists. Source facts are Fiona-owned;
attention membership and resolution reasons remain pure Fiona policy, never persisted.

`POST /inquiries/{inquiryId}/communications/acknowledge` (`acknowledgeInquiryCommunication`)
requires `fionas.communications.acknowledge` through the same live AccessControl for USER
or SERVICE, with existing unsafe-cookie Origin policy. It appends server Clock time and
authenticated provenance, clears inbound already durably recorded before the action, and
returns 204/no-store. Later ingestion stays outstanding regardless of `occurredAt`.
Repeated calls succeed and append another fact, even without outstanding inbound. Unknown
inquiries return 404, malformed UUIDs 400, missing auth/permission 401/403. It never changes
financial state or lifecycle. New bootstrap Administrators receive the permission; existing
roles require explicit read-modify-replace grants through `/admin/access`.

`ReadStaffDashboard` owns one unlocked REPEATABLE READ spanning the existing transaction-taking
operational core, bulk inquiry/requested-service/customer enrichment and one PostgreSQL
communication attention aggregate. `attentionFor(transaction, inquiryIds)` returns at most
one `(unacknowledgedSince, latestEmailAt)` value per inquiry with activity. A single set-based
window/filtered aggregate derives the clearing order and the two actual-time values; full
historical communication rows never reach the dashboard. Returned communication data is
O(inquiries), even as append-only history grows. Requested service restoration remains strict;
corrupt data fails the whole read rather than defaulting the minimum flag. Complete
population integrity is mandatory, including records outside queues. Nonempty populations
use exactly ten SQL statements for one or many inquiries. Concurrent commits cannot mix
financial, fulfillment, enrichment and communication snapshots. One injected Clock `asOf`
truncated to microseconds evaluates policy, not a database commit watermark. Read permissions
remain BOTH `fionas.inquiries.read` and `commerce.financial-document.read`; GET statuses stay
200/401/403/500, runtime errors, no-store success. Financial truth remains FinancialLineageView,
lifecycle remains InquiryLifecycle.project, and reads never promote/demote booking. No generic
commerce ownership moves into Fiona and no upstream release is required.

`fionaApplication` resolves `FIONAS_EVENT_TIME_ZONE` to an explicit `eventCalendarZone`
and passes it to `ReadStaffDashboard` and `DashboardAttentionPolicy`. Default:
`America/Los_Angeles`; valid alternate IANA IDs include `America/New_York` and `UTC`.
Invalid/blank configured IDs fail during application configuration/composition before serving.
The server Clock still obtains absolute Instants and microsecond timestamps, normally with
`Clock.systemUTC()`. Neither `asOf` nor persisted timestamps become local timestamps, and
event dates remain LocalDate. Event calendar interpretation never derives from `clock.zone`.

- Commerce owns `DepositTerms`, `DepositRequirement`, `DepositRequirementRevision`,
  persisted timestamps, frozen amount resolution, reconciliation/satisfaction,
  `FinancialLineageView`/`FinancialLineageActivity` and bulk reads. Fiona never duplicates
  their persistence or invariants. Fiona owns ownership, eligibility and HTTP exposure;
  deposit-satisfaction booking policy remains Fiona-owned (rules 13–15 above).
- `GetDepositRequirement`, `GetDepositRequirementHistory`, `SetDepositRequirement`,
  `WithdrawDepositRequirement`, and `QueryFinancialLineages` live in `financial`, explicitly
  wired through `FionaOperations` in `FionaApplication.kt`. HTTP translates DTOs only.
  Every document must first be owned through `InquiryFinancialDocumentRepository`;
  missing and unowned commerce lineages both return the same Fiona `404`.
- PUT `/financial-documents/{documentId}/deposit-requirement` approves, replaces or
  reactivates with `DepositRequirementManage`, never document-create, plus
  `fionas.financial-terms.manage` and a staff USER (`AccessControl.staffTerms`): standalone
  deposit terms are staff-negotiated values, so a SERVICE is `403` even with both permissions.
  The approver comes only from authentication. One transaction uses
  `FionaFinancialDocuments.expectLatest` to lock ownership and check expectedDocumentVersion,
  rejects canonical lineages with proposal history through `CanonicalProposalDepositPolicy`
  and `InquiryProposalRepository.latest` in that same transaction, including Invoice/BOOKED,
  applies shared Quote/Invoice payment eligibility for remaining lineages, and calls transaction-taking
  `activateDepositRequirement`. Null/absent expectedRequirementRevision expects no history;
  otherwise it must name the exact latest revision. Never reinterpret null as don't-care.
- Terms are a strict `type` union: FIXED has exact decimal amount and explicit ISO currency;
  PERCENTAGE has exact decimal percentage. Fiona applies existing money/minor-unit policy;
  upstream enforces positivity, matching currency, total bounds, `(0,100]`, HALF_UP resolution
  and positive resolved amount. No Double, percentage rounding, defaults, conversion or
  clamping. Original terms and frozen amounts survive later document changes.
- DELETE at that path (the same staff-USER authority as PUT) takes only expectedRequirementRevision, locks ownership and calls
  transaction-taking withdrawal, rejecting canonical lineages with proposal history under
  the same association lock regardless of stage, with no document-version check for other lineages. Return the new
  WITHDRAWN revision (`200`); never delete history or copy prior terms. Missing history is
  `404`, stale tokens/runtime NOWAIT conflicts are `409 conflict`, already withdrawn is
  the existing `409 illegal_transition`, and invalid values/Estimate approval are `422`.
  Never swallow conflicts, weaken runtime NOWAIT, retry internally or nest transactions.
- GET current at that path calls `financialLineages(transaction, listOf(id))` after ownership
  verification in one unlocked REPEATABLE READ snapshot. GET its `/history` child reads
  complete runtime history in REPEATABLE READ, oldest first. Both use FinancialDocumentRead.
  Current `state` is NONE (documentId only), ACTIVE (revision/previousRevision/createdAt,
  approvalDocumentVersion, terms, frozen requiredAmount and current satisfied), or WITHDRAWN
  (identity/revision/timestamp only). History has only ACTIVE/WITHDRAWN and no satisfaction.
  No nullable bag. Satisfaction remains `netApplied >= requiredAmount`, reversible by refunds;
  Fiona atomically issues a canonical Quote's Invoice when a triggering mutation satisfies
  its active deposit; runtime arithmetic remains authoritative. Refunds never demote Invoice.
- POST `/financial-documents/query` uses FinancialDocumentRead, retaining request order.
  Empty input succeeds, duplicates fail validation, any unowned/missing id fails the whole
  request. One set-based `inquiriesOf(transaction, ids)` query against Fiona's association
  table precedes one runtime `financialLineages(transaction, ids)` call in REPEATABLE READ.
  No locks or per-lineage authorship/ownership/reconciliation/deposit loops. Results pair inquiry
  ownership with latest version/stage/total/currency, reconciliation including refundAllocations,
  current deposit union and runtime activity timestamps.
- Map FinancialLineageActivity as-is: latestDocumentVersionAt, latestDepositRequirementAt,
  latestPaymentAllocationAt, latestRefundAllocationAt, latestFinancialActivityAt. Allocations
  and unwinds use allocatedAt; receipt receivedAt and unrelated standalone refunds never count.
  No lastRelevantActivityAt, age, dashboard labels or workflow interpretation.
- All five are ContractRoutes with operationIds getFinancialDocumentDepositRequirement,
  getFinancialDocumentDepositRequirementHistory, setFinancialDocumentDepositRequirement,
  withdrawFinancialDocumentDepositRequirement, queryFinancialDocumentLineages. Same AccessControl;
  the three reads accept a session OR SERVICE token, while PUT/DELETE are staff-session USER only
  (`staffSessionSecurity`); trusted Origin for unsafe cookies (query POST included), none for
  token-only calls, and runtime errors. OpenAPI derives unions, closed terms variants and null
  revision semantics from serializers/transport annotations, never maintained JSON.
- New bootstrap Administrator roles include DepositRequirementManage. Startup never modifies
  existing roles; operators read-modify-replace complete grants through `/admin/access`.
  The runtime-backed permission catalog exposes the key automatically.
- Keep deposit route/operation specs, bulk efficiency instrumentation, paused-snapshot coherence,
  NOWAIT ordering/rollback, bootstrap/migration adoption and OpenAPI union tests alongside
  existing financial/payment tests.

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
| `io.github.castab.fionas.commerce` | `Main.kt`, `FionaApplication.kt` (composition root), `PersistedJson.kt` (strict persisted-JSON configuration shared by the owning repositories) |
| `...customer` | `Customer` and its values, `CustomerRepository`, `JdbiCustomerRepository` |
| `...inquiry` | `Inquiry` and its values/repositories; descriptive `RequestedService` and its persisted JSON; submission key, canonical fingerprint and transaction-bound submission repository; lifecycle projection, fulfillment repository and explicit service/closeout; append-only communication activity/repository and `RecordInquiryCommunication`; `CreateInquiry`, `GetInquiry`, `ListInquiries` |
| `...financial` | Fiona's context for the runtime's financial ledger: the trusted line boundary (`PricedLine`, `LineProposal`, `resolveAgainst`, `validateChangeOrder`), the inquiry association and line-authorship repositories, the read models, the transaction-taking `MaterializeInquiryFinancialDocument` core, and the `CreateInquiryFinancialDocument`, `CreateChangeOrder`, `IssueQuote`, `IssueInvoice`, `RecordPayment`, `AllocatePayment`, `RecordDocumentPayment`, `RecordRefund`, the deposit operations and `QueryFinancialLineages`, `GetFinancialDocument`, `GetFinancialDocumentHistory`, `ListInquiryFinancialDocuments`, and `ListFinancialDocumentPaymentHistories` operations; proposals (`InquiryProposals` and its three operations), the pure `QuoteComposer`, `PreviewInquiryQuote`, and the immutable `InquiryServicePlan` with its repository |
| `...staff` | Fiona's credential persistence, password verification, permission definitions, first-admin bootstrap, `ReadStaffRequest` / canonical request projection, and `ReadStaffDashboard` / pure dashboard attention policy and projection |
| `...http` | The API contract (`FionaApi.kt`: `fionaApiRoutes`, `fionaApi`, `apiDocs`), its OpenAPI renderer and schemas (`OpenApi.kt`), browser origin policy, the line DTOs and principal-kind guards (`FinancialLines.kt`), and feature contract routes and transport DTOs (`InquiryRoutes.kt`, `FinancialDocumentRoutes.kt`, `InquiryProposalRoutes.kt`, `QuoteBuilderRoutes.kt`, `StaffRequestRoutes.kt`, `AuthRoutes.kt`, …) |
| `...openapi` (source set `src/openapi`) | The `generateOpenApi` entry point; not in the deployable jar |

There is no `offering` package. Do not create empty packages or layers for future work. Avoid
`service`, `manager`, `handler`, `util`, `common`, `base`, or `framework` packages and classes
unless they acquire a concrete, well-defined responsibility.

## Premature abstraction rule

Do not create generic frameworks, `Repository<T, ID>`, `CrudRepository`,
`BaseRepository`, generic service layers, plugin systems, or extension APIs for possible
future requirements. Repositories are narrow and intention-revealing. Extract only after
real repetition or a real requirement appears.

Not in scope until a dedicated slice decides otherwise: allocation reversals,
Stripe or any payment provider or SDK, payment
webhooks, caller/actor delegation, OAuth/OIDC, refresh tokens, self-service password resets, event dispatch beyond durable proposal issuance,
outbox, NATS, persisted financial projections, CQRS, separate Booking aggregates,
cancellation, decline, archive, reopen, unserve, zero-deposit booking, email provider integration and broader communication workflows,
any product catalog, offerings binding, pricing engine, price preview or price validation in Fiona (they belong to `fionas-web`),
on-behalf-of headers or delegated staff identity,
a generic line-source identity, stored balances or payment statuses, tax,
travel fees, minimum orders, inventory, availability schedules/windows, deposit
schedules, customer merge or deduplication, inquiry search, filters, or
arbitrary status mutation, and further event details (street address, time, contacts). Do not add placeholders for
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

### Known upstream gaps (last audited at commerce 0.0.23)

The application consumes commerce-runtime 0.0.23, with matching commerce-domain transitively.
Commerce 0.0.23 removes the Offerings catalog capability (Fiona no longer needs it), replaces
the runtime migration history with a single `V1__commerce_baseline`, and makes every ledger
lifecycle mutation take the caller's expected document version (`changeOrder`, `issueQuote`,
`issueInvoice`), closing the former "no expected-version write" gap. Earlier releases closed
the application history schema gap (0.0.15), the payment read gap (0.0.16), unapplied
discovery, persisted version timestamps and structured validation errors (0.0.17), and added
service credentials and tokens (0.0.20) and versioned deposits with bulk lineage facts (0.0.22).
Audit source: the tagged `v0.0.23` sources of castab/commerce-domain (domain financial types,
runtime ledger, capabilities, configuration, migrations, error and health routes). The gaps
below were rechecked and remain open; they do not justify Fiona workarounds.

- **The two catalog capabilities share one operationId.** `authorizationAdministrationHttpCapability`
  includes the same internal permission-catalog route as `permissionCatalogHttpCapability`,
  with the fixed operationId `authorizationListPermissions`, so a host can mount only one of
  them per OpenAPI document. Fiona mounts the administration one. Never wrap, clone, or rebuild
  a runtime route to rename it here. Minimal upstream fix: a distinct operationId for the
  administration catalog route, or a host-chosen operationId prefix.
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
  API contract rules forbid.
- **The error body lens is private.** `CommerceErrorHandling` renders `ErrorResponse`
  through a private lens, so Fiona builds its own `jsonBody(ErrorResponse.serializer())`
  to document error responses. It is the same runtime type, not a second error model.
- **`ErrorResponse` has no schema descriptions.** Its OpenAPI schema says `code` and
  `message` are required strings but cannot describe them, because the type is upstream.
- **Capability routes need a composed runtime.** The administration and service-token
  capabilities take a `CommerceRuntimeContext`, whose constructor is `internal`, and only
  `commerceRuntime(...)`, which needs a database, creates one. The generator (`src/openapi`,
  never the deployable jar) builds a rendering-only context reflectively against the 0.0.23
  constructor, with a transactor that opens no connection and a reflectively assembled
  `FinancialLedger` and authorization directory that refuse every call. This is provisional:
  commerce-runtime may eventually need a first-class contract composition seam.
- **Runtime capability routes carry no security metadata.** The authorization administration
  and current-principal routes enforce Fiona's `AccessControl`, but commerce-runtime 0.0.23
  gives the host no way to declare their OpenAPI security. Fiona documents its own routes and
  never wraps or clones runtime routes to change their metadata; `OpenApiDocumentSpec` pins
  the gap. Minimal upstream API: an optional OpenAPI `Security` the host supplies once.
- **Stage names are not published.** The runtime persists `ESTIMATE`/`QUOTE`/`INVOICE` but
  keeps the mapping private, so Fiona's HTTP layer maps the sealed stages to the same names
  itself. A public stage name (or enum) in commerce-domain would remove it.
- **Line identity has no generic source.** A change order replaces by id, which is all Fiona
  needs; a generic line-source identity is not required and is not requested upstream.

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
  exactly as `main()` does and exposes the runtime's own `Transactor`. `TestApplication`
  provisions a `fionas-web` SERVICE holding only `fionas.inquiries.create` and submits priced
  inquiries as it. The harness supplies Fiona's canonical Los Angeles event calendar default.
- Test lines come from a test-only pricing authority (`testing/Pricing.kt`: `TestLine`,
  `acceptanceLines` reproducing the historical $681.25 Estimate, `CHURROS`,
  `COURTESY_DISCOUNT`). Never move it into `src/main`. Test-only ledger helpers that read the
  latest version (`testing/LedgerFixtures.kt`) exist only to build fixtures.
- Keep: value-object tests, repository integration tests, operation tests (including atomic
  rollback), `RuntimeTransactionSpec`, `FinancialDocumentAtomicitySpec` (a Fiona failure after a
  ledger write rolls back the runtime's snapshot, payment, and allocation with Fiona's rows),
  `InquiryMaterializationSpec` (the authority's exact lines in order, SERVICE authorship,
  customer reuse, rollback inside the ledger, canonical uniqueness, bespoke lines and credits
  evolving without any catalog), `InquiryOperationsSpec`, `FinancialDocumentRepositoriesSpec`
  (associations and authorship), `ChangeOrderFoundationSpec` (granular identity-preserving
  changes, signed lines, nonnegative totals, concurrent staff edits, and financially identical
  lines whose reorder or remove-and-add appends a version while a true no-op does not),
  `LineProposalSpec` (pure identity-bearing resolution: reorders, remove-and-add, moved reused
  ids, scale-insensitive no-ops, tokens and notes bound to the actual final ids),
  `PricedLineSpec` (unit-rate versus settlement precision, per-currency minor units, bounds), `FinancialDocumentReadConsistencySpec`,
  `FinancialDocumentRoutesSpec` (the whole inquiry → staff estimate → line edits → quote →
  deposit → invoice → payment workflow through the complete handler; carry/override/remove/
  add/reorder identities; caller totals never authoritative; codes, conflicts, transitions,
  payment policy, permissions), `FinancialLedgerExpansionSpec`, `FinancialDocumentPaymentsSpec`,
  `UnappliedPaymentsSpec`, `InquiryRoutesSpec` (receipt privacy, required fields, invalid lines
  zero-write, requested service descriptive only, SERVICE-priced Estimate v1, keyset pages),
  `InquiryIdempotencySpec` and `InquiryIdempotencyRoutesSpec` (contention, rollback takeover,
  incomplete claims, lost-response recovery, changed lines conflict), `InquiryRequestFingerprintSpec`
  (pinned v2, every line value and order, numeric scale equivalence, opaque key validation),
  `ServicePrincipalAuthSpec` (SERVICE end to end; staff sessions refused priced submission; a
  SERVICE with every staff permission refused staff terms; session precedence; Origin only for
  cookies; session-only logout; provisioning and rotation), `AuthRoutesSpec` (bootstrap grants,
  removed routes `404` and permissions absent), `DepositRequirementRoutesSpec` (reads for USER or
  SERVICE; standalone deposit mutations only for a staff USER holding both
  `commerce.deposit-requirement.manage` and `fionas.financial-terms.manage`, refusals writing nothing), `MigrationLifecycleSpec`, `DatabaseSchemaSpec`
  (single baselines, eight published runtime FKs, no catalog or ledger-fact columns),
  `OpenApiDocumentSpec` (paths, operationIds, statuses, decimal-string schemas, no catalog or
  pricing schemas, per-route security including SERVICE-only `createInquiry` and staff-only
  terms routes, the runtime metadata gap pinned), `OpenApiRoutesSpec`, `GenerateOpenApiSpec`,
  `ApplicationVersionSpec`, `PaymentSmokeScriptSpec`, and `ArchitectureSpec`.
- Keep the quote builder suites: `QuoteBuilderSpec` (real PostgreSQL: keep-all `E1 → Q2`,
  **the churro example** from soft-serve Estimate to bespoke Quote with optional discount,
  deposit, exact payment, booked Invoice, served and closed with no catalog; override ids;
  deterministic and sensitive tokens; stable codes; stale/tampered reviews; rollback at the
  plan, deposit and publication; association contention; Quote and deposit revisions; a
  non-user approver refused) and `QuoteBuilderRoutesSpec` (shapes, codes, no-store, staff-USER
  authority, Origin, fail-closed corrupt plans), plus `InquiryProposalsSpec` and
  `InquiryProposalRoutesSpec`.
- Keep `InquiryCommunicationRepositorySpec` SQL/pure projection parity, real same-inquiry lock
  contention in both append directions, unrelated-writer independence and large-history
  database reduction. Keep `DashboardEventCalendarSpec` application composition with a UTC
  Clock, default/alternate event zones, local midnight and DST boundaries, and invalid
  configuration. Keep `StaffRequestSpec`/`StaffRequestRoutesSpec` paused-snapshot coherence,
  integrity failures and permission intersection.
- Run `./gradlew ktlintCheck test build` before considering work complete.
- Node.js 20 or newer must be on PATH for `PaymentSmokeScriptSpec`, which runs the actual
  payment script against a started test runtime and throwaway PostgreSQL. CI provisions
  Node.js 24 without npm packages or caching.

## Documentation synchronization

Changes to endpoints, the API contract, configuration, migrations, packages, the
customer-matching policy, the trusted priced-line boundary, the financial-document or payment policy,
the bootstrap grants, the version convention, or the upstream version must update
`README.md` and this file in the same change.
