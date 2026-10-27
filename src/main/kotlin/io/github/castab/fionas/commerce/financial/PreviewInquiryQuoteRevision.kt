package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId

data class QuoteRevisionIntent(
    val inquiryId: InquiryId,
    val expectedDocumentVersion: Version,
    val expectedDepositRequirementRevision: DepositRequirementRevision,
    val lines: LineProposal,
    val servicePlanChange: ServicePlanChange,
    val terms: DepositTerms,
)

data class QuoteRevisionPreview(
    val composition: ComposedQuote,
    val currentProposal: InquiryProposal,
    val currentServicePlan: InquiryServicePlan?,
    val servicePlanChange: Boolean,
    val depositChange: Boolean,
) {
    val resultingServicePlanRevision: Int? get() =
        if (servicePlanChange) (currentServicePlan?.revision ?: 0) + 1 else currentServicePlan?.revision
}

class PreviewInquiryQuoteRevision(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val proposals: InquiryProposalRepository,
    private val plans: InquiryServicePlanRepository,
) {
    operator fun invoke(intent: QuoteRevisionIntent): QuoteRevisionPreview =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val id =
                associations.initialEstimateOf(transaction, intent.inquiryId)
                    ?: throw CommerceFailure.NotFound("Canonical inquiry financial lineage was not found")
            composeQuoteRevision(transaction, ledger, proposals, plans, ledger.latest(transaction, id), intent)
        }
}

internal fun composeQuoteRevision(
    transaction: Transaction,
    ledger: FinancialLedger,
    proposals: InquiryProposalRepository,
    plans: InquiryServicePlanRepository,
    document: FinancialDocument,
    intent: QuoteRevisionIntent,
    rejectNoChange: Boolean = true,
): QuoteRevisionPreview {
    if (document.version != intent.expectedDocumentVersion) throw CommerceFailure.Conflict("Stale expected document version")
    if (document !is FinancialDocument.Quote) throw CommerceFailure.IllegalTransition("Proposal revision requires a Quote")
    val view = ledger.financialLineages(transaction, listOf(document.id)).single()
    val proposal = proposals.latest(transaction, intent.inquiryId)
    requireCoherentProposal(intent.inquiryId, proposal, view)
    checkNotNull(proposal)
    val active = view.depositRequirement!!.requirement as DepositRequirement.Active
    if (active.revision != intent.expectedDepositRequirementRevision) throw CommerceFailure.Conflict("Stale expected deposit revision")
    if (view.reconciliation.grossAllocated.amount
            .signum() > 0
    ) {
        throw CommerceFailure.IllegalTransition("Historical applied payment prevents ordinary proposal revision")
    }
    val currentPlan = plans.latest(transaction, intent.inquiryId)
    currentPlan?.let {
        check(it.describes(ledger.get(transaction, it.document))) { "Service approval disagrees with its financial snapshot" }
    }
    check(currentPlan?.revision == proposal.servicePlanRevision) { "Published proposal and effective service approval disagree" }
    val composed = QuoteComposer.compose(intent.inquiryId, document, intent.lines, null, intent.terms)
    val plan = intent.servicePlanChange.resolve(currentPlan, composed.lines)
    val serviceChanged = currentPlan?.content() != plan
    val depositChanged = composed.financialChange || !sameTerms(active.terms, intent.terms)
    if (rejectNoChange && !composed.financialChange && !depositChanged && !serviceChanged) {
        throw lineProposalFailure(listOf("NO_MATERIAL_CHANGE"), "The revision changes no financial, deposit or service terms")
    }
    val token =
        QuoteComposer.reviewToken(
            intent.inquiryId,
            document,
            composed.lines,
            composed.quote,
            intent.terms,
            composed.requiredDeposit,
            plan,
            listOf(
                "QUOTE_REVISION_V1",
                proposal.id.value.toString(),
                active.revision.number.toString(),
                (currentPlan?.revision ?: 0).toString(),
                intent.servicePlanChange.action.name,
            ),
        )
    return QuoteRevisionPreview(
        composed.copy(servicePlan = plan, reviewToken = token),
        proposal,
        currentPlan,
        serviceChanged,
        depositChanged,
    )
}
