# Quote builder implementation: initial composition, preview and atomic publication

> **Superseded (commerce 0.0.23, trusted priced lines).** This record describes the first,
> catalog-backed quote builder (pricing modes, source overrides, adjustments, catalog revisions).
> That design was removed: staff now commit complete final lines, and Fiona has no catalog or
> pricing engine. The current contract is
> [`AGENTS.md`](../AGENTS.md#quote-builder-staff-committed-lines); consumers migrate with
> [`trusted-priced-lines-migration.md`](trusted-priced-lines-migration.md). Kept as history.

Staff can start from the persisted canonical Estimate, explicitly compose service selections,
direct price overrides and separate adjustment lines, obtain a write-free preview, and issue
the reviewed result through the one existing initial proposal operation. One READ COMMITTED
transaction yields an immutable Quote, its approved service plan, a deposit approved against
that exact Quote, and the INITIAL publication. Every #19 guarantee and the deposit-only
issuance contract are unchanged. The concrete rules are in
[`AGENTS.md`](../AGENTS.md#quote-builder-initial-composition); the principle is in
[`ARCHITECTURE.md`](../ARCHITECTURE.md#staff-quote-composition).

## Base and dependencies

- Branch `claude/quote-builder`, from `main` at `7bcd404` (the merge of PR #19,
  `codex/change-order-foundation`, head `ede6ff2`).
- commerce-runtime and transitive commerce-domain `0.0.22`, unchanged. Upstream APIs relied on
  were read at tag `v0.0.22` (`4d1d8b5`): `FinancialDocument.changeOrder`/`toQuote`,
  `ChangeOrder` add/replace/remove, `DepositTerms.resolve`, and the transaction-taking
  `FinancialLedger.changeOrder`, `issueQuote`, `activateDepositRequirement` and
  `financialLineages`.
- **No upstream gap blocks this slice.** `DepositTerms.resolve(document)` is public and pure,
  so the preview resolves the authoritative deposit against the pure Quote candidate with the
  shared HALF_UP and bound rules; nothing is written and no Fiona rounding exists. The known
  expected-version ledger gap (#19 audit) is still compensated by the locked association and
  `expectLatest` before every write.

## Design summary

| Concern | Decision |
|---|---|
| Intent | `QuoteComposition`: one `QuotePricing` mode, `QuoteLineOverride`s, `QuoteAdjustment`s. Closed HTTP unions (`mode`, override `type`) with strict serializers. |
| Modes | `KEEP_ESTIMATE` (persisted lines and ids, no pricing), `REVISE_SERVICE_SELECTIONS` (persisted lines; selections must price identically today), `REPRICE_CONFIGURATION` (complete reviewed configuration at the current catalog revision). |
| Provenance | `FionasChargeSource` from the engine's own policy: base service, ice cream service, a selected offering (category/key), extra toppings. Persisted Estimate lines are targeted by id only. |
| Overrides | Final nonnegative flat amount (`quantity = null`), same id when replacing an Estimate line; no rounded unit prices. |
| Adjustments | Positive magnitude; `CHARGE` positive, `DISCOUNT`/`CREDIT` negative; new server id; request-local `clientKey`. |
| Financial change | `sameCharges` over every line fact and order; intermediate Estimate only when charges differ. |
| Totals and deposit | commerce-domain's document (`changeOrder(...).toQuote()`) and `DepositTerms.resolve`; Quote total must be positive. |
| Review | SHA-256 token over every reviewed fact except generated ids; approval re-derives and compares under the lock. |
| Persistence | V15 `inquiry_service_plans`, money-free, one immutable row per exact composed Quote. |
| Read model | Optional `servicePlan` on the staff request and on issuance responses. |

## Migration and integrity review

`V15__inquiry_service_plans.sql` creates one Fiona-owned table and alters nothing else.

| Property | Enforcement |
|---|---|
| Ownership | `(inquiry_id, document_id)` FK to `fionas.inquiry_financial_documents`; the repository additionally requires the `INITIAL_ESTIMATE` purpose under the association lock. |
| Version pinning | Primary key `(document_id, document_version)`; FKs from it and from `(document_id, reviewed_document_version)` to the published `commerce.financial_document_snapshots (document_id, version)`, the runtime contract already sanctioned for V3. |
| Ordering | `document_version >= 2` and `1 <= reviewed_document_version < document_version`. |
| Closed values | `pricing_basis` and `principal_kind` checks; `catalog_revision >= 1`; `plan` must be a JSON object; `approved_at` is microsecond `timestamptz(6)`. |
| No ledger facts | No amount/price/total/balance/stage/status column or plan key; `DatabaseSchemaSpec`, `ArchitectureSpec` and `PersistedServicePlanSpec` guard it. Lines are named by ledger id inside the plan. |
| Strict content | `PersistedServicePlan.kt` decodes strictly (unknown/missing/null/wrong-type/invalid combinations fail); only `JdbiInquiryServicePlanRepository` encodes or restores it. |
| Coherent reads | The staff read checks the plan against the exact Quote snapshot's line ids in the same REPEATABLE READ; corruption is a `500`, never a reinterpretation. |
| Legacy absence | Earlier Quotes, deposit-only issuance and later Quote revisions have no plan; nothing is backfilled or invented. |
| Rollback | The plan is inserted after the Quote and before the deposit approval and publication in one transaction; tests inject failures at every step. |

The migration is additive on an empty table and needs no data conversion; rolling deploys
are safe because older instances never read or write it.

## Contract examples

The examples below are real responses captured from the complete handler against the
acceptance catalog (an Estimate of base $250.00, ice cream $160.00 for 40 guests and waffle
cones $30.00).

Ids are shortened (`…`); everything else is verbatim, compacted.

### `KEEP_ESTIMATE` with an override and two adjustments, fixed deposit

```json
POST /staff/requests/29adfdb9-…/quote-preview
{"expectedDocumentVersion": 1,
 "composition": {"pricing": {"mode": "KEEP_ESTIMATE"},
   "overrides": [{"target": {"type": "EXISTING_LINE", "lineItemId": "549596e2-…"},
                  "finalAmount": "145.00", "currency": "USD", "reason": "Negotiated package rate"}],
   "adjustments": [{"clientKey": "travel-1", "kind": "CHARGE", "description": "Additional travel fee",
                    "amount": "25.00", "currency": "USD", "reason": "Outside normal service area"},
                   {"clientKey": "courtesy-1", "kind": "DISCOUNT", "description": "Courtesy discount",
                    "amount": "20.00", "currency": "USD", "reason": "Customer accommodation"}]},
 "terms": {"type": "FIXED", "amount": "105.00", "currency": "USD"}}

200 OK  Cache-Control: no-store
{"inquiryId": "29adfdb9-…", "documentId": "b2ba453a-…", "reviewedDocumentVersion": 1,
 "estimateTotal": "440.00", "pricingBasis": "KEEP_ESTIMATE", "catalogRevision": 5,
 "financialChange": true, "quoteVersion": 3,
 "service": {"guestCount": 40, "guestCountIsMinimum": false, "durationMinutes": 120,
   "selections": [{"category": "soft-serve-flavor", "displayName": "Soft Serve",
                   "offerings": [{"offering": "vanilla", "displayName": "Vanilla"}]},
                  {"category": "topping", "displayName": "Toppings", "offerings": [
                     {"offering": "sprinkles", "displayName": "Sprinkles"}, {"offering": "oreos", "displayName": "Oreos"},
                     {"offering": "strawberries", "displayName": "Strawberries"}, {"offering": "brownies", "displayName": "Brownies"}]},
                  {"category": "cone-option", "displayName": "Cones",
                   "offerings": [{"offering": "waffle-cone", "displayName": "Waffle cones"}]}]},
 "lines": [
   {"lineItemId": "3a852773-…", "origin": {"type": "ESTIMATE_LINE"}, "description": "Base service",
    "subDescription": "2 hours · setup, staff & local travel", "unitPrice": "250.00", "subtotal": "250.00",
    "taxAmount": "0.00", "total": "250.00", "currency": "USD"},
   {"lineItemId": "549596e2-…", "origin": {"type": "ESTIMATE_LINE"}, "description": "Ice cream service",
    "unitPrice": "145.00", "subtotal": "145.00", "taxAmount": "0.00", "total": "145.00", "currency": "USD",
    "override": {"reason": "Negotiated package rate", "originalQuantity": "40", "originalUnitPrice": "4.00",
                 "originalTotal": "160.00"}},
   {"lineItemId": "06e3618c-…", "origin": {"type": "ESTIMATE_LINE"}, "description": "Waffle cones",
    "quantity": "40", "unitPrice": "0.75", "subtotal": "30.00", "taxAmount": "0.00", "total": "30.00", "currency": "USD"},
   {"origin": {"type": "ADJUSTMENT", "kind": "CHARGE", "reason": "Outside normal service area", "clientKey": "travel-1"},
    "description": "Additional travel fee", "unitPrice": "25.00", "subtotal": "25.00", "taxAmount": "0.00",
    "total": "25.00", "currency": "USD"},
   {"origin": {"type": "ADJUSTMENT", "kind": "DISCOUNT", "reason": "Customer accommodation", "clientKey": "courtesy-1"},
    "description": "Courtesy discount", "unitPrice": "-20.00", "subtotal": "-20.00", "taxAmount": "0.00",
    "total": "-20.00", "currency": "USD"}],
 "subtotal": "430.00", "taxAmount": "0.00", "total": "430.00", "currency": "USD",
 "deposit": {"terms": {"type": "FIXED", "amount": "105.00", "currency": "USD"},
             "requiredAmount": {"amount": "105.00", "currency": "USD"}},
 "reviewToken": "d0bf75539c59600ceb1d6a29afd5fe38b6a2c6b1b613bd02e9f024f4f25ba98f"}
```

### Approval of that reviewed composition

```json
POST /staff/requests/29adfdb9-…/proposals
{"expectedDocumentVersion": 1, "terms": {"type": "FIXED", "amount": "105.00", "currency": "USD"},
 "composition": { …exactly as previewed… },
 "reviewToken": "d0bf75539c59600ceb1d6a29afd5fe38b6a2c6b1b613bd02e9f024f4f25ba98f"}

200 OK  Cache-Control: no-store
{"proposal": {"id": "7404eaba-…", "inquiryId": "29adfdb9-…", "documentId": "b2ba453a-…", "documentVersion": 3,
              "depositRequirementRevision": 1, "issuedAt": "2026-09-26T18:30:00.123456Z", "principalKind": "USER",
              "principalId": "68d00891-…", "issuanceKind": "INITIAL"},
 "financial": {"id": "b2ba453a-…", "version": 3, "previousVersion": 2, "stage": "QUOTE",
               "lines": [ …the five previewed lines, now with ids 3a852773-…, 549596e2-…, 06e3618c-…, 5b1c18d9-…, 0046fa7a-… ],
               "subtotal": "430.00", "taxAmount": "0.00", "total": "430.00", "currency": "USD",
               "reconciliation": {"grossAllocated": "0.00", "netApplied": "0.00", "balance": "430.00", "currency": "USD"}},
 "depositRequirement": {"state": "ACTIVE", "documentId": "b2ba453a-…", "revision": 1, "approvalDocumentVersion": 3,
                        "terms": {"type": "FIXED", "amount": "105.00", "currency": "USD"},
                        "requiredAmount": {"amount": "105.00", "currency": "USD"}, "satisfied": false},
 "servicePlan": {"documentId": "b2ba453a-…", "documentVersion": 3, "reviewedDocumentVersion": 1,
   "pricingBasis": "KEEP_ESTIMATE", "catalogRevision": 5, "approvedAt": "2026-09-26T18:30:00.123456Z",
   "principalKind": "USER", "principalId": "68d00891-…",
   "service": { …as previewed… },
   "lines": [{"lineItemId": "3a852773-…", "origin": {"type": "ESTIMATE_LINE"}},
             {"lineItemId": "549596e2-…", "origin": {"type": "ESTIMATE_LINE"}, "overrideReason": "Negotiated package rate"},
             {"lineItemId": "06e3618c-…", "origin": {"type": "ESTIMATE_LINE"}},
             {"lineItemId": "5b1c18d9-…", "origin": {"type": "ADJUSTMENT", "kind": "CHARGE", "reason": "Outside normal service area"}},
             {"lineItemId": "0046fa7a-…", "origin": {"type": "ADJUSTMENT", "kind": "DISCOUNT", "reason": "Customer accommodation"}}]}}
```

The ledger now holds Estimate v1 ($440.00, unchanged), Estimate v2 ($430.00) and Quote v3
($430.00). The inquiry is QUOTED; nothing was sent or paid.

### `REPRICE_CONFIGURATION` with a source override, percentage deposit

```json
POST /staff/requests/2d5462cb-…/quote-preview
{"expectedDocumentVersion": 1,
 "composition": {"pricing": {"mode": "REPRICE_CONFIGURATION", "catalogRevision": 5, "guestCount": 50,
     "durationMinutes": 150,
     "selections": [{"category": "soft-serve-flavor", "offerings": ["vanilla", "horchata"]},
                    {"category": "topping", "offerings": ["sprinkles", "oreos", "strawberries", "brownies"]},
                    {"category": "cone-option", "offerings": ["waffle-cone"]}]},
   "overrides": [{"target": {"type": "SELECTED_OFFERING", "category": "cone-option", "offering": "waffle-cone"},
                  "finalAmount": "30.00", "currency": "USD", "reason": "Cone promotion"}]},
 "terms": {"type": "PERCENTAGE", "percentage": "20"}}

200 OK  Cache-Control: no-store
{"estimateTotal": "440.00", "pricingBasis": "REPRICE_CONFIGURATION", "catalogRevision": 5,
 "financialChange": true, "quoteVersion": 3,
 "lines": [
   {"origin": {"type": "GENERATED", "source": {"type": "BASE_SERVICE"}}, "description": "Base service",
    "subDescription": "2.5 hours · setup, staff & local travel", "unitPrice": "275.00", "total": "275.00", …},
   {"origin": {"type": "GENERATED", "source": {"type": "ICE_CREAM_SERVICE"}}, "description": "Ice cream service",
    "subDescription": "50 guests", "quantity": "50", "unitPrice": "4.00", "total": "200.00", …},
   {"origin": {"type": "GENERATED", "source": {"type": "SELECTED_OFFERING", "category": "soft-serve-flavor",
    "offering": "horchata"}}, "description": "Horchata", "quantity": "50", "unitPrice": "0.50", "total": "25.00", …},
   {"origin": {"type": "GENERATED", "source": {"type": "SELECTED_OFFERING", "category": "cone-option",
    "offering": "waffle-cone"}}, "description": "Waffle cones", "unitPrice": "30.00", "total": "30.00", …,
    "override": {"reason": "Cone promotion", "originalQuantity": "50", "originalUnitPrice": "0.75", "originalTotal": "37.50"}}],
 "total": "530.00",
 "deposit": {"terms": {"type": "PERCENTAGE", "percentage": "20"}, "requiredAmount": {"amount": "106.00", "currency": "USD"}},
 "reviewToken": "ab7d25bc…"}
```

New lines have no `lineItemId` until issuance assigns one; repricing replaces the Estimate's
line set explicitly.

### `REVISE_SERVICE_SELECTIONS` (service-only change)

```json
{"composition": {"pricing": {"mode": "REVISE_SERVICE_SELECTIONS", "catalogRevision": 5,
   "selections": [{"category": "soft-serve-flavor", "offerings": ["chocolate"]},
                  {"category": "topping", "offerings": ["sprinkles", "oreos", "strawberries", "brownies"]},
                  {"category": "cone-option", "offerings": ["waffle-cone"]}]}}, …}

200 OK  {"pricingBasis": "REVISE_SERVICE_SELECTIONS", "financialChange": false, "quoteVersion": 2,
         "service": {"selections": [{"category": "soft-serve-flavor", "offerings": [{"offering": "chocolate",
                     "displayName": "Chocolate"}]}, …]},
         "lines": [ …the three persisted Estimate lines and ids, unchanged… ], "total": "440.00",
         "deposit": {"terms": {"type": "PERCENTAGE", "percentage": "20"},
                     "requiredAmount": {"amount": "88.00", "currency": "USD"}}, …}
```

### Failures

```json
422  {"code": "validation_failed",
      "message": "An initial Quote requires a positive total, which its positive deposit cannot exceed",
      "violations": [{"code": "QUOTE_TOTAL_NOT_POSITIVE"}]}          // a $440.00 CREDIT on the $440.00 Estimate
409  {"code": "CATALOG_REVISION_STALE",
      "message": "The offerings catalog changed; reload it and review the selections before pricing again"}  // no-store
409  {"code": "QUOTE_REVIEW_STALE",
      "message": "The quote changed since it was reviewed; preview it again and review the result"}          // no-store
400  {"code": "malformed_request", …}   // composition without reviewToken, or a variant with foreign fields
```

### Deposit-only issuance (unchanged)

```json
POST /staff/requests/2d5462cb-…/proposals
{"expectedDocumentVersion": 1, "terms": {"type": "PERCENTAGE", "percentage": "20"}}

200 OK  {"proposal": {…, "documentVersion": 2, "issuanceKind": "INITIAL"},
         "financial": {…, "version": 2, "stage": "QUOTE", "total": "440.00"},
         "depositRequirement": {"state": "ACTIVE", …, "requiredAmount": {"amount": "88.00", "currency": "USD"}}}
```

No `servicePlan` key appears, as before this slice.

## Testing and validation

Executed against the resolved 0.0.22 artifacts with JDK 25, the Docker-CLI PostgreSQL harness
and Node.js on PATH (for `ReplaceCatalogSpec`):

| Command | Result |
|---|---|
| Baseline before changes: `test` for the proposal, change-order, staff-request, architecture and schema specs | Passed, 96 tests |
| `./gradlew ktlintFormat` | Passed |
| `./gradlew ktlintCheck test build` | Passed: 625 tests, 0 failures, 0 skipped |
| `./gradlew generateOpenApi` | Passed; `build/openapi/fionas-commerce-openapi.json` |
| `git diff --check` | Passed |

New suites: `QuoteComposerSpec` (19, pure), `QuoteBuilderSpec` (11, PostgreSQL),
`QuoteBuilderRoutesSpec` (7, complete handler) and `PersistedServicePlanSpec` (2). Preserved
and extended: `ChangeOrderFoundationSpec` (10), `FinancialDocumentAtomicitySpec` (6),
`FinancialDocumentRoutesSpec` (18), `RepricingSpec` (4), `InquiryProposalsSpec` (20),
`InquiryProposalRoutesSpec` (7), `StaffRequestSpec` (7), `StaffRequestRoutesSpec` (6),
`FinancialDocumentReadConsistencySpec` (4), `OpenApiDocumentSpec` (60), `OpenApiRoutesSpec` (5),
`ArchitectureSpec` (27), `DatabaseSchemaSpec` (21) and `MigrationLifecycleSpec` (6). No
existing assertion was weakened; list-style guards (tables, migrations, operations, schemas,
repositories, transaction owners, serialization owners) were extended for the new route, table
and files. A first full run caught only `MigrationLifecycleSpec`'s expected history list, which
now includes V15.

The handoff's illustrative $415 Estimate cannot be produced by Fiona's production policy, so
`QuoteComposerSpec` reproduces it exactly with an explicit test policy and catalog
($205 + $180 + $30 → $165 override, +$25, −$20 → $405, $105 deposit). The integration and
HTTP suites derive the equivalent case from the real engine ($440 → $430, $105 deposit) and
book it with the existing exact-deposit payment. No tests were blocked and no upstream change
was needed.

## Next `fionas-web` handoff

The builder is a staff page over the BFF; the backend owns every amount.

1. **Load** `GET /staff/requests/{inquiryId}`. Show `inquiry.pricingInputs` (what the customer
   requested, read-only) and `financial` (the persisted Estimate lines and total, never
   recomputed). Proceed only when `inquiry.lifecycle.stage` is `REQUESTED`; keep
   `financial.version` as `expectedDocumentVersion`. Offer `suggestedDepositTerms` as the default.
2. **Load choices** from `GET /offering-catalog` (current revision, names, prices, state) for the
   selection editor. Disabled offerings are not choices; unavailable ones are shown but not
   selectable.
3. **Edit** with the three explicit modes. Default `KEEP_ESTIMATE`. Offer line overrides on
   Estimate lines by `lineItemId`. When staff change only unpriced or equally priced choices,
   use `REVISE_SERVICE_SELECTIONS` with the catalog revision; when they change guests, duration
   or priced choices, switch to `REPRICE_CONFIGURATION` and say clearly that the whole price is
   recalculated from today's catalog. In repricing mode, overrides target sources
   (`BASE_SERVICE`, `ICE_CREAM_SERVICE`, `EXTRA_TOPPINGS`, `SELECTED_OFFERING`). Adjustments
   take a positive amount and a kind; generate a stable `clientKey` per row.
4. **Preview** `POST /staff/requests/{inquiryId}/quote-preview` after edits (debounced). Render
   its `lines` (with `override` originals and `origin`s), `total`, `deposit.requiredAmount` and
   `service` names. Never compute totals or deposits in the browser. Show violation codes next
   to their fields. `CATALOG_REVISION_STALE`: reload the catalog and ask staff to review;
   `conflict` on the version: reload the request.
5. **Deposit**: let staff pick `FIXED` (amount and currency) or `PERCENTAGE`; preview again to
   show the exact resolved amount. Terms are part of the reviewed result.
6. **Approve once**: `POST /staff/requests/{inquiryId}/proposals` with the same
   `expectedDocumentVersion`, `terms`, `composition` and the preview's `reviewToken`. Disable the
   button while in flight. Do not retry automatically on timeouts or `5xx`; reload the request to
   learn whether it published. `QUOTE_REVIEW_STALE`: show the new preview and require another
   explicit approval.
7. **PRG**: after `200`, redirect and reload `GET /staff/requests/{inquiryId}`; render the
   persisted Quote from `financial`, the plan from `servicePlan`, and `proposal` as **Issued**,
   never **Sent**, until communication delivery exists.

Not in this slice: drafts/autosave, decline, notes, expiry, messages or email, public quote
links, payment capture, Quote revision through composition, Invoice adjustments and post-close
corrections.
