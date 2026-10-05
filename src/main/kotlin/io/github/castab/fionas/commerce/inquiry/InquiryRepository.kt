package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasPricingInputs

/**
 * Persistence of [Inquiry]s, each with the Fiona pricing inputs the customer configured with
 * it, inside the caller's [Transaction]. Never begins, commits, or rolls back a transaction;
 * the calling operation owns the boundary.
 *
 * The requested inputs are part of the inquiry's own row: an inquiry cannot be written without
 * them, and they are written with it once and never changed. They are the customer's request,
 * pinned to the catalog revision it names; never lines, amounts, or totals.
 */
interface InquiryRepository {
    /** Complete inquiry population for checking canonical relationship coverage, without loading inquiry details. */
    fun ids(transaction: Transaction): Set<InquiryId>

    /** Bulk inquiry and strictly restored requested pricing enrichment. No ordering is promised. */
    fun findRequestedByIds(
        transaction: Transaction,
        ids: Set<InquiryId>,
    ): Map<InquiryId, RequestedInquiry>

    /**
     * Inserts [inquiry] with the [pricingInputs] the customer requested, in one row. Its customer
     * must already exist in the same database.
     */
    fun insert(
        transaction: Transaction,
        inquiry: Inquiry,
        pricingInputs: FionasPricingInputs,
    )

    fun findById(
        transaction: Transaction,
        id: InquiryId,
    ): Inquiry?

    /**
     * The inquiry [id] with the pricing inputs requested with it, or `null` when no inquiry has
     * that id. Fails with an [IllegalStateException] when the stored inputs are malformed.
     */
    fun findRequested(
        transaction: Transaction,
        id: InquiryId,
    ): RequestedInquiry?

    /**
     * At most [limit] inquiries, newest first (by creation time, then by id, descending),
     * starting strictly after [after], or from the newest when it is `null`.
     */
    fun listNewestFirst(
        transaction: Transaction,
        after: InquiryListPosition?,
        limit: Int,
    ): List<Inquiry>
}

/** An [inquiry] and the [pricingInputs] the customer configured with it, as recorded together. */
data class RequestedInquiry(
    val inquiry: Inquiry,
    val pricingInputs: FionasPricingInputs,
)
