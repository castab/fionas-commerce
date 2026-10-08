# Change-order financial foundation audit

> **Follow-up status (commerce 0.0.23, trusted priced lines).** Both gaps this audit identified
> are closed: staff change orders now commit granular, identity-preserving final lines instead
> of complete catalog repricing, and commerce-runtime 0.0.23's `changeOrder`, `issueQuote` and
> `issueInvoice` take the caller's expected document version. The domain semantics recorded
> below still hold. Kept as history of the 0.0.22 audit.

## Scope and dependency evidence

This slice hardens existing Fiona mutations; it introduces no granular staff command,
request DTO, frontend, ledger persistence, refund automation or post-close correction.
Gradle `dependencyInsight --dependency commerce --configuration testRuntimeClasspath`
resolved **commerce-runtime 0.0.22 and transitive commerce-domain 0.0.22**. Source inspection
used tag `v0.0.22`, commit `4d1d8b5dc047f82338e82d86ef088ae11198b35a`, in the local
`commerce-domain` checkout, not its default branch. No upstream files were changed.
The consumer integration tests exercise these compiled dependencies through `TestApplication`
and real PostgreSQL. Source-derived findings are identified separately below.

## Capability assessment

| Required capability | Existing support | Evidence | Gap | Owning repository |
|---|---|---|---|---|
| Negative line items | Signed exact Money, nullable quantity, signed price/quantity/tax, mixed lines and currency validation | `ChangeOrderFoundationSpec`: -40.00 flat credit persistence, fractional signed quantity/tax arithmetic, mixed-currency rejection | None | commerce-domain models; commerce-runtime storage |
| Granular changes | Ordered add, same-ID replace in position, remove; atomic invalid-change rejection | Foundation spec: retained original line, replacement/removal, add-then-replace, missing-ID rollback | Staff API still accepts only complete catalog repricing | fionas-commerce workflow; existing shared semantics suffice |
| Immutable versions | Same ID/stage, immediate successor, immutable history and database timestamps | Foundation spec: exact restored facts and timestamp preservation; existing read-consistency and atomicity specs | Runtime write API lacks expected-version argument (see below) | commerce-domain and commerce-runtime |
| Cross-version reconciliation | Old allocation references survive; latest total determines balance; explicit refunds unwind allocations | Foundation spec: 600/200/400, 675/200/475, 560/200/360, 560/600/-40, refund to 560/560/0 | None; no stored Fiona balance needed | commerce-runtime using commerce-domain reconciliation |
| Nonnegative total policy | Added Fiona validation before ledger mutation for both repricing paths | `validateChangeOrder`; foundation spec: decimal scales, positive/zero acceptance, negative rejection and no successor/metadata | Future targeted commands must invoke the same seam | fionas-commerce |
| Atomic proposal republication | Reviewed Quote/deposit pair, allocation-history guard, replacement deposit and publication in one transaction | `InquiryProposalsSpec`, `InquiryProposalRoutesSpec`; foundation spec: negative and zero Quote failures leave prior publication intact | Zero Quote cannot publish a positive deposit; free booking remains undecided | Fiona workflow, existing shared deposit invariants |
| Post-close separation | Ordinary canonical change orders now reject CLOSED under the association lock | `InquiryLifecycleSpec`: BOOKED/SERVED preservation, credit-enabled closure, HTTP rejection with unchanged history/provenance | Post-close correction remains a separate future workflow | fionas-commerce |

## Monetary and mutation findings

`Money` keeps `BigDecimal` precision and scale; its structural equality is scale-sensitive.
Fiona tests numeric sign with `signum()`. Domain `LineItem` has no global quantity, price
or tax sign restriction. Its subtotal is price times quantity, or flat price when quantity
is null; tax is added once. Document totals sum the ordered lines. Shared document creation
and change application allow negative totals. These are intentional shared semantics,
not deficiencies to restrict for all consumers.

Fiona now applies the shared pure `FinancialDocument.changeOrder` to the locked reviewed
snapshot, validates the derived total, and then passes the same immutable order to the
transaction-taking ledger operation. It does not duplicate line application or sum amounts.
The ledger applies the order again to persist its successor. This is safe for Fiona writers
because every relevant mutation holds the association lock; see the upstream limitation
below for independent runtime callers. Expected failures use existing `ValidationFailed`
and HTTP `422 validation_failed`; invalid shared changes also use runtime `validating`.
No new error envelope or global monetary restriction is introduced.

The runtime persists line values in snapshot JSON. Tests verify exact negative decimal
restoration, null quantity, order, IDs, tax, subtotal and total via supported ledger reads.
Runtime version wrappers are not value-equality objects; tests compare their document and
creation-time facts. Database timestamps are asserted independently of Fiona's fixed clock.

Zero is allowed by Fiona's total policy. A canonical zero Quote cannot resolve a positive
deposit within its total. Existing runtime deposit validation rejects that approval and the
whole proposal operation rolls back. This is different from a valid zero Invoice or an
overpaid Invoice with a negative balance. No free-booking path is implied.

`repricing` deliberately removes all current lines and adds newly evaluated lines, generating
new identities. It neither matches descriptions nor tracks which line was manually entered.
Consequently it would discard an unrelated manual charge/credit. Preserve this explicit
replacement contract until the next slice defines targeted edits. The shared add/replace/remove
contract already preserves all untouched identities; no generic line-source model is needed
merely to address an existing line by ID.

## Lifecycle, concurrency and atomicity

