package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.FinancialDocumentReconciliation
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Clock
import java.time.temporal.ChronoUnit

data class InvoiceRevisionIntent(
    val inquiryId: InquiryId,
    val expectedDocumentVersion: Version,
    val lines: LineProposal,
    val servicePlanChange: ServicePlanChange,
)

data class InvoiceRevisionPreview(
    val inquiryId: InquiryId,
    val reviewed: FinancialDocument.Invoice,
    val invoice: FinancialDocument.Invoice,
    val lines: ResolvedLineProposal,
    val servicePlan: ComposedServicePlan?,
    val currentServicePlan: InquiryServicePlan?,
    val reconciliation: FinancialDocumentReconciliation,
    val reviewToken: QuoteReviewToken,
) {
    val financialChange get() = lines.changes != null
    val servicePlanChange get() = servicePlan != currentServicePlan?.content()
    val resultingServicePlanRevision: Int? get() =
        if (servicePlanChange) (currentServicePlan?.revision ?: 0) + 1 else currentServicePlan?.revision
}

data class RevisedInquiryInvoice(
    val financial: InquiryFinancialDocument,
    val servicePlan: InquiryServicePlan?,
)

/** Canonical Invoice amendments compose the existing ledger with independent Fiona service approvals. */
class InquiryInvoiceRevisions(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
    private val proposals: InquiryProposalRepository,
    private val plans: InquiryServicePlanRepository,
    private val fulfillment: InquiryFulfillmentRepository,
    private val clock: Clock,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    data class Command(
        val intent: InvoiceRevisionIntent,
        val reviewToken: QuoteReviewToken,
        val approvedBy: UserId,
    )

    fun preview(intent: InvoiceRevisionIntent): InvoiceRevisionPreview =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            compose(transaction, ledger.latest(transaction, canonical(transaction, intent.inquiryId)), intent)
        }

    fun revise(command: Command): RevisedInquiryInvoice =
        transactor.inTransaction { transaction ->
            val intent = command.intent
            val current = documents.expectLatest(transaction, canonical(transaction, intent.inquiryId), intent.expectedDocumentVersion)
            // Refunds serialize on payment rows rather than the association. Freeze the existing payment
            // histories too, so a concurrent unwind cannot change the balance staff approves mid-command.
            ledger.paymentHistoriesForLineage(transaction, current.document.id).map { it.payment.id }.sorted().forEach {
                ledger.reconcilePayment(transaction, it)
            }
            val preview = compose(transaction, current.document, intent, rejectNoChange = false)
            if (preview.reviewToken != command.reviewToken) {
                throw CommerceFailure.Conflict("The Invoice changed since review; preview it again", QuoteReviewStale())
            }
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            val invoice =
                if (preview.financialChange) {
                    documents.commitLines(transaction, current, preview.lines, command.approvedBy, now)
                } else {
                    current.document
                }
            check(invoice == preview.invoice) { "Persisted Invoice differs from the reviewed amendment" }
            val plan =
                plans.approveChange(
                    transaction,
                    intent.inquiryId,
                    preview.currentServicePlan,
                    preview.servicePlan,
                    invoice,
                    current.document.version,
                    now,
                    command.approvedBy,
                )
            RevisedInquiryInvoice(documents.describeLocked(transaction, intent.inquiryId, invoice.id), plan)
        }

    private fun canonical(
        transaction: Transaction,
        inquiryId: InquiryId,
    ) = associations.initialEstimateOf(transaction, inquiryId)
        ?: throw CommerceFailure.NotFound("Canonical inquiry financial lineage was not found")

    private fun compose(
        transaction: Transaction,
        document: FinancialDocument,
        intent: InvoiceRevisionIntent,
        rejectNoChange: Boolean = true,
    ): InvoiceRevisionPreview {
        if (document.version != intent.expectedDocumentVersion) throw CommerceFailure.Conflict("Stale expected document version")
        if (document !is FinancialDocument.Invoice) throw CommerceFailure.IllegalTransition("Invoice amendment requires a booked Invoice")
        if (fulfillment.find(transaction, intent.inquiryId)?.closed != null) {
            throw CommerceFailure.IllegalTransition("A closed inquiry requires a separate post-close adjustment workflow")
        }
        val view = ledger.financialLineages(transaction, listOf(document.id)).single()
        val proposal = proposals.latest(transaction, intent.inquiryId)
        requireCoherentProposal(intent.inquiryId, proposal, view)
        val currentPlan = plans.latest(transaction, intent.inquiryId)
        currentPlan?.let {
            check(it.describes(ledger.get(transaction, it.document))) { "Service approval disagrees with its financial snapshot" }
        }
        val lines = intent.lines.resolveAgainst(document)
        val invoice = lines.changes?.let { validateChangeOrder(document, it) as FinancialDocument.Invoice } ?: document
        val plan = intent.servicePlanChange.resolve(currentPlan, lines)
        if (rejectNoChange && lines.changes == null && currentPlan?.content() == plan) {
            throw lineProposalFailure(listOf("NO_MATERIAL_CHANGE"), "The amendment changes no financial or service terms")
        }
        val histories = ledger.paymentHistoriesForLineage(transaction, document.id)
        val reconciliation =
            FinancialDocumentReconciliation.reconcile(
                invoice,
                histories.flatMap { it.allocations },
                refundAllocations = histories.flatMap { it.refundAllocations },
            )
        val context =
            listOf(
                "INVOICE_REVISION_V1",
                proposal!!.id.value.toString(),
                (currentPlan?.revision ?: 0).toString(),
                intent.servicePlanChange.action.name,
            ) +
                histories
                    .flatMap { history ->
                        listOf(history.payment.id) + history.allocations.map { it.id } +
                            history.refunds.map { it.id } + history.refundAllocations.map { it.id }
                    }.map { it.toString() }
        val token = QuoteComposer.reviewToken(intent.inquiryId, document, lines, invoice, null, null, plan, context)
        return InvoiceRevisionPreview(intent.inquiryId, document, invoice, lines, plan, currentPlan, reconciliation, token)
    }
}
