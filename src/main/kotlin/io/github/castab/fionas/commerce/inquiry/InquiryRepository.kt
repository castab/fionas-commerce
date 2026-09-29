package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction

/**
 * Persistence of [Inquiry]s, inside the caller's [Transaction]. Never begins, commits, or
 * rolls back a transaction; the calling operation owns the boundary.
 */
interface InquiryRepository {
    /** Inserts [inquiry]. Its customer must already exist in the same database. */
    fun insert(
        transaction: Transaction,
        inquiry: Inquiry,
    )

    fun findById(
        transaction: Transaction,
        id: InquiryId,
    ): Inquiry?

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
