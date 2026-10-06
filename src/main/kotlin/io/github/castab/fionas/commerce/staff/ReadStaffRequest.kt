package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.financial.GetFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryProposalRepository
import io.github.castab.fionas.commerce.financial.requireCoherentProposal
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryId

/** Owns one unlocked repeatable snapshot spanning inquiry, customer, lifecycle and financial reads. */
class ReadStaffRequest(
    private val transactor: Transactor,
    private val inquiries: GetInquiry,
    private val financial: GetFinancialDocument,
    private val ledger: FinancialLedger,
    private val proposals: InquiryProposalRepository,
) {
    operator fun invoke(id: InquiryId): StaffRequest =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val inquiry = inquiries.read(transaction, id)
            val document =
                try {
                    financial.read(transaction, inquiry.lifecycle.documentId)
                } catch (failure: CommerceFailure.NotFound) {
                    throw IllegalStateException("Staff request canonical financial data is missing", failure)
                }
            val view = ledger.financialLineages(transaction, listOf(document.latest.document.id)).single()
            val proposal = proposals.latest(transaction, id)
            requireCoherentProposal(id, proposal, view)
            StaffRequest(inquiry, document, proposal, view)
        }
}
