# Migrating to trusted priced lines (commerce 0.0.23)

This release removes Fiona's catalog and pricing engine. Fiona now accepts money only as
complete, already-priced lines from two authorities, and records who authored them:

| Authority | Principal | What it prices | Where |
|---|---|---|---|
| `fionas-web` server | SERVICE with `fionas.inquiries.create` | The customer's configured request → canonical Estimate v1 | `POST /inquiries` |
| Staff member | USER with the commerce permission **and** `fionas.financial-terms.manage` | Every negotiated document after that | staff document, change-order and proposal routes |

Fiona validates structure and exact arithmetic, derives every total, and never checks prices
against anything. Contract details: [`README.md`](../README.md#endpoints) and
[`AGENTS.md`](../AGENTS.md#trusted-priced-lines).

## What fionas-web must change

1. **Own the catalog and pricing in SvelteKit server-only modules** (`$lib/server/...`, never
   shipped to the browser). The form, its choices, and the price calculation move there; Fiona
   no longer serves `/inquiry-form`, `/estimate-preview` or `/offering-catalog`.
2. **Submit priced inquiries as the SERVICE.** Exchange the service credential at
   `POST /auth/service/token` and send `Authorization: Bearer`. The body adds
   `requestedService` (descriptive) and `lines` (authoritative); `pricingInputs` is gone.
   Keep one `Idempotency-Key` per logical submission **with the same lines** across retries.
3. **Proxy staff actions with the staff member's own identity.** Forward the verified staff
   session cookie (`__Host-fionas_session`) and a trusted `Origin`. Never call staff routes
   with the service token (`403 forbidden`), and there is no on-behalf-of header.
4. **Build staff editors on final lines.** Load the reviewed version's lines (with ids) from
   `GET /staff/requests/{inquiryId}` or `GET /financial-documents/{id}`, let staff carry, edit,
   remove and add lines, send the complete ordered set, and preview before publishing.
5. **Drop catalog-staleness handling.** `CATALOG_REVISION_STALE` no longer exists.

## API changes

| Before | After |
|---|---|
| `GET /inquiry-form` (`getInquiryForm`) | Removed (`404`). The form lives in fionas-web. |
| `POST /estimate-preview` (`previewEstimate`) | Removed (`404`). Price in fionas-web. |
| `/offering-catalog/**` (`fionasOfferings*`, 17 operations) | Removed (`404`). |
| `POST /inquiries` body `pricingInputs` | `requestedService` + `lines`; SERVICE principal only; staff sessions `403`. |
| `GET /inquiries/{id}` `pricingInputs` | `requestedService` (descriptive). |
| `POST /inquiries/{id}/estimates` commercial inputs | `{lines}`; staff USER + `fionas.financial-terms.manage`. |
| `POST /inquiries/{id}/financial-documents` `{stage, …inputs}` | `{stage, lines}`; staff USER + `fionas.financial-terms.manage`. |
| `POST /financial-documents/{id}/change-orders` `{expectedVersion, …inputs}` | `{expectedVersion, lines}` with `lineItemId` or `key` per line; staff USER + terms permission. |
| `FinancialDocumentResponse.pricing` | `linesAuthoredBy` (`principalKind`, `principalId`, `recordedAt`). |
| `POST /staff/requests/{id}/quote-preview` `{composition: {pricing, overrides, adjustments}}` | `{expectedDocumentVersion, lines, servicePlan?, terms}`; staff USER + terms permission. |
| `POST /staff/requests/{id}/proposals` `{…, composition?, reviewToken?}` | `{expectedDocumentVersion, terms, lines?, servicePlan?, reviewToken?}`. |
| `POST …/proposals/quote-revisions` repriced from inputs | `{expectedDocumentVersion, expectedDepositRequirementRevision, lines, terms, servicePlan?}`. |
| Proposal routes: two commerce permissions, USER or SERVICE | Plus `fionas.financial-terms.manage`, USER only. |
| `InquiryProposalResponse.principalKind`/`principalId` | `issuedBy` (staff user id). |
| `servicePlan` with pricing basis, selections, line provenance | `{documentId, documentVersion, reviewedDocumentVersion, description, guestCount?, durationMinutes?, items, lineNotes, approvedAt, approvedBy}`. |
| `409 CATALOG_REVISION_STALE` | Removed. `IDEMPOTENCY_KEY_REUSED` and `QUOTE_REVIEW_STALE` remain. |
| Quote builder codes `OVERRIDE_*`, `DUPLICATE_ADJUSTMENT_KEY`, `SERVICE_SELECTIONS_*`, offering codes | `LINE_NOT_IN_REVIEWED_DOCUMENT`, `CURRENCY_MISMATCH`, `NO_FINANCIAL_CHANGE`, `NEGATIVE_DOCUMENT_TOTAL`, `QUOTE_TOTAL_NOT_POSITIVE`, `SERVICE_PLAN_LINE_NOT_FOUND`. |

Unchanged: inquiry listing and reads, the dashboard, `GET /staff/requests/{id}` (shape aside
from `servicePlan`), deposit routes, payments, refunds, served/closed, authentication and
administration.

## Removed types and permissions

- **Permissions:** `fionas.inquiry-form.read`, `fionas.estimate-preview.create` (and group
  `fionas.pricing`); Fiona no longer uses `commerce.offerings.manage`.
- **New permission:** `fionas.financial-terms.manage` (group `fionas.financial-terms`).
- **Fiona code:** the `offering` package (`FionasPricingInputs`, `FionasOfferingsContext`,
  `FionasPricingPolicy`, `FionasOfferingsEngine`, `FionasPricing`, `PreviewEstimate`,
  `PersistedPricingInputs`, `FionaOfferings`), `GetInquiryForm`, `InquiryForm`,
  `PublicInquiryPricing`, `PublicInquiryOfferings`, `InquiryPricingPreview`,
  `CreateInquiryEstimate`, `FinancialDocumentPricingRepository`, `QuoteComposition`,
  `InquiryQuoteComposition`, `PersistedServicePlan`, and their HTTP DTOs.
- **Tables:** `fionas.financial_document_pricing` and `inquiries.pricing_inputs` (replaced by
  `financial_document_authorship` and `inquiries.requested_service`).
- **Script:** `scripts/replace-catalog.mjs`.

## Examples

SERVICE priced submission:

```http
POST /inquiries
Authorization: Bearer <fionas-web service access token>
Idempotency-Key: 6f1d0c8e-6c1a-4b7e-9d55-0c3f2b1a9e77
Content-Type: application/json

{"name": "Jane Doe", "email": "jane@example.com", "zipCode": "92626",
 "eventDate": "2026-12-05", "eventType": "BIRTHDAY", "message": "Ice cream for a birthday.",
 "requestedService": {"guestCount": 75, "guestCountIsMinimum": false, "durationMinutes": 120,
   "items": [{"label": "Horchata soft serve", "group": "Soft serve", "key": "soft-horchata"}],
   "pricingReference": "fionas-web-pricing@2026-10-01"},
 "lines": [
   {"description": "Base event service", "unitPrice": "250.00", "taxAmount": "0.00", "currency": "USD"},
   {"description": "Ice cream service", "subDescription": "75 guests", "quantity": "75",
    "unitPrice": "4.00", "taxAmount": "0.00", "currency": "USD"}]}
```

```json
201 Created
Location: /inquiries/c755f7cd-1e28-4c75-a85f-d066ede7387d
{"id": "c755f7cd-1e28-4c75-a85f-d066ede7387d", "createdAt": "2026-09-26T21:19:39.321012Z"}
```

USER staff preview, then publication of exactly that review (the churro example):

```http
POST /staff/requests/c755f7cd-1e28-4c75-a85f-d066ede7387d/quote-preview
Cookie: __Host-fionas_session=<staff session>
Origin: https://staff.example.com
Content-Type: application/json

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

```json
200 OK (Cache-Control: no-store)
{"reviewedDocumentVersion": 1, "estimateTotal": "681.25", "financialChange": true, "quoteVersion": 3,
 "lines": [
   {"id": "8f1d2c3b-…", "origin": "NEW", "key": "churros", "description": "Churro catering service",
    "subDescription": "Prepared on site", "quantity": "1", "unitPrice": "450.00", "subtotal": "450.00",
    "taxAmount": "0.00", "total": "450.00", "currency": "USD"},
   {"id": "2a3b4c5d-…", "origin": "NEW", "key": "courtesy", "description": "Courtesy discount",
    "unitPrice": "-50.00", "subtotal": "-50.00", "taxAmount": "0.00", "total": "-50.00", "currency": "USD"}],
 "subtotal": "400.00", "taxAmount": "0.00", "total": "400.00", "currency": "USD",
 "deposit": {"terms": {"type": "PERCENTAGE", "percentage": "20"},
             "requiredAmount": {"amount": "80.00", "currency": "USD"}},
 "servicePlan": {"…": "notes now name line ids"},
 "reviewToken": "9f2c4a1b…"}
```

Publish with the same body plus `"reviewToken"` to `POST /staff/requests/{id}/proposals`. The
response is `{proposal, financial, depositRequirement, servicePlan}`: Estimate v2 holds the
staff lines and Quote v3 is published with an approved $80.00 deposit, issued by the staff
user. A changed result is `409 QUOTE_REVIEW_STALE`; preview again.

USER staff republish with an override (unpaid Quote v3, deposit revision 1):

```http
POST /staff/requests/c755f7cd-1e28-4c75-a85f-d066ede7387d/proposals/quote-revisions
Cookie: __Host-fionas_session=<staff session>
Origin: https://staff.example.com
Content-Type: application/json

{"expectedDocumentVersion": 3, "expectedDepositRequirementRevision": 1,
 "lines": [
   {"lineItemId": "8f1d2c3b-…", "description": "Churro catering service", "subDescription": "Prepared on site",
    "quantity": "1", "unitPrice": "420.00", "taxAmount": "0.00", "currency": "USD"},
   {"lineItemId": "2a3b4c5d-…", "description": "Courtesy discount",
    "unitPrice": "-50.00", "taxAmount": "0.00", "currency": "USD"},
   {"key": "travel", "description": "Additional travel fee",
    "unitPrice": "25.00", "taxAmount": "0.00", "currency": "USD"}],
 "terms": {"type": "PERCENTAGE", "percentage": "20"}}
```

The churro line keeps its id at the new price, the discount is carried, the travel fee is
added, and Quote v4 is published with a replacement deposit. Identical lines are
`422 NO_FINANCIAL_CHANGE`; use `deposit-revisions` to change only the deposit.

## Operators

- **Reset databases manually.** Both migration streams were rebaselined; an existing database
  fails validation at startup. Stop the application, drop and recreate the database (or
  `docker compose down -v`), start once with the bootstrap variables, and re-provision
  `fionas-web`. The application never resets anything itself.
- **Grant the new permission.** Fresh Administrators receive `fionas.financial-terms.manage`;
  for other staff roles, read the role and PUT its complete permission set with the key added.
- **Re-scope `fionas-web`.** Its role needs exactly `fionas.inquiries.create`.
