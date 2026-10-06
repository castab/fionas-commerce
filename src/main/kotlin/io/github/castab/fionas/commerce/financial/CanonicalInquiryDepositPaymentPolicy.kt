package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction

/** Canonical deposit acceptance in the caller's transaction, after its association lock is acquired. */
internal class CanonicalInquiryDepositPaymentPolicy(
    private val ledger: FinancialLedger,
    private val documents: FionaFinancialDocuments,
    private val proposals: InquiryProposalRepository,
) {
    /** Returns the exact acceptance context; Invoice and RELATED payments retain ordinary semantics. */
    fun validate(
        transaction: Transaction,
        current: FionaFinancialDocuments.Current,
        expectedProposalId: InquiryProposalId?,
        amount: Money,
    ): InquiryProposal? {
        if (current.document !is FinancialDocument.Quote || !documents.isCanonical(transaction, current)) return null
        val view = ledger.financialLineages(transaction, listOf(current.document.id)).single()
        val proposal = proposals.latest(transaction, current.inquiryId)
        requireCoherentProposal(current.inquiryId, proposal, view)
        checkNotNull(proposal)
        if (expectedProposalId == null) throw CommerceFailure.ValidationFailed("Canonical deposit payment requires an expected proposal id")
        if (expectedProposalId != proposal.id || !proposal.isCurrentPayable(proposal, view)) {
            throw CommerceFailure.Conflict("The proposal is no longer payable; reload and retry")
        }
        val active = view.depositRequirement!!.requirement as DepositRequirement.Active
        if (view.reconciliation.grossAllocated.amount
                .signum() > 0
        ) {
            throw CommerceFailure.IllegalTransition("Historical applied payment prevents canonical deposit acceptance")
        }
        if (amount.currency != active.requiredAmount.currency || amount.amount.compareTo(active.requiredAmount.amount) != 0) {
            throw CommerceFailure.ValidationFailed("Canonical deposit payment must equal the complete approved deposit amount")
        }
        return proposal
    }

    /** Booking failure must roll back the payment and allocation, including an unexpected incomplete promotion. */
    fun requireBooked(
        proposal: InquiryProposal,
        view: FinancialLineageView?,
    ) {
        checkNotNull(view) { "Canonical deposit acceptance has no booking result" }
        check(view.latestVersion.document is FinancialDocument.Invoice && view.depositSatisfied == true) {
            "Canonical deposit acceptance did not produce a satisfied Invoice"
        }
        requireCoherentProposal(proposal.inquiryId, proposal, view)
    }
}
