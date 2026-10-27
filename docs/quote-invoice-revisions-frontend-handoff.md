# Canonical Quote and Invoice revisions: frontend handoff

This backend consumes commerce-runtime 0.0.26. It introduces no pricing rules, booking
aggregate, delivery workflow or payment provider. All amounts are exact decimal strings.
The generated OpenAPI (`build/openapi`, also GET `/openapi.json`) is authoritative for DTOs.

## Endpoints and authority

All paths below start with `/staff/requests/{inquiryId}`. POST success is 200 with
`Cache-Control: no-store`; existing session authentication and trusted cookie Origin apply.

| Endpoint | operationId | Authority |
|---|---|---|
| POST `/quote-preview` | previewInquiryQuote | Quote permissions |
| POST `/proposals` | issueInquiryProposal | Quote permissions |
| POST `/quote-revision-preview` | previewInquiryQuoteRevision | Quote permissions |
| POST `/proposals/quote-revisions` | reviseInquiryQuoteProposal | Quote permissions |
| POST `/proposals/deposit-revisions` | reviseInquiryProposalDeposit | Quote permissions |
| POST `/invoice-revision-preview` | previewInquiryInvoiceRevision | Invoice permissions |
| POST `/invoice-revisions` | reviseInquiryInvoice | Invoice permissions |
| GET base path | readStaffRequest | Existing read permissions |

Quote permissions: staff USER plus `commerce.financial-document.create`,
`commerce.deposit-requirement.manage`, `fionas.financial-terms.manage`.
Invoice permissions: staff USER plus `commerce.financial-document.create` and
`fionas.financial-terms.manage`. Deposit management is not required or used.
Reads require `fionas.inquiries.read` and `commerce.financial-document.read`, for USER or
SERVICE. SERVICE cannot preview or approve staff amendments, even with all permissions.
Never send `approvedBy` or `issuedBy`; the authenticated USER supplies provenance.

## Initial publication versus revision

Initial preview is unchanged: `quote-preview` takes `expectedDocumentVersion`, complete
ordered `lines`, optional `servicePlan` and required `terms`. It accepts only the canonical
Estimate. `/proposals` accepts those exact inputs plus the returned `reviewToken` when
lines are supplied. Omitting both lines and token retains the existing initial-issuance path.
Initial publication has no servicePlanAction. Keeping lines issues E1 -> Q2; changing them
issues E1 -> E2 -> Q3. An initially absent plan remains absent, never synthesized.

Quote revisions now require a separate revision preview. The old tokenless Quote revision
request is no longer accepted. Never reuse an initial preview token for a revision.

A revision preview request (existing line UUIDs below are illustrative):

```json
{
  "expectedDocumentVersion": 3,
  "expectedDepositRequirementRevision": 1,
  "lines": [{
    "lineItemId": "11111111-1111-4111-8111-111111111111",
    "description": "Catering service",
    "unitPrice": "1450.00",
    "taxAmount": "0.00",
    "currency": "USD"
  }],
  "terms": {"type": "PERCENTAGE", "percentage": "25"},
  "servicePlanAction": "REPLACE",
  "servicePlan": {
    "description": "Chocolate service for the larger party",
    "guestCount": 120,
    "durationMinutes": 120,
    "items": [{"label": "Chocolate", "group": "Flavors", "key": "chocolate"}],
    "lineNotes": [{
      "lineItemId": "11111111-1111-4111-8111-111111111111",
      "note": "Staff negotiated the complete service price"
    }]
  }
}
```

POST this to `/quote-revision-preview`. Its response nests the existing Quote preview:

