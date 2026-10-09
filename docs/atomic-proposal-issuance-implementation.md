# Atomic proposal issuance implementation

> **Update (commerce 0.0.23, trusted priced lines).** Proposal issuance and both revisions now
> require a staff USER holding `fionas.financial-terms.manage` in addition to the two commerce
> permissions; the proposal records the approving `UserId` (`issuedBy`), and Quote revision
> commits staff-authored lines instead of catalog repricing. Migration history V14 is now part
> of Fiona's single `V1__fionas_baseline`. The atomicity, currentness and payment rules below
> are unchanged. Current contract: [`AGENTS.md`](../AGENTS.md#atomic-canonical-proposal-publication).

Canonical customer Quote publication now commits the immutable Quote, its explicitly approved
deposit requirement and a durable Fiona proposal issuance together. Staff can reissue the
Quote/deposit pair before any payment has been applied; deposit-only reissuance keeps the
Quote version. Payment/allocation deposit satisfaction retains the existing atomic booking policy.

## Branch and dependencies

- Branch: `codex/atomic-proposal-issuance`.
- Fetched and verified `origin/main` base: `d6949325e17b762644c45c2cd9526c372f62274a`.
- Pre-commit validation HEAD: `d6949325e17b762644c45c2cd9526c372f62274a`;
  the implementation was validated as workspace changes before committing.
- Shared dependency remains commerce-runtime `0.0.22`, with matching transitive commerce-domain.
  Reviewed upstream APIs at `v0.0.22` (`4d1d8b5dc047f82338e82d86ef088ae11198b35a`).
- No upstream capability gap, new dependency, copied shared type or production commerce-schema SQL.
- No deliberate deviation from the requested slice. Development Quotes receive no backfill.

## Business boundaries

`IssueInquiryProposal`, `ReviseInquiryQuoteProposal` and `ReviseInquiryProposalDeposit` each
own one runtime READ COMMITTED transaction. The narrow transaction-taking `InquiryProposals`
core derives the canonical lineage from inquiry identity, locks the existing Fiona association
row before checking reviewed tokens, and calls the released runtime ledger overloads.
Waiting writers see the preceding committed document/deposit revisions. Failures roll back
ledger snapshots, deposit revisions, legacy pricing metadata and proposal rows together.

Initial publication transitions the exact reviewed Estimate without repricing, copies existing
optional pricing metadata and requires no prior proposal/deposit history. Quote revision
requires reviewed document/deposit tokens, current-catalog pricing, a meaningful financial
change and replacement terms. It approves a new deposit revision against the new Quote even
when terms stay at 20%. Deposit-only revision rejects numerically equivalent same-form terms;
percentage-to-fixed changes remain meaningful at equal resolved amounts. Shared runtime owns
deposit resolution and rounding. Both revisions reject positive historical gross allocation,
including payments later completely refunded and unwound.

## Persistence and currentness

Fiona migration `V14__inquiry_proposals.sql` adds an append-only issuance table with UUID,
inquiry/document identities, Quote version, deposit revision, issuance kind, microsecond
timestamp and USER/SERVICE principal provenance. A composite FK references Fiona's association.
The exact pair and one INITIAL issuance per inquiry are unique. Identity ordering determines
history/latest under the association lock, independently of timestamps.

`recorded_order` is useful for deterministic history within an inquiry. Identity allocation
does not establish global commit order across concurrent inquiries. A future dispatcher must
not assume every event below a high-water mark has committed and been safely observed;
explicit delivery/claim semantics or another durable dispatch design are required.

The row is the durable business event for a future listener. Publication does not record
communication delivery. No after-commit publisher, dispatcher or mutable superseded flag exists.

`IsCurrentPayableInquiryProposal` reads one unlocked REPEATABLE READ snapshot. A target is
current only when it is the latest issuance and its exact Quote reference and active deposit
approval/revision match authoritative facts. Any reissue supersedes all previous ids. Invoice
booking makes all proposal targets non-payable while preserving their historical identities.
This is a backend seam; proposal UUIDs are business identities rather than bearer credentials.

This query is not a payment acceptance transaction. A future customer payment flow must
lock/revalidate exact proposal currentness and record/allocate payment in the same atomic
transaction. Querying first and recording money separately would race proposal reissuance.

## HTTP and staff reads

All three ContractRoutes return 200 with `{proposal, financial, depositRequirement}` and no-store:

| POST path | Operation ID |
| --- | --- |
| `/staff/requests/{inquiryId}/proposals` | `issueInquiryProposal` |
| `/staff/requests/{inquiryId}/proposals/quote-revisions` | `reviseInquiryQuoteProposal` |
| `/staff/requests/{inquiryId}/proposals/deposit-revisions` | `reviseInquiryProposalDeposit` |

Each requires both `commerce.financial-document.create` and
`commerce.deposit-requirement.manage` using existing USER/SERVICE access control and trusted
Origin policy for unsafe cookie requests. No permission or bootstrap grant was introduced.
Requests reuse the existing strict shared deposit wire union and current pricing-input DTO.
The runtime error envelope handles malformed inputs, stale tokens, illegal transitions,
validation/invariant failures and internal failures.

`GET /staff/requests/{inquiryId}` retains its existing read permission intersection and one
unlocked REPEATABLE READ transaction. It adds the authoritative 20% `suggestedDepositTerms`,
optional latest `proposal` and current `depositRequirement`. A canonical Quote with missing or
mismatched publication/deposit identity fails internally. BOOKED retains historical proposal
context. OpenAPI schemas and route descriptions describe the new workflow and exact identities.

Standalone canonical Quote issuance, Quote change orders, deposit activation/replacement and
withdrawal now reject. Manual canonical Invoice issuance remains forbidden. Canonical Estimate
change orders, booked Invoice change orders and RELATED standalone Quote/deposit/change-order
behavior remain supported. Standalone canonical deposit mutation is forbidden once proposal
history exists, including after Invoice/BOOKED. `CanonicalProposalDepositPolicy` checks
canonical association and `InquiryProposalRepository.latest` under the caller's association
lock in the same transaction. Before publication, Estimate deposit eligibility remains shared
validation; unpaid Quote deposits change only through atomic proposal reissuance. After booking,
the accepted deposit requirement/history is immutable, including across later Invoice versions.
Staff reads validate that the active approval reference/revision still matches the historical
accepted Quote/deposit pair; they never equate that Quote version with the Invoice version.

## Initial validation

- `./gradlew.bat ktlintFormat --console=plain`: passed (4 seconds).
- `./gradlew.bat ktlintCheck test build --console=plain`: passed (5 minutes 26 seconds).
  All 556 tests across 53 suites passed: zero failures, errors or skipped tests.
- Fresh-database schema and migration lifecycle tests passed through Fiona V14, including
  repeated startup and independent runtime/Fiona migration streams.
- Architecture checks passed. New production SQL accesses Fiona tables only; shared ledger,
  deposit, principal and transaction types remain upstream-owned.
- Full regression coverage includes observed PostgreSQL lock waits, repeatable snapshot reads,
  late cross-schema rollback, historical allocations after complete refund, canonical route
  restrictions, unchanged RELATED/Estimate/Invoice behavior, USER/SERVICE authorization and
  Origin enforcement, coherent staff reads and exact generated OpenAPI schemas/examples.
- `generateOpenApi` and JAR/distribution packaging ran successfully as part of the build.
  Inspected `build/openapi/fionas-commerce-openapi.json`: all three POST paths, operation IDs,
  required tokens/terms, response identities, status mappings and coherent revision examples.
- Inspected `build/libs/fionas-commerce-all.jar`: the three mutations, currentness operation
  and V14 migration are present. Packaged V14 SHA-256 matches the source migration.
- `git diff --check`: passed.

Validation evidence is retained under the ignored `build/proposal-validation/` directory:
`final.log`, `full-suite-summary.json` and `artifact-summary.json`.

## Initial implementation files

The 44 changed files include production, migrations, contracts, regression fixtures and documentation.

- `AGENTS.md`
- `ARCHITECTURE.md`
- `README.md`
- `docs/atomic-proposal-issuance-implementation.md`
- `src/main/kotlin/io/github/castab/fionas/commerce/FionaApplication.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/CreateChangeOrder.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/InquiryFinancialDocuments.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/InquiryProposal.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/InquiryProposalRepository.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/InquiryProposals.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/IsCurrentPayableInquiryProposal.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/IssueInquiryProposal.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/IssueQuote.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/JdbiInquiryProposalRepository.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/ReviseInquiryProposalDeposit.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/ReviseInquiryQuoteProposal.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/SetDepositRequirement.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/WithdrawDepositRequirement.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/http/DepositRequirementRoutes.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/http/FinancialDocumentRoutes.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/http/FionaApi.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/http/InquiryProposalRoutes.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/http/StaffRequestRoutes.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/staff/ReadStaffRequest.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/staff/StaffRequest.kt`
- `src/main/resources/db/fionas/V14__inquiry_proposals.sql`
- `src/openapi/kotlin/io/github/castab/fionas/commerce/openapi/GenerateOpenApi.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/ArchitectureSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/DatabaseSchemaSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/MigrationLifecycleSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/financial/InquiryProposalsSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/financial/DepositRequirementOperationsSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/FinancialDocumentRoutesSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/InquiryLifecycleRoutesSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/InquiryProposalRoutesSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/OpenApiDocumentSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/StaffDashboardRoutesSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/StaffRequestRoutesSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/inquiry/InquiryLifecycleSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/inquiry/InquiryMaterializationSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/inquiry/InquiryOperationalStatesSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/staff/DashboardAttentionSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/staff/StaffRequestSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/testing/Proposals.kt`

## PR #16 corrective pass: immutable accepted deposits

Verified remote base `d6949325e17b762644c45c2cd9526c372f62274a` and reviewed PR head
`3a9567342589b1a5add9e17fd897bead1b6a881e` before this pass; the working branch matched
and needed no rebase. No new migration, dependency, permission or shared-runtime gap is involved.

`CanonicalProposalDepositPolicy` detects canonical published lineages with the existing
association and `InquiryProposalRepository.latest`. Set and Withdraw hold the association
row lock first, then perform that history check inside their existing mutation transaction.
They reject with `CommerceFailure.IllegalTransition` independently of financial stage.
No stored publication flag or second transaction is introduced.

| Lineage state | Standalone Set/Withdraw behavior |
| --- | --- |
| Canonical Estimate before publication | Shared Estimate validation remains; no deposit/proposal flow is added. |
| Canonical published unpaid Quote | Reject; use atomic proposal reissuance. |
| Canonical Invoice/BOOKED or later fulfillment | Reject; accepted deposit history is immutable. |
| RELATED Quote/Invoice | Preserve approval, replacement, reactivation and withdrawal. |

Booked Invoice change orders, pre-publication Estimate change orders, payments, allocations,
refunds and the manual canonical Invoice-transition restriction retain their existing behavior.
Booked staff reads retain the historical Quote identity and validate its exact active deposit
approval/revision across later Invoice versions. Those proposal targets remain non-payable.

New operation coverage rejects approval/reactivation and withdrawal on canonical Invoice v4
and v5, verifies the history reads share the locked READ COMMITTED transaction, and proves
financial/deposit/publication history and staff responses remain unchanged. New HTTP PUT and
DELETE regressions verify `409 illegal_transition`, BOOKED/INVOICE projection, historical
accepted identity and false current-payable evaluation. RELATED Invoice reactivation,
replacement and withdrawal remain covered alongside canonical Invoice change orders.
Corrupt booked acceptance changed through direct runtime test calls fails internally.

The future payment acceptance transaction and the limits of sequence dispatch ordering are
documented above; no payment-link or dispatcher implementation is added. No deviation from
the corrective directions.

### Corrective validation

- `./gradlew.bat ktlintFormat --console=plain` passed.
- `./gradlew.bat ktlintCheck test build --console=plain` passed in 5m 36s:
  560 tests across 53 suites, zero failures, errors or skips.
- Fresh PostgreSQL migration/schema validation passed, including all 6 migration lifecycle
  and 20 database schema cases. No corrective migration was needed.
- All 15 proposal operation tests and 7 proposal route tests passed, including existing
  contention/rollback cases and the new booked deposit immutability regressions. Preserved
  Invoice change orders, RELATED mutations, payment booking and refund guards passed.
- OpenAPI generation and all 57 OpenAPI contract tests passed. Inspected generated PUT/DELETE
  deposit descriptions for proposal-history detection, Invoice/BOOKED rejection, immutable
  accepted deposits and RELATED behavior; all three proposal paths remain present.
- Inspected the deployable JAR for the new policy, Set/Withdraw operations and V14 migration.
  Packaged V14 SHA-256 matches source:
  `4692ef60c8f69801630730288294aedeafca9251faad20cd51b3acc6299ff546`.
- `git diff --check` passed. Rechecked remote base/head before publication: unchanged from
  the verified hashes above, so no rebase was necessary.

### Corrective changed files

- `AGENTS.md`
- `ARCHITECTURE.md`
- `README.md`
- `docs/atomic-proposal-issuance-implementation.md`
- `src/main/kotlin/io/github/castab/fionas/commerce/FionaApplication.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/CanonicalProposalDepositPolicy.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/InquiryProposal.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/IsCurrentPayableInquiryProposal.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/SetDepositRequirement.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/financial/WithdrawDepositRequirement.kt`
- `src/main/kotlin/io/github/castab/fionas/commerce/http/DepositRequirementRoutes.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/ArchitectureSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/financial/DepositRequirementOperationsSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/financial/InquiryProposalsSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/InquiryProposalRoutesSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/http/OpenApiDocumentSpec.kt`
- `src/test/kotlin/io/github/castab/fionas/commerce/inquiry/InquiryLifecycleSpec.kt`
