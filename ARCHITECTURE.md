# Fiona's Commerce Architecture

## Purpose

`fionas-commerce` is the concrete commerce application for Fiona's Ice Cream.

It composes the reusable commerce platform with Fiona-specific business concepts, workflow, policy, persistence, authentication, and HTTP behavior.

This repository is **not** a second commerce platform.

Its primary architectural responsibility is to translate Fiona's business requirements into application-owned behavior while preserving the boundaries established by `commerce-domain` and `commerce-runtime`.

This document defines those application-level boundaries.

---

# 1. Fiona Is the Application Layer

The system is conceptually:

```text
Fiona frontend / BFF clients
            ↓
      fionas-commerce
            ↓
      commerce-runtime
            ↓
      commerce-domain
```

The shared commerce platform owns reusable commerce meaning and machinery.

Fiona owns the concrete business built on top of it.

`fionas-commerce` therefore owns:

- its executable and process lifecycle;
- deployment configuration;
- logging implementation and configuration;
- Fiona-specific HTTP routes;
- Fiona-specific persistence;
- inquiry workflow;
- customers and contacts;
- Fiona pricing policy;
- relationships between Fiona entities and shared commerce facts;
- Fiona authentication and credential verification;
- application-specific permission definitions;
- application-specific presentation and workflow policy.

Fiona must not reimplement shared commerce capabilities merely because it is their first or primary consumer.

---

# 2. Shared Commerce Boundaries

The reusable commerce platform follows this ownership model:

- `commerce-domain` owns reusable commerce vocabulary and invariants.
- `commerce-runtime` operationalizes that vocabulary through persistence, transactions, authorization, sessions, reusable HTTP capabilities, and semantic read/write APIs.
- Fiona owns application-specific policy, workflow, composition, relationships, and presentation.

These boundaries apply even when the implementation happens first because Fiona needs a feature.

If a Fiona requirement exposes a concept that appears inherently reusable, determine whether the shared platform is missing that concept before creating a Fiona-local substitute.

Do not choose application ownership merely because it produces the smallest immediate patch.

---

# 3. Fiona Owns Its Business Entities

Fiona owns business entities that do not have independent reusable commerce meaning.

Examples include:

- inquiries;
- Fiona customers;
- customer contact information;
- event or service details;
- inquiry fulfillment and closeout facts;
- inquiry communication activity and Fiona attention policy;
- locations;
- guest counts;
- catering-specific selections or context;
- application workflow metadata.

These concepts remain strongly typed in Fiona.

Do not push them into the shared platform merely to simplify persistence or HTTP composition.

Do not use generic metadata maps or opaque JSON to smuggle Fiona-specific data into shared commerce objects.

---

# 4. Relationships Between Fiona and Commerce Are Application-Owned

Shared commerce facts intentionally exist independently of Fiona entities.

For example, a financial document does not intrinsically belong to a Fiona inquiry or customer.

Fiona owns relationships such as:

```text
Inquiry ───────────────→ Financial document lineage
Customer ──────────────→ Inquiry
Application workflow ──→ Shared commerce facts
```

Store those relationships in Fiona-owned persistence.

Reference shared commerce facts using their supported identities or references.

Do not modify shared commerce objects to carry Fiona ownership information.

Do not add fields such as:

- `inquiryId`;
- `customerId`;
- `eventId`;
- `fionaReference`;
- generic `metadata`;

to shared commerce models merely to make application relationships convenient.

---

# 5. Fiona Does Not Mirror Shared Commerce State

Shared commerce state has one authoritative owner.

Fiona must not persist local copies of runtime-owned facts such as:

- financial document snapshots;
- payment histories;
- payment balances;
- reconciliation results;
- refund state;
- deposit requirement state;
- deposit satisfaction;
- permission catalogs;
- runtime authorization state;
- shared offering catalog contents.

If Fiona needs a convenient view of shared state, prefer:

