package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.conversation.InquiryTimelineRevisionRepository
import java.util.UUID

/** Whole payment histories appear in every canonical lineage ever allocated from this receipt. */
internal class AdvancePaymentTimelines(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository = JdbiInquiryFinancialDocumentRepository(),
    private val revisions: InquiryTimelineRevisionRepository = InquiryTimelineRevisionRepository(),
) {
    /** The successful ledger mutation already owns the payment lock; no later business locks may follow. */
    operator fun invoke(
        transaction: Transaction,
        paymentId: UUID,
    ) {
        val lineages =
            ledger
                .paymentHistory(transaction, paymentId)
                .allocations
                .map { it.financialDocumentReference.id }
                .toSet()
        val owners = associations.inquiriesOf(transaction, lineages)
        val affected = owners.values.toSet().filter { associations.initialEstimateOf(transaction, it) in lineages }
        revisions.advance(transaction, affected)
    }
}
