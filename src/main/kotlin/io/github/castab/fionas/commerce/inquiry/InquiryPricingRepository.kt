package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasPricingInputs

/**
 * Persistence of the Fiona pricing inputs a customer configured with an [Inquiry], at most one
 * record per inquiry, inside the caller's [Transaction]. Never begins, commits, or rolls back
 * a transaction; the calling operation owns the boundary.
 *
 * These are the customer's request, pinned to the catalog revision it names; never lines,
 * amounts, or totals. Records are written with their inquiry and never changed.
 */
interface InquiryPricingRepository {
    /** Records [inputs] as what the customer requested with [inquiryId], which must already exist. */
    fun insert(
        transaction: Transaction,
        inquiryId: InquiryId,
        inputs: FionasPricingInputs,
    )

    /** The pricing inputs requested with [inquiryId], or `null` when none were. */
    fun find(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): FionasPricingInputs?
}
