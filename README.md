# fionas-commerce

The commerce backend of Fiona's Ice Cream and its catering business: a concrete Kotlin/JVM
application built on the reusable
[`commerce-runtime`](https://github.com/castab/commerce-domain/tree/v0.0.23/runtime) and
[`commerce-domain`](https://github.com/castab/commerce-domain/tree/v0.0.23/domain)
artifacts.

> **Status: early slices.** Fiona records inquiries and their customers, owns which inquiry
> each financial document belongs to, publishes canonical proposals (Quote + approved deposit),
> projects the booking lifecycle, and records served/closed facts. **Fiona has no catalog and
> no pricing engine.** Every financial line arrives already priced from a trusted authority:
> the `fionas-web` server (a SERVICE principal) for a customer's initial Estimate, and a
> verified staff USER for every negotiated document after it. Fiona validates and stores
> those exact lines through commerce-runtime's financial ledger, which derives every total.
> Documents, payments, refunds and settlement are commerce-runtime's; there is no separate
> Booking aggregate and no payment-provider integration yet.

## How it fits together

```text
commerce-domain       reusable commerce vocabulary and invariants
      │                (financial documents, line items, money, change orders, payments,
      │                 deposits, principals)
      ▼
commerce-runtime      reusable runtime: PostgreSQL/HikariCP, JDBI, Flyway, Transactor,
      │                http4k on Jetty, configuration, error contract, /health, /ready,
      │                principals, sessions, service tokens, authorization administration,
      │                the financial ledger: document snapshots, deposits, payments,
      │                allocations, refunds, reconciliation (commerce.*)
      ▼
fionas-commerce       Fiona's application: customers, inquiries and their requested service,
                       Fiona's HTTP API and tables, the trusted priced-line boundary,
                       inquiry → financial-document ownership and line authorship,
                       canonical proposals and service plans, payment acceptance policy,
                       lifecycle and fulfillment, application.conf, Logback, main(), jar
fionas-web (separate) the customer catalog, its pricing, and the staff UI; calls Fiona as
                       a SERVICE for submissions and on behalf of a verified staff session
```

`fionas-commerce` depends on `io.github.castab:commerce-runtime:0.0.23`, which brings
`commerce-domain:0.0.23` with it. It contributes its migration schema and location,
permissions, and routes to the runtime through `ApplicationContributions`, and every write
goes through the runtime's shared `Transactor`:

```text
HTTP request
   │
   ▼
commerce-runtime error handling ({"code","message"}, optional validation violations)
   │
   ▼
Fiona contract route http/InquiryRoutes.kt     JSON DTO → application values (PricedLine…)
   │
   ▼
Fiona operation      inquiry/CreateInquiry.kt  opens one runtime transaction
   │
   ├── InquirySubmissionRepository, CustomerRepository, InquiryRepository ─┐
   └── MaterializeInquiryFinancialDocument → context.financialLedger ──────┴── same Transaction
                                   │
                                   ▼
                     PostgreSQL: fionas.* (Fiona)   commerce.* (runtime ledger)
```

The rules behind this structure are in [`AGENTS.md`](AGENTS.md) and
[`ARCHITECTURE.md`](ARCHITECTURE.md). Consumers upgrading from the catalog-backed API read
[`docs/trusted-priced-lines-migration.md`](docs/trusted-priced-lines-migration.md).

## Endpoints

**Inquiry API**, implemented by Fiona:

| Endpoint | Permission | Behavior |
|---|---|---|
| `POST /inquiries` | `fionas.inquiries.create`, **SERVICE only** | Requires `Idempotency-Key`. Records the customer, the inquiry, its descriptive `requestedService`, and the already-priced `lines` as canonical Estimate v1, atomically. `201` with the receipt (`id`, `createdAt`) and `Location`; identical retries return the same receipt. Never exposes the stored customer. A staff session is `403` even with the permission. |
| `GET /inquiries` | `fionas.inquiries.read` | Staff inbox: inquiries newest first, `limit` (1–100, default 25) per page, continued with the opaque `cursor` a page returns as `nextCursor`. |
| `GET /inquiries/{inquiryId}` | `fionas.inquiries.read` | The persisted inquiry, its customer, its requested service, and its lifecycle. `404` when unknown, `400` when the id is not a UUID. |
| `POST /inquiries/{inquiryId}/communications/acknowledge` | `fionas.communications.acknowledge` | Record explicit customer-email acknowledgement; repeated calls return `204`. |
| `GET /staff/dashboard` | **Both** `fionas.inquiries.read` and `commerce.financial-document.read` | One coherent snapshot of operational counts and enriched staff work queues. USER sessions and SERVICE tokens are supported. |
| `GET /staff/requests/{inquiryId}` | **Both** `fionas.inquiries.read` and `commerce.financial-document.read` | Coherent inquiry detail and current canonical financial lineage for staff review, with the published Quote's optional `servicePlan`. `400` for malformed UUID, `404` for unknown inquiry; successful responses are no-store. |
| `POST /staff/requests/{inquiryId}/quote-preview` | `commerce.financial-document.create`, `commerce.deposit-requirement.manage`, `fionas.financial-terms.manage`, **staff USER only** | Write-free preview of the initial Quote from staff-committed final lines: resolved lines, domain totals, deposit, service plan and `reviewToken`. See [Quote builder](#quote-builder). |
| `POST /staff/requests/{inquiryId}/proposals` | Same as preview | Atomic initial Quote publication with approved deposit terms; optionally the reviewed `lines`, `servicePlan` and `reviewToken`. See [Atomic canonical proposal publication](#atomic-canonical-proposal-publication). |
| `POST /staff/requests/{inquiryId}/proposals/quote-revisions` | Same as preview | Republish the canonical Quote from staff-committed final lines and replacement deposit terms. |
| `POST /staff/requests/{inquiryId}/proposals/deposit-revisions` | Same as preview | Republish the canonical Quote with new deposit terms only. |

**Who may author money.** Fiona trusts exactly two pricing authorities and nothing else:

- **The `fionas-web` server, as a SERVICE**, prices a customer's configured request with its
  own server-only catalog and pricing modules and submits `POST /inquiries` with the exact
  lines. A staff USER session can never submit priced inquiries: the route requires a SERVICE
  principal in addition to `fionas.inquiries.create` (`403 forbidden` otherwise).
- **A verified staff USER** authors every later negotiated amount: staff document creation,
  change orders, Quote preview and publication, Quote and deposit revisions, and standalone
  deposit approval and withdrawal. These routes
  require the commerce permission, `fionas.financial-terms.manage`, **and** a USER principal.
  A SERVICE token is refused (`403`) even when it holds every permission, so a SERVICE is never
  recorded as a staff approver. A backend-for-frontend proxying staff requests must forward the
  staff member's own session cookie (with a trusted `Origin`); there is no on-behalf-of header
  and no delegation.

The server never prices, reprices, or checks lines against a catalog, and no request may
carry an authoritative total: commerce-domain derives subtotals, tax and totals from the lines.

The server-side `fionas-web` obtains its SERVICE token from `POST /auth/service/token` (see
[Service authentication](#service-authentication)) and sends `Authorization: Bearer <token>`.
No valid authentication is `401 unauthenticated`; an authenticated principal without the
permission, or of the wrong kind, is `403 forbidden`. There is no static API key and no
implicit frontend trust; grants are resolved live on every request.

**Financial documents and payments API**, implemented by Fiona on commerce-runtime's
financial ledger (see [Financial documents and payments](#financial-documents-and-payments)).
Routes that author amounts ("staff terms") require a staff USER holding the listed permission
**and** `fionas.financial-terms.manage`; the others accept a staff session or SERVICE token:

| Endpoint | Permission | Behavior |
|---|---|---|
| `POST /inquiries/{inquiryId}/estimates` | staff terms: `commerce.financial-document.create` | Persists staff-committed `lines` as version 1 of a new RELATED Estimate the inquiry owns. `201` with the document and `Location: /financial-documents/{documentId}`. |
| `POST /inquiries/{inquiryId}/financial-documents` | staff terms: `commerce.financial-document.create` | Persists staff-committed `lines` as a new RELATED Estimate, Quote, or Invoice at version 1 (`stage`), with no predecessor. `201` and `Location`. |
| `GET /inquiries/{inquiryId}/financial-documents` | `commerce.financial-document.read` | The inquiry's documents, each at its latest version with its current settlement. |
| `GET /financial-documents/{documentId}` | `commerce.financial-document.read` | The latest version, who authored its lines (`linesAuthoredBy`), and current settlement. |
| `GET /financial-documents/{documentId}/history` | `commerce.financial-document.read` | Every version, oldest first, with its line authorship. |
| `POST /financial-documents/{documentId}/quote` | `commerce.financial-document.create` | Issues a RELATED Estimate as Quote, unchanged; canonical issuance uses staff proposals. |
| `POST /financial-documents/{documentId}/invoice` | `commerce.financial-document.create` | Manually invoices a RELATED Quote, unchanged. Canonical lineages require deposit-driven booking. |
| `POST /financial-documents/{documentId}/change-orders` | staff terms: `commerce.financial-document.create` | Commits the complete final `lines` (carry, replace by id, remove, add) to Estimates, Invoices and RELATED Quotes; canonical Quote revision uses staff proposals. |
| `POST /financial-documents/{documentId}/payments` | `commerce.payment.record` | Records a payment and applies all of it to the latest version, a quote or an invoice. `201`. |
| `GET /financial-documents/{documentId}/payments` | `commerce.financial-document.read` | Every payment ever allocated to any version of the document, each with its complete history: allocations (to any document), refunds, refund allocations, and derived reconciliation. `[]` when it has none. |
| `POST /payments` | `commerce.payment.record` | Records money received without assigning it to a document. `201` with the payment fact. |
| `GET /payments/unapplied` | `commerce.payment.record` | Complete payment histories with positive derived `reconciliation.unallocated`, including standalone receipts; ordered by `receivedAt`, then `paymentId`. |
| `POST /payments/{paymentId}/allocations` | `commerce.payment.record` | Allocates some or all of an existing payment to an exact, currently latest Quote or Invoice version. `201` with the allocation and reconciliation. |
| `POST /payments/{paymentId}/refunds` | `commerce.refund.record` | Refunds part or all of a payment, unwinding the allocations the request names. `201` with the refund, its unwinds, and the payment's reconciliation. |

**Deposit requirements and bulk financial reads**, implemented by Fiona on the runtime ledger:

| Endpoint | OperationId | Permission | Response |
|---|---|---|---|
| `GET /financial-documents/{documentId}/deposit-requirement` | `getFinancialDocumentDepositRequirement` | `commerce.financial-document.read` | `200` current `NONE`, `ACTIVE`, or `WITHDRAWN`. |
| `GET /financial-documents/{documentId}/deposit-requirement/history` | `getFinancialDocumentDepositRequirementHistory` | `commerce.financial-document.read` | `200 {revisions: [...]}`, oldest first. |
| `PUT /financial-documents/{documentId}/deposit-requirement` | `setFinancialDocumentDepositRequirement` | staff terms: `commerce.deposit-requirement.manage` | `200` new `ACTIVE` state: activation, replacement, or reactivation. |
| `DELETE /financial-documents/{documentId}/deposit-requirement` | `withdrawFinancialDocumentDepositRequirement` | staff terms: `commerce.deposit-requirement.manage` | `200` new immutable `WITHDRAWN` revision. |
| `POST /financial-documents/query` | `queryFinancialDocumentLineages` | `commerce.financial-document.read` | `200 {lineages: [...]}`, in request order. |

All requested documents must belong to Fiona through `fionas.inquiry_financial_documents`;
missing and unowned runtime lineages both return `404`. These routes use the same `AccessControl`.
The reads (both GETs and the query POST) accept a USER session or a SERVICE token. Approving and
withdrawing deposit terms (PUT/DELETE) are staff decisions: they require a **staff USER session**
holding `commerce.deposit-requirement.manage` **and** `fionas.financial-terms.manage`; a SERVICE
token is `403 forbidden` even with both, and the approver comes only from authentication.
Cookie-bearing PUT/DELETE/POST requests need a trusted Origin; token-only reads need none. Deposit
management does not require `commerce.financial-document.create`.

PUT takes `expectedDocumentVersion`, nullable `expectedRequirementRevision`, and a strict terms
union. For example:

```json
{
  "expectedDocumentVersion": 2,
  "expectedRequirementRevision": null,
  "terms": {"type": "FIXED", "amount": "100.00", "currency": "USD"}
}
```

The exact latest Quote/Invoice must match the expected version. Canonical lineages with
published proposal history reject standalone approval, replacement, reactivation and withdrawal
with `409 illegal_transition`, including Invoice/BOOKED. RELATED Quote/Invoice behavior remains
unchanged. Estimates reject activation
with `422 invariant_violated`. Null (or absent) requirement revision expects no history at all,
including no withdrawal history. A non-null revision must equal the latest requirement revision;
it is never a don't-care token. Replacement and reactivation use this same PUT.
Ownership/document checks, activation and returned financial-lineage projection share one
READ COMMITTED transaction under the association lock. Canonical published-lineage detection
reads proposal history in that same transaction. Unpaid Quote deposit changes use atomic
proposal reissuance; after booking the accepted requirement/history is immutable.

Terms accept exactly `FIXED` amount/currency or `PERCENTAGE` percentage, for example
`{"type":"PERCENTAGE","percentage":"25.125"}`. Unknown discriminators and mixed/missing fields
fail with `400 malformed_request`. All monetary values and percentages are exact decimal strings.
Fixed money requires an explicit matching ISO currency, Fiona's currency minor-unit precision,
and a positive amount no greater than the approval total. Percentages stay exact in `(0,100]`;
the runtime resolves them against the exact approval snapshot with HALF_UP currency rounding.
A zero resolved amount fails; nothing is clamped or converted. Original terms and the resolved
amount are frozen at approval; later document versions never reprice them.

Current state is a discriminated `state` union: `NONE` carries only `documentId`; `ACTIVE` carries
documentId, revision, optional previousRevision, persisted createdAt, approvalDocumentVersion,
original terms, requiredAmount (`amount`, `currency`) and `satisfied`; `WITHDRAWN` carries only
documentId, revision, previousRevision and createdAt. Satisfaction is the runtime's current
financial comparison `netApplied >= requiredAmount`; refund unwinds can make it false again.
History returns only `ACTIVE`/`WITHDRAWN`, oldest first, preserving timestamps, approval references,
original terms and frozen amounts. It has no historical satisfaction or synthetic `NONE` entry.

DELETE takes only `{"expectedRequirementRevision":2}`. It intentionally has no expected document
version; canonical lineages with proposal history reject it even after booking. RELATED lineages
remain eligible after document stage/version changes. It appends history without
copying prior terms. Missing history is `404`; stale tokens and runtime contention are
`409 conflict`; already withdrawn is the existing `409 illegal_transition`. Other invalid
terms/revisions are `422`. Each mutation locks Fiona's association row before checking/calling
the runtime in one transaction. Runtime NOWAIT conflicts roll back the whole operation; callers
reload/retry at their boundary. Fiona retries nothing and opens no nested transaction.

Query with POST body `{"documentIds":["<uuid>","<uuid>"]}`. Empty input returns an empty list;
duplicates fail `422`, and any unowned/missing id fails the whole request with `404`. One set-based
Fiona ownership query and one transaction-taking runtime `financialLineages(ids)` call run in an
unlocked REPEATABLE READ snapshot, retaining request order. No per-lineage pricing queries are
added. Each entry carries inquiryId, documentId, latest version/stage/total/currency, reconciliation
(grossAllocated, refundAllocations, netApplied, balance, currency), the same current deposit union,
and objective activity: latestDocumentVersionAt, optional latestDepositRequirementAt,
latestPaymentAllocationAt, latestRefundAllocationAt, and latestFinancialActivityAt. Activity maps
the runtime's persisted document/deposit creation and allocation/unwind `allocatedAt` times,
never payment `receivedAt` or unrelated standalone refund time. It carries no dashboard labels,
age, customer details or workflow state.

Commerce domain/runtime owns deposit vocabulary, invariants, persistence, frozen resolution,
reconciliation and bulk facts. Fiona owns lineage ownership, Quote/Invoice eligibility,
authorization exposure, HTTP contracts and the canonical deposit-satisfaction booking policy below.
Runtime satisfaction arithmetic remains authoritative; Fiona does not persist its result.

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

`/admin/access/permissions` is Fiona's only catalog route. Commerce-runtime 0.0.23's
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

A method an API path does not declare is `405` with an empty body.

A priced inquiry submission, as the `fionas-web` server sends it:

```bash
curl -i -X POST localhost:8080/inquiries -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <fionas-web service access token>' \
  -H 'Idempotency-Key: 6f1d0c8e-6c1a-4b7e-9d55-0c3f2b1a9e77' \
  -d '{
  "name": "Jane Doe",
  "email": "jane@example.com",
  "zipCode": "92626",
  "message": "Ice cream for a birthday.",
  "eventDate": "2026-12-05",
  "eventType": "BIRTHDAY",
  "requestedService": {
    "guestCount": 75,
    "guestCountIsMinimum": false,
    "durationMinutes": 120,
    "items": [
      {"label": "Vanilla soft serve", "group": "Soft serve", "key": "soft-vanilla"},
      {"label": "Horchata soft serve", "group": "Soft serve", "key": "soft-horchata"},
      {"label": "Waffle cones", "group": "Cones and cups", "key": "waffle-cone"}
    ],
    "pricingReference": "fionas-web-pricing@2026-10-01"
  },
  "lines": [
    {"description": "Base event service", "subDescription": "Up to 2 hours",
     "unitPrice": "250.00", "taxAmount": "0.00", "currency": "USD"},
    {"description": "Ice cream service", "subDescription": "75 guests", "quantity": "75",
     "unitPrice": "4.00", "taxAmount": "0.00", "currency": "USD"},
    {"description": "Waffle cones", "quantity": "75",
     "unitPrice": "0.75", "taxAmount": "0.00", "currency": "USD"}
  ]
}'
```

```json
{
  "id": "c755f7cd-1e28-4c75-a85f-d066ede7387d",
  "createdAt": "2026-09-26T21:19:39.321012Z"
}
```

`name` (at most 200 characters), `email`, `zipCode`, `eventDate`, `eventType`,
`requestedService` and `lines` are required; `message` is optional (at most 4000 characters)
and stays free-form text. Values are trimmed, and the email is lowercased. If a customer
already has that email, the inquiry is attached to that customer, whose stored name a later
inquiry does not change (see
[Customer matching](AGENTS.md#customer-matching-current-deliberately-simple-policy)). The
response is a receipt of the new inquiry only: it never contains the stored customer's id,
name, or email.

**`lines` are the authoritative financial input.** One to 100 lines, all in one currency.
Every amount and quantity is an exact decimal string, never a JSON number. A precise **unit
rate** is allowed, but every **settlement amount** is exact in the currency's minor units:

- with a `quantity` (nonzero), `unitPrice` is a rate of at most 12 decimal places, kept exactly
  in the ledger and every response, and the extended subtotal `unitPrice × quantity` must be
  exact in minor units: USD `0.125 × 8 = 1.00` is valid, `0.125 × 3 = 0.375` is rejected;
- without a `quantity` (a flat charge), `unitPrice` is the subtotal itself, so it has at most
  the currency's minor-unit digits (USD `0.125` flat is rejected);
- `taxAmount` is the whole line's tax, always in minor units.

Nothing is rounded. Negative lines (discounts, credits) are allowed, but the document total must not
be negative. There is no `total`, `subtotal` or line id in the request: Fiona assigns line
ids and commerce-domain derives every total. Fiona checks the shape and arithmetic, never the
prices: it has no catalog to compare against. Invalid lines are `422 validation_failed` and
write nothing.

**`requestedService` is descriptive only.** The guest count (and whether it is a minimum),
optional duration, the ordered `items` the customer chose (`label`, optional `group` and
`key` from the web catalog), and an optional `pricingReference` naming the web pricing
policy. Fiona stores it as pinned customer intent in `fionas.inquiries.requested_service`,
shows it to staff, and uses `guestCountIsMinimum` to label dashboard totals "from". It never
prices, validates against, or derives lines from it; `key` identifies nothing in Fiona.

One READ COMMITTED transaction claims the idempotency key, finds or creates the customer,
inserts the inquiry, appends the ledger's Estimate v1 snapshot with exactly the submitted
lines, records the `INITIAL_ESTIMATE` association, and records the SERVICE as the lines'
author. Any failure rolls everything back, including the key claim.

`POST /inquiries` requires exactly one `Idempotency-Key` header, an opaque, case-sensitive
token of 1–128 ASCII letters, digits, underscores or hyphens. UUIDs are supported; no
trimming or business meaning is assigned. The key is not a credential and need not be
secret. Missing, blank, repeated, oversized or malformed keys return `400 malformed_request`.
Authentication and request-shape validation precede the application command and write nothing.
No other endpoint requires this header.

`fionas.inquiry_submissions` holds a unique key, SHA-256 request fingerprint, unique
resulting inquiry id, and creation time. `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`
claims the key before any business write, waiting for any competing transaction. The
resulting inquiry id is reserved immediately; its non-null, deferred foreign key to
`fionas.inquiries` allows claim-before-inquiry but prevents an incomplete claim from
committing. A rollback removes the claim and all inquiry/Estimate state; a waiter either
observes the completed owner or claims the key after rollback. Keys have no expiration.

The fingerprint (encoding v2) is a deterministic, length-prefixed binary encoding of the
canonical command hashed with SHA-256: normalized name, email and optional message, ZIP,
event date and type, the complete requested service (items in order), and **every line's
values in submitted order** (description, sub-description, quantity, unit price, tax,
currency). Amounts compare numerically, so `"4.5"` and `"4.50"` are the same intent; a
changed amount, line order, or currency is a different intent. The key, the submitting
service, generated ids, timestamps and derived totals are excluded.

A committed same-key/same-intent replay returns the original `201 Created`, identical
`id`/`createdAt` body and `Location`, without writing anything. Same key with changed intent
returns `409 IDEMPOTENCY_KEY_REUSED` in the runtime `ErrorResponse` envelope, with
`Cache-Control: no-store`; no previous payload or fingerprint is exposed. Failed attempts do
not consume keys, so a corrected same-key retry may succeed. Different keys are distinct
commands, even with identical payloads; the existing concurrent new-email conflict remains.

`fionas-web` must create one non-secret submission token per logical form submission and
preserve it across timeouts, lost responses, retries and double delivery: two browser requests
for the same visible submission must resolve to the same key, carrying the same priced lines.
Independently generating a UUID inside each backend attempt defeats idempotency.

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
Inquiry submission additionally uses `IDEMPOTENCY_KEY_REUSED` (409) in that same envelope, and
staff Quote publication `QUOTE_REVIEW_STALE` (409).
Validation failures may also carry optional `violations`, each with a stable string `code`.
Ordinary value-validation failures can omit that list. Clients use codes to identify
failures and present `message` as diagnostic text; they never parse it for codes.

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
| `fionas.inquiries.create` | `fionas.inquiries` | `POST /inquiries` (priced submission; a SERVICE principal only) |
| `fionas.inquiries.manage` | `fionas.inquiries` | Mark inquiries served and close them |
| `fionas.communications.acknowledge` | `fionas.communications` | Explicitly acknowledge customer email attention |
| `fionas.financial-terms.manage` | `fionas.financial-terms` | As a verified staff USER, commit staff-authored lines, overrides, adjustments, proposal deposit terms and standalone deposit terms |

`fionas.inquiry-form.read`, `fionas.estimate-preview.create` (and its `fionas.pricing`
group) no longer exist, and Fiona no longer uses the runtime's `commerce.offerings.manage`.

The bootstrap Administrator role explicitly grants FinancialDocumentRead,
FinancialDocumentCreate, DepositRequirementManage, PaymentRecord, RefundRecord, PrincipalRead,
PrincipalManage, RoleRead, RoleManage, RoleAssign, the runtime's ServiceCredentialManage
(`commerce.service-credential.manage`, so the first administrator can issue a service's
credential, not only create it and assign its roles), CredentialsManage, InquiriesRead,
InquiriesManage, CommunicationsAcknowledge, and FinancialTermsManage. It does not grant
`fionas.inquiries.create`: priced submission belongs to the `fionas-web` SERVICE, and a staff
USER could not use it anyway. There is no wildcard. Future permissions are not granted
automatically, and the grants are fixed when bootstrap creates the role: startup never
changes an existing Administrator role. An installation upgrading from an earlier release
retains its existing grants. To enable newer capabilities for that role, first
`GET /admin/access/roles/commerce.administrator` and inspect its current permissions. Add
whichever of `fionas.financial-terms.manage`, `commerce.deposit-requirement.manage`,
`commerce.refund.record`, `fionas.inquiries.read`, `commerce.service-credential.manage`,
`fionas.communications.acknowledge`, and `fionas.inquiries.manage` it lacks to that set, then
`PUT /admin/access/roles/commerce.administrator/permissions` with the **complete desired
permission list**. This endpoint replaces the role's full set of grants; sending only the
new permission would remove every existing grant, including installation-specific ones.
Without `fionas.financial-terms.manage`, staff can read but not author financial lines.

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
service access token carries no cookie and needs no `Origin`. Priced inquiry submission
(`POST /inquiries`) requires `fionas.inquiries.create` and a SERVICE principal; listing and
reading inquiries require `fionas.inquiries.read`. Every route that authors financial lines or
terms requires a staff USER (see [Who may author money](#endpoints)). `/health` and `/ready`
remain public.

## Service authentication

Software callers, such as the server-side web frontend, authenticate as SERVICE principals
through commerce-runtime 0.0.23's service authentication. Fiona composes it; it implements
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
inquiries, and one granted a commerce permission may use that permission's reads and payment
routes. Two deliberate exceptions name the principal kind: priced inquiry submission accepts
only a SERVICE (the web pricing authority), and staff-authored financial terms accept only a
USER (a SERVICE is never recorded as a staff approver). Routes that need a human say so
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
4. Grant exactly `fionas.inquiries.create` (in the role body, or
   `PUT /admin/access/roles/fionas.web/permissions`). Never grant it staff, administration,
   or financial permissions; staff work goes through the staff member's own session.
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

## Atomic canonical proposal publication

Fiona publishes its canonical `INITIAL_ESTIMATE` Quote only through `IssueInquiryProposal`:
Estimate -> immutable Quote + active deposit approved against that exact Quote + append-only
`InquiryProposal` issuance commit together. Staff explicitly supplies shared `DepositTerms`;
`FIONAS_DEFAULT_DEPOSIT_TERMS` proposes 20% and never writes an implicit approval. RELATED
lineages retain their standalone shared financial behavior.

`fionas.inquiry_proposals` stores only publication UUID, inquiry/lineage identities,
Quote version, deposit revision, issuance kind, microsecond Clock time and the approving
staff USER (`issued_by`, a foreign key to `commerce.users`, so a SERVICE cannot be recorded).
Its composite FK references Fiona's association and the exact published runtime snapshot.
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
before reviewed-token checks. Quote revision commits staff-authored final lines (see
[Quote builder](#quote-builder)) with the no-financial-change rule, appends a same-stage Quote,
then replaces the deposit against the new Quote even if terms remain 20%. Deposit-only revision keeps the Quote
version and rejects numerically equivalent same-form terms; changing percentage to fixed
is meaningful even if the resolved amount is equal. Both require exact reviewed Quote and
deposit revision tokens. Any historical `grossAllocated > 0` blocks both revisions, even
when refunds unwind all applied value. Exact complete deposit receipt atomically promotes Quote -> Invoice/BOOKED; refunds never
demote it. Standalone allocations cannot fund canonical deposits.

Every publication is the exact `(documentId, documentVersion, depositRequirementRevision)`.
`IsCurrentPayableInquiryProposal` reads one REPEATABLE READ snapshot: only the latest
publication whose exact Quote and active approval/revision still match is payable. Every
reissue supersedes all earlier ids; Invoice makes all publication targets non-payable.
Business UUIDs are not bearer secrets. No public payment link, payment provider, delivery,
contact collection, cancellation, or post-payment adjustment workflow is introduced.

`IsCurrentPayableInquiryProposal` is only a read/query seam. `RecordDocumentPayment` validates
the expected proposal id and exact frozen deposit amount under the association lock, then
records and allocates the receipt and books in that same transaction. A separate currentness
query never authorizes acceptance.

The durable proposal row is the business event for future at-least-once integrations.
It commits with the financial facts, has a stable id, and never implies STAFF_EMAIL_SENT.
Actual delivery alone may append communication activity. A future generic durable dispatch
capability belongs upstream when needed; no after-commit crash-window publisher exists here.

POST `/staff/requests/{inquiryId}/proposals` (`issueInquiryProposal`), its `/quote-revisions`
child (`reviseInquiryQuoteProposal`) and `/deposit-revisions` child
(`reviseInquiryProposalDeposit`) derive the document from the inquiry. All require
`commerce.financial-document.create`, `commerce.deposit-requirement.manage` and
`fionas.financial-terms.manage`, a staff USER principal (a SERVICE token is `403`), and the
unsafe-cookie Origin policy. Responses are 200 with
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

## Quote builder

Staff turn the canonical Estimate into the initial Quote by committing the **complete,
ordered final lines** they negotiated with the customer. Fiona never prices them: a verified
staff user is the pricing authority for negotiated terms. A preview writes nothing; one
approval publishes exactly the reviewed result. Contract rules:
[`AGENTS.md`](AGENTS.md#quote-builder-staff-committed-lines).

```text
GET  /staff/requests/{I}                 Estimate v1 (REQUESTED): financial.version, lines with ids
POST /staff/requests/{I}/quote-preview   lines + servicePlan + terms → resolved lines, totals,
                                         deposit, plan, reviewToken (no writes)
POST /staff/requests/{I}/proposals       same lines + servicePlan + terms + reviewToken →
                                         [Estimate v2 when charges change] → Quote, service plan,
                                         deposit approved against that Quote, INITIAL proposal
GET  /staff/requests/{I}                 QUOTED, proposal, depositRequirement, servicePlan
```

Each entry of `lines` names exactly one of:

- `lineItemId`: a line of the reviewed version, with its complete values. Unchanged values
  carry the line as is; changed values replace it **in place under the same id** (a direct
  override, for example a negotiated package rate).
- `key`: a new line (a bespoke service, a separate charge, discount or credit). Its ledger id
  is derived from the document, the reviewed version and the key, so a preview and its
  approval agree. Keys are 1–64 ASCII letters, digits, `_` or `-`, unique per request.

Reviewed lines left out are **removed**. Order is the final order. Every line carries the
same exact-decimal fields as an inquiry line (`description`, optional `subDescription` and
`quantity`, `unitPrice`, `taxAmount`, `currency`); there is never a total. Fiona derives one
commerce-domain `ChangeOrder` (remove, replace in place, add) that turns the reviewed lines
into exactly these, keeping ids wherever the line survives, even when it moves. Only the same
ordered ids with numerically equal values are "no change"; a reorder or a remove-and-add of
financially identical lines is a real change, reviewed and published as such.

The churro example: the customer asked for soft serve (Estimate v1, $681.25), and staff agree
on churros instead, with a courtesy discount:

```json
POST /staff/requests/{inquiryId}/quote-preview
{"expectedDocumentVersion": 1,
 "lines": [
   {"key": "churros", "description": "Churro catering service", "subDescription": "Prepared on site",
    "quantity": "1", "unitPrice": "450.00", "taxAmount": "0.00", "currency": "USD"},
   {"key": "courtesy", "description": "Courtesy discount",
    "unitPrice": "-50.00", "taxAmount": "0.00", "currency": "USD"}],
 "servicePlan": {"description": "Churro catering for an evening reception", "guestCount": 100,
   "durationMinutes": 120, "items": ["Churros with chocolate sauce", "Cinnamon sugar"],
   "lineNotes": [{"key": "courtesy", "note": "Returning customer courtesy"}]},
 "terms": {"type": "PERCENTAGE", "percentage": "20"}}
```

The preview answers `financialChange: true`, `quoteVersion: 3`, both lines with their derived
ids and `origin` (`CARRIED`, `REPLACED` or `NEW`, with the `key` of new lines), `subtotal`
`400.00`, `total` `400.00`, `deposit.requiredAmount` `80.00`, the resolved `servicePlan` (notes
now name line ids), and a `reviewToken`. Issuing the same body plus `"reviewToken"` appends
Estimate v2 ($400.00, every soft-serve line removed) and Quote v3, and returns
`{proposal, financial, depositRequirement, servicePlan}`. Without any financial change the chain
is Estimate v1 → Quote v2 (the Estimate's own lines and ids). No catalog is read at any point.
Issued is not sent: nothing is emailed, paid or booked; the exact deposit payment books the
Quote as before.

- **Preview** (`previewInquiryQuote`) runs in one unlocked REPEATABLE READ: canonical lineage
  at exactly `expectedDocumentVersion`, Estimate stage, coherent empty proposal/deposit history,
  then composition. It never writes, even on failure. `200` no-store.
- **Issuance** accepts the deposit-only body unchanged (`expectedDocumentVersion` and `terms`
  publish the Estimate's lines as they are). `lines` and `reviewToken` come together or not at
  all, and `servicePlan` requires `lines` (`400`). Under the association lock Fiona re-composes
  from authoritative state and requires the identical token; a result that no longer matches
  is `409 QUOTE_REVIEW_STALE` (no-store); preview again.
- **Quote revision** (`POST …/proposals/quote-revisions`) takes `expectedDocumentVersion`,
  `expectedDepositRequirementRevision`, `lines` against the current Quote, `terms`, and an
  optional `servicePlan`; it appends a same-stage Quote and replaces the deposit. Identical
  lines are `422` `NO_FINANCIAL_CHANGE`.
- **Authority.** Preview, issuance and both revisions require `commerce.financial-document.create`,
  `commerce.deposit-requirement.manage` and `fionas.financial-terms.manage`, and a staff USER
  (`403` for a SERVICE token). Cookie requests need a trusted `Origin`.
- Errors use the runtime envelope: `400 malformed_request` for unreadable or contradictory
  shapes (both or neither of `lineItemId`/`key`, an unreadable UUID); `409 conflict` (stale
  `expectedDocumentVersion`), `illegal_transition` (no longer an unissued Estimate) or
  `QUOTE_REVIEW_STALE`; `422 validation_failed` for invalid lines (empty or more than 100, a
  blank description, malformed or over-precise decimals, an inexact subtotal) and with stable
  `violations` codes: `LINE_NOT_IN_REVIEWED_DOCUMENT`, `CURRENCY_MISMATCH`,
  `NO_FINANCIAL_CHANGE`, `NEGATIVE_DOCUMENT_TOTAL`, `QUOTE_TOTAL_NOT_POSITIVE`,
  `SERVICE_PLAN_LINE_NOT_FOUND`.
- **Service plan** (`servicePlan` on issuance, Quote revision and `GET /staff/requests/{inquiryId}`):
  the money-free commitment the staff user approved with this exact Quote: `documentId`,
  `documentVersion`, `reviewedDocumentVersion`, a free-text `description`, optional
  `guestCount` and `durationMinutes`, ordered `items`, `lineNotes` by `lineItemId`,
  `approvedAt` and `approvedBy` (the staff USER id). It names no price, offering or catalog;
  join `lineNotes` with the financial lines by id. Quotes published without a plan have none.

## Financial documents and payments

Every accepted inquiry already creates its canonical Estimate. Staff evolve that lineage:

```text
Inquiry I
   │ POST /inquiries                                    D/v1 canonical Estimate
   │ POST /financial-documents/{D}/change-orders         D/v2 Estimate   (staff-committed lines)
   │ POST /staff/requests/{I}/proposals                  D/v3 Quote + approved $300 deposit + issuance (same lines)
   │ POST /financial-documents/{D}/payments              $300 allocated to D/v3; D/v4 Invoice atomically
   │ POST /financial-documents/{D}/change-orders         D/v5 Invoice    (staff-committed lines)
   │ POST /inquiries/{I}/served                          SERVED fact (Invoice unchanged)
   │ POST /financial-documents/{D}/payments              final payment → allocated to D/v5
   ▼ POST /inquiries/{I}/close                           CLOSED fact, only at exact zero balance
GET /financial-documents/{D}   latest Invoice D/v5, settlement across the whole lineage
```

An inquiry can also begin a new lineage directly at `D/v1 Quote` or `D/v1 Invoice`
through `POST /inquiries/{inquiryId}/financial-documents`. These are legitimate first
snapshots with `previousVersion: null`; no Estimate or Quote is synthesized before them.
These staff-created lineages are RELATED and never drive inquiry lifecycle. The existing
`/estimates` endpoint creates a RELATED `D/v1 Estimate` through the same operation. All of
them persist the staff-committed `lines` exactly.

### Inquiry lifecycle and fulfillment

`GET /staff/requests/{inquiryId}` (`readStaffRequest`) returns `inquiry` and `financial`
through the existing DTOs, `suggestedDepositTerms` (20%), optional `proposal`, and current
`depositRequirement`. One unlocked REPEATABLE READ covers all customer/inquiry, lifecycle,
financial, deposit and publication facts. Both existing read permissions remain required,
and successful responses use no-store. REQUESTED has no publication and a NONE deposit;
QUOTED must have the exact published Quote/deposit pair or the read fails internally.
BOOKED retains the latest proposal as historical context while financial describes Invoice.
Use inquiryId and financial.version to explicitly issue approved terms through the staff
proposal operation. Reading never changes state or sends a communication.
Staff `GET /inquiries/{inquiryId}` includes `lifecycle`: canonical `documentId`, projected
`stage`, and optional `served`/`closed` facts. Each fact has `occurredAt`, `principalKind`
(`USER` or `SERVICE`) and `principalId`. Detail requires only `fionas.inquiries.read` and
reads inquiry, canonical latest stage and operational facts in one REPEATABLE READ snapshot.

| Canonical latest document and fulfillment | Lifecycle stage |
|---|---|
| Estimate, no fulfillment | REQUESTED |
| Quote, no fulfillment | QUOTED |
| Invoice, no served fact | BOOKED |
| Invoice, served, not closed | SERVED |
| Invoice, served and closed | CLOSED |

Issuing Quote is the firm proposal boundary. Booking requires one complete, exact deposit
payment naming the current proposal through `RecordDocumentPayment`. Partial and excessive
deposit payments reject without writes; standalone `AllocatePayment` cannot fund a canonical
Quote. Fiona validates the publication and runtime frozen deposit amount under the association
lock, then records/allocates the whole payment and promotes Quote to Invoice atomically.
Promotion or authorship-copy failure rolls back everything. Old proposal ids conflict even when
deposit-only republication keeps the Quote version and amount. Historical allocation blocks
acceptance even after refunds unwind all applied value.

If a customer can pay less, staff first negotiates and publishes revised deposit terms. Extra
money requires two explicit receipts: exact deposit against Quote, then a separate ordinary
Invoice payment. Invoice partial payments remain supported. Application results return the
current Invoice; HTTP receipts retain the allocation's accepted Quote reference and current
settlement. Read document/detail/bulk views for the Invoice. Refunds never undo booking.

`POST /financial-documents/{documentId}/invoice` (`issueInvoice`) rejects canonical
INITIAL_ESTIMATE lineages with `409 illegal_transition`. It remains available for RELATED
Quotes, whose Invoices do not book inquiries. First-snapshot RELATED Invoices likewise
leave the canonical lifecycle unchanged. Invoice is the durable booking fact: later refunds
may make deposit satisfaction false without demotion. Invoice change orders retain BOOKED or
SERVED and preserve fulfillment provenance. Ordinary change orders on a CLOSED canonical
Invoice return `409 illegal_transition`; a separate post-close correction workflow is deferred.
RELATED financial lineages and existing payment/refund operations retain their eligibility.

Fiona change orders allow negative lines but reject a negative resulting document total
before persistence (`422 validation_failed`). Zero Invoice totals are valid. A negative
balance means overpayment and is permitted; refunds require explicit refund/allocation-unwind
operations. Zero canonical Quotes cannot satisfy the existing positive-deposit publication
policy. Change orders carry, replace, remove and add individual lines by id, so manual
adjustments survive later edits.
There is no zero-deposit booking, separate Booking aggregate or stored five-state status.

| Action | operationId | Eligibility |
|---|---|---|
| `POST /inquiries/{inquiryId}/served` | `markInquiryServed` | BOOKED; records service without financial mutation |
| `POST /inquiries/{inquiryId}/close` | `closeInquiry` | SERVED and current canonical Invoice balance exactly zero |

Both actions take no body and return `InquiryLifecycleResponse` (`200`), recording the
authenticated principal and injected Clock timestamp at microsecond precision. They require
`fionas.inquiries.manage`, independently of inquiry/financial read grants. Session cookies
require trusted Origin; token-only SERVICE calls use ordinary live permission enforcement.
Anonymous/forbidden requests return `401`/`403`; missing inquiry `404`; malformed id `400`;
ineligible or repeated transitions `409 illegal_transition` without rewriting provenance.
New bootstrap Administrator roles include the new permission. Existing roles are never
expanded at startup: read their current grants, add `fionas.inquiries.manage`, then replace
the complete desired grant set through `/admin/access`.

Fiona V12 `inquiry_fulfillment` stores served time/principal and optional complete closed
time/principal. Closed requires served at both model and database level. It stores no
financial stage, balance or deposit satisfaction. Positive outstanding balance and negative
overpayment both prevent closure; equality uses exact decimal semantics. There is no
automatic close, date-driven progression, unserve or reopen. Later ledger mutations after
closure remain allowed. Impossible fulfillment before a canonical Invoice fails internally.

`ReadInquiryOperationalStates` provides an application-only operational snapshot of all
inquiries. Set-based canonical association, runtime financial lineage and fulfillment reads
share one unlocked REPEATABLE READ transaction; an additional inquiry-ID read checks that
every inquiry has its canonical relationship. Missing relationships fail instead of omitting
inquiries. Each state retains the runtime's authoritative `FinancialLineageView` and uses
`InquiryLifecycle.project`. Pure counts classify New as REQUESTED, Quoted as QUOTED,
Booked as BOOKED or SERVED, and Needs closing as SERVED with an exactly-zero current
Invoice balance, independent of decimal scale. Booked and Needs closing overlap; CLOSED
contributes to neither. Event dates and deposit satisfaction do not affect these counts.
Operational states and counts are never persisted. The standalone reader retains its own
transaction; its transaction-taking core also composes with `ReadStaffDashboard`.

`GET /staff/dashboard` (`readStaffDashboard`) returns `asOf`, `summary` (the four counts
above), and `workQueue`. It requires **both** `fionas.inquiries.read` and
`commerce.financial-document.read`, resolved live for USER sessions or SERVICE tokens;
the `/staff` path adds no human-only restriction. No role grants change at startup.
It returns `200`, `401`, `403`, or a caller-safe `500` for missing/corrupt data, using
the runtime's error envelopes. Successful responses have `Cache-Control: no-store`.

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

Fiona V13 `inquiry_communications` stores append-only source facts: activity UUID, inquiry
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

The responsibilities are split three ways:

```text
commerce-domain      FinancialDocument (Estimate → Quote → Invoice), ChangeOrder, LineItem,
                     Money, PaymentRecord, PaymentAllocation, RefundRecord, RefundAllocation,
                     reconciliation invariants
commerce-runtime     financial snapshot persistence and lifecycle orchestration, payment and
                     allocation and refund persistence, reconciliation, transaction-aware ledger
                     operations (context.financialLedger)
fionas-commerce      inquiry → document ownership/purpose, line authorship, the trusted
                     priced-line boundary, starting stage, change-order resolution,
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

**Lines are authored, never derived.** Every snapshot's lines come from an authorized
pricing authority exactly as committed: the `fionas-web` SERVICE for the canonical Estimate v1,
a verified staff USER for everything after it. Once persisted, the snapshot's description,
quantity, price, tax, currency and derived amounts stand alone; no catalog, requested service
or pricing input is ever consulted to read, evolve, transition or settle it. Later versions
evolve from the previous snapshot plus explicit line changes.

`fionas.financial_document_authorship` records, for each exact version whose lines were
committed, who committed them (`USER` or `SERVICE` principal) and when. Transitions copy the
predecessor's authorship to the successor (the lines did not change); a version without a row
was written outside Fiona's authored paths. It holds no money. Document responses expose it as
`linesAuthoredBy` (`principalKind`, `principalId`, `recordedAt`).

```text
D/v1 Estimate  $681.25   lines by SERVICE fionas-web (customer submission)
D/v2 Estimate  $400.00   lines by USER staff (churros + courtesy discount)
D/v3 Quote     $400.00   copied from D/v2 (issuance never changes lines)
D/v4 Invoice   $400.00   copied from D/v3 (deposit-driven booking)
```

**Staff creation takes final lines.** `POST /inquiries/{inquiryId}/financial-documents` takes
`stage` (`ESTIMATE`, `QUOTE`, or `INVOICE`) and `lines`; `/estimates` takes `lines` only. They
cannot accept totals, document versions or predecessors. Fiona creates version 1, associates it
with the inquiry as `RELATED`, and records the staff USER as author in one transaction. A
direct Quote can later become an Invoice; a direct Invoice has no further transition.

```bash
curl -s -X POST localhost:8080/inquiries/$INQUIRY/financial-documents -b "$COOKIE" -H "Origin: $ORIGIN" \
  -H 'Content-Type: application/json' -d '{
  "stage": "QUOTE",
  "lines": [
    {"description": "Churro catering service", "subDescription": "Prepared on site", "quantity": "1",
     "unitPrice": "450.00", "taxAmount": "0.00", "currency": "USD"},
    {"description": "Courtesy discount", "unitPrice": "-50.00", "taxAmount": "0.00", "currency": "USD"}
  ]}'
```

A document response has the ledger's facts (`id`, `version`, `createdAt`, `previousVersion`, `stage`
`ESTIMATE`/`QUOTE`/`INVOICE`, `lines` with their durable ids, `subtotal`, `taxAmount`,
`total`, `currency`), the owning `inquiryId`, optional `linesAuthoredBy`, and, on
the latest version only, `reconciliation`: `grossAllocated`, `netApplied`, and `balance`,
derived by commerce-runtime across every version of the lineage. Historical versions in
`/history` carry no reconciliation. Every amount is an exact decimal string.

Every immutable version includes `createdAt`, the RFC 3339 string of the runtime's
persisted `Instant` (for example `"createdAt":"2026-09-29T18:30:00.123456Z"`). PostgreSQL
assigns it when that exact snapshot is inserted. Fiona reads `latestVersion` or
`versionHistory` in its existing `REPEATABLE_READ` transaction and preserves that metadata
alongside its line authorship. Reads, inquiry document lists, histories, and document
mutation responses all expose it. It stays unchanged on reread and is distinct from
Fiona's inquiry-association timestamp; Fiona generates no document creation timestamp.

**Transitions never change lines.** `quote` appends a quote with the estimate's lines and copies
their authorship; manual `invoice` does the same from a RELATED quote.
Canonical Quotes instead reach Invoice through atomic deposit satisfaction. There is no
estimate-to-invoice shortcut: a transition the latest version does not have is
commerce-runtime's `409 illegal_transition`.

**Change orders commit final lines while preserving the stage.** Canonical Quotes use
`POST /staff/requests/{inquiryId}/proposals/quote-revisions`, including explicit replacement
deposit terms and both reviewed tokens. The standalone route supports canonical Estimates,
booked Invoices and RELATED lineages:

```json
POST /financial-documents/{documentId}/change-orders
{"expectedVersion": 1,
 "lines": [
   {"lineItemId": "<base service line id>", "description": "Base event service",
    "unitPrice": "200.00", "taxAmount": "0.00", "currency": "USD"},
   {"key": "travel", "description": "Additional travel fee",
    "unitPrice": "25.00", "taxAmount": "0.00", "currency": "USD"}]}
```

The lines follow the [Quote builder](#quote-builder) rules: `lineItemId` carries or replaces
a reviewed line in place, `key` adds one, omitted lines are removed. Fiona derives the
commerce-domain `ChangeOrder`, applies it through the domain to prove it yields exactly these
lines, rejects a negative total, and appends the successor through the ledger's
expected-version `changeOrder`. Only the reviewed snapshot itself, the same line ids in the same
order with numerically equal values (`100`, `100.0` and `100.00` are equal), is
`422 NO_FINANCIAL_CHANGE`. Identity is part of the snapshot: reordering financially identical
lines, or omitting a line and adding an identical one under a new `key`, appends a new version
with exactly the requested ids and order. Lines are never matched by description or position:
identity is the id the caller names.

**Every document-lineage mutation names the version it acts on.** `expectedVersion` (transitions and change
orders) and `documentVersion` (allocations and combined payments) must be the latest version; otherwise the request is
`409 conflict` and nothing is appended, so staff never act on a version they did not see.
Every ledger mutation also passes that expected version to commerce-runtime 0.0.23
(`changeOrder`, `issueQuote` and `issueInvoice` take an `expectedDocumentVersion`), so the
runtime rejects a stale successor too.
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

Fiona requires that version to remain latest and be a RELATED Quote or any Invoice, locks its lineage,
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
 "expectedProposalId": "34b41196-38b8-4e27-a48d-e2aaf896f570",
 "receivedAt": "2026-09-27T17:05:00Z",
 "externalReference": {"provider": "square", "reference": "pay_7Q2Rk9"}}
```

- `documentVersion` must be the latest version, and it must be a quote (a deposit) or an
  invoice; an estimate is `422 invariant_violated`.
- `expectedProposalId` is required for a canonical Quote and must identify the exact current
  payable proposal; missing identity is `422 validation_failed`, stale identity is `409 conflict`.
  Invoice and RELATED payments need no proposal identity.
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
total minus the net applied. Over-application remains allowed for Invoices/RELATED payments;
canonical Quote receipts must equal the complete deposit exactly. The balance may be negative. The
response names the payment, the allocation, the exact version, and the settlement after it.
The two facts are committed atomically. All payment amounts remain exact decimal strings,
never JSON floating-point numbers. Provider SDKs, webhooks, stored payment status,
stored document balance, separate Booking aggregates, events/outbox/CQRS, and allocation
reversals remain outside this workflow.

**One transaction per operation.** Each operation opens one runtime transaction and passes
it to every ledger call (`context.financialLedger.create(transaction, …)`,
`issueQuote(transaction, …)`, `changeOrder(transaction, …)`,
`recordPayment(transaction, …)`, `allocatePayment(transaction, …)`,
`recordPaymentAgainstDocument(transaction, …)`, `recordRefund(transaction, …)`,
`reconcilePayment(transaction, …)`, `reconcile(transaction, exactReference)`,
`paymentHistoriesForLineage(transaction, …)`) and to Fiona's repositories. If any step fails,
the commerce snapshot, payment, or allocation rolls back with Fiona's association, authorship,
proposal and service plan.

## API contract and OpenAPI

The Fiona API describes itself. Each endpoint is an http4k contract route that carries its
own OpenAPI metadata next to its handler: path, method, `operationId`, summary, tag,
request and response bodies with examples, and every status it answers. The OpenAPI
document is rendered from those routes; there is no hand-maintained `openapi.json` or YAML,
so changing an endpoint changes its documentation in the same place.

- **`GET /openapi.json`** is the machine-readable contract, served live by the application.
  It needs no database and describes Fiona's API (inquiries, staff requests and proposals,
  financial documents, deposits, payments, and authentication) and the runtime capabilities
  Fiona mounts (`/admin/access`, `/authorization/me`, `/auth/service/token`), not the runtime's
  `/health` and `/ready` or the documentation routes.

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
  network access. `./gradlew build` runs it, and pull request CI keeps the file as the
  ephemeral `fionas-commerce-openapi` workflow artifact of every successful run, for reviewing
  a change's contract. The permanent, versioned contract other applications should use without
  a deployed instance is the asset every release attaches to its GitHub Release (see
  [Releasing](#releasing)).

The document is OpenAPI 3.1.0. `info.version` is the Gradle project version
(`0.0.0-SNAPSHOT` by default in `gradle.properties`; a release build sets
`-Pversion=<version>`). It declares no server host, so it is the same in every
environment. Its 63 operations have stable, unique `operationId`s. Fiona's own are
`createInquiry`, `listInquiries`, `getInquiry`, `acknowledgeInquiryCommunication`,
`markInquiryServed`, `closeInquiry`, `readStaffDashboard`, `readStaffRequest`,
`previewInquiryQuote`, `issueInquiryProposal`, `reviseInquiryQuoteProposal`,
`reviseInquiryProposalDeposit`, `createInquiryEstimate`, `createInquiryFinancialDocument`,
`listInquiryFinancialDocuments`, `getFinancialDocument`, `getFinancialDocumentHistory`,
`issueQuote`, `issueInvoice`, `createChangeOrder`, `getFinancialDocumentDepositRequirement`,
`getFinancialDocumentDepositRequirementHistory`, `setFinancialDocumentDepositRequirement`,
`withdrawFinancialDocumentDepositRequirement`, `queryFinancialDocumentLineages`,
`recordPayment`, `listFinancialDocumentPayments`, `recordStandalonePayment`,
`listUnappliedPayments`, `allocatePayment`, `recordRefund`, `login`, `logout`,
`getCurrentUser`, and `setStaffPassword`; the runtime contributes `serviceAuthenticationIssueToken`,
`authorizationCurrentPrincipal`, and the `authorization…` administration operations.

Fiona's schemas are derived from the kotlinx.serialization descriptors of the transport
DTOs, the wire format itself, so `required` matches what the server reads and writes: strings,
`int32` and `int64` integers, booleans, arrays, enums, sealed unions with a `type`
discriminator (deposit terms), and nested objects, each its own component. Every amount is a
string with a signed-decimal `pattern`; no schema accepts a JSON number for money. Known
gaps: the `Location` header of `201` is described in prose only, because http4k 6.58's
contract metadata cannot declare response headers. Fiona uses the runtime's
`ValidationErrorResponse` and `ValidationViolationResponse` schemas for validation failures,
with optional `violations`; ordinary errors retain `ErrorResponse`. Swagger UI groups the
operations under **Inquiries**, **Staff dashboard**, **Staff requests**, **Staff proposals**,
**Financial documents**, **Deposit requirements**, **Payments**, **Authentication** (which
also holds the runtime's `POST /auth/service/token`), **Authorization** and **Staff
administration**. The document declares two security schemes for the two authentication
transports: `staffSession` (an API key in the `__Host-fionas_session` cookie) and
`serviceAccessToken` (HTTP bearer, the runtime's `serviceAccessTokenOpenApiSecurity`). Routes
open to either principal list them as two separate requirement objects (OR). Routes that
accept only one kind say so: `createInquiry` lists `serviceAccessToken` alone, and every
staff-terms route (`createInquiryEstimate`, `createInquiryFinancialDocument`,
`createChangeOrder`, `previewInquiryQuote`, and the three proposal operations) lists
`staffSession` alone. `POST /auth/logout` lists `staffSession` OR anonymous (`{}`), never
`serviceAccessToken`: it is idempotent browser cleanup, while a request authenticated only by
a service access token is `403`. `POST /auth/login` and `POST /auth/service/token` declare no
security. The schemes are documentation only: enforcement stays each route's `AccessControl`
and principal guard. **Known gap:** the runtime capability routes (`/admin/access`,
`/authorization/me`) enforce the same `AccessControl` but carry no security metadata, because
commerce-runtime 0.0.23 offers the host no way to add it. Fiona does not wrap or clone runtime
routes to change their metadata; see
[`AGENTS.md`](AGENTS.md#known-upstream-gaps-last-audited-at-commerce-0023).
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
| `FIONAS_EVENT_TIME_ZONE` | IANA zone interpreting event LocalDate boundaries, independent of UTC server Clock; invalid values fail startup | `America/Los_Angeles` |
| `FIONAS_TRUSTED_ORIGINS` | Comma-separated exact browser origins for login and cookie-authenticated mutations | none; browser login is denied until configured |
| `FIONAS_BOOTSTRAP_ADMIN_USERNAME` | First administrator's username; required with password and display name | none |
| `FIONAS_BOOTSTRAP_ADMIN_PASSWORD` | First administrator's password, at least 12 characters; remove after provisioning | none |
| `FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME` | First administrator's nonblank display name; required with username and password | none |
| `FIONAS_BOOTSTRAP_ADMIN_FIRST_NAME`, `FIONAS_BOOTSTRAP_ADMIN_LAST_NAME` | Optional profile fields | none |
| `LOG_LEVEL` | Level of the application's and runtime's own logs | `INFO` |

Logging is Logback ([`logback.xml`](src/main/resources/logback.xml)): `key=value` lines
on stdout, with library logging at `WARN`/`INFO`. The database password is never logged.

## Database and migrations

> **Rebaseline for the trusted priced-line release (commerce 0.0.23).** commerce-runtime
> 0.0.23 replaces its migration history with a single `V1__commerce_baseline`, and Fiona
> replaces its `V1`–`V15` with a single `V1__fionas_baseline`. Neither stream converts an
> older database: a database migrated by an earlier release **fails Flyway validation at
> startup and must be recreated by hand.** The application never drops, resets or repairs a
> database itself. For a disposable development or staging database:
>
> 1. Stop every application instance using the database.
> 2. Drop and recreate the database (`DROP DATABASE fionas; CREATE DATABASE fionas OWNER fionas;`),
>    or, for the local Compose database, remove its volume: `docker compose down -v`, then
>    `docker compose up -d db`.
> 3. Start the upgraded application once with the bootstrap administrator variables; both
>    baselines apply, runtime first.
> 4. Provision the `fionas-web` service and grant its role exactly `fionas.inquiries.create`
>    (see [Service authentication](#service-authentication)).
>
> There is no `baselineOnMigrate`, `flyway repair`, data conversion or compatibility read.
> Catalog contents are not migrated: the catalog now lives in `fionas-web`. See
> [`docs/trusted-priced-lines-migration.md`](docs/trusted-priced-lines-migration.md).

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
- The two streams have independent version spaces: Fiona's next migration is `V2`,
  regardless of the runtime's numbering.
- `V1__fionas_baseline` creates, in `fionas`: `customers` (unique email), `inquiries`
  (customer, message, ZIP, event date/type, `requested_service` jsonb, and the
  `inquiries_created_at_id_idx` keyset index), `user_credentials` (→ `commerce.users`),
  `inquiry_financial_documents` (lineage ownership and purpose, → the lineage's first runtime
  snapshot, at most one `INITIAL_ESTIMATE` per inquiry), `financial_document_authorship`
  (→ the exact runtime snapshot), `inquiry_submissions` (idempotency keys, deferred FK to the
  inquiry), `inquiry_fulfillment`, `inquiry_communications`, `inquiry_proposals` (→ the exact
  published Quote snapshot and the approving `commerce.users` row), and
  `inquiry_service_plans` (→ the Quote and reviewed snapshots and the approving user). Its
  eight foreign keys into `commerce` reference only published runtime contracts
  (`commerce.users` and `commerce.financial_document_snapshots`). No Fiona table holds an
  amount, total, stage, balance, catalog or pricing input.
- Composing the runtime runs the migration phase before anything is served. By default
  (`MIGRATIONS_ON_STARTUP=migrate`) it applies the runtime's pending migrations, then
  Fiona's; re-running against a current database applies nothing. A deployment that
  migrates in a separate release step sets `MIGRATIONS_ON_STARTUP=validate` on its
  instances. There is no separate migration command yet.
- If any migration fails, or validation finds the database behind or from an older history,
  the process logs `event=startup_failed` and exits without serving.

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

### Smoke test Invoice payments and a refund locally

With Fiona running locally, use Node.js 20 or newer to exercise the priced submission and the
separate payment recording, allocation, and refund routes. The local Administrator needs
`commerce.financial-document.create`, `fionas.financial-terms.manage`, `commerce.payment.record`
and `commerce.refund.record` (a fresh bootstrap grants them), and you need the credential of a
SERVICE holding `fionas.inquiries.create`:

```bash
FIONAS_ADMIN_PASSWORD='your-local-password' FIONAS_WEB_SERVICE_ID='<serviceId>' FIONAS_WEB_SERVICE_SECRET='<secret>' node scripts/spoof-payment.mjs
```

The payment script uses `FIONAS_BASE_URL` and `FIONAS_ORIGIN` (both default to
`http://localhost:8080`), `FIONAS_ADMIN_USERNAME` (default `admin`), and the required
`FIONAS_ADMIN_PASSWORD`, `FIONAS_WEB_SERVICE_ID` and `FIONAS_WEB_SERVICE_SECRET`. Playing the
web pricing authority, it obtains a service token and submits a priced inquiry (which also
creates its initial Estimate), then, as staff, creates a direct Invoice v1 from explicit lines,
records and allocates `$200.00` and `$150.00`, refunds `$50.00` from the second payment's
allocation, then pays the server-returned reopened balance. Each standalone receipt is
rediscovered through `GET /payments/unapplied`; the first is allocated in two parts, with a
read showing its remaining available value between them, and disappears from the queue when
fully applied. It proves payment facts survive a reload: the refund uses no id retained from a
payment, allocation, or refund response. The refund's payment and allocation ids are
rediscovered from `GET /financial-documents/{documentId}/payments`, and the refund and its
unwind are verified by reading that again. Exact cent arithmetic verifies gross and net
allocation and a final zero balance. It writes ordinary development data to the configured
database, so use it in local/disposable environments. It has no npm dependencies and is not a
payment-provider or webhook simulator.

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

That is a development image: the jar inside reports the version in `gradle.properties`,
`0.0.0-SNAPSHOT`. The optional `APP_VERSION` build argument sets the application version
instead (it is passed to Gradle as `-Pversion`, so it is also the OpenAPI document's
`info.version`), which is how a release builds. To build the way a release does:

```bash
docker build --build-arg APP_VERSION=0.0.21 --build-arg GITHUB_ACTOR=<user> --build-arg GITHUB_TOKEN=<read:packages token> -t fionas-commerce:0.0.21 .
```

The build fails if the jar does not report `APP_VERSION`. `GITHUB_ACTOR` and `GITHUB_TOKEN` are
declared in the build stage only, just before Gradle runs; the runtime stage copies nothing but
the jar, so the token is not in the final image (a release verifies this).

```bash
docker run --rm -p 8080:8080 -e DATABASE_JDBC_URL=... -e DATABASE_USERNAME=... -e DATABASE_PASSWORD=... fionas-commerce
```

The image build runs `shadowJar` only; lint and tests belong to pull request CI.

### Deploying on Railway

Railway builds the root `Dockerfile` when it detects one. In the service settings, set the
healthcheck path to `/ready` (`/health` is liveness only). Railway passes service variables to a Dockerfile build only when the Dockerfile declares
them with `ARG`, which is why `GITHUB_ACTOR` and `GITHUB_TOKEN` are declared there. `APP_VERSION`
is optional on Railway and defaults to `0.0.0-SNAPSHOT`; to deploy a release instead of
building from source, point the service at the published Docker Hub image
(`<DOCKERHUB_IMAGE>:<version>`), which already contains the application at that version. Set
these variables on the service (Railway also injects `PORT`):

| Variable | Value |
|---|---|
| `GITHUB_ACTOR`, `GITHUB_TOKEN` | A GitHub user and a token with `read:packages` (seal the token). Railway also exposes service variables to the running container, so use a read-only token. |
| `DATABASE_JDBC_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DATABASE_USERNAME` | `${{Postgres.PGUSER}}` |
| `DATABASE_PASSWORD` | `${{Postgres.PGPASSWORD}}` |
| `SERVICE_TOKENS_SIGNING_KEY` | `openssl rand -base64 32` (seal it); fionas-commerce's own key, never shared with fionas-web |
| `SERVICE_TOKENS_ISSUER` | `fionas-commerce-production` (or `-staging`) |
| `FIONAS_EVENT_TIME_ZONE` | Optional IANA event calendar zone; defaults to `America/Los_Angeles`, independent of server timestamps |
| `FIONAS_TRUSTED_ORIGINS` | The service's public origin, for example `https://<domain>` |
| `FIONAS_BOOTSTRAP_ADMIN_*` | First provisioning only; remove after the admin exists |

Protect `POST /auth/service/token` with edge rate limiting and/or private networking for
fionas-web (see [Service authentication](#service-authentication)). Replace `Postgres` with the
name of your Railway PostgreSQL service. The application is
tested against PostgreSQL 18. It applies its migrations on startup
(`MIGRATIONS_ON_STARTUP=migrate`, the default).

## Releasing

CI verifies source; a release promotes source that is already verified.
Pull request CI provisions Java 25 and Node.js 24. Its PostgreSQL-backed tests run the
actual payment script; no npm packages are installed. Release builds
continue compiling the backend without running those smoke tests.

| | Pull request CI ([`ci.yml`](.github/workflows/ci.yml)) | Tag release ([`release.yml`](.github/workflows/release.yml)) |
|---|---|---|
| Trigger | `pull_request` to `main` | push of a tag `vMAJOR.MINOR.PATCH` |
| Question | Is this change safe and consistent enough to merge? | Publish this commit of `main` as version `X.Y.Z` |
| Does | `ktlintCheck`, `test`, `build` (which generates the OpenAPI document and runs its contract tests), and dry runs of the release's two artifact steps with a throwaway version, publishing nothing: the OpenAPI packaging (`scripts/package-openapi.sh`) and a `docker build` of the release image | validates the tag, confirms the commit is in `main`, generates the versioned OpenAPI document, builds and pushes the Docker image, creates the GitHub Release |
| Does not | publish anything; it has no Docker Hub credentials | lint or test again |

A push to a feature branch runs nothing until it has a pull request; each push to that pull request
runs CI once (the `pull_request` event only, never an additional `push` run); merging into `main` runs
no second verification. `fionas-commerce` is an application: no Maven artifact or package is
published.

### Branch protection

This model rests on `main` being protected; the workflows cannot enforce it, and no workflow
configures it. Configure the repository ruleset or branch protection for `main` to:

- require a pull request before merging;
- require the `Build and test (Java 25)` status check (the CI job) to pass;
- prevent ordinary direct pushes to `main`.

Without that, a commit can reach `main` and then a release without ever being verified. The
release workflow's only protection against that is the check that the tagged commit is in `main`.

### Repository configuration

Settings → Secrets and variables → Actions:

| Kind | Name | Value |
|---|---|---|
| Variable | `DOCKERHUB_IMAGE` | The Docker Hub repository to publish to, lowercase `<namespace>/<repository>`, for example `castab/fionas-commerce` |
| Secret | `DOCKERHUB_USERNAME` | The Docker Hub user that owns the access token |
| Secret | `DOCKERHUB_TOKEN` | A Docker Hub access token with read and write access to that repository (not the account password) |
| Secret (optional) | `PACKAGES_READ_TOKEN` | As for CI: a token with `read:packages`, used instead of the workflow's `GITHUB_TOKEN` when the commerce packages have not granted this repository read access |

Only the release workflow uses the Docker Hub secrets; pull request CI never receives them. The
release workflow fails early, before building anything, if `DOCKERHUB_IMAGE`, `DOCKERHUB_USERNAME`, or `DOCKERHUB_TOKEN` is missing. No Docker Hub namespace is
written into the repository. Creating the GitHub Release uses the workflow's own `GITHUB_TOKEN`
(`contents: write`).

### How to release

```bash
git tag v0.0.21
git push origin v0.0.21
```

Push that one tag by name, never `git push --tags`: every pushed tag matching `v*` starts a release.
Do not edit `gradle.properties`, which stays at the development default `0.0.0-SNAPSHOT`.

### What a release does

For `v0.0.21` the workflow, in order:

1. Requires the tag to be exactly `vMAJOR.MINOR.PATCH` (numeric, no leading zeros): `v0.0.21`,
   `v1.0.0`, and `v12.4.7` are accepted; `release-0.0.21`, `v0.0`, `v0.0.21-beta.1`, and `foo` are
   rejected. It takes the tag's version, `0.0.21`, once, and uses only that for everything below.
2. Checks out the tagged commit with its full history and requires it to be reachable from
   `origin/main` (`git merge-base --is-ancestor`). A tag on a commit that never landed in `main`
   stops the release before anything is built or published.
3. Runs `scripts/package-openapi.sh 0.0.21 release-assets`: `./gradlew generateOpenApi -Pversion=0.0.21`,
   a requirement that the document's `info.version` is `0.0.21`, and the two release assets. Pull
   request CI dry-runs the same script with a throwaway version, so generation failures surface
   before a tag.
4. Builds the Docker image with `APP_VERSION=0.0.21` (pull request CI dry-runs the same Dockerfile
   build with a throwaway version, without checking labels or pushing), loads it locally, and checks its OCI labels, that
   the jar inside reports `0.0.21`, and that the package token appears in neither its configuration,
   its history, nor its layers.
5. Only then logs in to Docker Hub and pushes `${DOCKERHUB_IMAGE}:0.0.21`.
6. Creates the GitHub Release `v0.0.21` (title `v0.0.21`, generated notes) on the existing tag and
   attaches the OpenAPI document.

The result:

```text
Docker Hub:
  ${DOCKERHUB_IMAGE}:0.0.21

GitHub Release:
  v0.0.21
  ├── fionas-commerce-openapi-0.0.21.json
  └── fionas-commerce-openapi-0.0.21.json.sha256
```

The application inside the container and the OpenAPI `info.version` both report `0.0.21`: the one
version, `-Pversion=0.0.21`, becomes `fionas-commerce.properties`, `fionaVersion()`, and the
document's `info.version`, and reaches the image through `APP_VERSION`. The OpenAPI document is
rendered from the application's contract by the existing `generateOpenApi` task; no running backend
is involved, and "the exact Fiona API contract for v0.0.21" is that release asset. Its `.sha256`
file is in `sha256sum` format:

```bash
sha256sum --check fionas-commerce-openapi-0.0.21.json.sha256
```

Image details:

- **One tag, `0.0.21`.** There is no `latest`, no moving major or major.minor alias, and no
  `v0.0.21` duplicate; moving aliases are a later, deliberate release policy.
- **OCI labels** `org.opencontainers.image.title` (`fionas-commerce`), `.version` (`0.0.21`),
  `.revision` (the tagged commit's SHA), and `.source` (this repository), plus the metadata
  action's other defaults.
- **One tag, one commit.** A tag push checks out exactly the tagged commit, and the workflow asserts it.
  Never move or reuse a release tag; to correct a bad release, publish the next version. A tag
  ruleset on `v*` that blocks updates and deletion enforces this.
- **Provenance** is attached in its minimal form. The maximal form would record the build
  arguments, which include the package token.
- The Docker layer cache (GitHub Actions cache) only speeds the build up; the release never
  depends on it.

### Failure and reruns

Any failing step (an invalid tag, a tagged commit outside `main`, a version mismatch, the Docker
build or push, or the GitHub Release) fails the run, and every check before the Docker Hub login
fails before anything is published. The release runs no lint or tests: it relies on the pull
request CI that `main` requires. Runs for one tag are serialized and never cancelled mid-publication.

A rerun of the same tag is safe. The ancestry and artifact checks repeat; pushing `0.0.21` again from the same commit
replaces it with identical content; the GitHub Release is created if it does not exist, and
otherwise only its two assets are replaced. The tag is never created, moved, or rewritten by the
workflow. So if the image was pushed but the GitHub Release step failed (for example, a
transient API error), rerun the failed workflow run from the Actions tab.

## Testing

```bash
./gradlew test
```

The tests need Docker: Gradle starts a throwaway `postgres:18-alpine` container through
the Docker CLI and removes it when the build ends. To use an existing server instead, set
`TEST_DATABASE_JDBC_URL`, `TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` (the user
must be allowed to `CREATE DATABASE`). Each spec creates and drops its own database, and
commerce-runtime applies the real migrations. There is no H2 and no test schema.
Node.js 20 or newer must also be on PATH: `PaymentSmokeScriptSpec` runs the actual payment
script against a started test runtime. CI provisions Node.js 24 without npm dependencies or
caching; Gradle tracks the script as a test input. Test lines come from a test-only pricing
authority (`testing/Pricing.kt`), never from production code: Fiona has no pricing to test.

| Spec | Proves |
|---|---|
| `CustomerValuesSpec`, `InquiryValuesSpec` | Value-object validation and normalization |
| `DatabaseSchemaSpec` | Fiona's tables and keys are in `fionas`; `commerce` holds exactly what commerce-runtime creates; nothing Fiona-owned in `commerce` or `public`; one baseline per stream; exactly eight foreign keys into published runtime tables; no catalog/offering/pricing table anywhere; no Fiona table or column restates a ledger fact |
| `MigrationLifecycleSpec` | Fiona as a consumer of the runtime's migration phase: the runtime baseline first, Fiona's `V1` second, independent version spaces, each history in its own schema, repeat startup applies nothing, failures prevent composition |
| `JdbiCustomerRepositorySpec`, `JdbiInquiryRepositorySpec` | Insert/read, email and batch id lookup, unique email conflict, foreign keys; newest-first keyset listing with timestamp ties and an `EXPLAIN` proving a backward index scan without a sort; requested service round trips strictly |
| `RuntimeTransactionSpec` | Fiona repositories write through the runtime `Transaction`: customer and inquiry roll back together, and nothing is visible before commit |
| `InquiryOperationsSpec`, `InquiryMaterializationSpec` | Customer + inquiry + Estimate v1 with exactly the authority's lines in order, authored by the SERVICE; customer reuse; requested service recorded as submitted; invalid or negative line sets rejected before any write; rollback across both schemas inside the ledger; one canonical initial Estimate; bespoke lines and credits evolve, quote, pay and refund with no catalog |
| `InquiryRequestFingerprintSpec` | Pinned v2 encoding; every customer, event, requested-service and line value, line order and currency; numeric scale, normalization, key and submitting principal excluded; lossless string boundaries; bounded opaque key validation |
| `InquiryIdempotencyRoutesSpec`, `InquiryIdempotencySpec` | Full-handler replay with exact receipts and no second inquiry/Estimate; changed lines or amounts conflict `IDEMPOTENCY_KEY_REUSED`; failed attempts release keys; forced overlapping PostgreSQL commands, observed unique-key waits, late rollback, incomplete claims rejected at commit, lost-response recovery |
| `InquiryRoutesSpec` | The inquiry API through the complete handler: the receipt never reveals an existing customer; required event and ZIP fields; missing/null `requestedService` or `lines` malformed; invalid lines `422` with nothing recorded; caller totals ignored; requested service descriptive only; SERVICE-priced inquiry → staff read → Estimate v1 authored by the service; keyset inbox pages; runtime error bodies and `405` |
| `ServicePrincipalAuthSpec` | SERVICE principals end to end: token issuance, `401`/`403`/authorized for priced submission, live permissions, a staff session refused priced submission even with the permission, a SERVICE holding every staff permission refused staff terms (`403 forbidden`), session precedence, Origin only for cookies, session-only logout, provisioning and rotation |
| `AuthRoutesSpec`, `LoginRateLimitSpec`, `BootstrapAdminEnvironmentSpec` | Fresh bootstrap grants (financial terms, no catalog or submission grant), the removed permissions absent from the catalog, removed routes `404`, login failures and limiting, session lifecycle, administration, credentials and Origin checks |
| `LineProposalSpec`, `PricedLineSpec` | Pure staff line resolution with identity: financially identical lines reordered or removed-and-re-added are real changes with exactly the requested ids, a true no-op ignores only decimal scale, review tokens and plan notes bind the actual final ids; precise unit rates (USD `0.125 × 8`) kept exactly while flat prices, extended subtotals and tax settle in each currency's minor units (USD, JPY, BHD), nothing rounded |
| `ChangeOrderFoundationSpec` | Signed flat credits, ordered granular changes keeping untouched identities, exact tax arithmetic, numeric total validation with negative lines allowed, allocations staying on their snapshots, negative staff change orders rejected after payment, concurrent staff edits of one version (one succeeds, the stale writer conflicts), and Quote revisions that would be negative or zero preserving the approved proposal |
| `QuoteBuilderSpec`, `QuoteBuilderRoutesSpec` | Staff composition on PostgreSQL and through the handler: keeping every line (Estimate v1 → Quote v2), **the churro example** (soft serve removed, bespoke service and optional discount added, no catalog, deposit, exact payment, booked Invoice, served, closed), overrides keeping line ids, deterministic tokens binding lines/order/plan/terms/version, stable rejection codes, tampered or stale reviews writing nothing, rollback at the plan, deposit or publication, concurrent approvals, Quote and deposit revisions, a non-user approver refused by the database, malformed shapes, no-store, staff-USER-only authority and Origin, corrupt plans failing closed |
| `InquiryProposalsSpec`, `InquiryProposalRoutesSpec` | Exact full deposit acceptance, zero-write partial/excess/stale rejection, deposit-only republication, Quote revisions from staff lines, gross-allocation guard after refunds, rollback at late writes, association contention, ordered history with USER provenance, currentness, canonical deposit mutation rejected before/after booking, booked Invoice change orders, permission intersection and corrupt-pair failures |
| `InquiryLifecycleSpec`, `InquiryLifecycleRoutesSpec` | Canonical projection; exact deposit booking; RELATED isolation; refund stability; served/closed provenance, exact-zero closeout and closed change-order rejection through the handler |
| `InquiryOperationalStatesSpec`, `StaffDashboardSpec`, `StaffDashboardRoutesSpec`, `DashboardAttentionSpec`, `InquiryCommunicationSpec`, `InquiryCommunicationRepositorySpec`, `DashboardEventCalendarSpec` | Complete canonical counts; the three attention queues and onset ordering; durable-order clearing with PostgreSQL contention; SQL/pure projection parity; EXACT/FROM qualifiers from the requested service; event-zone and DST boundaries; ten set-based SQL reads; snapshot coherence; strict corrupt-data failures; live USER/SERVICE permissions |
| `StaffRequestSpec`, `StaffRequestRoutesSpec` | Canonical request detail before/after issuance, RELATED exclusion, integrity failures, one unlocked repeatable snapshot during concurrent Quote/payment commits, nested response parity, permission intersection and no-store |
| `FinancialDocumentRoutesSpec` | The whole workflow through the complete handler: staff Estimate, line edits, Quote, deposit, Invoice and final payment; several lineages per inquiry; caller totals never authoritative; carry, override, remove, add and reorder keeping identities; change orders in every stage; stable rejection codes; stale versions; illegal transitions; payment policy and validation; duplicate references; non-Fiona documents `404`; staff-only routes with live permissions; declared methods |
| `FinancialLedgerExpansionSpec`, `FinancialDocumentAtomicitySpec` | Direct first-snapshot stages from staff lines; invalid line sets append nothing; standalone receipts, partial and split allocations, runtime limits and Fiona stage/version policy; every cross-boundary write (creation, change orders, combined payments, allocations) rolls back the ledger with Fiona's rows |
| `FinancialDocumentRepositoriesSpec` | Associations (several lineages per inquiry, one inquiry per lineage, first-snapshot FK) and line authorship (USER/SERVICE round trips, copies to successors, exact-snapshot FK, once per version, transactional rollback, kind check) |
| `FinancialDocumentReadConsistencySpec` | Current-document, history, and inquiry-list reads keep their snapshot while a payment or quote commits; ownership lookups never take the mutation lock |
| `FinancialDocumentPaymentsSpec`, `UnappliedPaymentsSpec` | Canonical deposit acceptance and rejection; document payment histories rediscovered after their responses are gone; split payments whole; runtime ordering; unapplied receipts; permission boundaries |
| `DepositRequirementRoutesSpec`, `DepositRequirementOperationsSpec` | Reads for USER or SERVICE; standalone PUT/DELETE only for a staff USER with both `commerce.deposit-requirement.manage` and `fionas.financial-terms.manage` (a SERVICE with both, or a USER with either alone, is `403` and writes nothing); deposit unions, history, frozen amounts, payment/refund satisfaction, ownership, permissions, bulk facts and activity, set-based reads, REPEATABLE READ coherence and NOWAIT rollback |
| `PaymentSmokeScriptSpec` | The actual Node payment script against a running backend: service-priced submission with no catalog, staff Invoice, payments, refund and zero balance; stops before writing without a pricing-authority credential or permission |
| `OpenApiDocumentSpec` | The OpenAPI document: every Fiona route, operationIds, statuses, schemas and no host; exact decimal strings for every amount and no request total; no catalog, preview or pricing-input schema; `serviceAccessToken` alone on `createInquiry`, `staffSession` alone on staff-terms routes, both elsewhere; the runtime routes' metadata gap pinned |
| `OpenApiRoutesSpec`, `GenerateOpenApiSpec` | `/openapi.json` and `/docs` through the complete handler, parity with the generator, no external assets; the generated file byte-identical on every run |
| `ApplicationVersionSpec` | The version the application reports, and the OpenAPI document's `info.version`, is the Gradle project version the build was given (`-Pversion` in a release) |
| `FionaApplicationSpec` | `application.conf` loads, `/health` and `/ready`, a real server on a port |
| `ArchitectureSpec` | Repositories take a `Transaction` and build no transaction infrastructure; no SQL in routes; no HTTP in persistence; every endpoint is a contract route with an `operationId`; no hand-written OpenAPI file; one http4k version and no Jackson; no catalog, Offerings or pricing code, SQL or dependency; every ledger mutation passes an expected version with the operation's `Transaction`; the SERVICE guard on priced submission and the USER guard on every staff-terms route; no Fiona table restating a ledger fact; only published runtime keys referenced |

Full verification, as CI runs it (`build` also generates the OpenAPI document):

```bash
./gradlew ktlintCheck test build
```

`./gradlew ktlintFormat` fixes formatting.