1. an existing semantic runtime read capability;
2. composing several supported runtime reads;
3. adding an appropriate reusable runtime read model when one is genuinely missing.

Do not create a Fiona table merely because a dashboard or endpoint needs a faster or more convenient read.

A UI read requirement is not sufficient reason to create another source of truth.

---

# 6. Fiona Never Reads Runtime Tables Directly

The `commerce` schema belongs to `commerce-runtime`.

The `fionas` schema belongs to this application.

Sharing one PostgreSQL database does not imply shared persistence ownership.

Fiona application code must consume runtime-owned information through supported runtime APIs, repositories exposed for composition, ledger operations, or semantic read models.

Do not issue application SQL directly against runtime-owned tables.

Do not depend on undocumented runtime persistence representation.

Runtime storage may change while its supported semantic contract remains stable.

---

# 7. Cross-Layer Work Should Be Transactionally Composable

Fiona may need to persist application-owned state and shared commerce facts as one business operation.

For example:

```text
create inquiry relationship
        +
create authoritative estimate
        =
one atomic business operation
```

Use the runtime's shared transaction facilities for such operations.

Application repositories should accept the caller's transaction when they participate in a larger business operation.

Do not implement multi-owner operations as unrelated transactions when partial success would produce an invalid application state.

Transaction ownership should be explicit.

---

# 8. Inquiry Intent and Financial Facts Are Different Concepts

An inquiry records what a prospective customer is asking Fiona to provide.

A financial document records an authoritative commercial proposition.

These are related by Fiona policy but are not the same concept.

Fiona owns the relationship between an inquiry and any estimate, quote, invoice, booking, payment activity, or other commerce fact arising from it.

Do not make the shared financial model own the inquiry.

Do not make the inquiry itself become a financial document.

Where Fiona policy requires an inquiry to produce an estimate, Fiona orchestrates that operation explicitly.

---

# 9. Offering Selection Is Part of Fiona Inquiry Intent

For Fiona's current business workflow, an inquiry requires the customer to describe the ice-cream service they want through the configured offering selections.

Offering selection is therefore not an optional enhancement to the inquiry experience.

The public application collects the selections required to evaluate Fiona's service offering, and Fiona applies its business policy to them.

The generic offering catalog remains owned by the shared commerce platform.

Fiona owns:

- the relevant catalog binding;
- business context supplied to evaluation;
- catering-specific selection policy;
- pricing interpretation;
- the relationship between the resulting evaluation and the inquiry.

---

# 10. Pricing Policy Is Fiona-Owned and Server-Authoritative

The offering catalog describes reusable commercial choices and generic price metadata.

Fiona determines what those choices mean in the context of its business.

Fiona may apply policy involving concepts such as:

- guest count;
- service duration;
- catering-specific premiums;
- packages;
- dependencies between selections;
- Fiona-specific minimums or constraints.

These rules belong in Fiona unless they become complete reusable commerce concepts in their own right.

Clients submit intent, not authoritative financial values.

The backend derives:

- accepted selections;
- authoritative prices;
- line items;
- totals;
- provenance;
- the resulting financial document.

Never trust client-submitted totals or prices simply because the UI calculated or displayed them.

---

# 11. Financial History Remains Shared Commerce History

Once Fiona creates an estimate, quote, invoice, payment, refund, allocation, or deposit requirement through the shared platform, that commerce history remains authoritative there.

Fiona policy may decide **when** such actions are appropriate.

It must not redefine **what those facts mean**.

Examples:

- Fiona may decide when a quote can be accepted.
- Fiona does not redefine quote version semantics.
- Fiona may decide that a deposit is required before a workflow transition.
- Fiona does not store its own mutable `depositPaid` truth.
- Fiona may decide whether a refund is allowed.
- Fiona does not redefine a refund as mutation of a payment.

Application workflow reacts to shared commerce facts rather than rewriting their semantics.

---

# 12. Derived State Should Stay Derived

Do not persist application state that merely restates authoritative commerce facts.

Avoid fields such as:

