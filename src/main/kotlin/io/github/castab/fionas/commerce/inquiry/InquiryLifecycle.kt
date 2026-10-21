package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.staff.PrincipalId
import java.time.Instant
import java.util.UUID

/** A read projection, never persisted as inquiry status. */
enum class InquiryStage { REQUESTED, QUOTED, BOOKED, SERVED, CLOSED, DECLINED }

/** A durable Fiona business fact; the actor is the authenticated USER or SERVICE principal. */
data class InquiryMilestone(
    val occurredAt: Instant,
    val principalId: PrincipalId,
)

data class InquiryFulfillment(
    val served: InquiryMilestone,
    val closed: InquiryMilestone? = null,
)

/**
 * Canonical financial identity and Fiona operational facts, independent of event date.
 *
 * A [decline] is terminal and coexists only with the exact canonical Estimate it declined: a
 * decline beside a Quote, an Invoice, fulfillment or another Estimate version is contradictory
 * authoritative state, never a lifecycle, and fails loudly.
 */
class InquiryLifecycle private constructor(
    val documentId: UUID,
    val stage: InquiryStage,
    val fulfillment: InquiryFulfillment?,
    val decline: InquiryDecline?,
) {
    companion object {
        fun project(
            document: FinancialDocument,
            fulfillment: InquiryFulfillment?,
            decline: InquiryDecline? = null,
        ): InquiryLifecycle {
            check(fulfillment == null || document is FinancialDocument.Invoice) {
                "Inquiry fulfillment requires a canonical Invoice"
            }
            check(decline == null || (document is FinancialDocument.Estimate && decline.estimate == document.reference)) {
                "An inquiry decline requires the exact canonical Estimate it declined"
            }
            val stage =
                when (document) {
                    is FinancialDocument.Estimate -> if (decline == null) InquiryStage.REQUESTED else InquiryStage.DECLINED
                    is FinancialDocument.Quote -> InquiryStage.QUOTED
                    is FinancialDocument.Invoice ->
                        when {
                            fulfillment?.closed != null -> InquiryStage.CLOSED
                            fulfillment != null -> InquiryStage.SERVED
                            else -> InquiryStage.BOOKED
                        }
                }
            return InquiryLifecycle(document.id, stage, fulfillment, decline)
        }
    }
}