```json
{
  "quote": {
    "inquiryId": "22222222-2222-4222-8222-222222222222",
    "documentId": "33333333-3333-4333-8333-333333333333",
    "reviewedDocumentVersion": 3,
    "estimateTotal": "1200.00",
    "financialChange": true,
    "quoteVersion": 4,
    "lines": [{
      "id": "11111111-1111-4111-8111-111111111111",
      "origin": "REPLACED",
      "description": "Catering service",
      "unitPrice": "1450.00",
      "subtotal": "1450.00",
      "taxAmount": "0.00",
      "total": "1450.00",
      "currency": "USD"
    }],
    "subtotal": "1450.00",
    "taxAmount": "0.00",
    "total": "1450.00",
    "currency": "USD",
    "deposit": {
      "terms": {"type": "PERCENTAGE", "percentage": "25"},
      "requiredAmount": {"amount": "362.50", "currency": "USD"}
    },
    "servicePlan": {
      "description": "Chocolate service for the larger party",
      "guestCount": 120,
      "durationMinutes": 120,
      "items": [{"label": "Chocolate", "group": "Flavors", "key": "chocolate"}],
      "lineNotes": [{"lineItemId": "11111111-1111-4111-8111-111111111111", "note": "Staff negotiated the complete service price"}]
    },
    "reviewToken": "0000000000000000000000000000000000000000000000000000000000000000"
  },
  "currentProposalId": "44444444-4444-4444-8444-444444444444",
  "reviewedDepositRequirementRevision": 1,
  "currentServicePlanRevision": 1,
  "resultingServicePlanRevision": 2,
  "servicePlanChange": true,
  "depositChange": true
}
```

`quote.estimateTotal` is the existing preview field name: on this revision endpoint it is
the reviewed **current Quote total**, not the original Estimate total. Render it as
“Current total.” `quote.quoteVersion` equals the reviewed version for a service-only or
deposit-only change. `depositChange` includes financial changes that require a fresh
approval against the new Quote even when the deposit terms do not change.

Publish the **identical request body** to `/proposals/quote-revisions`, adding
`reviewToken` from `response.quote.reviewToken` at the top level. The zero token shown above
is illustrative; always use the actual returned token. Response remains
`{proposal, financial, depositRequirement, servicePlan?}`. `proposal` adds optional
`servicePlanRevision`; its `id` is always a new publication identity. `financial` is the
existing authoritative financial response, including reconciliation.

To change only the deposit, the dedicated route is still available:

```json
{
  "expectedDocumentVersion": 4,
  "expectedDepositRequirementRevision": 2,
  "terms": {"type": "FIXED", "amount": "300.00", "currency": "USD"}
}
```

POST to `/proposals/deposit-revisions`. This route retains its existing version-check
contract, needs no reviewToken, and returns the retained service approval as well.
It does not append a financial version. Equal numeric terms in the same form reject;
percentage-to-fixed is meaningful even if the resolved amount is unchanged.

## Service commitments and line identity

On Quote and Invoice revisions, `servicePlanAction` is:

- `RETAIN` (default): omit servicePlan; keep the existing approval and its notes.
- `REPLACE`: supply the complete proposed servicePlan, including ordered items and lineNotes.
- `CLEAR`: omit servicePlan; append an explicit clearing approval when a plan exists.

Sending servicePlan with RETAIN/CLEAR or omitting it with REPLACE is 422. There is no
implicit replacement based on field presence. No-op replacement or clearing an absent
plan alone is `NO_MATERIAL_CHANGE`. Reordering selections or changing descriptions, guest
counts, durations or line notes is meaningful even at the same price.

Retained notes refer to stable ledger line IDs. Removing a noted line requires a replacement
plan without that note, or CLEAR; otherwise `SERVICE_PLAN_LINE_NOT_FOUND` rejects. Replacing
line values under the same ID retains its note. A new line uses `key` instead of lineItemId;
a proposed note on it uses the same key. Its deterministic ID is returned by preview.
Omission removes financial lines. Always send the complete ordered financial set, including
unchanged lines, with exact values; the backend never looks up a catalog. Descriptions,
sub-descriptions, quantities, flat charges, precise unit rates, discounts and credits retain
the existing validation rules. No client total is accepted as authority.

