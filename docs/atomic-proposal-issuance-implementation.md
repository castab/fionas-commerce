# Atomic proposal issuance implementation

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

The row is the durable business event for a future listener. Publication does not record
communication delivery. No after-commit publisher, dispatcher or mutable superseded flag exists.

`IsCurrentPayableInquiryProposal` reads one unlocked REPEATABLE READ snapshot. A target is
current only when it is the latest issuance and its exact Quote reference and active deposit
approval/revision match authoritative facts. Any reissue supersedes all previous ids. Invoice
booking makes all proposal targets non-payable while preserving their historical identities.
This is a backend seam; proposal UUIDs are business identities rather than bearer credentials.

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
change orders, booked Invoice operations and RELATED standalone Quote/deposit/change-order
behavior remain supported.

## Validation

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

## Changed files

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
