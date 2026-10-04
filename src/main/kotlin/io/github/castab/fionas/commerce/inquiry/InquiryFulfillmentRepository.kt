package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction

/** Operational facts only; callers serialize mutations on the canonical lineage association. */
interface InquiryFulfillmentRepository {
    fun find(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryFulfillment?

    fun serve(
        transaction: Transaction,
        inquiryId: InquiryId,
        milestone: InquiryMilestone,
    )

    fun close(
        transaction: Transaction,
        inquiryId: InquiryId,
        milestone: InquiryMilestone,
    )
}
