package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction

/**
 * Persistence of [Inquiry]s, each with the [RequestedService] recorded with it, inside the
 * caller's [Transaction]. Never begins, commits, or rolls back a transaction; the calling
 * operation owns the boundary.
 *
 * The requested service is part of the inquiry's own row: an inquiry cannot be written without
 * it, and it is written once and never changed. It is descriptive request history; never lines,
 * amounts, or totals.
 */
interface InquiryRepository {
    /** Complete inquiry population for checking canonical relationship coverage, without loading inquiry details. */
    fun ids(transaction: Transaction): Set<InquiryId>

    /** Bulk inquiry and strictly restored requested-service enrichment. No ordering is promised. */
    fun findRequestedByIds(
        transaction: Transaction,
        ids: Set<InquiryId>,
    ): Map<InquiryId, RequestedInquiry>

    /**
     * Inserts [inquiry] with the [requestedService] recorded with it, in one row. Its customer
     * must already exist in the same database.
     */
    fun insert(
        transaction: Transaction,
        inquiry: Inquiry,
        requestedService: RequestedService,
    )

    fun findById(
        transaction: Transaction,
        id: InquiryId,
    ): Inquiry?

    /**
     * The inquiry [id] with the service requested with it, or `null` when no inquiry has that id.
     * Fails with an [IllegalStateException] when the stored request is malformed.
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

/** An [inquiry] and the [requestedService] recorded with it. */
data class RequestedInquiry(
    val inquiry: Inquiry,
    val requestedService: RequestedService,
)
