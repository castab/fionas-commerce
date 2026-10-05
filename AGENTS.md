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
      │                  current Offerings catalogs and the Offerings catalog capability, the
      │                  financial ledger (documents, deposit requirements, payments, allocations, reconciliation)
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
- which inquiry owns each commerce-runtime `FinancialDocument` lineage and its semantic purpose,
  optional legacy staff pricing metadata, change-order intent, and payment acceptance policy (see
  [Financial documents and payments](#financial-documents-and-payments));
- inquiry served/closed operational facts with authenticated principal provenance;
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

- **Every Fiona inquiry is a request for configured ice cream service.** A valid inquiry
  includes a current catalog revision, service quantity/duration, and required offering
  selections. Every accepted inquiry is priced exactly once and atomically materializes
  Estimate v1. There is one inquiry model and one successful creation path: no contact-only
  inquiry, no optional `pricingInputs`, and no successful inquiry without its Estimate.
  `CreateInquiryRequest.pricingInputs`, `CreateInquiry.Command.pricingInputs`,
  `InquiryDetails.pricingInputs`, and `InquiryResponse.pricingInputs` are non-null; an
  omitted or `null` request value is a `LensFailure` (`400 malformed_request`) that writes
  nothing. Never reintroduce nullable plumbing, transitional flags, or a separate endpoint.
- **Submission requires `fionas.inquiries.create`; reading requires `fionas.inquiries.read`.**
  `POST /inquiries` is normally called by the web frontend's SERVICE principal with a service
  access token. `GET /inquiries` and `GET /inquiries/{inquiryId}` require Fiona's
  `fionas.inquiries.read`. There is no public confirmation lookup: a confirmation renders
  what the customer just submitted.
- **The inbox is a primitive, not a query API.** `ListInquiries` returns the newest
  inquiries first, ordered by `created_at DESC, id DESC`, `limit` 1–100 (default 25) per
  page, continuing strictly after the previous page's last `(created_at, id)` (keyset, never
  offsets). The HTTP cursor is that position, base64url-encoded, and is opaque to clients.
  The `inquiries_created_at_id_idx` index serves every page. Never add search,
  filters, inquiry status, offsets, or a generic pagination framework here without a
  dedicated slice.
- **Requested pricing inputs are the customer's request, never amounts.** Every inquiry
  carries the `FionasPricingInputs` the customer configured (catalog revision, selections,
  guest count, duration), in the inquiry row's own `NOT NULL` `pricing_inputs` jsonb (Fiona
  `V11`), so one insert writes the complete inquiry and none exists without them. New `CreateInquiry` commands use Fiona-local `PublicInquiryPricing` before business writes:
  categories must belong to the shared `publicOfferingQuestions` definition used by
  `GetInquiryForm`, and the submitted revision must equal the latest observed in the
  same READ COMMITTED transaction. Older revisions produce `409 CATALOG_REVISION_STALE`
  in the runtime's `ErrorResponse` envelope; future/nonexistent revisions remain `404`.
  No catalog locks, nested transactions, automatic selection migration, or silent repricing.
  A publication after that observation is allowed: price that immutable observed snapshot
  with `FionasPricing`, exactly once. Public categories are Fiona-owned; enabled offerings
  in them are advertised while disabled offerings remain in the authoritative catalog.
  Authoritative pricing receives the full snapshot; the runtime enforces selection state,
  availability (disabled takes precedence), membership, retirement and cardinality before policy validation.
  Hidden categories produce `422` with `PUBLIC_INQUIRY_CATEGORY_NOT_ALLOWED`.
  Those exact concrete lines materialize
  Estimate v1 through the transaction-taking `MaterializeInquiryFinancialDocument` core,
  shared with staff creation. Customer, inquiry, requested inputs, ledger snapshot/lines,
  and the `INITIAL_ESTIMATE` relationship commit or roll back together. All rejections commit
  nothing, including their key claim. Inputs remain pinned inquiry history, never rewritten or copied to the initial
  financial snapshot. Financial documents have no dependency on offerings after materialization.
  Public-category eligibility never constrains staff financial operations; current-revision pricing applies to all new pricing requests.
  Every inquiry reads the catalog: none is accepted before the catalog is initialized (`404`).
- **Public submission identity is explicit and durable.** Only `POST /inquiries` requires
  exactly one `Idempotency-Key`: 1–128 ASCII letters/digits/underscore/hyphen, case-sensitive,
  untrimmed, opaque and non-secret (UUIDs work). Invalid/missing/repeated keys are a
  `LensFailure` (`400 malformed_request`), after authorization and before the command.
  `CreateInquiry.Command` carries `InquirySubmissionKey`; the transport header never leaks
  into repositories as an HTTP concept. Other public/staff routes require no key.
- **Idempotency precedes current business validation.** Canonical command fingerprint →
  claim/inspect key → replay or mismatch → only for new claims, public selections/current
  revision/pricing → existing atomic inquiry materialization. A successful replay returns
  the stored Inquiry, hence the exact original 201 receipt/Location, with no catalog access,
  public-category check, pricing or ledger write, even after a publication. A changed intent
  conflicts with `409 IDEMPOTENCY_KEY_REUSED` using runtime `ErrorResponse` and no-store;
  no previous request, fingerprint or submission state is exposed. Keep this distinguishable
  from `CATALOG_REVISION_STALE`. Authentication and request-shape failures persist nothing.
- **The database serializes command identity.** Fiona `V9` owns `inquiry_submissions`.
  `JdbiInquirySubmissionRepository.claim` uses unique-key `INSERT ... ON CONFLICT DO NOTHING`
  in the existing READ COMMITTED operation transaction. A contender waits for the owner's
  commit/rollback; a separate statement then sees its committed result, or the contender
  becomes claimant after rollback. Never catch a unique SQL exception and continue an
  aborted transaction. The claim reserves a non-null, unique inquiry id with a deferred
  FK to `fionas.inquiries`, permitting early claim but preventing incomplete commits.
  Claim, customer/inquiry/history, Estimate and association are one atomic unit. Any failure
  rolls back the claim too, including late ledger/association failures; corrected retries
  may use that key. No independent finalization transaction, Redis, JVM mutex or TTL.
- **Fingerprint semantic intent, never raw bytes or prices.** `InquiryRequestFingerprint`
  hashes stable v1 length-prefixed UTF-16 code units/binary canonical values with SHA-256. Include all
  inquiry values and pricing revision/context/ordered categories/offerings; exclude
  command key, generated identities, clocks, current catalog and derived financial amounts.
  Preserve order because it determines materialized line order. Normalized text and optional
  defaults remain equivalent. Encoding changes require durable replay compatibility decisions:
  the byte that once marked pricing presence is a constant v1 marker, keeping every committed
  (priced) fingerprint replayable; `InquiryRequestFingerprintSpec` pins a v1 hash.
  One key plus one semantic request yields one committed result; never claim application code
  literally executes once. Different keys mean distinct commands, even for the same email/body.
  Preserve customer-matching concurrency policy; never deduplicate by email or body similarity.
- **Future SvelteKit responsibility.** Keep one non-secret token per logical visible submission,
  shared across duplicate browser requests and all backend retries/timeouts. A UUID generated
  independently inside each backend attempt defeats idempotency. Carry/retain the logical
  token across attempts. A committed replay remains successful; `IDEMPOTENCY_KEY_REUSED`
  means changed intent under a used key, while `CATALOG_REVISION_STALE` requires refresh/review
  of an uncommitted command. No frontend work belongs in this slice.
