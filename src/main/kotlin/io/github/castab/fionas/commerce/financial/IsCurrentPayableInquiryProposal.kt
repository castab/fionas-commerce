package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor

/**
 * Read/query seam; ids identify business facts, never bearer secrets. A future customer payment
 * must lock/revalidate this exact proposal and record/allocate money in one transaction.
 * Calling this query then recording money in a separate transaction would race reissuance.
 */
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