Canonical Quote changes continue through `ReviseInquiryQuoteProposal`: lock association,
check reviewed financial version and deposit revision, reject historical allocation activity,
reprice, approve the replacement deposit, append publication. Old publication IDs become
non-payable. Refunds do not reopen the pre-payment revision window. Existing proposal tests
exercise late publication rollback and both acceptance/revision race outcomes.

Invoice changes keep accepted deposits and their historical Quote allocations. BOOKED and
SERVED do not depend on the adjusted balance; served provenance stays unchanged. Closure
still requires exactly zero balance and rejects both debt and overpayment. The narrow CLOSED
guard is in `CreateChangeOrder`, after ownership/version locking and before pricing. It only
affects the canonical lineage, leaving RELATED lineages and payment/refund policy intact.
No reopen or automatic re-close is introduced.

Concurrent Fiona repricing attempts against one reviewed version yield one immediate successor
and one `Conflict`. Existing proposal tests additionally observe PostgreSQL association-lock
waits. Runtime successor uniqueness remains the final guard. All new validation and fulfillment
reads use the caller's READ COMMITTED transaction; no nested transaction, retries or runtime-table
SQL were added. Existing REPEATABLE READ financial queries retain coherent snapshot semantics.

## Upstream gap report

One existing shared API limitation remains relevant; **no upstream change is required for
this slice or for targeted Fiona changes using the existing serialization contract**.

**Expected-version ledger mutations — commerce-runtime.** At v0.0.22, `changeOrder`,
`issueQuote` and `issueInvoice` read latest internally and do not accept the caller's reviewed
reference. Fiona supplies the missing review-token check under its own association lock.
Independent runtime writers do not acquire that Fiona lock. Thus the shared write API alone
cannot promise to apply the exact reviewed predecessor, and such writers must not bypass
Fiona policy for Fiona-owned lineages. This is confirmed by tagged implementation inspection,
not a newly introduced persistence gap.

Minimal future contract: transaction-taking overloads accepting the expected
`FinancialDocumentReference`, checking it under runtime lineage serialization before deriving
and inserting a successor, returning `Conflict` for a moved predecessor. Keep existing
overloads compatible; preserve NOWAIT/lock-order and caller-owned transaction rules. Tests
should cover stale references, competing successors, independent concurrent writers,
rollback and composition with payments/deposits. Implement and release upstream only in a
separately authorized task. A runtime policy callback, alternate snapshot writer or Fiona
copy of persistence is not required here.

No missing negative-line, targeted-change, history, refund or reconciliation API was found.
Post-close eligibility and zero-Quote booking decisions belong to Fiona, not upstream.

The quote builder slice additionally verified, at the same tag, that commerce-domain's public
`DepositTerms.resolve(document)` (used by `DepositRequirement.Active.approve`) is a pure,
write-free resolver: a preview resolves the exact deposit against the uncommitted pure Quote
candidate with the same HALF_UP and bound rules the ledger applies at approval. No deposit
preview gap exists. Targeted overrides and adjustments (next-slice items 1, 2 and 4) are now
implemented for initial canonical Quote composition only; see
[the quote builder contract](../AGENTS.md#quote-builder-initial-composition).

## Next-slice readiness

1. Define authorized Fiona staff intents for add charge/credit, replace and remove using
   explicit reviewed versions and existing line IDs. Decide permitted manual input signs,
   tax/currency/precision and meaningful no-op behavior without restricting shared Money.
2. Compose those intents into shared `ChangeOrder` under the association lock, preserve
   untouched lines, invoke `validateChangeOrder`, and keep pricing provenance accurate.
   Do not copy old catalog inputs as if they described a manually adjusted snapshot.
3. Route canonical Quote changes through atomic deposit/proposal republication with both
   review tokens. Keep accepted Invoice deposit/publication history fixed and reject CLOSED.
4. Define how an optional service-line repricing action preserves manual adjustments; whole
   replacement remains explicitly destructive of the old line set. Do not infer identity
   from descriptions or array positions.
5. Add transport/auth/OpenAPI and UI only for those decided intents. Keep refunds explicit;
   free booking and post-close correction require separate business decisions/workflows.

## Validation report

Executed against the resolved 0.0.22 artifacts and the repository's real PostgreSQL harness:

| Check | Result |
|---|---|
| `dependencyInsight --dependency commerce --configuration testRuntimeClasspath` | Passed; runtime and domain both 0.0.22 |
| `ktlintFormat` | Passed |
| `ktlintCheck test build` | Passed; 583 tests, zero failures/errors/skipped |
| Final `ktlintCheck test --tests '*ChangeOrderFoundationSpec' --tests '*InquiryLifecycleSpec' --tests '*OpenApiDocumentSpec' build` | Passed; 76 tests after the final regression assertions and API descriptions, zero failures/errors/skipped |
| `git diff --check` | Passed |

The full suite included `FinancialDocumentAtomicitySpec` (6),
`FinancialDocumentRoutesSpec` (18), `FinancialDocumentPaymentsSpec` (13),
`FinancialDocumentReadConsistencySpec` (4), `RepricingSpec` (4),
`InquiryProposalsSpec` (20), `InquiryProposalRoutesSpec` (7),
`DepositRequirementOperationsSpec` (5), `DepositRequirementRoutesSpec` (31),
`InquiryLifecycleSpec` (7), and `ChangeOrderFoundationSpec` (10).

Initial development runs caught test-fixture charges and identity-based wrapper comparisons;
the fixtures and assertions were corrected without weakening existing tests. No tests were
blocked by the local tool sandbox failure: approved unsandboxed execution recovered access.
JDK native-access/Unsafe and Gradle deprecation notices remain non-failing toolchain warnings.