```text
paid
balanceDue
depositSatisfied
hasRefund
paymentStatus
latestQuoteTotal
```

when their meaning is entirely derivable from shared commerce state.

It is acceptable for Fiona to expose derived values in an HTTP response or UI-facing read model.

A derived response field is not automatically a persistent application field.

Persist something only when it represents an independent Fiona business fact or decision.

## Atomic canonical proposal publication

Fiona publishes its canonical `INITIAL_ESTIMATE` Quote only through `IssueInquiryProposal`:
Estimate -> immutable Quote + active deposit approved against that exact Quote + append-only
`InquiryProposal` issuance commit together. Staff explicitly supplies shared `DepositTerms`;
`FIONAS_DEFAULT_DEPOSIT_TERMS` proposes 20% and never writes an implicit approval. RELATED
lineages retain their standalone shared financial behavior.

Fiona V14 `inquiry_proposals` stores only publication UUID, inquiry/lineage identities,
Quote version, deposit revision, issuance kind, microsecond Clock time and USER/SERVICE
provenance. Its composite FK references Fiona's association, with no new runtime-table FK.
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
before reviewed-token checks. Quote revision uses current-catalog `FionasPricing` and the
existing no-op change-order rule, appends a same-stage Quote, then replaces the deposit
against the new Quote even if terms remain 20%. Deposit-only revision keeps the Quote
version and rejects numerically equivalent same-form terms; changing percentage to fixed
is meaningful even if the resolved amount is equal. Both require exact reviewed Quote and
deposit revision tokens. Any historical `grossAllocated > 0` blocks both revisions, even
when refunds unwind all applied value. Deposit satisfaction by existing payment/allocation
operations still atomically promotes Quote -> Invoice/BOOKED; refunds never demote it.

Every publication is the exact `(documentId, documentVersion, depositRequirementRevision)`.
`IsCurrentPayableInquiryProposal` reads one REPEATABLE READ snapshot: only the latest
publication whose exact Quote and active approval/revision still match is payable. Every
reissue supersedes all earlier ids; Invoice makes all publication targets non-payable.
Business UUIDs are not bearer secrets. No public payment link, payment provider, delivery,
contact collection, cancellation, or post-payment adjustment workflow is introduced.

`IsCurrentPayableInquiryProposal` is only a read/query seam. Future customer payment
acceptance must lock/revalidate the exact proposal currentness and record/allocate money
in one atomic transaction. A query followed by a separate payment transaction would race
proposal reissuance.

The durable proposal row is the business event for future at-least-once integrations.
It commits with the financial facts, has a stable id, and never implies STAFF_EMAIL_SENT.
Actual delivery alone may append communication activity. A future generic durable dispatch
capability belongs upstream when needed; no after-commit crash-window publisher exists here.

POST `/staff/requests/{inquiryId}/proposals` (`issueInquiryProposal`), its `/quote-revisions`
child (`reviseInquiryQuoteProposal`) and `/deposit-revisions` child
(`reviseInquiryProposalDeposit`) derive the document from the inquiry. All require BOTH
`commerce.financial-document.create` and `commerce.deposit-requirement.manage`, existing
USER/SERVICE AccessControl and unsafe-cookie Origin policy; no new permission/bootstrap grant.
Responses are 200 with `{proposal, financial, depositRequirement}` and no-store.

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
match the immutable accepted deposit. Pre-slice development Quotes receive
no compatibility shim, invented publication, or backfill.

## Inquiry lifecycle projection and booking policy

The inquiry's unique `INITIAL_ESTIMATE` lineage drives its operational lifecycle. Other
`RELATED` lineages remain financial artifacts, including first-snapshot Invoices, and
never make the inquiry booked. There is no separate Booking aggregate or persisted
five-state status.

| Canonical latest stage and Fiona facts | Projected inquiry stage |
|---|---|
| Estimate, no fulfillment facts | REQUESTED |
| Quote, no fulfillment facts | QUOTED |
| Invoice, no served fact | BOOKED |
| Invoice, served and not closed | SERVED |
| Invoice, served and closed | CLOSED |