- **The event ZIP code is required inquiry-owned location data.** Non-null `zipCode` is
  trimmed five-digit US text (leading zeroes preserved), held in the non-null
  `fionas.inquiries.zip_code` column and read only by staff with the inquiry. Blank is
  invalid. It is never customer data or part of pricing inputs. It supports staff travel review;
  no operating-area rule or automatic travel surcharge has been decided.
- **Event date and type are required inquiry facts.** `eventDate` is an actual calendar date
  (YYYY-MM-DD, years 0001–9999) with no time or time zone; `eventType` is one of BIRTHDAY,
  WEDDING, CORPORATE, SCHOOL_EVENT, NEIGHBORHOOD_EVENT, OTHER. Both are non-null Kotlin
  values and columns on `fionas.inquiries` (`date` and checked `text`), never customer data
  or pricing inputs. Staff list/detail reads return them unchanged. No availability,
  future-date restriction, booking, or event-type pricing rule is introduced. Street address
  and further details remain customer-authored `message` text.

## Fiona's customer inquiry form

- `GET /inquiry-form` requires `fionas.inquiry-form.read`, a ContractRoute with operationId `getInquiryForm`.
  `GetInquiryForm` reads one current snapshot through the runtime's `GetOfferingsCatalog`,
  wired in the composition root, and adapts it into `InquiryForm`. No persistence, seeding,
  second catalog, generic form DSL, or shared commerce form concept is introduced.
- Fiona owns the ordered sections, stable question keys, labels, submission bindings, and
  small rendering hints. Semantic inputs are distinct from hints: TEXT, EMAIL, INTEGER,
  BOOLEAN, INTEGER_CHOICE, DATE, STRING_CHOICE, and OFFERING_CHOICE. No frontend component names.
- Public offering questions explicitly reference stable category keys (soft serve, hand-scooped,
  toppings from the pricing policy, cones/cups), ordered by Fiona's question definition.
  Missing/retired categories are omitted; restoration restores the configured question.
  Other active categories never become public questions automatically. Catalog categories
  and public question exposure are separate concerns.
- Offering choices project catalog-owned limits and enabled options from that single
  snapshot. HTTP reuses the runtime's `dto()`, `OfferingDto`, and `OfferingPriceDto`;
  never restate offering identity, price forms, or selection validation. Allowed durations
  come from `FionasPricingPolicy`; text limits come from Fiona's value-object constants.
- The response exposes `definitionVersion` (11 for the current code-owned definition) and
  `catalogId`/`catalogRevision`. Clients submit the latter revision as
  `pricingInputs.catalogRevision`; inquiry submission requires it still to be current,
  and current-revision backend pricing remains authoritative.
  Change the definition version deliberately when code-owned questions/bindings change.
- Definition 11 uses `CHIPS` for duration and the four public offering questions (soft serve
  flavors, hand-scooped flavors, toppings, cones/cups). Hand-scooped follows soft serve in
  the existing required service section, bound to `/pricingInputs/selections`, with stable
  field key `offering:hand-scooped-flavor`. The local catalog requires exactly four hand-scooped
  selections alongside soft serve. Duration remains `INTEGER_CHOICE` with one allowed integer;
  offerings remain `OFFERING_CHOICE`, with catalog-owned minimum/maximum and single or multiple
  selection. Event type remains `STRING_CHOICE` with `SELECT`. Hints never change semantics.
- `scripts/replace-catalog.mjs` replaces the active catalog at a local or remote endpoint
  using that endpoint's administrator credentials and `commerce.offerings.manage`.
  It manages catalog definitions only: it performs no estimate preview,
  price calculation, or expected-total check. It prompts for base URL (blank defaults to
  `http://localhost:8080`), required administrator username, and required administrator
  password, hiding terminal password input and preserving password whitespace. It reads no
  connection environment variables; `Origin` is the entered URL's origin and must be trusted
  by the application. Three-line stdin input supports integration tests. Invalid or incomplete
  input fails before HTTP calls. A fresh run adds four categories and one batch
  of 19 offerings, reaching revision 6. Every subsequent default run replaces all active
  contents through the runtime HTTP API: retire all active offerings, retire all active
  categories, then restore known keys or add new ones with the script's complete definitions.
  Omitted entries stay retired and omitted optional properties are cleared. Preserve script
  category/offering order, using consecutive add/restore offering batches when keys are mixed.
  Catalog identity and lifetime key reservation remain intact; revisions increase rather than
  reset. Read current and retired identities at the same observed revision before writing,
  thread every mutation's returned expected revision, and stop on conflicts without retrying.
  Read-back verification compares the complete ordered active contents. These API calls commit
  separately; a failed run may leave partial contents, and a rerun rebuilds from that state.
  No runtime-table SQL, catalog reset endpoint, or application startup seeding is introduced.
  `hand-scooped-flavor` has minimum/maximum four; all its keys use `hand-scooped-`
  prefixes to avoid collisions with soft serve. Butter Pecan and New York Cheesecake are
  ENABLED/UNAVAILABLE and retain their nut/returning notes; other offerings are ENABLED/AVAILABLE.
  Chopped Peanuts adds a seventh topping with `infoNote: Contains peanuts`; topping limits
  remain four to six. New offerings have no catalog prices; existing catalog prices remain
  ordinary editable properties. Baseline pricing fixtures remain unchanged. Label edits use
  the same keys and the normal replacement run; the script accepts no command-line options.
  Notes never schedule availability changes.
- Integer/string options have default-null `badge`, `statusNote`, and `infoNote`, mapped to
  response DTOs and omitted from JSON when absent. Each supplied domain value is nonblank,
  without trimming. Fiona currently consumes this text only for CHIPS rendering. Runtime
  offering `dto()` preserves catalog-owned text for offering CHIPS. Code-owned duration and
  event-type options use fixed null defaults; non-CHIPS options supply no text, and no endpoint
  or API edits code-owned option text. Broader control usage or editing is a future feature,
  not an implicit extension. Retain the shared option fields/wire shape; no separate CHIPS
  presentation metadata model is needed. `statusNote` never overrides
  availability. Enabled unavailable offerings remain visible and unselectable, disabled
  offerings remain omitted. No frontend component name, new persistence, or form DSL is added.

- Contact information includes required event `zipCode`, bound to `/zipCode`, with a TEXT
  hint and text length/pattern semantics from `ZipCode`. Retain the guest-count question
  while per-guest pricing applies, with approximate-count help text. The minimum-count
  checkbox is omitted; the existing request flag remains optional with default false.
- Required Event details follow Contact information: `eventDate` binds `/eventDate` with
  DATE semantics and a DATE control hint; `eventType` binds `/eventType` with STRING_CHOICE
  semantics and a SELECT hint. The six labels are Birthday, Wedding, Corporate, School event,
  Neighborhood event, Other, in that order. Submit the option value, not the display label.
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
  public-form pricing constraints. Availability schedules/windows remain a separate slice.
- `publicInquiryOfferings` centralizes Fiona's visible-option rule: `selectionState == ENABLED`.
  It omits both disabled combinations and retains enabled unavailable options, with their
  runtime DTO state/availability and full metadata in catalog order. Clients display
  `UNAVAILABLE` options but prevent selection (check back later); unavailability never means
  retired or disabled. The form and public pricing-preview facts share this projection.
  Minimum viability counts enabled visible options, including unavailable ones; insufficient
  enabled options still fail configuration, but temporary unavailability never does.
  Never construct a filtered snapshot for authoritative pricing: tampered current submissions
  and current-revision previews receive `OFFERING_DISABLED` or `OFFERING_UNAVAILABLE` from the
  runtime. State changes advance the current revision, so stale new submissions fail `CATALOG_REVISION_STALE`
  first. Successful idempotent replay skips later state validation even after retirement.
  Materialized financial documents remain independent of offering state.
