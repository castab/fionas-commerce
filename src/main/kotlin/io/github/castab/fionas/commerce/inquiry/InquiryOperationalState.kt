package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.financial.FinancialLineageView
import java.util.Collections

/** Canonical lifecycle and authoritative financial facts, sufficient for operational derivations without further reads. */
class InquiryOperationalState(
    val inquiryId: InquiryId,
    val financial: FinancialLineageView,
    fulfillment: InquiryFulfillment?,
) {
    val lifecycle: InquiryLifecycle = InquiryLifecycle.project(financial.latestVersion.document, fulfillment)

    val needsQuote: Boolean get() = lifecycle.stage == InquiryStage.REQUESTED
    val awaitingQuoteReply: Boolean get() = lifecycle.stage == InquiryStage.QUOTED
    val needsClosing: Boolean get() =
        lifecycle.stage == InquiryStage.SERVED &&
            financial.reconciliation.balance.amount
                .signum() == 0
}

/** Overlapping operational counts, derived only from canonical lifecycle and runtime reconciliation. */
data class InquiryOperationalCounts(
    val new: Int,
    val quoted: Int,
    val booked: Int,
    val needsClosing: Int,
) {
    companion object {
        fun project(states: Collection<InquiryOperationalState>): InquiryOperationalCounts =
            InquiryOperationalCounts(
                new = states.count { it.needsQuote },
                quoted = states.count { it.awaitingQuoteReply },
                booked = states.count { it.lifecycle.stage == InquiryStage.BOOKED || it.lifecycle.stage == InquiryStage.SERVED },
                needsClosing = states.count { it.needsClosing },
            )
    }
}

/** One coherent population snapshot; neither states nor counts are persisted. No ordering is promised. */
class InquiryOperationalSnapshot(
    states: Collection<InquiryOperationalState>,
) {
    val states: List<InquiryOperationalState> = Collections.unmodifiableList(states.toList())
    val counts: InquiryOperationalCounts = InquiryOperationalCounts.project(this.states)
}