Service approvals have their own revision numbers. Financial-only changes keep the exact
previous service approval (including its original snapshot reference and provenance).
Service-only changes keep the financial version and deposit revision unchanged. Proposal
identity, not the pair of amounts or financial version, determines which offer is payable.

## Invoice amendments

Only BOOKED and SERVED canonical Invoices are eligible. For example, after a $300 deposit
books a $1,200 Quote, POST `/invoice-revision-preview`:

```json
{
  "expectedDocumentVersion": 4,
  "lines": [{
    "lineItemId": "11111111-1111-4111-8111-111111111111",
    "description": "Catering service",
    "unitPrice": "1450.00",
    "taxAmount": "0.00",
    "currency": "USD"
  }],
  "servicePlanAction": "RETAIN"
}
```

Response:

```json
{
  "inquiryId": "22222222-2222-4222-8222-222222222222",
  "documentId": "33333333-3333-4333-8333-333333333333",
  "reviewedDocumentVersion": 4,
  "resultingDocumentVersion": 5,
  "currentTotal": "1200.00",
  "total": "1450.00",
  "subtotal": "1450.00",
  "taxAmount": "0.00",
  "currency": "USD",
  "lines": [{
    "id": "11111111-1111-4111-8111-111111111111",
    "origin": "REPLACED",
    "description": "Catering service",
    "unitPrice": "1450.00",
    "subtotal": "1450.00",
    "taxAmount": "0.00",
    "total": "1450.00",
    "currency": "USD"
  }],
  "financialChange": true,
  "servicePlanChange": false,
  "currentServicePlanRevision": 1,
  "resultingServicePlanRevision": 1,
  "servicePlan": {
    "description": "Original service",
    "guestCount": 100,
    "durationMinutes": 120,
    "items": [{"label": "Vanilla", "group": "Flavors", "key": "vanilla"}],
    "lineNotes": []
  },
  "netApplied": "300.00",
  "balance": "1150.00",
  "settlement": "UNDERPAID",
  "reviewToken": "0000000000000000000000000000000000000000000000000000000000000000"
}
```

Publish identical inputs plus the actual top-level `reviewToken` to `/invoice-revisions`.
Its response is `{financial, servicePlan?}` using existing financial and approved-plan DTOs.
Refetch staff detail for the complete workspace. A service-only amendment sends the unchanged
complete lines plus REPLACE/CLEAR: `financialChange=false`, resultingDocumentVersion stays
the same and resultingServicePlanRevision advances. Combined changes commit or roll back
together. There is no new Invoice-to-Quote transition or manual booking route.

The accepted $300 deposit remains $300: the original percentage is never recomputed against
$1,450. Existing partial payments also remain applied. `settlement` is UNDERPAID for positive
balance, SETTLED for zero, OVERPAID for negative. A reduction below netApplied does not refund
money or rewrite allocations. Use existing explicit refund operations. Further payments
use ordinary Invoice payments; no new deposit is collected. Amendment never marks served,
closes, reopens or changes fulfillment provenance. Closure still needs SERVED and exact zero.

## Current and historical reads

`GET /staff/requests/{inquiryId}` returns one coherent snapshot:

| Field | Meaning |
|---|---|
| financial | Latest canonical document and current shared reconciliation |
| proposal | Latest publication; immutable accepted proposal after booking |
| servicePlan | Approval bound to proposal, retaining its earlier meaning |
| effectiveServicePlan | Latest fulfillment approval, which may differ after Invoice amendments |
| acceptedServicePlan | Proposal's approval after booking; absent before booking or if none existed |
| servicePlanHistory | All approvals in independent revision order |
| proposalHistory | All publications in durable publication order |

Approved-plan example:

```json
{
  "documentId": "33333333-3333-4333-8333-333333333333",
  "documentVersion": 5,
  "reviewedDocumentVersion": 4,
  "description": "Revised service",
  "guestCount": 120,
  "durationMinutes": 120,
  "items": [{"label": "Chocolate", "group": "Flavors", "key": "chocolate"}],
  "lineNotes": [],
  "approvedAt": "2026-10-27T18:00:00Z",
  "approvedBy": "55555555-5555-4555-8555-555555555555",
  "revision": 2,
  "cleared": false
}
```

