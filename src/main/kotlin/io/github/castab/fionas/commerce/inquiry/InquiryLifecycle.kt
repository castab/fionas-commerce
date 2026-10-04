package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.staff.PrincipalId
import java.time.Instant
import java.util.UUID

/** A read projection, never persisted as inquiry status. */
enum class InquiryStage { REQUESTED, QUOTED, BOOKED, SERVED, CLOSED }

/** A durable Fiona business fact; the actor is the authenticated USER or SERVICE principal. */
data class InquiryMilestone(
    val occurredAt: Instant,
    val principalId: PrincipalId,
)

data class InquiryFulfillment(
    val served: InquiryMilestone,
    val closed: InquiryMilestone? = null,
)

/** Canonical financial identity and Fiona operational facts, independent of event date. */
class InquiryLifecycle private constructor(
    val documentId: UUID,
    val stage: InquiryStage,
    val fulfillment: InquiryFulfillment?,
) {
    companion object {
        fun project(
            document: FinancialDocument,
            fulfillment: InquiryFulfillment?,
        ): InquiryLifecycle {
            check(fulfillment == null || document is FinancialDocument.Invoice) {
                "Inquiry fulfillment requires a canonical Invoice"
            }
            val stage =
                when (document) {
                    is FinancialDocument.Estimate -> InquiryStage.REQUESTED
                    is FinancialDocument.Quote -> InquiryStage.QUOTED
                    is FinancialDocument.Invoice ->
                        when {
                            fulfillment?.closed != null -> InquiryStage.CLOSED
                            fulfillment != null -> InquiryStage.SERVED
                            else -> InquiryStage.BOOKED
                        }
                }
            return InquiryLifecycle(document.id, stage, fulfillment)
        }
    }
}
