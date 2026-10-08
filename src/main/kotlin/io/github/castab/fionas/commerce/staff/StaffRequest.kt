package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.financial.PaymentHistory
import io.github.castab.fionas.commerce.financial.FIONAS_DEFAULT_DEPOSIT_TERMS
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryProposal
import io.github.castab.fionas.commerce.financial.InquiryServicePlan
import io.github.castab.fionas.commerce.inquiry.InquiryDetails

/** A derived inquiry detail and its current canonical INITIAL_ESTIMATE lineage, never persisted. */
data class StaffRequest(
    val inquiry: InquiryDetails,
    val financial: InquiryFinancialDocument,
    val proposal: InquiryProposal?,
    val deposit: FinancialLineageView,
    val payments: List<PaymentHistory>,
    /** The approved service plan of the published Quote; absent before issuance or for a Quote issued without one. */
    val servicePlan: InquiryServicePlan? = null,
) {
    val suggestedDepositTerms get() = FIONAS_DEFAULT_DEPOSIT_TERMS

    init {
        servicePlan?.let { plan ->
            check(plan.inquiryId == inquiry.inquiry.id && plan.quote == proposal?.documentReference) {
                "Staff request service plan disagrees with its published proposal"
            }
        }
        check(financial.inquiryId == inquiry.inquiry.id) { "Staff request canonical financial ownership disagrees with inquiry" }
        check(financial.latest.document.id == inquiry.lifecycle.documentId) {
            "Staff request canonical financial identity disagrees with lifecycle"
        }
        check(deposit.latestVersion.document == financial.latest.document && deposit.reconciliation == financial.reconciliation) {
            "Staff request financial and deposit views disagree"
        }
        if (financial.latest.document is FinancialDocument.Invoice) {
            val accepted = checkNotNull(proposal) { "Booked request has no accepted proposal" }
            val requirement = deposit.depositRequirement!!.requirement as DepositRequirement.Active
            // Refunds may unwind the deposit, but never erase its immutable acceptance receipt.
            val receipts =
                payments.filter { history ->
                    history.allocations.any { it.financialDocumentReference == accepted.documentReference }
                }
            val receipt = receipts.singleOrNull()
            check(
                receipt != null &&
                    receipt.payment.currency == requirement.requiredAmount.currency &&
                    receipt.payment.amount.amount
                        .compareTo(requirement.requiredAmount.amount) == 0 &&
                    receipt.allocations.singleOrNull()?.let {
                        it.financialDocumentReference == accepted.documentReference &&
                            it.currency == requirement.requiredAmount.currency &&
                            it.amount.amount.compareTo(requirement.requiredAmount.amount) == 0
                    } == true,
            ) { "Booked request has no unique complete accepted Quote deposit receipt" }
        } else {
            check(payments.isEmpty()) { "Unbooked canonical request has historical payment allocations" }
        }
    }
}
