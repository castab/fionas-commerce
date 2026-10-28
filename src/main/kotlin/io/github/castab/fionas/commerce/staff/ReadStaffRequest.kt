package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.financial.GetFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryProposalRepository
import io.github.castab.fionas.commerce.financial.InquiryServicePlanRepository
import io.github.castab.fionas.commerce.financial.ListFinancialDocumentPaymentHistories
import io.github.castab.fionas.commerce.financial.requireCoherentProposal
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId

/** Owns one unlocked repeatable snapshot spanning inquiry, lifecycle, financial, proposal, deposit and payment reads. */
class ReadStaffRequest(
    private val transactor: Transactor,
    private val inquiries: GetInquiry,
    private val financial: GetFinancialDocument,
    private val ledger: FinancialLedger,
    private val proposals: InquiryProposalRepository,
    private val payments: ListFinancialDocumentPaymentHistories,
    private val servicePlans: InquiryServicePlanRepository,
    private val communications: InquiryCommunicationRepository,
) {
    operator fun invoke(id: InquiryId): StaffRequest =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction -> read(transaction, id) }

    internal fun read(
        transaction: Transaction,
        id: InquiryId,
    ): StaffRequest {
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
        val histories = payments.read(transaction, document.latest.document.id)
        val history = servicePlans.history(transaction, id)
        history.forEach { plan ->
            check(plan.describes(ledger.get(transaction, plan.document))) { "Service approval disagrees with its financial snapshot" }
        }
        val publishedPlan =
            proposal?.servicePlanRevision?.let { revision ->
                checkNotNull(history.singleOrNull { it.revision == revision }) { "Missing published service approval" }
            }
        return StaffRequest(
            inquiry,
            document,
            proposal,
            view,
            histories,
            publishedPlan,
            history.lastOrNull(),
            history,
            proposals.history(transaction, id),
            communications.history(transaction, id),
        )
    }
}
