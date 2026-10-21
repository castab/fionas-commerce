package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository

/** Reads canonical financial stage, fulfillment and decline facts inside the detail operation's transaction. */
class ReadInquiryLifecycle(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val fulfillment: InquiryFulfillmentRepository,
    private val declines: InquiryDeclineRepository = JdbiInquiryDeclineRepository(),
) {
    fun read(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryLifecycle {
        val id =
            checkNotNull(associations.initialEstimateOf(transaction, inquiryId)) {
                "Inquiry ${inquiryId.value} has no canonical initial Estimate lineage"
            }
        return InquiryLifecycle.project(
            ledger.latest(transaction, id),
            fulfillment.find(transaction, inquiryId),
            declines.find(transaction, inquiryId),
        )
    }
}
