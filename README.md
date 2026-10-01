# fionas-commerce

The commerce backend of Fiona's Ice Cream and its catering business: a concrete Kotlin/JVM
application built on the reusable
[`commerce-runtime`](https://github.com/castab/commerce-domain/tree/v0.0.20/runtime) and
[`commerce-domain`](https://github.com/castab/commerce-domain/tree/v0.0.20/domain)
artifacts.

> **Status: early slices.** The application implements inquiries (a prospective customer
> submits an inquiry with the ice cream service they configured, and staff list and read
> them), serves Fiona's Offerings catalog through
> commerce-runtime's reusable Offerings capability, and prices selections from it with
> Fiona's own pricing (`POST /estimate-preview`, which records nothing). Every accepted
> inquiry is priced and atomically materializes its initial Estimate. Staff turn an
> inquiry into persisted financial documents (an estimate, change orders, a quote, an
> invoice) and record payments against them; the documents, payments, and settlement are
> commerce-runtime's financial ledger, and Fiona records which inquiry owns each document and
> optional legacy staff pricing metadata. Financial snapshots contain self-contained concrete
> lines and need no offering/catalog data. There are no bookings or payment-provider
> integrations yet. Staff authentication protects administration and every financial route.

## How it fits together

```text
commerce-domain       reusable commerce vocabulary and invariants
      │                (financial documents, payments, booking lifecycle, principals)
      ▼
commerce-runtime      reusable runtime: PostgreSQL/HikariCP, JDBI, Flyway, Transactor,
      │                http4k on Jetty, configuration, error contract, /health, /ready,
      │                Offerings snapshots (commerce.offering*) and the Offerings
      │                catalog capability: operations, HTTP contract routes, DTOs, schemas;
      │                the financial ledger: document snapshots, payments, allocations,
      │                reconciliation (commerce.financial_document*, commerce.payment*)
      ▼
fionas-commerce       Fiona's application: customers, inquiries, Fiona's HTTP API and
                       tables, Fiona's catalog id and where its catalog is served,
                       Fiona's pricing (FionasOfferingsEngine) and estimate previews,
                       inquiry → financial-document ownership and canonical initial estimate,
                       optional legacy staff pricing metadata, change-order and payment policy,
                       application.conf, Logback, main(), deployable jar
```

`fionas-commerce` depends on `io.github.castab:commerce-runtime:0.0.20`, which brings
`commerce-domain:0.0.20` with it. It contributes its migration schema and locations, permissions, and routes to the runtime
through `ApplicationContributions`, and every write goes through the runtime's shared
`Transactor`:

```text
HTTP request
   │
   ▼
commerce-runtime error handling ({"code","message"}, optional validation violations)
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
OfferingsSnapshotRepository (runtime)  ──────  commerce.offerings_snapshots
                                               (each row holds its revision's catalog)
```

The rules behind this structure are in [`AGENTS.md`](AGENTS.md).

## Endpoints

**Inquiry API**, implemented by Fiona:

| Endpoint | Permission | Behavior |
|---|---|---|
| `POST /inquiries` | `fionas.inquiries.create` | Requires `Idempotency-Key`. Requires `pricingInputs`. Records customer intent, prices it exactly once, and atomically materializes exactly one canonical initial Estimate v1. `201` with the receipt (`id`, `createdAt`) and `Location`; identical successful retries return the same receipt. Never exposes the stored customer. |
| `GET /inquiry-form` | `fionas.inquiry-form.read` | Explicit public questions, input constraints, rendering hints, and advisory pricing facts from one catalog revision. `404` before catalog initialization; `500` for incompatible public pricing configuration. |
| `GET /inquiries` | `fionas.inquiries.read` | Staff inbox: inquiries newest first, `limit` (1–100, default 25) per page, continued with the opaque `cursor` a page returns as `nextCursor`. |
| `GET /inquiries/{inquiryId}` | `fionas.inquiries.read` | The persisted inquiry, its customer, and its requested pricing inputs. `404` when unknown, `400` when the id is not a UUID. |

The server-side web frontend (`fionas-web`, or a future BFF) calls `GET /inquiry-form`,
`POST /estimate-preview`, and `POST /inquiries` as a SERVICE principal, with
`Authorization: Bearer <service access token>` obtained from `POST /auth/service/token` (see
[Service authentication](#service-authentication)). Each route requires its own Fiona
permission through the same `AccessControl` as every other protected route: no valid
authentication is `401 unauthenticated`, and an authenticated principal without the
permission is `403 forbidden`. There is no static API key and no implicit frontend trust; the
service's role grants are its authorization, resolved live on every request. A staff session
holding the permission is accepted as well.

Authorized `GET /inquiry-form` responses carry
`Cache-Control: private, max-age=60, must-revalidate` (one minute fresh, then revalidate).
Failures carry `Cache-Control: no-store`. This retains private caching while reducing
stale-form failures under the strict current-revision submission policy: the former
15-minute freshness and one-hour stale-while-revalidate allowance could repeatedly serve
unsubmittable forms after a publication. A form already open can still become stale.
On `CATALOG_REVISION_STALE`, the future SvelteKit UI must invalidate/bypass its cached form,
fetch the current inquiry form, and ask the customer to review updated selections/pricing
before resubmission. It must never silently migrate selections or automatically resubmit.
That frontend behavior is not implemented in this backend repository.

**Offerings Catalog API**, exposed by Fiona and implemented by commerce-runtime's Offerings
capability (see [Offerings catalog](#offerings-catalog)):

| Endpoint | Behavior |
|---|---|
| `GET /offering-catalog` | The latest revision of the whole catalog: categories in order, each with its offerings in order. |
| `POST /offering-catalog` | Initializes the empty catalog as revision 1. `409` if it exists. |
| `GET /offering-catalog/revisions/{revision}` | Exactly the catalog as that revision recorded it. |
| `GET /offering-catalog/categories` | The latest revision's categories, in order. |
| `POST /offering-catalog/categories` | Adds a category in a successor revision; requires body `expectedRevision`. |
| `GET /offering-catalog/categories/{categoryKey}` | One category of the latest revision. |
| `PUT /offering-catalog/categories/{categoryKey}` | Replaces category properties in a successor revision; requires body `expectedRevision`. |
| `DELETE /offering-catalog/categories/{categoryKey}` | Retires an empty category; requires query `expectedRevision`, returns the successor revision. |
| `POST /offering-catalog/categories/{categoryKey}/restore` | Restores the same category identity with supplied properties; requires body `expectedRevision`. |
| `GET /offering-catalog/categories/{categoryKey}/offerings` | That category and its offerings, in order. |
| `GET /offering-catalog/offerings` | The latest revision's offerings, in order. |
| `POST /offering-catalog/offerings` | Adds an offering to an active category in a successor revision; requires body `expectedRevision`. |
| `GET /offering-catalog/offerings/{offeringKey}` | One offering of the latest revision. |
| `PUT /offering-catalog/offerings/{offeringKey}` | Replaces offering properties in a successor revision; requires body `expectedRevision`. |
| `DELETE /offering-catalog/offerings/{offeringKey}` | Retires an offering; requires query `expectedRevision`, returns the successor revision. |
| `POST /offering-catalog/offerings/{offeringKey}/restore` | Restores the same offering identity with supplied properties; requires body `expectedRevision`. |
| `GET /offering-catalog/retired/offerings` | Administrative discovery: current revision and each retired offering's last representation and `lastSeenRevision`. |
| `GET /offering-catalog/retired/categories` | Administrative discovery: current revision and each retired category's last representation and `lastSeenRevision`. |

**Estimate preview API**, implemented by Fiona (see [Estimate preview](#estimate-preview)):

| Endpoint | Behavior |
|---|---|
| `POST /estimate-preview` | Requires `fionas.estimate-preview.create`. Prices a selection from an exact catalog revision for a guest count and service duration. `200` with the lines and totals; records nothing. |

**Financial documents and payments API**, implemented by Fiona on commerce-runtime's
financial ledger (see [Financial documents and payments](#financial-documents-and-payments)).
Every route needs a staff session:

| Endpoint | Permission | Behavior |
|---|---|---|
| `POST /inquiries/{inquiryId}/estimates` | `commerce.financial-document.create` | Prices commercial inputs on the server and persists them as version 1 of a new estimate the inquiry owns. `201` with the document and `Location: /financial-documents/{documentId}`. |
| `POST /inquiries/{inquiryId}/financial-documents` | `commerce.financial-document.create` | Prices commercial inputs and creates a new inquiry-owned Estimate, Quote, or Invoice at version 1, with no predecessor. `201` and `Location: /financial-documents/{documentId}`. |
| `GET /inquiries/{inquiryId}/financial-documents` | `commerce.financial-document.read` | The inquiry's documents, each at its latest version with its current settlement. |
| `GET /financial-documents/{documentId}` | `commerce.financial-document.read` | The latest version, optional legacy staff pricing metadata, and current settlement. |
| `GET /financial-documents/{documentId}/history` | `commerce.financial-document.read` | Every version, oldest first, with optional legacy staff pricing metadata. |
| `POST /financial-documents/{documentId}/quote` | `commerce.financial-document.create` | Issues the latest estimate as a quote, unchanged. |
| `POST /financial-documents/{documentId}/invoice` | `commerce.financial-document.create` | Issues the latest quote as an invoice, unchanged. |
| `POST /financial-documents/{documentId}/change-orders` | `commerce.financial-document.create` | Reprices the latest version from revised inputs, in its current stage. |
| `POST /financial-documents/{documentId}/payments` | `commerce.payment.record` | Records a payment and applies all of it to the latest version, a quote or an invoice. `201`. |
| `GET /financial-documents/{documentId}/payments` | `commerce.financial-document.read` | Every payment ever allocated to any version of the document, each with its complete history: allocations (to any document), refunds, refund allocations, and derived reconciliation. `[]` when it has none. |
| `POST /payments` | `commerce.payment.record` | Records money received without assigning it to a document. `201` with the payment fact. |
| `GET /payments/unapplied` | `commerce.payment.record` | Complete payment histories with positive derived `reconciliation.unallocated`, including standalone receipts; ordered by `receivedAt`, then `paymentId`. |
| `POST /payments/{paymentId}/allocations` | `commerce.payment.record` | Allocates some or all of an existing payment to an exact, currently latest Quote or Invoice version. `201` with the allocation and reconciliation. |
| `POST /payments/{paymentId}/refunds` | `commerce.refund.record` | Refunds part or all of a payment, unwinding the allocations the request names. `201` with the refund, its unwinds, and the payment's reconciliation. |

**Staff authentication API**, implemented by Fiona (see [Staff authentication](#staff-authentication)):

| Endpoint | Behavior |
|---|---|
| `POST /auth/login` | Anonymous; verifies a staff password and sets Fiona's secure session cookie. Requires a trusted browser origin. Per-IP burst of five; one attempt refills every five minutes; `429` with `Retry-After` when exhausted. |
| `POST /auth/logout` | Revokes the staff browser session and clears the cookie, including on repeated logout or a stale cookie. Session-only: a request authenticated only by a service access token is `403`, and no token is revoked. |
| `GET /auth/me` | Returns the active human staff profile, current role keys, and sorted effective live `permissions`; requires no role-administration permission. |
| `PUT /admin/users/{userId}/credentials/password` | Sets a runtime user's Fiona password; requires `fionas.credentials.manage` and trusted Origin. |
| `POST /auth/service/token` | commerce-runtime's endpoint, mounted by Fiona: public; a SERVICE principal exchanges `{"serviceId","secret"}` for a short-lived bearer access token. `401` for every authentication failure, `400` for a malformed body. `Cache-Control: no-store`. |

The runtime administration capability is mounted at `/admin/access`: it exposes users,
services, service credentials, roles, permission catalog, role grants, and principal role
assignments in the same OpenAPI document. Its routes use Fiona's `AccessControl`
(staff session cookie, or a service access token) and trusted Origin policy for cookies.

**Authorization reads**, two of them commerce-runtime's own contract routes:

| Endpoint | Requires | Answers |
|---|---|---|
| `GET /auth/me` | An active human staff session (USER only; a SERVICE is `403`) | Fiona's staff profile (id, username, names), role keys, and live effective permissions. |
| `GET /authorization/me` | Any authenticated principal, USER or SERVICE | The runtime's description of the request's principal (`kind`, `id`, `displayName`), its sorted live effective permissions, and `permissionCatalogRevision`. |
| `GET /admin/access/permissions` | `commerce.role.read` | The complete permission catalog, runtime and Fiona permissions together, ordered by key, with its `revision` (`sha256:…`). |

`/auth/me` answers "who is the current Fiona staff user?"; `/authorization/me` answers
"which principal authenticated this backend request?". Both use the same `AccessControl`
(staff session cookie, or a service access token). A backend-for-frontend calling
`/authorization/me` with its own service access token receives its own SERVICE identity and
permissions, never the browser user's: there is no delegation. A client can compare
`permissionCatalogRevision` with the catalog's `revision` to detect a changed vocabulary.
Effective permissions only guide what a UI shows; every operation still enforces its own.

`/admin/access/permissions` is Fiona's only catalog route. Commerce-runtime 0.0.20's
standalone `permissionCatalogHttpCapability` reuses the administration route's fixed
`authorizationListPermissions` operationId, so only one of the two can be mounted in a
single OpenAPI document; Fiona keeps the administration one.

**Runtime infrastructure**, served by commerce-runtime and not in the OpenAPI document:

| Endpoint | Behavior |
|---|---|
| `GET /health` | Liveness: `200 {"status":"ok"}`. |
| `GET /ready` | Readiness: `200 {"status":"ready"}` when the database is reachable, `503` otherwise. |

**API documentation**:

| Endpoint | Behavior |
|---|---|
| `GET /openapi.json` | The OpenAPI 3.1 document of every API above except the runtime infrastructure, rendered from the running contract. |
| `GET /docs` | Swagger UI for that document (redirects to `/docs/index.html`). |

A method an API path does not declare is `405` with an empty body; in particular, no
catalog route replaces or deletes anything.

```bash
curl -i -X POST localhost:8080/inquiries -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <service access token>' \
  -d '{"name":"Jane Doe","email":"jane@example.com","zipCode":"92626","message":"Ice cream for a birthday.",
       "eventDate":"2026-12-05","eventType":"BIRTHDAY",
       "pricingInputs":{"catalogRevision":12,"guestCount":75,"durationMinutes":120,
         "selections":[{"category":"soft-serve-flavor","offerings":["vanilla","horchata"]},
                       {"category":"topping","offerings":["sprinkles","oreos","strawberries","brownies"]},
                       {"category":"cone-option","offerings":["waffle-cone"]}]}}'
```

```json
{
  "id": "c755f7cd-1e28-4c75-a85f-d066ede7387d",
  "createdAt": "2026-09-26T21:19:39.321012Z"
}
```

`name` (at most 200 characters), `email`, `zipCode`, `eventDate`, and `eventType` are required;
`message` is optional (at most
4000 characters) and stays free-form text. Values are trimmed, and the email is lowercased.
If a customer already has that email, the inquiry is attached to that customer, whose
stored name a later inquiry does not change (see
[Customer matching](AGENTS.md#customer-matching-current-deliberately-simple-policy)). The
public response is a receipt of the new inquiry only: it never contains the stored
customer's id, name, or email, so submitting someone else's address reveals nothing. A
confirmation page renders what the customer just submitted.

Every Fiona inquiry is a request for configured ice cream service. A valid inquiry includes
a current catalog revision, service quantity/duration (`guestCount`, `durationMinutes`), and
the required offering selections; every accepted inquiry is priced exactly once and
atomically materializes Estimate v1. There is no contact-only inquiry: an omitted or `null`
`pricingInputs` is `400 malformed_request` and records nothing.

`pricingInputs` is required and has exactly the shape `POST /estimate-preview` and
`POST /inquiries/{inquiryId}/estimates` take (`catalogRevision`, `guestCount`,
`guestCountIsMinimum`, `durationMinutes`, `selections`). The revision describes the form
the customer used; it is not permission to request historical pricing. `PublicInquiryPricing`
requires it to equal the latest revision observed in the submission's READ COMMITTED
transaction, before business writes (after the atomic idempotency claim). An older revision fails with HTTP 409 and the runtime
envelope `{"code":"CATALOG_REVISION_STALE","message":"…"}`; the message is diagnostic,
and the code tells the frontend to refresh/review. A revision beyond the observed latest
(or an uninitialized catalog) remains `404 not_found`. There is no catalog locking: a publication
after that observation does not invalidate an accepted submission, which prices the same
immutable observed snapshot. No selections are silently reinterpreted or repriced.

GET `/inquiry-form` and POST `/inquiries` share `publicOfferingQuestions`, Fiona's ordered
code-owned category definition. Hidden/internal categories fail `422 validation_failed`
with violation `PUBLIC_INQUIRY_CATEGORY_NOT_ALLOWED`, even if catalog-valid. Enabled offerings
in those categories are advertised; disabled offerings remain in the authoritative catalog
but are omitted from the public form. `PublicInquiryPricing` still supplies the full immutable
snapshot to `FionasPricing`. Runtime structural validation rejects tampered current selections
with `OFFERING_DISABLED` or `OFFERING_UNAVAILABLE` (disabled takes precedence when both apply),
alongside membership, retirement and selection limits. Fiona checks guest count and duration.
Ordinary catalog/pricing failures retain `404`/`422`.
All rejections commit nothing, including any key claim. A new command prices exactly once and uses those exact concrete lines to
materialize Estimate v1 through the runtime ledger. One READ COMMITTED transaction includes
idempotency claim, customer lookup/creation, inquiry insert, requested pricing history, ledger snapshot/lines,
and the canonical initial-estimate association. Any failure rolls everything back.
The inputs are stored only as customer intent (the inquiry row's own `pricing_inputs` jsonb,
see [Persisted pricing inputs](#persisted-pricing-inputs)), pinned to the submitted revision; amounts are never accepted.
No catalog/offering provenance is written for the financial snapshot. Every inquiry reads the
current catalog, so none can be accepted before the catalog is initialized. The column is
`NOT NULL` (`V11`), so one insert writes the complete inquiry and none can exist without its
inputs. Public restrictions apply only
to inquiry submission; staff creation/evolution continues independently of the public form,
including historical catalog inputs and hidden categories. Offerings are not dependencies
of a materialized financial document. Submission requires `fionas.inquiries.create`, never a staff
financial-create permission; staff routes retain their permissions. Staff read requested inputs back as `pricingInputs` on
`GET /inquiries/{inquiryId}` and can submit that object unchanged to
`POST /inquiries/{inquiryId}/estimates`. Street address and further event details remain
customer-authored `message` text; event date and type are stored separately on the inquiry.

`POST /inquiries` requires exactly one `Idempotency-Key` header, an opaque, case-sensitive
token of 1–128 ASCII letters, digits, underscores or hyphens. UUIDs are supported; no
trimming or business meaning is assigned. The key is not a credential and need not be
secret. Missing, blank, repeated, oversized or malformed keys return `400 malformed_request`.
Authentication and request-shape validation precede the application command and write nothing.
No staff endpoint or other public endpoint requires this header. This is an intentional
breaking change for inquiry clients: they must supply a stable logical submission identity.

Fiona's `V9` adds `fionas.inquiry_submissions`: a unique key, SHA-256 request fingerprint,
unique resulting inquiry id, and creation time. `INSERT ... ON CONFLICT (idempotency_key)
DO NOTHING` claims the key before business validation, waiting for any competing transaction.
The resulting inquiry id is reserved immediately. Its non-null, deferred foreign key to
`fionas.inquiries` allows claim-before-inquiry but prevents an incomplete claim from
committing. No separate finalization transaction is needed. A rollback removes the claim
and all inquiry/Estimate state; a waiter either observes the completed owner or claims the
key after rollback. There is no duplicate-key exception recovery inside an aborted transaction.
Keys have no expiration or cleanup in this release, and prior inquiries are not backfilled.

The fingerprint is a versioned, deterministic binary encoding of canonical application
values, hashed with SHA-256: normalized name/email/optional message, ZIP, event date/type,
revision, guests/minimum flag, exact duration, and all category/offering
identities in submitted order. Length-prefixed UTF-16 code units, the optional message's presence bit, fixed-width integers
and list lengths avoid ambiguous concatenation. The byte that once marked pricing presence is
now a constant v1 marker, so every committed (priced) fingerprint replays unchanged. Raw JSON, the key, generated ids,
timestamps, current catalog state and derived prices are excluded. Property order,
insignificant JSON whitespace, normalized text and omitted default false remain equivalent;
category/offering order remains meaningful. Changing the encoding requires a deliberate
compatibility decision because successful keys are durable.

A committed same-key/same-intent replay returns the original `201 Created`, identical
`id`/`createdAt` body and `Location`. Replay detection precedes public selection validation,
catalog reads, authoritative pricing and materialization, including after catalog publication.
Same key with changed canonical intent returns `409 IDEMPOTENCY_KEY_REUSED` in the runtime
`ErrorResponse` envelope, with `Cache-Control: no-store`; no previous payload or fingerprint
is exposed. `CATALOG_REVISION_STALE` remains distinct and applies to new, uncommitted
commands. Failed attempts do not consume keys, so a corrected same-key retry may succeed.
The guarantee is one key plus one semantic request yields one committed inquiry result;
it does not promise application code literally executes once. Different keys are distinct commands,
even with identical payloads/email; the existing concurrent new-email conflict remains unchanged.

The future SvelteKit implementation must create one non-secret submission token per logical
form submission and preserve it across timeouts, lost responses, retries and double delivery.
Two browser-to-SvelteKit requests for the same visible submission must resolve to the SAME
key. Independently generating a server UUID inside each backend attempt is insufficient;
the UI may carry a token with the rendered form or otherwise retain it across attempts.
`IDEMPOTENCY_KEY_REUSED` indicates accidental reuse for changed intent. A stale failure
requires refresh/review of an uncommitted command; afterward its rolled-back key can be
reused, or the UI may deliberately choose a new one. No frontend changes are included here.

Staff discover inquiries with `GET /inquiries`, newest first by creation time, ties broken
by id. `limit` bounds a page (1–100, default 25; outside that range is `422`, not an
integer `400`). When more inquiries follow, the page carries `nextCursor`; pass it back
unchanged as `cursor`. A cursor is opaque (a cursor the API did not issue is `400`), and a
page continues strictly after the previous page's last inquiry, so inquiries recorded at the
same instant are neither repeated nor skipped and new inquiries never shift later pages.
There is no search, filtering, or offset pagination.

Errors use commerce-runtime's contract, `{"code": "...", "message": "..."}`:
`malformed_request` (400), `unauthenticated` (401), `forbidden` (403), `not_found` (404),
`conflict` (409), `illegal_transition` (409), `validation_failed` (422),
`invariant_violated` (422), `internal_failure` (500, never describing the cause).
Public inquiry submission additionally uses `CATALOG_REVISION_STALE` and `IDEMPOTENCY_KEY_REUSED` (409) in that same
envelope, instructing the UI to fetch a fresh form and ask the customer to review it.
Validation failures may also carry optional `violations`, each with a stable string `code`.
Ordinary value-validation failures can omit that list. Clients use codes to identify
failures and present `message` as diagnostic text; they never parse it for codes.

## Customer inquiry form

`GET /inquiry-form` (`getInquiryForm`) returns Fiona's code-owned question definition
resolved against one current Offerings snapshot. The response includes `definitionVersion`
(currently 7), Fiona's stable `catalogId`, `catalogRevision`, ordered `sections`, and
advisory `pricingPreview` facts.
Definition version identifies the code-owned questions and bindings; catalog edits change
the catalog revision independently. Sections are **Contact information**, **Event details**, **Build your
ice cream service**, and **Additional information**. Within each section, `fields` are in
display order. Each field has a stable `key`, `label`, optional `description`,
`submissionPointer`, `required`, semantic `input`, and a separate `presentation.control`.

The input is discriminated by `type`:

| Type | Answer and metadata |
|---|---|
| `TEXT` | String; `minLength`, `maxLength`, and optional `pattern` after trimming. Name must also be nonblank; blank optional text counts as absent. |
| `EMAIL` | String; `maxLength` after trimming and lowercasing. Existing shallow email validation still applies. |
| `INTEGER` | A signed 32-bit integer at least `minimum`; used for guest count. |
| `BOOLEAN` | Boolean with a `defaultValue`. |
| `INTEGER_CHOICE` | One integer `value` from the labeled `options`, derived from the pricing policy's allowed durations. |
| `DATE` | Calendar date string in `YYYY-MM-DD`; `format` is `date`, without a time or time zone. |
| `STRING_CHOICE` | One string `value` from the labeled `options`; used for event type. |
| `OFFERING_CHOICE` | Selected offering keys from `options`; category-owned `minSelections` and optional `maxSelections` (absent means unbounded). |

Hints are `TEXT`, `TEXTAREA`, `NUMBER`, `CHECKBOX`, `SELECT`, `CARDS`, `DATE`, and
`CHECKBOXES`. They express preferences; clients retain control of components,
accessibility, styling, and layout. Multiline notes are a string with a textarea hint.
No Svelte component names appear in the contract.

Contact information includes a required event **ZIP code**, bound to `/zipCode`.
It accepts five ASCII digits after trimming and preserves leading zeroes. Missing, null,
or non-string values receive `400`; blank or invalid strings receive `422`.
`POST /inquiries` records it in the non-null `fionas.inquiries.zip_code` text column;
staff detail and inbox reads include it. It is never stored on the customer. The ZIP supports staff review
of travel needs; operating-area rules and automatic surcharges remain undecided.
The guest-count question remains because current pricing depends on the count, and
reassures customers that an approximate count is fine during quoting. The minimum-count
checkbox is omitted. The existing request's optional `guestCountIsMinimum` still defaults
to false for clients that omit it.

**Event details** contains required `eventDate` and `eventType`, bound to `/eventDate`
and `/eventType`. The date uses a DATE control hint for a date picker; submit a real calendar
date in `YYYY-MM-DD` (years 0001–9999), without a timestamp or time zone. The event-type
dropdown uses STRING_CHOICE with a SELECT hint, in this order:

| Label | Submitted value |
|---|---|
| Birthday | `BIRTHDAY` |
| Wedding | `WEDDING` |
| Corporate | `CORPORATE` |
| School event | `SCHOOL_EVENT` |
| Neighborhood event | `NEIGHBORHOOD_EVENT` |
| Other | `OTHER` |

Missing, null, or wrongly typed event fields and unknown event types receive `400`;
invalid calendar-date strings receive `422`. Both fields are non-null inquiry data,
returned in staff detail and inbox reads. No availability or future-date restriction is
applied, and neither field changes pricing.

Public offering questions explicitly reference Fiona's configured category keys: soft
serve, toppings from the pricing policy, then cones/cups. An absent or retired category
contributes no field; restoring it restores its configured question and position.
Other catalog categories do not become public questions. Catalog administration and
public question configuration are separate concerns.
All option identity, display names, descriptions, prices, option order, and selection
limits come from that same immutable snapshot. Options use commerce-runtime's existing
`OfferingDto` / `OfferingPriceDto` representation, including all three price forms.
Retired offerings disappear from the current form; no second catalog or form persistence
is introduced.

`publicInquiryOfferings` centralizes the public visibility rule for form options and
pricing-preview option facts: include exactly `selectionState == ENABLED`, preserving order.
Every returned option retains the runtime's required `selectionState` and `availability` fields:

| Selection state | Availability | Public inquiry behavior |
|---|---|---|
| `ENABLED` | `AVAILABLE` | Visible and selectable. |
| `ENABLED` | `UNAVAILABLE` | Visible with its name, description and price; client must render unselectable, with a check-back-later message. |
| `DISABLED` | Either value | Hidden from the public form and its pricing-preview contributions. |

The two facts are independent. Unavailability is distinct from retirement or deliberate
disabling. Enabled unavailable offerings retain advisory price metadata and duration
contributions. Changing either fact advances the catalog revision: old forms become stale,
so new submissions receive `409 CATALOG_REVISION_STALE` before selection-state validation.
Exact-revision `POST /estimate-preview` uses the full snapshot and rejects disabled/unavailable
selections structurally. A successful idempotent inquiry replay returns its original receipt
without revalidating later availability, disabling or retirement. Financial documents retain
their materialized lines and have no dependency on later offering state.

`pricingPreview` is a concrete Fiona projection of the same `FIONAS_PRICING_POLICY` and
snapshot used to resolve the questions. It supplies the currency, guest quantity
dimension, per-guest amount, topping category/included count/additional rate, and an
ordered entry for each allowed duration. Each duration entry includes its base service
amount and resolved flat contributions for public `PER_DURATION` offerings. Amounts are
exact decimal strings; there is no client-side duration conversion or rounding rule.
Local arithmetic adds base service, per-guest amount × guests, selected catalog charges
(`FIXED` once, `PER_QUANTITY` × guests, `PER_DURATION` from the duration entry; no price
means zero), and excess topping selections × guests × the additional rate. The topping
adjustment applies in addition to any selected offering's catalog price.

This local total is advisory. `POST /estimate-preview`, inquiry validation, and persisted
documents still use `FionasOfferingsEngine` as their authority; the browser submits only
pricing inputs, never trusted amounts or lines. All preview facts and options belong to
the response's single catalog revision. Captured facts remain usable for that revision
after later catalog edits.

Form resolution fails with the runtime's generic `500 internal_failure` if a public
offering has another currency, a non-guest quantity dimension, or an interval that cannot
price every advertised duration exactly. Diagnostic detail stays on the server. The
shared pricing-policy checks preserve the engine's finite decimal multipliers: a one-hour
price supports 90 minutes at 1.5 units, while a 45-minute interval fails for 120 minutes.
A required category outside the public definition or insufficient enabled visible options for a
public category's minimum also fails rather than publishing an unusable form. Enabled options
count toward that minimum even while unavailable: temporary unavailability alone never causes
a form `500`. Optional
hidden catalog offerings are not subjected to public-form compatibility checks.

The service section is required, matching the required `pricingInputs`: an inquiry cannot
be submitted without configuring the service. Only **Additional information** is optional;
field `required` applies when its section is used. The browser need not call
`POST /estimate-preview` first: `POST /inquiries` performs the authoritative pricing. For ordinary answers,
`submissionPointer` is a JSON Pointer into `CreateInquiryRequest`; for example
`/name` or `/pricingInputs/guestCount`. Each offering field contributes
`{"category": input.category, "offerings": selectedKeys}` to
`/pricingInputs/selections`, including empty selections for optional categories.
Copy the response's `catalogRevision` to `pricingInputs.catalogRevision`.
Preview and submit the same inputs; later catalog edits do not replace this revision.
Submit answers only, never the definition, presentation hints, or prices.

The form introduces no submission validation path: name, email, ZIP, event date/type, and message value
objects, commerce-domain's structural selection validation, and Fiona's pricing engine
remain authoritative. Clients that bypass form metadata still receive the existing
`400`/`404`/`422` responses.
Structured phone, street address, and event contacts remain deferred.
Administration of the definition, historical form-definition reads, a generic form DSL,
and a shared commerce UI abstraction are deliberately deferred. Duration-dependent option
availability, conditional pricing, taxes, discounts, travel, promotions, and staff overrides
are not added to this projection.

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

Catalog persistence/history is **append-only and immutable**; management mutations create
complete successor revisions (`r1`, `r2`, …). Editing replaces properties in the successor;
deleting retires the identity from that successor. Any earlier revision can be read back
exactly with `GET /offering-catalog/revisions/{revision}` and remains a valid pricing source.
Retired natural keys remain reserved for the catalog's lifetime: restore the same identity
with new properties rather than re-add its key. Update and restore take the key from the
path, never an editable body field; their bodies replace all properties, with omitted
optional properties reset to defaults.

> **Upgrading to commerce 0.0.20 (Fiona `V11`).** Runtime V9 moves financial lines and
> catalog contents into their snapshot rows, and Fiona's V11 moves each complete set of
> pricing inputs into its owning row (`inquiries.pricing_inputs`,
> `financial_document_pricing.pricing_inputs`). Neither converts existing data: both refuse
> a populated database. Recreate the disposable local database/volume
> (`docker compose down -v`), restart Fiona, then rerun `scripts/setup-local-commerce.mjs`.
> Fresh databases migrate normally through runtime V10 and Fiona V11. No API changes.

> **Upgrading to commerce 0.0.19.** Offering add/update/restore bodies now require both
> `selectionState` (`ENABLED`/`DISABLED`) and `availability` (`AVAILABLE`/`UNAVAILABLE`),
> with no HTTP defaults. Reads expose both fields. All four combinations are valid.
> Runtime V8 refuses to invent these values for existing offering rows. Recreate the
> disposable local database/volume (`docker compose down -v`), start a fresh database,
> restart Fiona, then rerun `scripts/setup-local-commerce.mjs`. Empty databases migrate
> normally through runtime V8 and Fiona's own stream. Fiona adds no migration or backfill.

> **Upgrading to commerce 0.0.18.** No runtime or Fiona migration is added. The existing
> binding now exposes runtime-owned update, retire, restore, and retired discovery. Every
> mutation after creation (including add) requires the caller's observed integer
> `expectedRevision`: in the JSON body for add/update/restore, in the query for DELETE.
> Thread the revision returned by each successful mutation; stale requests receive
> `409 conflict`, create no successor, and must reload. Fiona never injects a current
> revision or retries a stale mutation. Missing/malformed revisions are `400`, domain-invalid
> revisions are `422`. This required field breaks pre-0.0.18 add clients.

On a fresh database **the catalog does not exist**: startup applies migrations and nothing
else, so `GET /offering-catalog` is `404 not_found` until it is initialized, once:

```bash
curl -i -X POST localhost:8080/offering-catalog
```

That creates the empty revision 1 (a second initialization is `409 conflict`). Categories
and offerings are then appended, each in its own new revision:

```bash
curl -i -X POST localhost:8080/offering-catalog/categories -H 'Content-Type: application/json' \
  -d '{"expectedRevision":1,"key":"soft-serve-flavor","displayName":"Soft Serve","minimumSelections":2,"maximumSelections":2}'
```

```bash
curl -i -X POST localhost:8080/offering-catalog/offerings -H 'Content-Type: application/json' \
  -d '{"expectedRevision":2,"key":"vanilla","category":"soft-serve-flavor","displayName":"Vanilla","description":"Classic vanilla soft serve","selectionState":"ENABLED","availability":"AVAILABLE"}'
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
        { "key": "vanilla", "category": "soft-serve-flavor", "displayName": "Vanilla", "description": "Classic vanilla soft serve", "selectionState": "ENABLED", "availability": "AVAILABLE" }
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

Every catalog mutation and dedicated retired-discovery read requires an active staff session with
`commerce.offerings.manage`. The runtime enforces this through Fiona's `AccessControl`;
ordinary active and exact historical catalog reads remain public under the existing policy.

## Staff authentication

Fiona verifies staff passwords. `commerce-runtime` owns human and service identities,
roles, role permissions, assignments, live permission resolution, and sessions.
`commerce-domain` defines the principal and permission vocabulary. A session holds only
identity, so changing a role or disabling a user takes effect immediately.

```text
POST /auth/login → PasswordAuthenticator → UserId → context.sessions.create(...)
                 → __Host-fionas_session cookie
next request     → authentication(SessionAuthenticator, ServiceAccessTokenAuthenticator)
                 → authenticatedPrincipal (USER or SERVICE) → AccessControl
                 → PermissionResolver → handler
```

`POST /auth/login` accepts `{"username":"...","password":"..."}` and answers `204`
with a `Secure`, `HttpOnly`, host-only, `Path=/`, `SameSite=Lax` cookie. Invalid credentials
or a disabled user receive the same `401` response. `GET /auth/me` returns the active
human staff profile, role keys, and sorted effective `permissions` without secrets.
Permissions come directly from the runtime's live resolver, so later grant or assignment
changes appear without a new login. Reading your own permissions requires no
`commerce.role.read`; that permission governs role administration. `POST /auth/logout` revokes the runtime
session and clears the cookie; repeating it, or presenting a cookie whose session is already revoked or expired,
still clears the cookie with `204`. It authenticates through the runtime's session authentication only: it
revokes browser sessions and nothing else, so a request authenticated only by a service access token is `403`.
Service access tokens are never revoked by logout; they expire, and disabling the service suspends them.
No raw session token is sent in JSON.

Login stays anonymous and is limited in memory per connection source IP: a burst of five
attempts, then one attempt refilled every five minutes, up to five. Successful, malformed,
and rejected attempts all count. Exhaustion returns `429` with `Retry-After` in whole
seconds (rounded up), `Cache-Control: no-store`, and
`{"code":"rate_limited","message":"Too many requests"}`. No progressive lockout is applied.
Each process has its own buckets and restart clears them. The synchronized map retains
at most 10,000 IPs, removes idle fully replenished entries, and conservatively shares a
depleted overflow bucket for new IPs when full. Missing source addresses share one bucket.
Forwarded, X-Forwarded-For, and X-Real-IP headers are ignored. Behind a proxy, clients share
the proxy connection's bucket; a trusted-proxy identity policy requires a separate decision.

The runtime directory normalizes usernames and stores the profile and status in
`commerce.users`. Fiona's `fionas.user_credentials` holds only the Argon2id hash and
change time, with a foreign key to that runtime user. Fiona contributes these permissions to
the runtime permission catalog, each naming a capability rather than a kind of caller:

| Permission | Group | Grants |
|---|---|---|
| `fionas.credentials.manage` | `fionas.credentials` | Set or reset staff password credentials |
| `fionas.inquiries.read` | `fionas.inquiries` | List and read inquiries |
| `fionas.inquiries.create` | `fionas.inquiries` | `POST /inquiries` |
| `fionas.inquiry-form.read` | `fionas.inquiries` | `GET /inquiry-form` |
| `fionas.estimate-preview.create` | `fionas.pricing` | `POST /estimate-preview` |

The bootstrap Administrator role explicitly grants OfferingsManage, FinancialDocumentRead,
FinancialDocumentCreate, PaymentRecord, RefundRecord, PrincipalRead, PrincipalManage, RoleRead, RoleManage,
RoleAssign, the runtime's ServiceCredentialManage (`commerce.service-credential.manage`, so the
first administrator can issue a service's credential, not only create it and assign its roles),
CredentialsManage, InquiriesRead, InquiriesCreate, InquiryFormRead, and EstimatePreviewCreate.
There is no wildcard. Future permissions are not granted automatically, and
the grants are fixed when bootstrap creates the role: startup never changes an existing
Administrator role. An installation upgrading from an earlier release retains its existing
grants. To enable newer capabilities for that role, first `GET /admin/access/roles/commerce.administrator`
and inspect its current permissions. Add whichever of `commerce.refund.record`, `fionas.inquiries.read`,
`commerce.service-credential.manage`, `fionas.inquiries.create`, `fionas.inquiry-form.read`, and
`fionas.estimate-preview.create` it lacks to that set, then
`PUT /admin/access/roles/commerce.administrator/permissions` with the **complete desired
permission list**. This endpoint replaces the role's full set of grants; sending only the
new permission would remove every existing grant, including installation-specific ones.
Until an upgraded Administrator has `fionas.inquiries.read`, `GET /inquiries` and
`GET /inquiries/{inquiryId}` answer it `403`.

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
When no origin is configured, browser login fails closed. A request authenticated only by a
service access token carries no cookie and needs no `Origin`. Inquiry submission
(`POST /inquiries`), `GET /inquiry-form`, and estimate previews require their own Fiona
permissions (normally the web frontend's SERVICE principal); listing and reading inquiries
require `fionas.inquiries.read`. `/health`, `/ready`, and ordinary Offerings reads remain public;
Offerings mutations and retired discovery require `commerce.offerings.manage`. Every financial-document and payment
route is staff-only.

## Service authentication

Software callers, such as the server-side web frontend, authenticate as SERVICE principals
through commerce-runtime 0.0.20's service authentication. Fiona composes it; it implements
none of it. Three different things are involved, and they are never interchangeable:

| Thing | Who holds it | Purpose |
|---|---|---|
| `SERVICE_TOKENS_SIGNING_KEY` | fionas-commerce only | Signs and verifies access tokens. Never handed to a frontend or any other service. |
| Service credential (`serviceId` + secret) | The service's server-side secret store | Long-lived; exchanged for access tokens. Shown once, at creation. |
| Access token | The service, in memory | Short-lived (15 minutes by default) `Authorization: Bearer` credential. Carries identity only, never permissions. |

```text
fionas-web (server side)  serviceId + credential secret
        │
        ▼
POST /auth/service/token  → short-lived access token
        │
        ▼
Authorization: Bearer <token> → SERVICE PrincipalId → current roles → current permissions
                              → AccessControl → handler
```

USER and SERVICE are first-class principals. Staff browser sessions and service access
tokens are two authentication transports feeding Fiona's one `AccessControl`, which tries the
staff session cookie first and then the service access token, so a request is exactly one
principal: a USER or a SERVICE. A request carrying both a valid session and a valid token stays
the session's user; identities are never merged. Permission-protected routes do not care which
kind of principal holds the permission: a SERVICE granted `fionas.inquiries.read` may list
inquiries, and one granted a commerce permission may use that permission's routes; the three
customer routes are simply what `fionas-web` is granted. Routes that need a human say so
explicitly: `GET /auth/me` is the human staff profile and answers a service `403`, while
`GET /authorization/me` describes any authenticated principal. `POST /auth/logout` revokes only
staff browser sessions. Authorization is
resolved live: granting or removing a role permission changes what an existing token may do
on its next request, and disabling a service suspends its tokens immediately.

**Initial `fionas-web` provisioning** (administrative, never at startup; uses the mounted
`/admin/access` API as the bootstrap administrator):

1. Log in as the bootstrap administrator.
2. Create the service identity: `POST /admin/access/services` with `{"name":"fionas-web"}`.
3. Create its role: `POST /admin/access/roles` with key `fionas.web`.
4. Grant exactly `fionas.inquiry-form.read`, `fionas.estimate-preview.create`, and
   `fionas.inquiries.create` (in the role body, or `PUT /admin/access/roles/fionas.web/permissions`).
   Never grant it staff, administration, or financial permissions.
5. Assign the role: `PUT /admin/access/services/{serviceId}/roles/fionas.web`.
6. Create a credential: `POST /admin/access/services/{serviceId}/credentials` with a `label`.
7. Capture `serviceId` and the returned `secret`. The secret is shown only in that response
   (`Cache-Control: no-store`); listing credentials returns metadata only.
8. Store both in fionas-web's **server-side** secret configuration. Never log them, and never
   put them in browser JavaScript. They are not `SERVICE_TOKENS_SIGNING_KEY`.
9. fionas-web exchanges them at `POST /auth/service/token`.

The intended frontend lifecycle (implemented in fionas-web, not here): request a token lazily,
cache it until shortly before `expiresAt`, send it as `Authorization: Bearer`, and obtain a new
one on expiry or after a single `401` retry.

**Credential rotation** needs no backend restart: create credential B, deploy fionas-web with
B, verify it, then revoke A (`DELETE /admin/access/services/{serviceId}/credentials/{credentialId}`).
A revoked credential can obtain no new token; tokens it already obtained remain valid until
they expire. After a suspected compromise, also disable the service until the token lifetime
has passed.

**Operational protection.** `POST /auth/service/token` is public by design and every
valid-shaped attempt performs one memory-hard Argon2id verification (including attempts for
unknown credentials, which are checked against a dummy hash). The runtime has no rate
limiter and Fiona adds none (its login limiter is for human login only), so production must
protect this route with edge or reverse-proxy rate limiting, restrict it to private/internal
reachability for its service consumers, or both. Neither the credential secret, its
verifier, an access token, nor the signing key is ever logged.

Credential administration uses the runtime's permissions: listing needs
`commerce.principal.read`; creating and revoking need `commerce.service-credential.manage`,
which only a principal trusted to act as any service should hold.

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
for a selection that does not fit the revision or Fiona's pricing. The optional `violations`
list exposes stable codes, for example `TOO_MANY_SELECTIONS`, `UNKNOWN_OFFERING`,
`INVALID_GUEST_COUNT`, `UNSUPPORTED_DURATION`, `UNSUPPORTED_CURRENCY`,
`UNSUPPORTED_QUANTITY_DIMENSION`, or `INCOMPATIBLE_DURATION_PRICE`.
The runtime's `offeringsValidationFailed` preserves both those codes and Fiona's useful
human explanations, across previews, inquiry submissions, persisted documents, and change orders:

```json
{"code":"validation_failed","message":"The selection cannot be estimated: TOO_MANY_SELECTIONS (category soft-serve-flavor allows at most 2 selections, got 3)","violations":[{"code":"TOO_MANY_SELECTIONS"}]}
```

## Financial documents and payments

Staff turn an inquiry into Fiona's first commercial workflow:

```text
Inquiry I
   │ POST /inquiries/{I}/estimates                       D/v1 Estimate
   │ POST /financial-documents/{D}/change-orders         D/v2 Estimate   (repriced)
   │ POST /financial-documents/{D}/quote                 D/v3 Quote      (same lines)
   │ POST /financial-documents/{D}/payments              $300 deposit → allocated to D/v3
   │ POST /financial-documents/{D}/invoice               D/v4 Invoice    (same lines)
   │ POST /financial-documents/{D}/change-orders         D/v5 Invoice    (repriced)
   ▼ POST /financial-documents/{D}/payments              final payment → allocated to D/v5
GET /financial-documents/{D}   latest Invoice D/v5, settlement across the whole lineage
```

An inquiry can also begin a new lineage directly at `D/v1 Quote` or `D/v1 Invoice`
through `POST /inquiries/{inquiryId}/financial-documents`. These are legitimate first
snapshots with `previousVersion: null`; no Estimate or Quote is synthesized before them.
The existing `/estimates` endpoint creates `D/v1 Estimate` through the same pricing and
persistence operation.

The responsibilities are split three ways:

```text
commerce-domain      FinancialDocument (Estimate → Quote → Invoice), ChangeOrder, LineItem,
                     Money, PaymentRecord, PaymentAllocation, RefundRecord, RefundAllocation,
                     reconciliation invariants
commerce-runtime     financial snapshot persistence and lifecycle orchestration, payment and
                     allocation and refund persistence, reconciliation, transaction-aware ledger
                     operations (context.financialLedger)
fionas-commerce      inquiry → document ownership/purpose, optional legacy staff pricing metadata,
                     server-authoritative starting stage and pricing, change-order intent,
                     payment acceptance and allocation policy, HTTP and auth
```

**The ledger is commerce-runtime's.** Every version of a document is an immutable
commerce-runtime snapshot `(documentId, version)` in `commerce.financial_document_snapshots`
with its lines; totals are derived from the lines. Payments and allocations are
commerce-runtime's `commerce.payment_records`, `commerce.payment_allocations`,
`commerce.refund_records`, and `commerce.refund_allocations`. Fiona has
no copy of any of it, and no stored balance or payment status.

**Fiona owns the context.** `fionas.inquiry_financial_documents` records which inquiry owns
each lineage: an inquiry may own several (an alternative or restarted proposal), a lineage
belongs to one inquiry, and revisions of one proposal are versions of its lineage. A
document no inquiry owns does not exist in Fiona's API (`404`), whatever else the ledger
holds. Association `purpose` identifies the canonical `INITIAL_ESTIMATE`; other lineages
are `RELATED`. A partial unique index permits at most one initial estimate per inquiry.
Existing associations remain related, with no inference from creation time.

**Materialization is the boundary.** Offerings help construct concrete lines. Once persisted,
the snapshot's description, quantity, price, tax, currency and derived amounts stand alone.
Financial reads, changes, quotes, invoices and payments need no originating catalog or
inquiry inputs. Later versions evolve from the previous snapshot plus explicit changes;
current offerings can suggest new lines, and custom lines require no offering identity.

`fionas.financial_document_pricing` remains optional legacy staff metadata: one row per
exact document version, whose `pricing_inputs` jsonb records the complete inputs used to
construct staff-created/replacement lines: catalog revision, guest count, whether the count is a minimum, duration, and the selections in
submitted order (an explicitly empty category included). The inquiry-generated initial
Estimate writes none, and reads/transitions work without it. This example is a staff-created
lineage with legacy metadata:

```text
D/v1 Estimate  $681.25   catalog r20, 75 guests, 120 minutes, selections X
D/v2 Estimate  $825.00   catalog r20, 100 guests, 120 minutes, selections X
D/v3 Quote     $825.00   the exact inputs of D/v2
D/v4 Invoice   $825.00   the exact inputs of D/v3
D/v5 Invoice   $850.00   catalog r20, 100 guests, 150 minutes, selections X
```

**The server prices, the browser never does.** A first-snapshot document and a change order take
the same commercial inputs as `POST /estimate-preview` (`catalogRevision`, `guestCount`,
`guestCountIsMinimum`, `durationMinutes`, `selections`) and price them with the same
`FionasOfferingsEngine`. Lines, amounts, and totals are never accepted; any such property
in a request is ignored. The catalog revision is the one the request names, never silently
the latest: staff may deliberately keep an old price book or adopt a newer one, and the
pricing source records which.

The new creation route adds only a `stage` (`ESTIMATE`, `QUOTE`, or `INVOICE`) to those
commercial inputs. It cannot accept client-authored lines, totals, currency, document
version, or predecessor. Fiona creates version 1, associates it with the inquiry, and
stores its exact pricing source in one transaction. A direct Quote can later become an
Invoice; a direct Invoice has no further quote or invoice transition.

```bash
curl -s -X POST localhost:8080/inquiries/$INQUIRY/estimates -b "$COOKIE" -H "Origin: $ORIGIN" \
  -H 'Content-Type: application/json' -d '{
  "catalogRevision": 20, "guestCount": 75, "durationMinutes": 120,
  "selections": [
    {"category": "soft-serve-flavor", "offerings": ["vanilla", "horchata"]},
    {"category": "topping", "offerings": ["sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough"]},
    {"category": "cone-option", "offerings": ["waffle-cone"]}
  ]}'
```

A document response has the ledger's facts (`id`, `version`, `createdAt`, `previousVersion`, `stage`
`ESTIMATE`/`QUOTE`/`INVOICE`, `lines` with their durable ids, `subtotal`, `taxAmount`,
`total`, `currency`), the owning `inquiryId`, optional legacy `pricing` metadata, and, on
the latest version only, `reconciliation`: `grossAllocated`, `netApplied`, and `balance`,
derived by commerce-runtime across every version of the lineage. Historical versions in
`/history` carry no reconciliation. Every amount is an exact decimal string.

Every immutable version includes `createdAt`, the RFC 3339 string of the runtime's
persisted `Instant` (for example `"createdAt":"2026-09-29T18:30:00.123456Z"`). PostgreSQL
assigns it when that exact snapshot is inserted. Fiona reads `latestVersion` or
`versionHistory` in its existing `REPEATABLE_READ` transaction and preserves that metadata
alongside its exact pricing source. Reads, inquiry document lists, histories, and document
mutation responses all expose it. It stays unchanged on reread and is distinct from
Fiona's inquiry-association timestamp; Fiona generates no document creation timestamp.

**Transitions never reprice.** `quote` appends a quote with the estimate's lines and copies
legacy pricing metadata only when present; `invoice` does the same from a quote. There is no
estimate-to-invoice shortcut: a transition the latest version does not have is
commerce-runtime's `409 illegal_transition`.

**Change orders reprice, at any stage.** A change order prices the revised inputs, then asks
commerce-runtime to apply a commerce-domain `ChangeOrder` that removes every current line and
adds every newly priced line, in the engine's order; the new version stays in the current
stage (estimate, quote, or invoice). Repricing replaces the line set on purpose: lines are
never matched by description or position, which would invent a line identity. Inputs that
produce exactly the current charges (descriptions, quantities, prices, tax, currency, and
order, ignoring line ids) are no financial change: `422` "The revised pricing produces no
financial change".

This existing staff HTTP route still replaces all lines from newly supplied inputs; it
never reads old catalog inputs to reconstruct the previous snapshot. Per-line/custom staff
HTTP changes and removal of legacy `financial_document_pricing` metadata are follow-up work.

**Every document-lineage mutation names the version it acts on.** `expectedVersion` (transitions and change
orders) and `documentVersion` (allocations and combined payments) must be the latest version; otherwise the request is
`409 conflict` and nothing is appended, so staff never act on a version they did not see.
Fiona's mutations of one lineage run one at a time (they lock its association row), and
commerce-runtime's unique `(document_id, previous_version)` rejects any competing successor.
Financial reads (current document, history, and inquiry list) use PostgreSQL REPEATABLE READ
for a stable point-in-time snapshot across their queries. They read ownership without locking
the association row, so a mutation can commit while a reader is open. Mutations retain the
association-row `SELECT ... FOR UPDATE` and expected-version check to serialize writes.
The current view reconciles the exact document snapshot it returns.

**Payments are two immutable facts.** Recording means money was received. Allocation
assigns some of that money to an exact document snapshot. Reconciliation is a derived
view over the document and its allocations. A payment can therefore be unapplied,
partially applied, or split across eligible documents. The unapplied amount is its
net received amount minus net allocations, including refund unwinds; Fiona stores no
payment status or balance.

`POST /payments` records a payment without knowing its destination:

```json
{"amount":"500.00","currency":"USD","method":"CARD",
 "receivedAt":"2026-09-28T20:00:00Z",
 "externalReference":{"provider":"stripe","reference":"pi_example"}}
```

The currency is required because no document supplies one. The response contains the
persisted payment id, amount, currency, method, receipt time, and optional external
reference, with no allocation or settlement fields. Receipt time and external reference
are optional. The runtime rejects duplicate provider/reference pairs.

`POST /payments/{paymentId}/allocations` assigns an existing payment to an exact snapshot:

```json
{"documentId":"...","documentVersion":3,"amount":"150.00"}
```

Fiona requires that version to remain latest and be a Quote or Invoice, locks its lineage,
and derives currency from that snapshot. The runtime locks the payment and enforces its
allocation history, including currency agreement and the maximum allocatable amount.
The response contains the allocation fact and reconciliation for the exact document
reference. Two allocations of a received `$500` may assign `$300` to one document and
`$200` to another; allocating `$150` leaves `$350` unapplied without storing that
remainder. Allocation reversals are not persisted by the runtime.

`POST /payments/{paymentId}/refunds` records a partial or full refund under the separate
`commerce.refund.record` permission. The request names the payment and explicitly identifies
each allocation to unwind; Fiona never selects an allocation automatically:

```json
{"amount":"50.00","currency":"USD","method":"OTHER",
 "allocations":[{"paymentAllocationId":"...","amount":"50.00"}]}
```

`amount` and allocation amounts are positive exact decimal strings. `currency` is required
and must match the payment. `method` may differ from the original payment method.
`refundedAt` is an optional RFC 3339 timestamp (server time when absent), and
`externalReference` is an optional refund-specific `{provider, reference}` pair. An empty
or omitted `allocations` list refunds unapplied value. Fiona generates the refund and refund
allocation ids and timestamps, then calls the runtime ledger in one transaction. The `201`
response includes the recorded refund, each refund allocation, and the payment's derived
`paymentAmount`, `totalRefunded`, `netReceived`, `grossAllocated`,
`allocationReversals`, `refundAllocations`, `netAllocated`, and `unallocated` amounts, plus
currency. It does not pick one document reconciliation: a payment may span documents.
Refunded money cannot be allocated again. No Fiona table stores a refund or balance.

`GET /financial-documents/{documentId}/payments` reads back everything those requests
recorded, so a staff screen can prepare a refund after a reload, from the document alone. It
requires `commerce.financial-document.read`: it is a child read of the document, and the
payment and refund write permissions carry no read meaning. It lists every payment ever
allocated to any version of the lineage, by `receivedAt`, then `paymentId`:

```json
{"documentId":"D",
 "payments":[{
   "payment":{"paymentId":"P","method":"CARD","amount":"500.00","currency":"USD",
              "receivedAt":"2026-09-28T20:00:00Z"},
   "allocations":[
     {"allocationId":"A1","paymentId":"P","documentId":"D","documentVersion":3,
      "amount":"200.00","currency":"USD","allocatedAt":"2026-09-28T20:01:00Z"},
     {"allocationId":"A2","paymentId":"P","documentId":"E","documentVersion":1,
      "amount":"150.00","currency":"USD","allocatedAt":"2026-09-28T20:01:30Z"}],
   "refunds":[{"refundId":"R","paymentId":"P","amount":"50.00","currency":"USD",
               "method":"OTHER","refundedAt":"2026-09-28T20:02:00Z"}],
   "refundAllocations":[{"refundAllocationId":"RA","refundId":"R","paymentAllocationId":"A1",
                         "amount":"50.00","currency":"USD","allocatedAt":"2026-09-28T20:02:00Z"}],
   "reconciliation":{"paymentAmount":"500.00","totalRefunded":"50.00","netReceived":"450.00",
                     "grossAllocated":"350.00","allocationReversals":"0.00",
                     "refundAllocations":"50.00","netAllocated":"300.00",
                     "unallocated":"150.00","currency":"USD"}}]}
```

- **Each payment is its whole history**, never only its part in the document it was found
  through. A split payment carries its allocations to other documents (`A2` above), because
  its reconciliation depends on them; a UI showing one document selects the allocations
  whose `documentId` is that document's.
- **Discovery is historical.** A payment stays listed after refunds unwind its allocations
  here completely.
- **Facts link by id**: a refund allocation names its refund (`refundId`) and the allocation it
  unwound (`paymentAllocationId`), which names its exact document version.
- Allocations are ordered by `allocatedAt`, refunds by `refundedAt`, refund allocations by
  `allocatedAt`, each then by id: commerce-runtime's order, which Fiona keeps. An id breaks
  only timestamp ties.
- Reconciliation is commerce-runtime's, derived from exactly these facts; Fiona recomputes,
  filters, and stores nothing, and there is no payment status.
- A document no inquiry owns is `404`, even when the ledger holds it; one without payments
  answers `"payments": []`. Fiona proves ownership and reads the histories through
  `FinancialLedger.paymentHistoriesForLineage` in one `REPEATABLE READ` transaction.

**Unapplied payments are discoverable.** `GET /payments/unapplied` returns
`{"payments":[PaymentHistoryResponse, ...]}`, including receipts with no inquiry or
document association. It requires `commerce.payment.record`, the permission for this
operational queue. Fiona delegates directly to `FinancialLedger.unappliedPayments`, whose
read uses one `REPEATABLE_READ` transaction. Complete histories and runtime ordering
(`receivedAt`, then `paymentId`) are preserved. The runtime calculates `unallocated` as
net received minus net allocated after refunds and allocation unwinds; only positive
values appear. Partial allocation keeps a payment discoverable; full allocation or
refund consumes its availability. Fiona stores no balance and performs no reconciliation
math. This endpoint is unpaged; payment search and `GET /payments/{paymentId}` remain deferred.

Allocation requests with unreadable `documentId` text answer `400 malformed_request`
with body-field metadata. A readable UUID still undergoes the existing domain validation
and ownership checks (`422` or `404` where appropriate).

The existing `POST /financial-documents/{documentId}/payments` means "we received this
payment, and all of it is for this document":

```json
{"documentVersion": 3, "amount": "300.00", "method": "CARD",
 "receivedAt": "2026-09-27T17:05:00Z",
 "externalReference": {"provider": "square", "reference": "pay_7Q2Rk9"}}
```

- `documentVersion` must be the latest version, and it must be a quote (a deposit) or an
  invoice; an estimate is `422 invariant_violated`.
- `amount` is a positive exact decimal string with at most the currency's minor-unit digits;
  JSON numbers are never accepted. The currency is the document's.
- `method` is `CASH`, `CHECK`, `CARD`, `BANK_TRANSFER`, `DIGITAL_WALLET`, or `OTHER`.
- `receivedAt` is an optional RFC 3339 timestamp; the time of recording when absent.
- `externalReference` is optional; its `provider` and `reference` come together. A pair
  already recorded is commerce-runtime's `409 conflict`; there is no second idempotency
  mechanism.

The runtime records the `PaymentRecord` and one `PaymentAllocation` of the whole amount to
that exact version. The allocation stays attached to it as the document advances: a deposit
on quote `D/v3` still counts when invoice `D/v5` is reconciled, whose balance is `D/v5`'s
total minus the net applied. Over-application is allowed; the balance is then negative. The
response names the payment, the allocation, the exact version, and the settlement after it.
The two facts are committed atomically. All payment amounts remain exact decimal strings,
never JSON floating-point numbers. Provider SDKs, webhooks, stored payment status,
stored document balance, booking conversion, events/outbox/CQRS, and allocation
reversals remain outside this workflow.

**One transaction per operation.** Each operation opens one runtime transaction and passes
it to every ledger call (`context.financialLedger.create(transaction, …)`,
`issueQuote(transaction, …)`, `changeOrder(transaction, …)`,
`recordPayment(transaction, …)`, `allocatePayment(transaction, …)`,
`recordPaymentAgainstDocument(transaction, …)`, `recordRefund(transaction, …)`,
`reconcilePayment(transaction, …)`, `reconcile(transaction, exactReference)`,
`paymentHistoriesForLineage(transaction, …)`) and to
Fiona's repositories, and prices from the exact catalog revision read in that same
transaction. If any step fails, the commerce snapshot, payment, or allocation rolls back
with Fiona's association and pricing source.

## API contract and OpenAPI

The Fiona API describes itself. Each endpoint is an http4k contract route that carries its
own OpenAPI metadata next to its handler: path, method, `operationId`, summary, tag,
request and response bodies with examples, and every status it answers. The OpenAPI
document is rendered from those routes; there is no hand-maintained `openapi.json` or YAML,
so changing an endpoint changes its documentation in the same place.

- **`GET /openapi.json`** is the machine-readable contract, served live by the application.
  It needs no database and describes Fiona's API (inquiries, estimate previews, financial
  documents, payments, and authentication) and the runtime capabilities Fiona mounts
  (`/offering-catalog`, `/admin/access`, `/authorization/me`), not the runtime's `/health` and `/ready` or the
  documentation routes.

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
environment. The stable `operationId`s are `createInquiry`, `listInquiries`, `getInquiry`, `getInquiryForm`,
`previewEstimate`, `createInquiryEstimate`, `createInquiryFinancialDocument`, `listInquiryFinancialDocuments`,
`getFinancialDocument`, `getFinancialDocumentHistory`, `issueQuote`, `issueInvoice`,
`createChangeOrder`, `recordPayment`, `recordStandalonePayment`, `allocatePayment`, `login`, `logout`, `getCurrentUser`, and
`setStaffPassword`, and for the catalog
`fionasOfferingsGetCatalog`, `fionasOfferingsCreateCatalog`,
`fionasOfferingsGetCatalogRevision`, `fionasOfferingsListCategories`,
`fionasOfferingsAddCategory`, `fionasOfferingsGetCategory`,
`fionasOfferingsListCategoryOfferings`, `fionasOfferingsListOfferings`,
`fionasOfferingsAddOffering`, and `fionasOfferingsGetOffering`, and for the request's
principal the runtime's `authorizationCurrentPrincipal`. Every operationId is unique.

The Offerings routes are commerce-runtime's own contract routes, mounted in the same
contract, so their documentation is the runtime's: the same routes serve requests and
describe themselves. Their schemas come from the runtime's `offeringsOpenApiRenderer`,
which keeps `OfferingPriceDto` a strict `oneOf` of `FixedOfferingPrice`,
`PerQuantityOfferingPrice`, and `PerDurationOfferingPrice`, discriminated by `kind`. That
renderer works only with http4k's Jackson, so `http4k-format-jackson` is a dependency, used
for nothing else: requests and responses stay kotlinx.serialization.

Fiona's own schemas are derived from the kotlinx.serialization descriptors of the transport
DTOs, the wire format itself, so `required` matches what the server reads and writes: strings,
`int32` and `int64` integers, booleans, arrays, enums, and nested objects, each its own component.
The inquiry form's sealed input serializer produces an explicit `type` discriminator,
a `oneOf` with one component per variant, and required constant discriminator values;
nested Offering schemas retain the runtime's price union unchanged. Known
gaps: the `Location` header of `201` is described in prose only, because http4k 6.58's
contract metadata cannot declare response headers. Commerce-runtime 0.0.20's Offerings
renderer omits invalid schema-level `"format": null` and preserves arbitrary example data.
Fiona uses the runtime's `ValidationErrorResponse` and `ValidationViolationResponse`
schemas for validation failures, with optional `violations`; ordinary errors retain
`ErrorResponse`. Commerce-runtime accepts Fiona's OpenAPI tags: Swagger UI groups
catalog operations under **Offerings catalog** and runtime administration plus Fiona's
password route under **Staff administration**, and `/authorization/me` under
**Authorization**; Fiona's own routes are grouped under
**Inquiries**, **Estimates**, **Financial documents**, **Payments**, and **Authentication** (which
also holds the runtime's `POST /auth/service/token`). The document declares two security
schemes for the two authentication transports: `staffSession` (an API key in the
`__Host-fionas_session` cookie) and `serviceAccessToken` (HTTP bearer, the runtime's
`serviceAccessTokenOpenApiSecurity`). Every Fiona route behind the shared `AccessControl` lists
them as two separate requirement objects, meaning either one (OR), never both together; this
includes the customer routes, `GET /auth/me` (which then rejects a SERVICE), the inquiry,
financial-document, and payment routes, and the password route. `POST /auth/logout` lists
`staffSession` OR anonymous (`{}`), never `serviceAccessToken`: it is idempotent browser cleanup
(a live session is revoked, an active or stale cookie cleared, and a request with no cookie also
succeeds with `204`), while a request authenticated only by a service access token is `403`
because service tokens are not logout sessions. `POST /auth/login` and `POST /auth/service/token` declare no security. The
schemes are documentation only: enforcement stays each route's `AccessControl`. **Known gap:**
the runtime capability routes (`/offering-catalog`, `/admin/access`, `/authorization/me`)
enforce the same `AccessControl` but carry no security metadata, because commerce-runtime
0.0.20 offers the host no way to add it and does not mark its public Offerings reads as public,
so a contract-wide default would mislabel them. Fiona does not wrap or clone runtime routes to
change their metadata; see [`AGENTS.md`](AGENTS.md#known-upstream-gaps-last-audited-at-commerce-0014).
Every Fiona endpoint must be part of the
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
environment. Bootstrap staff credentials and the service token signing key are supplied through
environment variables.

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
| `SERVICE_TOKENS_SIGNING_KEY` | Base64 of at least 32 random bytes (`openssl rand -base64 32`) that signs service access tokens; fionas-commerce's own secret, shared by all its instances, never given to a frontend, and not a service credential | required; no default |
| `SERVICE_TOKENS_ISSUER` | This deployment's token issuer, distinct per environment: `fionas-commerce-local`, `fionas-commerce-staging`, `fionas-commerce-production` | required; no default |
| `SERVICE_TOKENS_LIFETIME_MINUTES` | Access token lifetime (1–60) | `15` (runtime default) |
| `FIONAS_TRUSTED_ORIGINS` | Comma-separated exact browser origins for login and cookie-authenticated mutations | none; browser login is denied until configured |
| `FIONAS_BOOTSTRAP_ADMIN_USERNAME` | First administrator's username; required with password and display name | none |
| `FIONAS_BOOTSTRAP_ADMIN_PASSWORD` | First administrator's password, at least 12 characters; remove after provisioning | none |
| `FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME` | First administrator's nonblank display name; required with username and password | none |
| `FIONAS_BOOTSTRAP_ADMIN_FIRST_NAME`, `FIONAS_BOOTSTRAP_ADMIN_LAST_NAME` | Optional profile fields | none |
| `LOG_LEVEL` | Level of the application's and runtime's own logs | `INFO` |

Logging is Logback ([`logback.xml`](src/main/resources/logback.xml)): `key=value` lines
on stdout, with library logging at `WARN`/`INFO`. The database password is never logged.

## Database and migrations

> **Upgrading to commerce 0.0.17.** Runtime V7 adds the financial snapshot's
> `created_at timestamptz NOT NULL DEFAULT clock_timestamp()`. It intentionally refuses
> a pre-V7 database containing financial snapshots, because their creation times are unknown.
> Existing development/deployment databases have been ephemeral: recreate an affected
> database or volume. There is no backfill, synthetic timestamp, Fiona compensation
> migration, or Flyway validation bypass. Empty pre-V7 databases and fresh databases migrate
> normally. Fiona's own migrations remain exclusively in `fionas`.

commerce-runtime owns migration orchestration; Fiona owns only its own migrations and the schema they live in.

```text
commerce-runtime migrations    commerce schema    commerce.flyway_schema_history   first
Fiona migrations               fionas schema      fionas.flyway_schema_history      second
```

- Fiona's migrations live in [`src/main/resources/db/fionas`](src/main/resources/db/fionas)
  and are contributed, with their schema, as
  `ApplicationMigrations(schema = "fionas", locations = listOf("classpath:db/fionas"))`.
  The runtime's Flyway creates the `fionas` schema when missing, makes it the default schema
  of Fiona's stream, and keeps Fiona's `flyway_schema_history` in it, so Fiona's `V1` does
  not create the schema (its SQL still qualifies every object, `fionas.customers`). Nothing
  of Fiona's migrations, tables, or history is in `public`. The runtime's migrations come
  inside the `commerce-runtime` jar; Fiona never lists or copies them.
- The two streams have independent version spaces: Fiona's migrations are `V1`, `V2`, …
  regardless of the runtime's numbering.
- Fiona's tables are in the `fionas` schema: `customers`, `inquiries`, and
  `user_credentials` (`inquiries.customer_id → customers.id`, `customers.email` unique,
  `user_credentials.user_id → commerce.users.principal_id`), and, from `V3`,
  `inquiry_financial_documents` (primary key `document_id`, `inquiry_id → inquiries.id`,
  `(document_id, initial_version = 1) → commerce.financial_document_snapshots`, indexed by
  inquiry) and `financial_document_pricing` (keyed by `(document_id, document_version)`,
  referencing the association and the exact `commerce.financial_document_snapshots`
  version), and, from `V4`, the `inquiries_created_at_id_idx` index `(created_at, id)`
  behind the newest-first inquiry list. `V5` added optional `inquiry_locations`;
  `V6` replaces it with required `inquiries.zip_code` for empty inquiry data, without a
  default, backfill, or data transfer. The already-applied `V5` remains immutable. `V7`
  adds non-null `event_date` (`date`) and `event_type` (checked `text`) without defaults or
  backfill, assuming empty pre-release inquiry data. `V8` adds association `purpose`, with
  existing rows defaulting to `RELATED`, and a partial unique index permitting at most one
  `INITIAL_ESTIMATE` per inquiry. It adds no catalog/offering columns to financial tables.
  `V9` adds durable `inquiry_submissions` with unique key/inquiry identity, stored fingerprint and
  a deferred FK to Fiona's inquiry; existing inquiries are not assigned synthetic keys.
  `V10` adds a deferred foreign key from `inquiries.id` to `inquiry_pricing`, so no inquiry
  commits without its requested pricing inputs. It invents no inputs: a disposable
  development database holding an inquiry recorded without them fails `V10` and must be
  recreated.
  `V11` replaces V4's `inquiry_pricing` tables, V3's pricing child tables, V10's reverse
  reference, and the pricing source's scalar columns with one `NOT NULL` JSON-object
  `pricing_inputs` jsonb in `inquiries` and in `financial_document_pricing`. It converts
  nothing and fails on a populated database, which must be recreated.
  Fiona never creates or
  changes anything in `commerce`, where the runtime keeps its own tables, including the
  Offerings snapshots that hold Fiona's catalog and the financial ledger's snapshots (with
  their lines), payments, allocations, refunds, and refund allocations. None needs a Fiona copy.
- Composing the runtime runs the migration phase before anything is served. By default
  (`MIGRATIONS_ON_STARTUP=migrate`) it applies the runtime's pending migrations, then
  Fiona's; re-running against a current database applies nothing. A deployment that
  migrates in a separate release step sets `MIGRATIONS_ON_STARTUP=validate` on its
  instances. There is no separate migration command yet.
- If any migration fails, or validation finds the database behind, the process logs
  `event=startup_failed` and exits without serving.

> **Upgrading from commerce 0.0.11.** commerce-runtime 0.0.12 only adds its `V5` financial
> ledger migration, and Fiona adds `V3`; a database from the previous release migrates
> forward on startup, runtime first. The existing Administrator role keeps its grants (see
> [Staff authentication](#staff-authentication)).

> **Upgrading to commerce 0.0.15.** The runtime now keeps an application's migration
> history in the application's own declared schema, so Fiona's moves from
> `public.flyway_schema_history` to `fionas.flyway_schema_history`. Fiona adds no migration
> and 0.0.15 deliberately does not reinterpret the old history: it does not move,
> baseline, or copy it. A development database created by an earlier version has that
> history in `public` and must be recreated (for our disposable development environment,
> drop the database or its volume, for example `docker compose down -v`). After recreation,
> `commerce.flyway_schema_history` and `fionas.flyway_schema_history` exist and `public`
> holds nothing of Fiona's.
>
> **Inquiry discovery and requested pricing (Fiona `V4`).** Fiona's `V4` adds the
> `inquiries_created_at_id_idx` index and the `inquiry_pricing` tables; the runtime applies it
> on startup like any Fiona migration. `GET /inquiries/{inquiryId}` is no longer public, and
> `POST /inquiries` now answers a receipt (`id`, `createdAt`) instead of the inquiry and its
> customer. A fresh bootstrap grants `fionas.inquiries.read`; an existing Administrator role
> keeps its grants, so use the read-modify-replace permission flow in
> [Staff authentication](#staff-authentication) to add `fionas.inquiries.read`.
>
> **Upgrading to commerce 0.0.14.** The runtime applies `V6__refunds` to create its two
> refund tables. Fiona adds no migration. Existing Administrator grants are retained;
> use the read-modify-replace permission flow in [Staff authentication](#staff-authentication)
> to add `commerce.refund.record` before using the new endpoint.
>
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

### Persisted pricing inputs

A set of Fiona pricing inputs has no identity of its own, so it is stored in the row that
owns it rather than in child rows: `fionas.inquiries.pricing_inputs` (what the customer
requested) and `fionas.financial_document_pricing.pricing_inputs` (what one exact
staff-priced document version was priced from). Both are `NOT NULL` jsonb objects in Fiona's
own representation, independent of the HTTP DTOs and of commerce-runtime's snapshot JSON:

```json
{"catalogRevision": 20,
 "context": {"guestCount": 75, "guestCountIsMinimum": false, "durationMinutes": 120},
 "selections": [{"categoryKey": "soft-serve-flavor", "offeringKeys": ["soft-vanilla", "soft-horchata"]},
                {"categoryKey": "topping", "offeringKeys": []}]}
```

Every property is required; arrays keep submitted order, and an empty block stays empty.
Reads are strict: an unknown, missing, `null`, or wrongly typed property, or a value the
domain rejects, fails loudly with the owning inquiry or document version named, and is never
repaired. Lines, amounts, and totals are never stored here.


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

### Seed a fresh local catalog and preview an estimate

With Fiona running against a fresh, disposable local database, bootstrap the `admin` user
and set `FIONAS_TRUSTED_ORIGINS=http://localhost:8080` for the application. Use Node.js 20
or newer to enter the acceptance catalog through Fiona's API and preview its canonical
`$681.25` estimate. The script acts as the bootstrap administrator, whose session holds the
customer-operation permissions (a real frontend uses a service access token instead):

```bash
FIONAS_ADMIN_PASSWORD='your-local-password' node scripts/setup-local-commerce.mjs
```

The script also accepts `FIONAS_BASE_URL`, `FIONAS_ORIGIN`, and
`FIONAS_ADMIN_USERNAME`; each defaults to the local port 8080 setup and username `admin`.
It does not load `.env` files or install npm packages. It intentionally seeds only a fresh
catalog and stops if one already exists; existing catalogs are managed through revisioned
mutations. Each add sends `expectedRevision` from the preceding successful response, with
no intervening GET or automatic retry. See [setup-local-commerce.mjs](scripts/setup-local-commerce.mjs)
for the exact catalog entries and preview request.

Every seeded offering explicitly sends `selectionState=ENABLED` and `availability=AVAILABLE`.
For an existing catalog, `node scripts/setup-local-commerce.mjs --capitalize-toppings`
updates topping display names while preserving and verifying description, price, selection
state and availability. A label edit never enables or makes an offering available.

### Smoke test Invoice payments and a refund locally

With Fiona running locally and its Offerings catalog already initialized by
`setup-local-commerce.mjs`, use Node.js 20 or newer to exercise the separate payment
recording, allocation, and refund routes. The local Administrator needs
`commerce.refund.record` (fresh bootstrap grants it; update older roles using the
[replacement flow above](#staff-authentication)):

```bash
FIONAS_ADMIN_PASSWORD='your-local-password' node scripts/spoof-payment.mjs
```

The script accepts the same `FIONAS_BASE_URL`, `FIONAS_ORIGIN`, and
`FIONAS_ADMIN_USERNAME` defaults as the catalog setup script; `FIONAS_ADMIN_PASSWORD` is
required. It creates a real local inquiry, configured with the same service it then invoices (so the
inquiry also has its initial Estimate), and a direct Invoice v1, records and allocates
`$200.00` and `$150.00`, refunds `$50.00` from the second payment's allocation, then pays
the server-returned reopened balance. Each standalone receipt is rediscovered through
`GET /payments/unapplied`; the first is allocated in two parts, with a read showing its
remaining available value between them, and disappears from the queue when fully applied.
It proves payment facts survive a reload: the refund uses no id retained from a payment,
allocation, or refund response. The refund's payment and allocation
ids are rediscovered from `GET /financial-documents/{documentId}/payments`, and the refund
and its unwind are verified by reading that again. Exact cent arithmetic verifies gross and net
allocation and a final zero balance. It writes ordinary development data to the configured
database, so use it in local/disposable environments. It has no npm dependencies and is
not a payment-provider or webhook simulator.

### Packaging

`./gradlew shadowJar` produces `build/libs/fionas-commerce-all.jar`: one executable jar
with the application, its dependencies, `application.conf`, `logback.xml`, the Fiona
migrations (Flyway's service files are merged), and the Swagger UI assets served at
`/docs`. This is the deployable artifact. The
Gradle `application` plugin also provides `./gradlew run` and `installDist` for local
use. No Spring Boot is involved.

### Container image

The [`Dockerfile`](Dockerfile) builds the executable jar on Java 25 and runs it on a
Java 25 JRE as a non-root user. The runtime base is glibc (Debian/Ubuntu), not Alpine,
because `argon2-jvm` loads a native Argon2 library. Resolving `commerce-runtime` needs a
GitHub Packages token at build time, passed as build arguments that exist only in the
build stage, never in the final image:

```bash
docker build --build-arg GITHUB_ACTOR=<user> --build-arg GITHUB_TOKEN=<read:packages token> -t fionas-commerce .
```

```bash
docker run --rm -p 8080:8080 -e DATABASE_JDBC_URL=... -e DATABASE_USERNAME=... -e DATABASE_PASSWORD=... fionas-commerce
```

The image build runs `shadowJar` only; lint and tests stay in CI.

### Deploying on Railway

Railway builds the root `Dockerfile` when it detects one. In the service settings, set the
healthcheck path to `/ready` (`/health` is liveness only). Railway passes service variables to a Dockerfile build only when the Dockerfile declares
them with `ARG`, which is why `GITHUB_ACTOR` and `GITHUB_TOKEN` are declared there. Set
these variables on the service (Railway also injects `PORT`):

| Variable | Value |
|---|---|
| `GITHUB_ACTOR`, `GITHUB_TOKEN` | A GitHub user and a token with `read:packages` (seal the token). Railway also exposes service variables to the running container, so use a read-only token. |
| `DATABASE_JDBC_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DATABASE_USERNAME` | `${{Postgres.PGUSER}}` |
| `DATABASE_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `SERVICE_TOKENS_SIGNING_KEY` | `openssl rand -base64 32` (seal it); fionas-commerce's own key, never shared with fionas-web |
| `SERVICE_TOKENS_ISSUER` | `fionas-commerce-production` (or `-staging`) |
| `FIONAS_TRUSTED_ORIGINS` | The service's public origin, for example `https://<domain>` |
| `FIONAS_BOOTSTRAP_ADMIN_*` | First provisioning only; remove after the admin exists |

Protect `POST /auth/service/token` with edge rate limiting and/or private networking for
fionas-web (see [Service authentication](#service-authentication)). Replace `Postgres` with the
name of your Railway PostgreSQL service. The application is
tested against PostgreSQL 18. It applies its migrations on startup
(`MIGRATIONS_ON_STARTUP=migrate`, the default).

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
| `DatabaseSchemaSpec` | Fiona's tables and keys are in `fionas`; `commerce` holds exactly what commerce-runtime creates on its own (its Offerings and ledger tables included); nothing Fiona-owned in `commerce`, and `public` holds no Fiona objects or migration metadata; each stream's `flyway_schema_history` is in its own schema; the association and pricing-source keys reference the runtime's exact snapshots; no Fiona table or column restates a ledger fact |
| `MigrationLifecycleSpec` | Fiona as a consumer of the runtime's migration phase: runtime migrations first (the runtime's financial ledger before Fiona's `V3`; an application migration depending on them succeeds), independent version spaces, each history in its own schema (none in `public`), repeat startup applies nothing, failures prevent composition |
| `JdbiCustomerRepositorySpec`, `JdbiInquiryRepositorySpec` | Insert/read, email and batch id lookup, unique email conflict, foreign keys; newest-first keyset listing with timestamp ties and an `EXPLAIN` proving a backward index scan without a sort; requested pricing inputs round trip in order; an inquiry cannot commit without them |
| `RuntimeTransactionSpec` | Fiona repositories write through the runtime `Transaction`: customer, inquiry and requested inputs roll back together, and nothing is visible before commit |
| `InquiryOperationsSpec` | New customer + inquiry + initial Estimate together, customer reuse, requested pricing inputs recorded as submitted and pinned to their revision, rejected inputs record nothing, atomic failure (inquiry or pricing inputs), not found |
| `InquiryMaterializationSpec` | Exact persisted Estimate v1 lines from one evaluation and one latest lookup; publication after validation; new/reused customers; inquiry input history; rollback within ledger and during/after the final association write; canonical uniqueness with related lineages; financial reads, custom ledger changes and transitions after test-only catalog removal, without pricing metadata |
| `InquiryRequestFingerprintSpec` | Pinned v1 encoding, every semantic scalar, message presence, exact duration, category/offering identity and ordering; canonical normalization and key exclusion; bounded opaque key validation |
| `InquiryIdempotencyRoutesSpec` | Full-handler replay with exact receipts, no second inquiry/Estimate/association; replay after publication; changed-intent conflicts; canonical transport equivalence; stale/validation/malformed/authentication failures release keys; different keys remain distinct commands |
| `InquiryIdempotencySpec` | Forced overlapping PostgreSQL same/different commands, observed unique-key waits, either winner accepted; failed owner releases key to waiter; late rollback; incomplete claims rejected at commit; application-to-HTTP lost-response recovery and no replay pricing/catalog lookup |
| `PublicInquirySubmissionSpec` | Current revision materialization; stale machine-readable conflict with zero writes and refreshed success; every advertised option for every duration; engine failures and retirement; hidden categories/offerings rejected publicly but accepted by staff with historical revisions; missing/null `pricingInputs` malformed with zero writes; no inquiry before catalog initialization |
| `InquiryFormRoutesSpec` | Explicit public questions and lifecycle (service section required, unconfigured submissions malformed), input constraints and submission bindings, runtime prices, incompatible configuration failures, and response-only local totals matching authoritative previews across catalog revisions |
| `GetInquiryFormSpec` | One snapshot per resolution, pricing facts derived from policy changes, exact duration contributions, hidden categories, and unusable configuration failures |
| `InquiryRoutesSpec` | The HTTP API through the complete runtime handler: the public receipt never reveals an existing customer; inquiry list and detail require `fionas.inquiries.read` (`401`/`403`), including the documented Administrator upgrade grant; newest-first pages, default and maximum limits, full walks, timestamp ties, stable pages under new inquiries, invalid `limit`/`cursor`; pricing inputs recorded, pinned, rejected exactly as a preview rejects them, never trusting client amounts; preview → inquiry → staff read → estimate without re-entry; errors stay commerce-runtime's and undeclared methods stay `405` |
| `AuthRoutesSpec` | Fresh bootstrap (with the financial grants, never changed by a later startup), generic login failures, session lifecycle, live Offerings grants, runtime administration, credential provisioning, and Origin checks |
| `UnappliedPaymentsSpec` | Standalone receipt discovery with no inquiry, runtime ordering including ties, partial/full allocation, unapplied refunds and unavailable payment exclusion, payment-record authorization, and malformed allocation `documentId` body metadata |
| `FinancialDocumentRoutesSpec` | The whole workflow through the complete handler: preview records nothing; `D/v1` estimate priced as the preview; change order `D/v2`; quote `D/v3`; `$300` deposit allocated to `D/v3`; invoice `D/v4`; invoice change order `D/v5`; final payment; latest view, history with pricing sources, and inquiry listing. Also: no client-supplied totals; change orders at every stage; no-change rejection; explicit old and new catalog revisions; stale versions; illegal transitions; payment policy, validation, and duplicate external references; non-Fiona documents not found; permissions and Origin |
| `FinancialDocumentPaymentsSpec` | `GET /financial-documents/{documentId}/payments` through the complete handler: `[]` without payments; `404` for a lineage only the runtime ledger holds; `401`/`403` unless `commerce.financial-document.read` (payment and refund writes do not grant it); a payment, its allocation, a refund, and its unwind rediscovered after their responses are gone and reused for a second refund; a fully unwound allocation still listed; a split payment whole from either document with whole-payment reconciliation; unapplied payments not listed; commerce-runtime's ordering kept, ids breaking only timestamp ties |
| `FinancialDocumentAtomicitySpec` | Fiona's cross-boundary writes roll back together: first-snapshot Estimate, Quote, and Invoice creation, change orders, combined payments, and standalone allocations do not leave partial ledger or Fiona facts on failure |
| `FinancialLedgerExpansionSpec` | Direct first-snapshot stages and transitions; standalone receipt validation and persistence; partial, repeated, and split allocations; runtime limits and Fiona stage/version policy; concurrent allocations against one payment |
| `FinancialDocumentRepositoriesSpec` | The inquiry association and pricing-source repositories on PostgreSQL: several lineages per inquiry, one inquiry per lineage, ordered round trips with an empty category, copies to a successor, and foreign keys to the runtime's exact snapshots |
| `FinancialDocumentReadConsistencySpec` | Current-document, history, and inquiry-list reads pause between queries; a payment or quote commits while each reader is open, each reader retains its old snapshot, and a later read sees the committed state; ownership lookups fail immediately if they take the mutation-only lock |
| `RepricingSpec` | Change-order derivation: remove every current line, add every repriced line in order; identical charges are no financial change |
| `FionasOfferingsEngineSpec` | Fiona's pricing, purely: the `$681.25` estimate, base and duration, per-guest service, each catalog price form, included and extra toppings, premium toppings, every policy violation, minimum guest counts, line order and injected ids, zero tax, exact totals, and structural validation left to commerce-domain |
| `EstimatePreviewRoutesSpec` | `POST /estimate-preview` through the complete handler over a catalog built with the Offerings API: the `$681.25` estimate, nothing recorded (no financial document either), minimum guest counts, pricing from the requested revision rather than a later one, and the `400`/`404`/`422` error contract |
| `OfferingsCatalogSpec` | Fiona's runtime catalog through the complete handler: initialization, ordered and historical reads, price forms; offering and empty-category update/retire/discover/restore; lifetime key reservation; required/stale revision rejection with no successor; historical pricing remains $681.25 |
| `OpenApiDocumentSpec` | The OpenAPI document: Fiona routes (the inquiry receipt, list, and pricing inputs, financial documents, standalone receipts, allocations, and document payment histories with their linked fact schemas included), runtime Offerings and administration routes, operationIds, statuses, schemas, and no host; document creation takes commercial inputs without client-authored totals; the runtime's strict `OfferingPrice` `oneOf` |
| `OpenApiRoutesSpec` | `/openapi.json` and `/docs` through the complete handler; the served document equals the generated one; Swagger UI reads `/openapi.json`, which offers the Offerings operations, and loads nothing external |
| `GenerateOpenApiSpec` | `generateOpenApi` writes that document as UTF-8 JSON, byte-identical on every run |
| `FionaApplicationSpec` | `application.conf` loads, `/health` and `/ready`, a real server on a port |
| `ArchitectureSpec` | Repositories take a `Transaction` and build no transaction infrastructure; no SQL in routes; no HTTP in persistence; every endpoint is a contract route with an `operationId`; no hand-written OpenAPI file; one http4k version; the stable catalog id and binding; no Fiona Offerings types, repositories, or SQL; pricing depends only on commerce-domain and names no offering; previews record nothing; financial documents and payments only through the runtime's ledger, every call with the operation's `Transaction`, and no Fiona table restating a ledger fact; only published runtime keys referenced; explicit financial grants; Jackson only for the Offerings schemas |

Full verification, as CI runs it (`build` also generates the OpenAPI document):

```bash
./gradlew ktlintCheck test build
```

`./gradlew ktlintFormat` fixes formatting.