Issuing the canonical Quote is the firm proposal boundary. A positive active deposit is
required for booking. `RecordDocumentPayment` and `AllocatePayment` apply one Fiona policy after their mutation: read the runtime's
authoritative `FinancialLineageView`; if the canonical Quote's active deposit is satisfied,
issue its Invoice and copy optional legacy pricing metadata in the same transaction.
Promotion failure rolls back the triggering mutation. Partial deposit payment leaves it quoted. Manual canonical Invoice issuance is rejected. Manual
issuance on RELATED lineages remains available. No zero-deposit booking path exists.

Invoice is the durable booking fact. Refunds may reverse deposit satisfaction but do not
demote Invoice or erase booking. Invoice change orders retain BOOKED/SERVED/CLOSED
projection while changing runtime reconciliation. Event date never advances lifecycle.

Fiona V12 stores only operational facts in `inquiry_fulfillment`: served timestamp and
acting principal kind/identity, plus optional complete closed provenance. A row requires
served provenance, so closed cannot exist without served. USER and SERVICE identities
use commerce-domain's principal model. The injected Clock supplies microsecond timestamps.
No financial stage, balance, payment status or deposit truth is stored here.

Explicit service requires BOOKED; explicit closure requires SERVED and authoritative current
Invoice balance exactly zero, including scale-independent decimal equality. Negative
overpayment blocks closure. Repeated actions conflict without rewriting provenance. There
is no unserve or reopen. Later ledger activity after closure is allowed; eligibility is
evaluated at the close command. Internally impossible fulfillment before Invoice fails loudly.

Both payment booking triggers and fulfillment actions serialize on the existing canonical
association row. They use READ COMMITTED so waiters observe the preceding committed
allocations before applying booking policy. While that row is locked, Fiona cannot change
the document, its allocations or deposit terms. Refunds do not take that lock; the runtime
reads their unwinds in one statement against the stable allocation set. Detail reads use
one REPEATABLE READ snapshot. No nested transactions, retry loops or runtime-table SQL
are introduced.

Staff detail exposes this projection with `fionas.inquiries.read`. Business-action endpoints
`POST /inquiries/{inquiryId}/served` (`markInquiryServed`) and
`POST /inquiries/{inquiryId}/close` (`closeInquiry`) require `fionas.inquiries.manage`, using
the existing AccessControl, session/SERVICE authentication and cookie Origin policy.
New bootstrap Administrators receive the permission; existing role grants are unchanged.

---

# 13. Authentication Is Fiona-Owned; Authorization Infrastructure Is Shared

Fiona owns how human credentials are collected and verified.

After proving identity, Fiona uses the shared runtime's principal, session, and authorization facilities.

The shared runtime owns reusable concepts and machinery such as:

- principals;
- sessions;
- service identities;
- roles;
- permission definitions and catalog composition;
- assignments;
- live permission resolution;
- access control.

Fiona owns:

- login routes;
- credential storage and verification where applicable;
- application-specific authentication UX;
- application-specific permission definitions;
- deciding which protected capabilities are mounted.

Do not store permissions or roles in Fiona sessions.

Do not authorize by frontend-visible role names.

Do not introduce an application-local RBAC implementation beside the runtime directory.

Authorization must remain server-enforced even when the frontend hides unavailable actions.

---

# 14. Application Permissions Are Capabilities, Not UI Roles

Fiona may contribute application-specific permissions to the running permission catalog.

Permissions should describe meaningful capabilities.

The frontend may use the current principal's effective permissions to decide what controls to display.

Do not assume:

- a fixed set of roles;
- that role names imply specific grants forever;
- that the frontend knows every role that can exist;
- that hiding an action replaces server enforcement.

Administrative authorization interfaces may therefore expose roles and permissions that were created after the frontend was built.

---

# 15. Fiona Owns HTTP Composition