- Browser amounts are advisory only. Preview, inquiry submission, and persisted documents
  remain authoritative through `FionasOfferingsEngine`. Request DTOs accept pricing inputs,
  never trusted totals or line items; the form introduces no alternate validation path.
- Each field's `submissionPointer` is a JSON Pointer into the existing request; offering
  inputs append `{category, offerings}` at `/pricingInputs/selections`.
  The service section is required (`optional = false`), as `pricingInputs` is; only
  Additional information is optional, and field requirements apply when a section is used.
  Clients need not call `POST /estimate-preview` before submitting.
  The form advertises only supported answers. Phone, street addresses, event
  contacts, definition administration/history, and a reusable UI abstraction remain deferred.
- Wire inputs are sealed serializable DTOs in `http`, with a `type` discriminator.
  `KotlinxSchemas` derives explicit oneOf variants, discriminator mappings, required const
  tags, and string enum values from descriptors. Offering options nested inside Fiona DTOs
  still use `offeringsOpenApiRenderer`. Test the union and its runtime price references in
  `OpenApiDocumentSpec`, behavior through the complete handler in `InquiryFormRoutesSpec`,
  and policy/snapshot projection in `GetInquiryFormSpec`. Response-only local estimates
  must match authoritative current-revision previews; captured stale forms conflict.

## Generic commerce concepts

Do not duplicate or re-model anything `commerce-domain` defines:

- financial documents (`Estimate`, `Quote`, `Invoice`), versions, money, line items;
- offerings: `OfferingsSnapshot`, `OfferingCategory`, `Offering`, `OfferingPrice`, catalog
  ids and revisions, and the `OfferingsEngine` contract;
- deposit requirements and terms; payments, allocations, reversals, refunds, reconciliation;
- the payment-adapter contract;
- principals, roles, permissions;
- the booking lifecycle phase interfaces.

Use the upstream types. Fiona's inquiry lifecycle is a projection of its canonical financial
lineage and operational facts, not a separate Booking aggregate. Fiona tables *reference* commerce facts
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
- Offerings persistence (`OfferingsSnapshotRepository` and `commerce.offerings_catalogs`,
  whose one row per catalog holds current contents, lifetime reserved keys, and retired last
  representations since commerce 0.0.21), current catalog reads (`GetOfferingsCatalog`),
  operations (create/category lifecycle and batch add/update/retire/restore offerings),
  expected-revision concurrency, retired discovery (`RetiredCatalogValue.lastSeen`), and
  the Offerings HTTP capability (`offeringsHttpCapability`, `OfferingsHttpBinding`,
  `OfferingsHttpAccess`, contract routes, DTOs, `offeringsOpenApiRenderer`). Historical catalog
  retrieval is removed. Never vendor or recreate any of these capabilities in Fiona.
- principal session lifecycle and persistence (`context.sessions`, `SessionManager`,
  `commerce.principal_sessions`, `SessionCookie`, `sessionAuthentication`), the
  `authenticatedPrincipal` request lens, and `AccessControl` permission enforcement.
- human users, service identities, principal status, roles, role permissions, role
  assignments, live permission resolution, and the permission catalog
  (`context.authorization`); the authorization administration HTTP capability (which also
  serves the permission catalog) and the current-principal HTTP capability.
- the financial ledger: `FinancialLedger` (`context.financialLedger`), the
  `FinancialDocumentRepository` and `PaymentRepository`, their tables
  (`commerce.financial_document_snapshots`, whose rows hold their lines since 0.0.20,
  `commerce.payment_records`, `commerce.payment_allocations`, `commerce.refund_records`,
  `commerce.refund_allocations`), financial-document lifecycle
  orchestration (`create`, `changeOrder`, `issueQuote`, `issueInvoice`), payment recording
  and allocation, refund recording and explicit allocation unwinds, deposit requirements and revision history,
  coherent bulk financial lineage reads/activity, and derived reconciliation/satisfaction.

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
capability at `/admin/access` and the runtime's current-principal capability at
`/authorization/me` (`currentPrincipalHttpCapability(accessControl, "/authorization/me",
setOf(authorizationTag))`) with that same `AccessControl`, mounts the runtime's service
token endpoint (`fionaServiceAuthentication(context)`, `serviceAuthenticationHttpCapability` at
`/auth/service/token`), builds Fiona's financial
operations on `context.financialLedger` with Fiona's own association and pricing-source
repositories and one `FionasPricing` (the engine plus the runtime's transaction-bound
catalog read), and contributes exactly two route handlers:
`fionaApi(operations, offerings, authorizationAdmin, currentPrincipal, serviceAuthentication, version, auth)`
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
Fiona-owned rows and runtime-owned commerce facts. A priced inquiry claims its submission key and writes its customer,
inquiry, requested inputs, runtime Estimate and canonical association in one transaction.
Staff first-snapshot creation also writes optional legacy pricing metadata; a transition
copies that metadata only when present, and a staff change order records its newly supplied
inputs. No financial operation reconstructs old lines from old pricing inputs. Standalone
receipt and allocation are separate ledger
facts, while the combined payment operation writes both in one transaction.

- **Ledger calls take the operation's `Transaction`.** Inside `inTransaction`, call only the
  `FinancialLedger` overloads that take a `Transaction`
  (`context.financialLedger.issueQuote(transaction, id)`), never the convenience overloads
  (`issueQuote(id)`), which open a second transaction that commits on its own.
