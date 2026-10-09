package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.FinancialDocumentVersion
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Instant
import java.util.UUID

/**
 * Fiona's record that a commerce-runtime financial-document lineage belongs to an inquiry.
 *
 * The document itself (its snapshots, lines, and totals) is commerce-runtime's; Fiona owns
 * only this relationship. One inquiry may have several lineages, for example an alternative
 * or restarted proposal; revisions of one proposal are versions inside its lineage.
 */
data class InquiryDocumentAssociation(
    val inquiryId: InquiryId,
    val documentId: UUID,
    val createdAt: Instant,
    val purpose: InquiryDocumentPurpose = InquiryDocumentPurpose.RELATED,
)

enum class InquiryDocumentPurpose { INITIAL_ESTIMATE, RELATED }

/** One self-contained immutable snapshot, with who committed its lines when Fiona recorded them. */
data class DocumentSnapshot(
    val persisted: FinancialDocumentVersion,
    val authorship: LineAuthorship?,
) {
    val document: FinancialDocument get() = persisted.document
    val createdAt: Instant get() = persisted.createdAt
}

/**
 * An inquiry's financial-document lineage at its [latest] snapshot, with the settlement
 * commerce-runtime derives across the whole lineage: allocations stay attached to the
 * snapshot they were made against, and the latest snapshot supplies the total owed.
 */
data class InquiryFinancialDocument(
    val inquiryId: InquiryId,
    val latest: DocumentSnapshot,
    val reconciliation: FinancialDocumentReconciliation,
)

/**
 * Every snapshot of one lineage, oldest first, with its line authorship. Historical snapshots
 * carry no reconciliation: current settlement belongs to the latest snapshot.
 */
data class InquiryFinancialDocumentHistory(
    val inquiryId: InquiryId,
    val documentId: UUID,
    val versions: List<DocumentSnapshot>,
)

/** A payment Fiona recorded and applied in full to [allocation]'s exact snapshot, and the lineage afterwards. */
data class RecordedPayment(
    val payment: PaymentRecord,
    val allocation: PaymentAllocation,
    val document: InquiryFinancialDocument,
)

/**
 * Fiona's view of commerce-runtime's financial ledger, inside the caller's transaction: a
 * lineage exists for Fiona only when an inquiry owns it, whatever else the ledger holds.
 *
 * The lineage's `fionas.inquiry_financial_documents` row is the application-level
 * serialization point for one financial lineage. Mutations lock it before they check and
 * append ([expectLatest]). Every ledger mutation then names the exact version the caller
 * reviewed (or, for a succession inside one transaction, the version the preceding step just
 * appended), so the runtime's own lineage lock rejects anything stale; nothing is rebased.
 * Query operations read ownership normally ([current], [history]) in a REPEATABLE READ
 * transaction, so their multiple queries share one snapshot without locking the lineage.
 *
 * It reads through the ledger's `Transaction` overloads and never opens a transaction.
 */
