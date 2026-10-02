package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.CustomerRepository

/**
 * Staff's inquiry inbox: the newest inquiries first, a bounded page at a time, each with the
 * customer who made it. Deliberately not a query API: no filters, no search, no offsets.
 *
 * Pages continue from the position of the previous page's last inquiry (creation time, then
 * id), so inquiries recorded at the same instant are neither repeated nor skipped, and an
 * inquiry recorded while a client pages never shifts a later page.
 */
class ListInquiries(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val inquiries: InquiryRepository,
) {
    /** A request for the page of at most [limit] inquiries following [after], or the first page. */
    data class Command(
        val after: InquiryListPosition?,
        val limit: Int = DEFAULT_LIMIT,
    ) {
        init {
            require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        }
    }

    operator fun invoke(command: Command): InquiryPage =
        transactor.inTransaction { transaction ->
            // One more than the page holds says whether another page follows.
            val found = inquiries.listNewestFirst(transaction, command.after, command.limit + 1)
            val page = found.take(command.limit)
            val owners = customers.findByIds(transaction, page.map { it.customerId }.toSet())
            InquiryPage(
                inquiries =
                    page.map { inquiry ->
                        // The foreign key guarantees the customer exists; its absence is an internal failure.
                        val customer =
                            checkNotNull(owners[inquiry.customerId]) { "Inquiry ${inquiry.id.value} references a missing customer" }
                        InquirySummary(inquiry, customer)
                    },
                next = page.lastOrNull()?.takeIf { found.size > command.limit }?.let { InquiryListPosition(it.createdAt, it.id) },
            )
        }

    companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
    }
}