- **Reads that decide a write happen in that transaction too**: the current catalog snapshot
  (through transaction-bound `FionasPricing`, never a separately transacting catalog operation), the
  latest financial snapshot, and the association.

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
  persistence DTOs of [Persisted pricing inputs](#persisted-pricing-inputs), never wire DTOs.
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
   and `Long`s as `int64` integers, booleans, lists, enums, sealed input unions with string discriminators, and
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
  `gradle.properties` until a release sets `-Pversion`. Releases are tag-driven (see
  [Releases](#releases)); `gradle.properties` is never edited for one.
- **Swagger UI is self-contained**: its WebJar is packaged in the fat jar, and `/docs`
  never loads assets from a CDN. No authentication options are configured in Swagger UI;
  browser staff sessions use Fiona's cookie.
- **http4k modules stay at commerce-runtime's http4k version** (`http4k` in
  `libs.versions.toml`), never a newer BOM; `ArchitectureSpec` fails when two http4k
  versions meet on the classpath.

## Service authentication, customer operations, and login limiting

- **No static API key.** The former `UiApiKey`/`FIONAS_UI_API_KEY` mechanism is removed and
  must not return as a second credential. Software callers authenticate as SERVICE
  principals through commerce-runtime 0.0.22's service authentication; Fiona composes it and
  never recreates token issuing, verification, credential storage, or hashing.
- **One `AccessControl`, two runtime mechanisms, session first.** The composition root builds
  `authentication(SessionAuthenticator(context.sessions, cookie),
  ServiceAccessTokenAuthenticator(context.serviceAccessTokens))` behind `BrowserOrigin` and binds it
  with `AccessControl(..., context.authorization)`. A request is exactly one principal, USER or
  SERVICE; a valid session wins over a token on the same request, and identities are never
  merged. Authorization is the principal's current role grants, resolved live; tokens carry no
  permissions. Never trust a caller for being a service, never forbid a permission because the
  holder is a service, and never assume a principal is human: routes that need a human check it
  explicitly (`GET /auth/me` uses `as? UserId` and answers a service `403`).
- **USER and SERVICE are first-class principals.** Permission-protected routes never check
  the principal kind, keep route allow-lists for services, union a session's and a token's
  grants, or look at role names: whoever holds the permission is authorized. `fionas-web`
  holds only the three customer-operation permissions, but a SERVICE granted, for example,
  `fionas.inquiries.read` or a commerce permission may use those routes too.
- **Customer operations use ordinary permissions.** `GET /inquiry-form` requires
  `fionas.inquiry-form.read`, `POST /estimate-preview` `fionas.estimate-preview.create`, and
  `POST /inquiries` `fionas.inquiries.create`, each through `access.requirePermission(...)`
  outside the handler. `401` means no valid authentication, `403` an authenticated principal
  without the permission. Permissions describe capabilities, never callers: no `fionas.ui`,
  `fionas.frontend`, or `fionas.trusted`.
- **OpenAPI states both transports, separately from enforcement.** `http/OpenApi.kt` declares the
  `staffSession` scheme (API key in the `__Host-fionas_session` cookie) beside the runtime's
  `serviceAccessToken` bearer scheme, combined by Fiona's documentation-only `DocumentedSecurity`
  (one requirement object per alternative: OR). Never use http4k's `OrSecurity` for this: its
  filter re-runs the route per alternative and replaces a final `401` with a bodiless one.
  Every Fiona route states its metadata through `principalAuthentication()` (behind
  `access.authenticated()`) or `principalAccess(permission, …)` (behind
  `access.requirePermission(permission)`) in `http/AuthRoutes.kt`, while the handler still
  states its enforcement explicitly; the helpers never enforce anything. Public routes (login,
  the token endpoint) declare no security; logout declares `staffSession` OR anonymous (`{}`,
  `DocumentedSecurity(..., allowsAnonymous = true)`), never `serviceAccessToken`. The cookie name
  is one constant, `STAFF_SESSION_COOKIE` (`http/AuthRoutes.kt`), used by the composed
  `SessionCookie` and the `staffSession` scheme alike.
- **Logout is session-only.** `POST /auth/logout` applies `BrowserOrigin`, then the runtime's
  `sessionAuthentication(sessions, cookie)`: a valid session is revoked and the cookie cleared
  (`204`); a cookie whose session the runtime no longer accepts is still cleared (`204`);
  without a session cookie, a request that `AccessControl` authenticates (a SERVICE token) is
  `403`, never a pretend revocation; with neither, `204`. Logout never revokes a service access
  token (tokens expire; disabling the service suspends them). Never parse cookies beyond
  `SessionCookie`.
- **The token endpoint is the runtime's.** `fionaServiceAuthentication(context)` mounts
  `serviceAuthenticationHttpCapability` at `/auth/service/token` in Fiona's one contract. It is
  public (no security) and keeps the runtime's uniform `401`. Fiona builds no token endpoint and
  no limiter for it; deployments protect it with edge/reverse-proxy rate limiting and/or private
  reachability, because each valid-shaped attempt costs one Argon2id verification.
- **Configuration.** `application.conf` declares `serviceTokens { signingKey = "", issuer = "" }`,
  so `SERVICE_TOKENS_SIGNING_KEY` and `SERVICE_TOKENS_ISSUER` are required and startup fails
  without them. The signing key belongs only to fionas-commerce and is never a service
  credential or given to a frontend; the issuer is distinct per deployment
  (`fionas-commerce-local`, `-staging`, `-production`). The lifetime keeps the runtime default.
  Never log a credential secret, verifier, access token, or signing key.
- **Provisioning is administration, never startup.** Startup never creates `fionas-web`, a
  role for it, or a credential. An administrator creates the service, a `fionas.web` role
  granting exactly the three customer-operation permissions, assigns it, and issues a
  credential through `/admin/access`; rotation is create B, deploy B, verify, revoke A.
- Authorized successful form responses have `Cache-Control: private, max-age=60,
  must-revalidate`; failures have `no-store`, including exceptions rendered by
  the existing runtime error filter. Preserve definitionVersion and catalogRevision.
  The shorter freshness and removal of stale-while-revalidate reduce stale submissions
  after publication without removing caching. The future SvelteKit UI must invalidate/bypass
  cached forms on `CATALOG_REVISION_STALE`, fetch the current form and ask the customer to
  review updated choices/pricing before resubmission; never silently auto-resubmit.
- Anonymous `POST /auth/login` uses one synchronized in-memory limiter per process,
  capacity five, one token per five minutes, keyed by connection source IP. All attempts
  count before origin/body/password checks, including success; success never resets it.
  Monotonic time drives refill. Do not trust forwarded headers without an explicit proxy
  policy; a proxy currently shares its connection-IP bucket. Missing source shares a bucket.
- Retain at most 10,000 identities; prune fully replenished idle entries and use a depleted
  shared overflow bucket for new IPs at the bound, never evict depleted identities.
  Restart resets buckets; replicas do not share them. Exhaustion returns 429, Retry-After
  seconds rounded up, no-store, and runtime `ErrorResponse("rate_limited", "Too many requests")`.
  Runtime 0.0.22 has no rate-limit ErrorCategory; reuse its envelope and document the local
  status/code on the login ContractRoute. No new error framework or upstream subsystem.

## Releases

CI verifies source; a release promotes source that is already verified. `ci.yml` runs on
`pull_request` to `main` only: no run for a feature-branch push by itself, one run per pull request
update, and none after the merge into `main`. `main` is expected to be protected (changes arrive
through pull requests whose required CI check passed); the workflows cannot enforce this and must not
try to configure it. Never re-add a `push` trigger to `ci.yml`, and never give a pull request
workflow Docker Hub credentials.
Pull request CI provisions Java 25 and Node.js 24 for the actual catalog replacement/payment script
smoke tests against throwaway PostgreSQL, without npm dependencies or caching. Release
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

- **Commerce 0.0.22 owns runtime V13.** Deposit revisions and lineage concurrency references
  belong only to the runtime. Its stream runs before Fiona. Fiona V12 adds only inquiry
  served/closed provenance in `inquiry_fulfillment`; no duplicate deposit tables, runtime SQL
  or migration copy accompanies this policy.

- **Commerce 0.0.21's V11/V12 reject populated legacy catalogs.** Runtime V12 replaces
  `commerce.offerings_snapshots` with `commerce.offerings_catalogs`. Fiona adds no migration
  and changes no existing migration. Stop the application, recreate the disposable database
  volume, start PostgreSQL, start the upgraded backend, and run `scripts/replace-catalog.mjs`.
  Old/new backends cannot share the new catalog schema. Deployed resets and frontend/client
  compatibility follow `docs/commerce-0.0.21-rollout.md` as a separately scheduled cutover.
  Never add conversion, dual reads/writes, or runtime-table SQL to Fiona.

- **Commerce 0.0.20's runtime V9 and Fiona's V11 store aggregate-owned values in their
  owning rows.** Runtime V9 moves financial lines and catalog contents into their snapshot
  rows; Fiona V11 moves each complete `FionasPricingInputs` into `fionas.inquiries.pricing_inputs`
  and `fionas.financial_document_pricing.pricing_inputs`. Both refuse populated pre-release
  data instead of converting it: recreate the disposable database/volume, then rerun
  `scripts/replace-catalog.mjs`. Never add a Fiona workaround, backfill, dual read/write,
  or nullable transitional column.

- **Commerce 0.0.19's runtime V8 deliberately rejects existing offering rows.** Both
  independent state columns are required without invented defaults/backfills. Recreate
  disposable local databases/volumes, then rerun `scripts/replace-catalog.mjs`.
  Fresh databases apply runtime V8 before Fiona's migrations. Never compensate with a
  Fiona migration touching runtime tables, weaken V8, or continue after migration failure.

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
  The runtime publishes current catalog storage (`commerce.offerings_catalogs`),
  but no Fiona migration references it, and Fiona reaches the catalog only through the
  runtime's Offerings operations and snapshot read. Fiona's `V2` references the published
  `commerce.users(principal_id)` for the credential foreign key, and `V3` references the
  published `commerce.financial_document_snapshots(document_id, version)`: the association
  names each lineage's first snapshot, and each pricing source its exact snapshot. `V4`
  (the inquiry list index and an inquiry's requested pricing inputs) references only
  Fiona's own tables. `V5` added the optional inquiry-owned ZIP code location table;
  `V6` replaces it with required `inquiries.zip_code` for empty inquiry data, without a
  default, backfill, or data transfer. The already-applied `V5` remains immutable.
  `V7` adds required inquiry event date/type with calendar-range and allowed-type checks,
  without defaults or backfill; it assumes empty pre-release inquiry data.
  `V8` adds association `purpose` (`INITIAL_ESTIMATE` or `RELATED`) and a partial unique
  index on inquiry id for `INITIAL_ESTIMATE`. Existing associations default to `RELATED`;
  no canonical initial estimate is inferred for older inquiries.
  `V9` adds Fiona-only `inquiry_submissions`, with unique command key and inquiry id,
  bounded key/SHA-256 checks and a deferred inquiry FK. It does not modify or reference
  runtime structures. Existing inquiries receive no invented submission keys.
  `V10` adds a deferred foreign key from `inquiries.id` to Fiona's `inquiry_pricing`, so no
  inquiry commits without requested inputs. It invents none: a disposable database holding
  an inquiry recorded without them fails `V10` and must be recreated.
  `V11` stores each complete `FionasPricingInputs` as one jsonb object in its owner's row:
  `inquiries.pricing_inputs` and `financial_document_pricing.pricing_inputs`, both `NOT NULL`
  with a JSON-object check. It drops V10's reverse reference, `inquiry_pricing` and its
  `…_categories`/`…_selections`, the `financial_document_pricing_…` child tables, and the
  pricing source's scalar columns, keeping its `(document_id, document_version)` key and both
  foreign keys. It converts nothing and fails on a populated database, which must be recreated.
  `V12` adds `inquiry_fulfillment`, keyed by inquiry, with served timestamp and USER/SERVICE
  provenance plus optional complete closed timestamp/provenance. Served is mandatory in
  every row; closed fields must be all absent or all present. No stage, amount or backfill.
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
   the same logical id. Changing it orphans the catalog and its reserved identities; `ArchitectureSpec`
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
   calls `OfferingsSnapshotRepository` itself: the composition root hands it to the runtime's
   `GetOfferingsCatalog` (preview/form), and hands transaction-bound `retrieveLatestVersion`
   to `FionasPricing` (public inquiry and staff documents). New pricing validates the requested
   revision against the observed current snapshot before evaluating it. Reads deciding a
   write stay inside that write's operation-owned transaction.
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
   current catalog reads remain public.
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
12. **Management updates one current catalog.** Commerce 0.0.21 advances the revision once
    per successful mutation and stores current contents plus reserved/retired identities,
    not a retrievable catalog history. Offering add/update/restore are nonempty ordered batches
    of complete runtime DTOs with each key and explicit selection state/availability; retire
    is a nonempty key batch. Empty, duplicate, invalid, or stale batches save nothing.
    Optional properties omitted on update/restore are cleared. Natural keys remain reserved
    for life and must be restored, never re-added as unrelated identities. Category updates
    retain path-owned keys and category DELETE retains its query revision. Runtime batch
    operationIds are `fionasOfferingsAddOfferings`, `fionasOfferingsUpdateOfferings`,
    `fionasOfferingsRetireOfferings`, and `fionasOfferingsRestoreOfferings`; item GET remains.
    Retired discovery returns last representations with `lastSeenRevision`; it is not history.
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
  RoleManage, RoleAssign, the runtime's `RuntimePermissions.ServiceCredentialManage` (without it
  the first administrator could create a service but never issue its credential), and Fiona's
  `fionas.credentials.manage`, `fionas.inquiries.read`, `fionas.inquiries.create`, `fionas.inquiries.manage`,
  `fionas.inquiry-form.read`, and `fionas.estimate-preview.create`. It has no wildcard or
  automatic future grants. Grants are fixed when bootstrap creates the role; startup never
  mutates an existing Administrator role, whose grants are managed through `/admin/access`:
  an Administrator created by an earlier release gains any of these (for example
  `fionas.inquiries.read`, `commerce.deposit-requirement.manage`, `commerce.refund.record`, or `commerce.service-credential.manage`)
  only through the documented read-modify-replace grant.
- Generic commerce actions use commerce-domain's `CommercePermissions`
  (`FinancialDocumentRead`, `FinancialDocumentCreate`, `DepositRequirementManage`, `PaymentRecord`, `RefundRecord`); never define a Fiona
  duplicate. Only Fiona-specific actions get a Fiona permission.
- Fiona contributes `fionas.credentials.manage` (group `fionas.credentials`),
  `fionas.inquiries.read`, `fionas.inquiries.create`, `fionas.inquiries.manage`, `fionas.inquiry-form.read` (group
  `fionas.inquiries`), and `fionas.estimate-preview.create` (group `fionas.pricing`) through
  `ApplicationContributions.permissionDefinitions`. The runtime permission catalog and
  live resolver remain the only authorization source.
- Fiona composes one `AccessControl` from the cookie `SessionAuthenticator(context.sessions,
  SessionCookie("__Host-fionas_session"))`, then the `ServiceAccessTokenAuthenticator`, and the
  runtime's authorization directory. The runtime's
  `OfferingsHttpAccess.ReadWrite(accessControl)` protects its mutations and retired discovery. The same
  control guards runtime administration at `/admin/access` and Fiona's
  `PUT /admin/users/{userId}/credentials/password`. The credential endpoint verifies the
  runtime user, stores a new hash, returns no secret material, and does not revoke existing
  sessions. Every financial-document, payment, and refund route requires its commerce permission
  through the same control. A document's payment histories are a child read of the document
  and require `FinancialDocumentRead`. `GET /payments/unapplied` uses `PaymentRecord` for
  its operational queue; `RefundRecord` alone grants neither read. There is no new
  payment-read permission. Listing and reading inquiries require
  `fionas.inquiries.read`. Inquiry submission, the inquiry form, and estimate previews require
  their own Fiona permissions. Ordinary Offerings reads, the service token endpoint, health,
  and readiness remain public.
- `FIONAS_TRUSTED_ORIGINS` names exact permitted browser origins. Login and unsafe
  cookie-authenticated methods require a matching `Origin` and fail closed if none is
  configured. Fiona's CSRF policy is separate from the reusable runtime session filter. A
  token-only request carries no cookie and is never rejected for lacking an `Origin`.
- Service credentials map to `ServiceId` and reuse this authorization path (see
  [Service authentication, customer operations, and login limiting](#service-authentication-customer-operations-and-login-limiting)).
  Caller/actor delegation, user impersonation by a BFF, OAuth/OIDC, refresh tokens, and
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
  Both use `AccessControl`
  (`Authorization` and `Authentication` OpenAPI tags respectively).
- **One permission catalog route.** `GET /admin/access/permissions` (administration
  capability, `commerce.role.read`) is Fiona's only catalog endpoint: the complete runtime +
  Fiona vocabulary with the `revision` that `/authorization/me` reports. Do not also mount
  `permissionCatalogHttpCapability`: in commerce 0.0.22 both capabilities use the same
  fixed `authorizationListPermissions` operationId, so one OpenAPI document cannot hold both
  (see the known upstream gaps). Catalog membership grants nothing; bootstrap never expands
  the Administrator role to cover newly listed permissions.

## Fiona's pricing

`FionasOfferingsEngine` is the first Fiona-specific commerce policy: given one exact,
immutable catalog revision, a structurally valid selection, and a `FionasOfferingsContext`,
it produces the commerce `LineItem`s of Fiona's estimate. The split:

```text
commerce-domain     Offering vocabulary, OfferingsEngine and its structural validation,
                    LineItem, Money
commerce-runtime    catalog persistence, current catalog operations (GetOfferingsCatalog),
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
8. **New pricing requires the current catalog revision.** `requireCurrentCatalogRevision`
   accepts requested revision, observed snapshot, and a caller-safe conflict message. Missing
   catalog/future revision yields `404 not_found`; older yields `409 CATALOG_REVISION_STALE`
   with `Cache-Control: no-store`; equality prices that observed immutable snapshot. Preview
   reads once through `GetOfferingsCatalog` and records nothing. `FionasPricing` reads once
   through transaction-bound `retrieveLatestVersion` and evaluates inside the write transaction.
   A later concurrent publication is allowed after observation. Public inquiry retains its
   public-category check first, then delegates with its existing public conflict message;
   staff/preview use “The offerings catalog changed; reload it and review the selections
   before pricing again”. Narrow HTTP mapping translates only `CatalogRevisionStale` conflicts;
   change-order 409 also documents financial-version conflicts. Staff hidden-category pricing
   remains valid at current revision. A stored revision records what was priced, not a promise
   of future catalog retrieval. Successful idempotent replay skips all pricing/catalog reads;
   rejection rolls back the claim and every write. Existing lines/history/transitions stand alone.
9. **One input model, one pricing path.** `FionasPricingInputs` (catalog revision,
   `OfferingSelections`, `FionasOfferingsContext`) is what previews, persisted estimates,
   change orders, pricing-source history, and an inquiry's requested inputs share; `FionasPricing` turns it into lines and
   reports engine rejections identically everywhere. Public-category eligibility is an additional inquiry-only check; the shared current-revision
   requirement applies to every new pricing request, never to recorded financial facts.
   It is strongly typed Fiona data: never a
   metadata map or an opaque context.
   Rejections use runtime `offeringsValidationFailed(message, violations)`, preserving
   Fiona's explanations and stable structural/policy codes. HTTP `validation_failed` may
   carry optional `violations: [{code: ...}]`; ordinary validation can omit it. Clients
   match codes directly and never parse the diagnostic message. OpenAPI uses runtime
   `ValidationErrorResponse` and `ValidationViolationResponse`, never Fiona copies.
10. **A preview is not an estimate document.** `POST /estimate-preview` requires `fionas.estimate-preview.create`,
    remains stateless, and creates no `FinancialDocument`. Priced inquiry submission
    (`fionas.inquiries.create`) internally materializes an Estimate without any staff
    financial permission; explicit financial routes keep their commerce permissions.

### Persisted pricing inputs

A `FionasPricingInputs` has no identity of its own, so it is stored in the row that owns it,
never in child rows: an inquiry's `pricing_inputs` (what the customer requested) and a pricing
source's `pricing_inputs` (what one exact staff-priced document version was priced from). The
representation is Fiona's own (`offering/PersistedPricingInputs.kt`), separate from the HTTP
DTOs and from commerce-runtime's internal snapshot JSON:

```json
{"catalogRevision": 20,
  "context": {"guestCount": 75, "guestCountIsMinimum": false, "durationMinutes": 120},
  "selections": [{"categoryKey": "soft-serve-flavor", "offeringKeys": ["soft-vanilla", "soft-horchata"]},
    {"categoryKey": "topping", "offeringKeys": []}]}
```

- Every property is required and non-null; array order is submitted order, and an explicitly
  empty block is an empty array. The duration is whole minutes, checked before writing
  (`Duration.ofMinutes(minutes) == duration`), never truncated. No lines, amounts, or totals.
- Restoring is strict: unknown, missing, or `null` properties, wrong JSON types (`"75"` for an
  integer), and values the domain types or the stored invariants reject (revision < 1, no
  guests, non-positive duration, invalid keys, a category twice, an offering twice in a block)
  fail with an `IllegalStateException` naming the owning inquiry or document version. Nothing
  is defaulted, coerced, or repaired, and `copy` restores its source before writing it again.
- Only `JdbiInquiryRepository` and `JdbiFinancialDocumentPricingRepository` encode or restore
  it (`ArchitectureSpec`). Domain types stay unaware of JSON; never add serialization
  annotations to them or reuse HTTP DTOs as the database contract. A shape change is a
  durable-data decision with its own migration.

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
   Association purpose describes the relationship: `INITIAL_ESTIMATE` is the canonical
   inquiry-generated estimate, `RELATED` covers other lineages. A partial unique index
   enforces at most one initial estimate per inquiry; staff creation remains `RELATED`.
3. **Materialized snapshots stand alone.** Offerings are inputs to constructing concrete
   lines, never financial-document identity or interpretation. Description, quantity, price,
   tax and currency on the immutable snapshot are authoritative. Never re-read an old
   offering, catalog revision or inquiry inputs to display or evolve its financial state.
   Versions evolve from the previous financial snapshot plus explicit changes; newly priced
   offerings may supply new lines, and custom lines need no offering identity.
   `fionas.financial_document_pricing` (one row per exact version holding one complete
   `pricing_inputs` value) remains optional legacy staff
   metadata in this scoped slice. Staff creation/change orders still write it and transitions
   copy it when present; it is never used to reconstruct old lines. Inquiry-generated
   initial estimates write none. Reads and transitions tolerate its absence. The staff HTTP
   change-order route still offers complete replacement from newly supplied inputs; explicit
   per-line/custom staff mutations and retiring legacy metadata require a follow-up slice.
   `PricedSnapshot` retains runtime `FinancialDocumentVersion`, including its authoritative
   PostgreSQL-owned `createdAt`. Current reads use `latestVersion(transaction, id)`;
   history uses `versionHistory(transaction, id)`, within existing REPEATABLE READ boundaries.
   Document mutation responses read back the persisted version through `describeLocked`.
   Every `FinancialDocumentResponse` carries its RFC 3339 `createdAt`. Never use Fiona's
   clock, lineage-association timestamp, or inferred ordering to manufacture this fact.
4. **The server prices; the browser never supplies financial values.** First-snapshot
   Estimate, Quote, and Invoice documents and change orders accept commercial inputs only,
   never lines, prices, tax, or totals, and price them with `FionasPricing` from exactly
   the current catalog snapshot matching the revision the request names. The existing estimate endpoint delegates to the
   same creation choreography with starting stage Estimate.
5. **Transitions never reprice**; canonical Invoice issuance is exclusively deposit-driven; they go through `issueQuote` and `issueInvoice`, and the
   runtime reports a transition the stage does not have (`IllegalTransition`). There is no
   estimate-to-invoice shortcut.
6. **The existing staff change-order route replaces the line set.** It removes every current line and adds every
   repriced line in the engine's order (`repricing`); it never matches lines by
   description or position, and keeps the stage. Inputs that reproduce the current charges
   (ignoring line ids) are rejected as no financial change. A change to non-financial
   details is not a change order. A generic line-source identity is future work, decided
   from real need.
7. **Every document-snapshot mutation names the version it acts on** (`expectedVersion`, an
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
13. **Lifecycle is projected, never duplicated.** The unique `INITIAL_ESTIMATE` lineage
    drives REQUESTED (Estimate), QUOTED (Quote), BOOKED (Invoice without served), SERVED
    (Invoice with served, without closed), CLOSED (Invoice with served and closed).
    RELATED lineages never drive it. No separate Booking aggregate or stored status exists.
    `RecordDocumentPayment`, `AllocatePayment`, and `SetDepositRequirement` use the shared
    transaction-taking `FionaFinancialDocuments.bookIfDepositSatisfied` policy after mutation.
    Only an active positive deposit satisfied according to runtime `FinancialLineageView`
    promotes the canonical Quote to Invoice, atomically with that mutation and optional
    legacy metadata copy. Failure rolls everything back. Manual `IssueInvoice` rejects
    canonical lineages and remains supported for RELATED lineages. Invoice never demotes
    after refunds; change orders preserve stage. Event date never advances lifecycle.
14. **Fiona owns fulfillment only.** V12 `inquiry_fulfillment` stores mandatory served
    provenance and optional complete closed provenance (timestamp and USER/SERVICE identity).
    Closed cannot exist without served. `ManageInquiryFulfillment.markServed` requires BOOKED;
    `close` requires SERVED and authoritative current Invoice balance exactly zero. Positive
    and negative balances reject close. Repeated/stale actions are illegal transitions,
    never provenance rewrites. No automatic close, unserve, reopen or post-close ledger ban.
    Impossible fulfillment before Invoice fails internally. The injected Clock uses microseconds.
    Staff detail reads the canonical projection in REPEATABLE READ with `InquiriesRead`.
    POST `/inquiries/{inquiryId}/served` (`markInquiryServed`) and `/close` (`closeInquiry`)
    use `InquiriesManage` (`fionas.inquiries.manage`) through existing USER/SERVICE and Origin
    rules. Fresh bootstrap Administrators receive it; existing roles need read-modify-replace grants.
15. **Booking writes observe predecessors.** Triggering mutations and fulfillment actions
    hold the existing association row lock in READ COMMITTED. A waiter must observe the
    previous committed allocation; REPEATABLE READ would pin an earlier snapshot before
    waiting. Under that lock documents, allocations and deposit terms are stable against
    Fiona writers. Runtime reads refund unwinds in one statement against those stable facts.
    No runtime-table SQL, nested transactions, JVM locks or automatic retry loops.

## Deposit requirements and bulk financial lineages

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
  never replace them with per-inquiry reads. This primitive has no HTTP contract yet.

- Commerce 0.0.22 owns `DepositTerms`, `DepositRequirement`, `DepositRequirementRevision`,
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
  reactivates with `DepositRequirementManage`, never document-create. One transaction uses
  `FionaFinancialDocuments.expectLatest` to lock ownership and check expectedDocumentVersion,
  applies the shared Quote/Invoice payment eligibility policy, and calls transaction-taking
  `activateDepositRequirement`. Null/absent expectedRequirementRevision expects no history;
  otherwise it must name the exact latest revision. Never reinterpret null as don't-care.
- Terms are a strict `type` union: FIXED has exact decimal amount and explicit ISO currency;
  PERCENTAGE has exact decimal percentage. Fiona applies existing money/minor-unit policy;
  upstream enforces positivity, matching currency, total bounds, `(0,100]`, HALF_UP resolution
  and positive resolved amount. No Double, percentage rounding, defaults, conversion or
  clamping. Original terms and frozen amounts survive later document changes.
- DELETE at that path takes only expectedRequirementRevision, locks ownership and calls
  transaction-taking withdrawal, with no document-version or stage check. Return the new
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
  No locks or per-lineage pricing/ownership/reconciliation/deposit loops. Results pair inquiry
  ownership with latest version/stage/total/currency, reconciliation including refundAllocations,
  current deposit union and runtime activity timestamps.
- Map FinancialLineageActivity as-is: latestDocumentVersionAt, latestDepositRequirementAt,
  latestPaymentAllocationAt, latestRefundAllocationAt, latestFinancialActivityAt. Allocations
  and unwinds use allocatedAt; receipt receivedAt and unrelated standalone refunds never count.
  No lastRelevantActivityAt, age, dashboard labels or workflow interpretation.
- All five are ContractRoutes with operationIds getFinancialDocumentDepositRequirement,
  getFinancialDocumentDepositRequirementHistory, setFinancialDocumentDepositRequirement,
  withdrawFinancialDocumentDepositRequirement, queryFinancialDocumentLineages. Same AccessControl,
  session OR SERVICE token, trusted Origin for unsafe cookies (query POST included), none for
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
| `io.github.castab.fionas.commerce` | `Main.kt`, `FionaApplication.kt` (composition root) |
| `...customer` | `Customer` and its values, `CustomerRepository`, `JdbiCustomerRepository` |
| `...inquiry` | `Inquiry` and its values/repositories; submission key, canonical fingerprint and transaction-bound submission repository; requested pricing inputs/history; lifecycle projection, fulfillment repository and explicit service/closeout; `CreateInquiry`, `GetInquiry`, `ListInquiries`, public eligibility/pricing and the customer form's `InquiryForm` values/`GetInquiryForm` adapter |
| `...offering` | `FionaOfferings.kt` (Fiona's catalog id and its binding to commerce-runtime's Offerings capability), Fiona's pricing (`FionasPricingInputs`, `FionasOfferingsContext` and its violations, `FionasPricingPolicy`, `FionasOfferingsEngine`, `FionasPricing`), the persisted pricing-inputs JSON (`PersistedPricingInputs.kt`), and the `PreviewEstimate` operation with its `EstimatePreview` result |
| `...financial` | Fiona's context for the runtime's financial ledger: the inquiry association and optional legacy pricing repositories, the read models, the transaction-taking `MaterializeInquiryFinancialDocument` core, and the `CreateInquiryFinancialDocument`, `CreateInquiryEstimate`, `CreateChangeOrder`, `IssueQuote`, `IssueInvoice`, `RecordPayment`, `AllocatePayment`, `RecordDocumentPayment`, `RecordRefund`, the deposit operations and `QueryFinancialLineages`, `GetFinancialDocument`, `GetFinancialDocumentHistory`, `ListInquiryFinancialDocuments`, and `ListFinancialDocumentPaymentHistories` operations |
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
webhooks, caller/actor delegation, OAuth/OIDC, refresh tokens, self-service password resets, event publishing,
outbox, NATS, persisted financial projections, CQRS, separate Booking aggregates,
cancellation, decline, archive, reopen, unserve, zero-deposit booking, dashboard queries,
a generic line-source identity, stored balances or payment statuses, tax,
travel fees, minimum orders, inventory, availability schedules/windows, catalog seeding or import, deposit
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

### Known upstream gaps (last audited at commerce 0.0.22)

The application consumes commerce-runtime 0.0.22, with matching commerce-domain transitively.
The application history schema gap is closed by commerce 0.0.15 (applications declare their
own migration schema). The payment read gap is closed by commerce 0.0.16:
`FinancialLedger.paymentHistory` and `paymentHistoriesForLineage` (each with a
`Transaction` overload) return whole `PaymentHistory` values, which Fiona serves per document
(see [Financial documents and payments](#financial-documents-and-payments), rule 11). The
unapplied discovery gap is closed by 0.0.17 (`unappliedPayments`), as are persisted financial
version creation timestamps, structured validation errors, and invalid Offerings schema
formats. Commerce 0.0.18 supplies the managed Offerings lifecycle, retired discovery,
lifetime key reservation, and expected-revision concurrency without a new migration.
Commerce 0.0.20 stores aggregate-owned snapshot values in their snapshot rows, binds every
`AccessControl` to one `AuthorizationDirectory` (`AccessControl(authentication,
context.authorization)`), requires a `PermissionGroup` on each `PermissionDefinition` (Fiona's
are `fionas.credentials`, `fionas.inquiries`, and `fionas.pricing`), and adds runtime service
credentials and service access tokens. The authorization administration capability Fiona
mounts at `/admin/access` includes `/services/{serviceId}/credentials` (list with
`PrincipalRead`; create and revoke with the runtime's `RuntimePermissions.ServiceCredentialManage`,
which the bootstrap Administrator is granted). Fiona mounts the runtime's service token endpoint
and accepts its tokens through the generalized `authentication(...)`; see
[Service authentication, customer operations, and login limiting](#service-authentication-customer-operations-and-login-limiting).
The service token response states `expiresIn` as a `Long`, so `KotlinxSchemas` renders `Long`
as an `int64` integer.
Audit source: tagged `v0.0.22` (`4d1d8b5dc047f82338e82d86ef088ae11198b35a`),
including its root architecture contract, runtime README, capability/configuration/migration
implementations, error and health routes, financial ledger, and domain offering/financial types.
Commerce 0.0.21 additionally supplies current-only catalog storage, lifetime key reservation,
retired last representations, ordered atomic offering batches, and optional option text.
Commerce 0.0.22 adds runtime V13, versioned deposits and coherent bulk financial lineage facts.
The gaps below were rechecked and remain open; they do not justify unrelated Fiona workarounds.

- **The two catalog capabilities share one operationId.** `authorizationAdministrationHttpCapability`
  includes the same internal catalog route as `permissionCatalogHttpCapability`, with the
  fixed operationId `authorizationListPermissions`, so a host can mount only one of them
  per OpenAPI document. Fiona mounts the administration one. Never wrap, clone, or rebuild a
  runtime route to rename it here. Minimal upstream fix: a distinct operationId for the
  administration catalog route, or a host-chosen operationId prefix as Offerings binds have.

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
- **Runtime capability routes carry no security metadata.** The Offerings, authorization
  administration, and current-principal routes enforce Fiona's `AccessControl`, but commerce-runtime
  0.0.22 gives the host no way to declare their OpenAPI security, and its public Offerings reads
  are not marked `NoSecurity`, so a contract-wide default `security` would mislabel them as
  authenticated. Fiona documents its own routes (`staffSession` OR `serviceAccessToken`) and never
  wraps or clones runtime routes to change their metadata; `OpenApiDocumentSpec` pins the gap.
  Minimal upstream API: an optional OpenAPI `Security` the host supplies once, for example on
  `AccessControl` or each capability's binding, that every protected capability route declares,
  with public routes declaring `NoSecurity` explicitly.
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
  `InquiryMaterializationSpec` (exact lines from one evaluation, customer reuse,
  rollback inside ledger and during/after association writes, canonical uniqueness, catalog
  independence, custom ledger changes and transitions without pricing metadata),
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
  requested pricing inputs pinned, ordinary pricing errors preserved, and handed to an
  estimate unchanged), `PublicInquirySubmissionSpec` (stale zero-write conflict, fresh
  acceptance, every advertised option/duration, hidden categories and disguised offerings,
  retirement, domain failures, missing/null pricing inputs and an uninitialized catalog with
  zero writes, and unrestricted staff
  current hidden-category creation and stale rejection), `InquiryMaterializationSpec` (one evaluation, publication
  after validation, cross-schema rollback and financial evolution after test-only catalog
  removal), `InquiryIdempotencySpec` (forced/observed PostgreSQL same/different key contention,
  rollback takeover, incomplete-claim commit rejection, lost-response recovery and no replay
  catalog/pricing), `InquiryIdempotencyRoutesSpec` (full-handler replay, post-publication ordering,
  mismatch, key/header validation, failed-key reuse and authentication),
  `InquiryRequestFingerprintSpec` (all intent fields, ordered selections, normalization and
  opaque key validation), HTTP tests through the complete handler, schema tests,
  `MigrationLifecycleSpec` (consumer-level migration contract only; the runtime's suite owns
  the lifecycle internals), `OfferingsCatalogSpec` (Fiona's catalog through the complete
  handler: initialization, ordered current reads, price forms/option text, atomic offering batches and category
  lifecycle, key reservation, retired last representations, text clearing, and stale-client rejection;
  retired-discovery authorization uses the existing live-permission spec; integration only, the
  runtime's suite owns the capability), `FionasOfferingsEngineSpec` (Fiona's pricing,
  purely, with exact `BigDecimal` amounts), `EstimatePreviewRoutesSpec` (the preview through
  the complete handler over a catalog built with the Offerings API, including current/stale/future revisions and refreshed success), `ServicePrincipalAuthSpec` (SERVICE principals through the real application:
  credential → `/auth/service/token` → token; `401`/`403`/authorized for each customer route;
  independent, live permissions on one token; the retired UI key authenticates nothing; session
  precedence; Origin only for cookies; `/auth/me` safe for services; session-only logout: a SERVICE
  token is `403` and stays valid, a valid session is revoked, a stale cookie is still cleared;
  administrator provisioning and rotation through `/admin/access`), `OpenApiDocumentSpec` (the
  document's paths, operationIds, statuses, schemas, and security: `staffSession` OR
  `serviceAccessToken` on every protected Fiona route, `staffSession` OR anonymous on logout, nothing on
  login and the token endpoint, and the runtime routes' upstream metadata gap pinned), `OpenApiRoutesSpec` (`/openapi.json` and `/docs` through the
  complete handler, and parity with the generator), `GenerateOpenApiSpec` (the build
  artifact, byte-deterministic), `ApplicationVersionSpec` (the reported version and the
  OpenAPI `info.version` are the Gradle project version the build was given, so a release's
  `-Pversion` is proven to reach both), and `ArchitectureSpec`.
- Run `./gradlew ktlintCheck test build` before considering work complete.
- Node.js 20 or newer must be on PATH for `ReplaceCatalogSpec`, which runs the actual
  catalog replacement/payment scripts against a started test runtime and throwaway PostgreSQL. CI provisions
  Node.js 24 without npm packages or caching. This spec covers the expanded catalog, five
  CHIPS questions, exact-four validation, unavailable choices, zero-write rejections, and
  repeatable catalog replacement with only catalog
  permissions, edited definitions and mixed new/restored ordering, omitted-entry retirement,
  optional-property clearing, prompted stdin credentials and zero-write input failures,
  and recovery from a partially written catalog. Catalog replacement performs
  no customer or financial operation; baseline acceptance fixtures remain independent.

## Documentation synchronization

Changes to endpoints, the API contract, configuration, migrations, packages, the
customer-matching policy, the Offerings binding, the financial-document or payment policy,
the bootstrap grants, the version convention, or the upstream version must update
`README.md` and this file in the same change.