A clearing response keeps document references, revision and approval provenance, has
`cleared=true`, no description/guestCount/durationMinutes, and empty items/lineNotes.
Optional null properties follow existing CommerceJson omission behavior. Absence of the
whole approval differs from a historical clearing. The approval's documentVersion can be
older than the latest financial/proposal version when retained, or equal to reviewedDocumentVersion
for a service-only change. Never infer currentness from a financial version alone.

Join proposalHistory.servicePlanRevision to servicePlanHistory.revision. The final proposal
in history is the accepted proposal once financial.stage is INVOICE; later amendments do
not append proposals. Use GET `/financial-documents/{documentId}/history` for all immutable
financial snapshots (initial/revised Quote and initial/revised Invoice), and GET
`/financial-documents/{documentId}/deposit-requirement/history` for deposit revisions.
These histories remain runtime-owned; no duplicate financial history table exists.

## Errors, stale reviews and frontend eligibility

- 400 malformed_request: missing required fields/token, unreadable UUID, malformed shape, or a
  `servicePlanAction` other than RETAIN/REPLACE/CLEAR (both enums are closed in OpenAPI).
- 401/403: authentication, principal kind, permissions or trusted Origin failure.
- 404 not_found: missing canonical inquiry/lineage; unrelated IDs cannot select another lineage.
- 409 conflict: stale expected financial/deposit version. Reload and preview again.
- 409 QUOTE_REVIEW_STALE: authoritative state or reviewed intent changed. Both Quote and
  Invoice use this established code. Never replace the token without a new human review.
- 409 illegal_transition: wrong stage, CLOSED, or historical Quote allocation.
- 422 validation_failed: invalid lines/plan/terms or no material change. Stable violations
  include NO_MATERIAL_CHANGE, SERVICE_PLAN_LINE_NOT_FOUND, LINE_NOT_IN_REVIEWED_DOCUMENT,
  CURRENCY_MISMATCH, NEGATIVE_DOCUMENT_TOTAL and QUOTE_TOTAL_NOT_POSITIVE.
- Unexpected failures use the existing generic 500 envelope; all approval writes roll back.

Enable initial preview for REQUESTED, Quote revision for QUOTED, and Invoice amendments
for BOOKED/SERVED, subject to permissions. Server eligibility is authoritative. CLOSED and
DECLINED do not expose ordinary amendment actions. RELATED documents use existing standalone
routes and must not be treated as canonical booking state.

Require a fresh preview whenever lines/order, notes/selections, plan action, service details,
deposit terms, financial/deposit version or publication/approval identity changes. Invoice
payment or refund changes also invalidate review. Tokens bind immutable payment-history
identities, so even offsetting money changes require a fresh preview. Do not reuse tokens
across inquiries, stages, revisions or initial/revision endpoints.

Disable duplicate submit while approval is pending. A duplicate approval is not an idempotent
success: it conflicts after the first commit. After an uncertain response, reload the workspace
and inspect history before deciding whether another amendment is still needed. The public
inquiry-submission Idempotency-Key contract is unrelated and unchanged.

## Integration and deliberate exclusions

Deploy the backend's additive Fiona V4 migration normally; no database reset, repair or
commerce-schema mutation is required. Update tokenless Quote revision callers and callers
that previously sent servicePlan without an action. Current/service history fields are
additive; servicePlan keeps its historical proposal meaning. Prefer effectiveServicePlan for
fulfillment forms and acceptedServicePlan for acceptance comparison.

No fionas-web changes are included here. Delivery/email, customer acceptance/signatures,
provider payments, cancellation/reopening, post-close corrections and additional-deposit
workflows remain outside this milestone. NATS/outbox behavior is unchanged.