internal class FionaFinancialDocuments(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val authorship: FinancialDocumentAuthorshipRepository,
) {
    /** The latest snapshot of a lineage an operation is about to change, and the inquiry that owns it. */
    class Current(
        val inquiryId: InquiryId,
        val document: FinancialDocument,
    )

    /**
     * The latest snapshot of [documentId], provided it is still [expected], the version the
     * caller acted on; otherwise [CommerceFailure.Conflict], so that no action lands on a
     * snapshot its caller never saw.
     *
     * The lineage's association row is locked first, so Fiona's mutations of one lineage run
     * one at a time and this check still holds when the operation writes. The runtime's
     * expected-version lineage lock remains the final guard.
     */
    fun expectLatest(
        transaction: Transaction,
        documentId: UUID,
        expected: Version,
    ): Current {
        val inquiryId = lock(transaction, documentId)
        val latest = ledger.latest(transaction, documentId)
        if (latest.version != expected) {
            throw CommerceFailure.Conflict(
                "Financial document $documentId is at ${latest.version}, not the expected $expected; reload it and retry",
            )
        }
        return Current(inquiryId, latest)
    }

    /**
     * The lineage's latest snapshot, its line authorship, and current settlement, read from the
     * caller's transaction snapshot. [CommerceFailure.NotFound] for a lineage no inquiry owns.
     */
    fun current(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryFinancialDocument = describe(transaction, owner(transaction, documentId), documentId)

    /**
     * Describes a lineage after a mutation whose caller already holds the lineage lock
     * ([expectLatest]) or created the association in this transaction.
     *
     * The settlement reconciles exactly the snapshot returned, never "whatever is latest" by
     * a later statement.
     */
    fun describeLocked(
        transaction: Transaction,
        inquiryId: InquiryId,
        documentId: UUID,
    ): InquiryFinancialDocument = describe(transaction, inquiryId, documentId)

    /** Reuses the authoritative post-mutation view instead of reconciling it a second time. */
    fun describeLocked(
        transaction: Transaction,
        inquiryId: InquiryId,
        view: FinancialLineageView,
    ): InquiryFinancialDocument =
        InquiryFinancialDocument(
            inquiryId,
            DocumentSnapshot(view.latestVersion, authorship.find(transaction, view.latestVersion.document.reference)),
            view.reconciliation,
        )

    fun isCanonical(
        transaction: Transaction,
        current: Current,
    ): Boolean = associations.initialEstimateOf(transaction, current.inquiryId) == current.document.id

    /**
     * Appends the Quote successor of [current], the exact version the caller reviewed and locked;
     * the runtime owns the transition and rejects a stale version. The lines are unchanged, so
     * their authorship carries forward.
     */
    fun quote(
        transaction: Transaction,
        current: Current,
    ): FinancialDocument.Quote =
        ledger.issueQuote(transaction, current.document.id, current.document.version).also {
            authorship.copy(transaction, current.document.reference, it.reference)
        }

    /**
     * Commits staff-authored final lines to [current], whose association the caller has locked
     * and against which [resolved] was resolved: Fiona's [validateChangeOrder] first, then one
     * same-stage successor of exactly that version, authored by [author] at [recordedAt]. A
     * proposal without a financial change is rejected and writes nothing.
     */
    fun commitLines(
        transaction: Transaction,
        current: Current,
        resolved: ResolvedLineProposal,
        author: PrincipalId,
        recordedAt: Instant,
    ): FinancialDocument {
        check(current.document.reference == resolved.reviewed.reference) { "The proposal was resolved against another snapshot" }
        val changes =
            resolved.changes
                ?: throw lineProposalFailure(
                    listOf(LineProposalViolations.NO_FINANCIAL_CHANGE),
                    "The proposed lines are exactly the current lines; there is no financial change",
                )
        validateChangeOrder(current.document, changes)
        return ledger
            .changeOrder(transaction, current.document.id, changes, current.document.version)
            .also { authorship.insert(transaction, it.reference, LineAuthorship(author, recordedAt)) }
    }

    /**
     * Persists a composed initial Quote from the reviewed Estimate [current], whose association
     * the caller has locked. When the composed lines differ, one same-stage Estimate successor
     * authored by [author] precedes the Quote, which then follows exactly that new version. The
     * persisted Quote must equal the composed candidate.
     */
    fun composedQuote(
        transaction: Transaction,
        current: Current,
        composed: ComposedQuote,
        author: UserId,
        recordedAt: Instant,
    ): FinancialDocument.Quote {
        check(current.document.reference == composed.reviewed.reference) { "The composition was evaluated from another snapshot" }
        val source =
            if (composed.lines.changes == null) {
                current.document
            } else {
                commitLines(transaction, current, composed.lines, author, recordedAt)
            }
        val quote = ledger.issueQuote(transaction, source.id, source.version)
        authorship.copy(transaction, source.reference, quote.reference)
        check(quote == composed.quote) { "The persisted Quote differs from the composed Quote" }
        return quote
    }

    fun approveDeposit(
        transaction: Transaction,
        document: FinancialDocument,
        terms: DepositTerms,
        expected: DepositRequirementRevision?,
    ) {
        document.requirePaymentDestination()
        if (terms is DepositTerms.Fixed) paymentMoney(terms.amount.amount, terms.amount.currency)
        ledger.activateDepositRequirement(transaction, document.id, document.version, terms, expected)
    }

    fun rejectCanonicalQuoteMutation(
        transaction: Transaction,
        current: Current,
    ) {
        if (current.document is FinancialDocument.Quote && isCanonical(transaction, current)) {
            throw CommerceFailure.IllegalTransition("Canonical Quote changes require the atomic staff proposal workflow")
        }
    }

    /**
     * Shared Invoice mechanics from the exact Quote [current]; callers own the association lock
     * and transition policy. The runtime rejects a stale version.
     */
    fun invoice(
        transaction: Transaction,
        current: Current,
    ) {
        val invoice = ledger.issueInvoice(transaction, current.document.id, current.document.version)
        authorship.copy(transaction, current.document.reference, invoice.reference)
    }

    /**
     * Applies Fiona's booking policy after a deposit-affecting mutation under the association
     * lock. READ COMMITTED sees allocations committed by the preceding lock holder. Document,
     * allocations and deposit terms cannot change under that lock; the runtime reads refund
     * unwinds in one statement, so its reconciliation includes the refunds observed there.
     * An Invoice remains booked even when later refunds reduce deposit satisfaction.
     * Returns no booking view for Invoice or RELATED paths, which need only ordinary settlement.
     */
    fun bookIfDepositSatisfied(
        transaction: Transaction,
        current: Current,
    ): FinancialLineageView? {
        if (current.document !is FinancialDocument.Quote || !isCanonical(transaction, current)) return null
        val view = ledger.financialLineages(transaction, listOf(current.document.id)).single()
        if (view.depositRequirement?.requirement is DepositRequirement.Active &&
            view.depositSatisfied == true
        ) {
            invoice(transaction, current)
            return ledger.financialLineages(transaction, listOf(current.document.id)).single()
        }
        return view
    }

    private fun describe(
        transaction: Transaction,
        inquiryId: InquiryId,
        documentId: UUID,
    ): InquiryFinancialDocument {
        val latest = ledger.latestVersion(transaction, documentId)
        val document = latest.document
        val authored = authorship.find(transaction, document.reference)
        return InquiryFinancialDocument(inquiryId, DocumentSnapshot(latest, authored), ledger.reconcile(transaction, document.reference))
    }

    /**
     * Every snapshot of the lineage, oldest first, with its line authorship, read from the
     * caller's transaction snapshot. [CommerceFailure.NotFound] for a lineage no inquiry owns.
     */
    fun history(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryFinancialDocumentHistory {
        val inquiryId = owner(transaction, documentId)
        val authored = authorship.findAll(transaction, documentId)
        val versions = ledger.versionHistory(transaction, documentId).map { DocumentSnapshot(it, authored[it.document.version]) }
        return InquiryFinancialDocumentHistory(inquiryId, documentId, versions)
    }

    /** Locks the lineage's association row until the transaction ends; the inquiry that owns it. */
    private fun lock(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryId = associations.lockInquiryOf(transaction, documentId) ?: throw notFound(documentId)

    /** Reads the inquiry that owns the lineage without locking its association row. */
    private fun owner(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryId = associations.inquiryOf(transaction, documentId) ?: throw notFound(documentId)

    private fun notFound(documentId: UUID) = CommerceFailure.NotFound("Financial document $documentId was not found")
}