Fiona is the deployed application and therefore owns the externally assembled HTTP surface.

It may compose:

- Fiona-specific routes;
- reusable runtime HTTP capabilities;
- authentication filters;
- authorization policies;
- OpenAPI aggregation;
- application-specific exposure decisions.

Runtime capabilities should be mounted rather than reimplemented when they already represent the desired reusable operation.

Fiona-specific endpoints should express Fiona business intent.

Prefer endpoints conceptually like:

```text
submit inquiry
approve quote
configure deposit requirement
record business action
```

over requiring a frontend to orchestrate a fragile sequence of low-level ledger operations.

The browser should not need to understand internal transaction choreography.

---

# 16. Public and Administrative Surfaces Are Distinct

A capability existing in the backend does not imply that it belongs on the public client surface.

Public APIs should expose only what an unauthenticated or specifically authenticated public experience requires.

Administrative APIs may expose richer operational capabilities under appropriate authorization.

Do not leak internal identifiers, administration capabilities, unrestricted searches, infrastructure concepts, or low-level ledger machinery merely because they already exist in backend code.

Exposure is an application decision.

---

# 17. UI Requirements Do Not Define Persistence

Fiona's `ReadStaffDashboard` composes the existing complete canonical operational reader's
transaction-taking core with bulk inquiry and customer enrichment in one unlocked REPEATABLE
READ snapshot. Financial truth remains runtime `FinancialLineageView`; lifecycle remains
`InquiryLifecycle.project`. Integrity failures reject the whole dashboard. Neither counts,
queues nor enriched financial facts are persisted, and reads never trigger booking promotion.

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
operational core, bulk inquiry/requested-pricing/customer enrichment and one PostgreSQL
communication attention aggregate. `attentionFor(transaction, inquiryIds)` returns at most
one `(unacknowledgedSince, latestEmailAt)` value per inquiry with activity. A single set-based
window/filtered aggregate derives the clearing order and the two actual-time values; full
historical communication rows never reach the dashboard. Returned communication data is
O(inquiries), even as append-only history grows. Requested pricing restoration remains strict;
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

Dashboard and frontend requirements often ask questions such as:

- is the deposit satisfied?
- what is the current quote?
- what financial activity happened most recently?
- what actions may this staff member perform?

Treat these first as **read-model questions**.

Determine whether the answer is:

- existing shared commerce state;
- derived shared commerce state;
- a composition of Fiona and commerce state;
- genuinely new Fiona business state.

Do not respond to every new UI field by adding a database column.

UI requirements can reveal missing shared capabilities, but they do not establish ownership by themselves.

---

# 18. Presentation Language Is Application-Owned

Fiona may translate shared domain/runtime concepts into language appropriate for customers and staff.

Presentation-specific concepts may include:

- labels;
- descriptions;
- guidance;
- grouping;
- explanatory text;
- display status derived from underlying facts.

Name application models according to business meaning rather than a specific widget.

Avoid backend concepts such as:

- `Card`;
- `BadgeComponent`;
- `TooltipData`;
- `DashboardTile`;

unless the object genuinely represents a transport/UI contract whose purpose is intentionally tied to that surface.

Business meaning should survive a frontend framework change.

---

# 19. Schema and Migration Ownership

Fiona owns its application schema and migration stream.

Runtime owns the `commerce` schema and runtime migration stream.

Fiona migrations may create or modify Fiona-owned objects.

They must not alter runtime-owned DDL.

When an application relationship references a published runtime identity, preserve the ownership boundary explicitly.

Do not duplicate runtime tables into the Fiona schema.

While this project remains pre-production without production data requiring compatibility, prefer the correct target Fiona schema over unnecessary transitional compatibility structures.

Once production data exists, migration compatibility becomes a deliberate operational requirement.

---

# 20. Prefer Strict Application Models

When absence is not a legitimate Fiona business state, model the value as required.

Do not make a field nullable solely because:

- a migration is easier;
- a partially implemented workflow temporarily lacks it;
- a client could omit it;
- compatibility with unused data would otherwise require work.

Transport validation, application models, and persistence constraints should agree about what constitutes valid Fiona state.

---

# 21. Do Not Generalize Fiona Policy Into Commerce Incidentally

When implementing a Fiona feature, do not modify the shared platform merely because extracting code appears cleaner.

Before proposing a shared abstraction, ask:

1. Does the concept have complete meaning without Fiona?
2. Would another substantially different commerce application recognize the same semantics?
3. Does the shared platform need to enforce an invariant around it?
4. Is Fiona merely the first place the missing shared concept became visible?

If the answer points to application policy, keep it in Fiona.

If the answer points to reusable commerce semantics, address the missing abstraction in the shared platform deliberately rather than creating both a Fiona version and a future shared version.

---

# 22. Do Not Work Around Missing Shared Capabilities Locally

When the shared runtime lacks an operation or read model Fiona legitimately needs, do not automatically compensate with:

- direct SQL against `commerce`;
- duplicate persistence;
- local copies of shared domain types;
- reconstructed payment/reconciliation logic;
- application-specific versions of generic runtime operations.

Identify the missing capability.

If it belongs in the shared platform, implement and release it there first, then consume the released capability from Fiona.

This repository should not become a compatibility layer around missing commerce-runtime features.

---

# 23. Dependency Versions Are Architectural Boundaries

Fiona consumes released versions of the shared commerce artifacts.

Code in Fiona must target the behavior of the version it actually declares.

Do not write application code against unreleased assumptions about `commerce-domain` or `commerce-runtime`.

When a shared capability is required:

1. implement and validate it in the shared repository;
2. release the shared package;
3. update Fiona to the released version;
4. integrate through the supported public API.

This keeps the boundary between repositories explicit and reproducible.

---

# 24. Decision Test for Fiona Changes

Before introducing new application state, persistence, or backend behavior, ask:

1. Is this Fiona business policy, shared commerce semantics, or reusable runtime machinery?
2. Would the concept still make sense if Fiona's Ice Cream did not exist?
3. Am I creating local state that duplicates something already authoritative in commerce?
4. Can this answer be derived instead of stored?
5. Am I reaching into runtime persistence because the supported API is inconvenient?
6. Does this operation need to compose atomically with shared commerce work?
7. Is the client being asked to make a decision the server should own?
8. Is a UI requirement being mistaken for a new domain concept?
9. Am I embedding Fiona-specific assumptions into a supposedly reusable abstraction?
10. Does this change alter an HTTP, authorization, transaction, persistence, or financial contract?

If ownership remains unclear, surface the ambiguity before implementation.

Do not resolve architectural uncertainty through the smallest local patch.

---

# 25. Architectural Conflict Handling

This document defines application-level architectural constraints.

If requested work conflicts with these principles:

1. identify the conflict explicitly;
2. determine whether the application architecture or shared commerce architecture needs to change;
3. do not introduce a local workaround merely to complete the immediate feature;
4. make shared-platform changes in the shared repository when shared ownership is appropriate;
5. update this document or record an ADR when an intentional architectural decision changes these rules.

Architecture should change deliberately, not accidentally through feature implementation.

---

# Summary

Fiona follows five recurring rules:

**Fiona owns the business; commerce owns reusable commerce semantics.**

Inquiry workflow, customer relationships, catering policy, authentication experience, and application composition belong here.

**Shared commerce state has one owner.**

Fiona consumes financial, payment, deposit, catalog, session, and authorization capabilities rather than reproducing them.

**Relationships are application-owned.**

Fiona relates inquiries, customers, bookings, and other application concepts to independent shared commerce facts.

**Clients express intent; the backend establishes authoritative business facts.**

Pricing, financial documents, permissions, and transactional orchestration are server responsibilities.

**When a Fiona feature exposes a missing shared concept, fix the boundary instead of hiding it locally.**
