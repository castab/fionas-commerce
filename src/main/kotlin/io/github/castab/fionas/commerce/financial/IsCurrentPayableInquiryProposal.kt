package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor

/** Future payment-link resolution seam; ids identify business facts, never bearer secrets. */
class IsCurrentPayableInquiryProposal(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val proposals: InquiryProposalRepository,
) {
    operator fun invoke(id: InquiryProposalId): Boolean =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val proposal = proposals.find(transaction, id) ?: return@inTransaction false
            if (associations.initialEstimateOf(transaction, proposal.inquiryId) != proposal.documentReference.id) return@inTransaction false
            val view = ledger.financialLineages(transaction, listOf(proposal.documentReference.id)).single()
            val latest = proposals.latest(transaction, proposal.inquiryId)
            requireCoherentProposal(proposal.inquiryId, latest, view)
            proposal.isCurrentPayable(latest, view)
        }
}
