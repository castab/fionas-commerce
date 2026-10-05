package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.CustomerRepository

/**
 * Reads one inquiry, its customer, requested pricing inputs and canonical lifecycle from
 * one repeatable database snapshot.
 */
class GetInquiry(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val inquiries: InquiryRepository,
    private val lifecycle: ReadInquiryLifecycle,
) {
    /** Fails with [CommerceFailure.NotFound] when no inquiry has [id]. */
    operator fun invoke(id: InquiryId): InquiryDetails =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            read(transaction, id)
        }

    /** Composes the same detail in the caller's repeatable snapshot, without opening a transaction. */
    internal fun read(
        transaction: Transaction,
        id: InquiryId,
    ): InquiryDetails {
        val (inquiry, requested) =
            inquiries.findRequested(transaction, id)
                ?: throw CommerceFailure.NotFound("Inquiry ${id.value} was not found")
        // The foreign key guarantees the customer exists; its absence is an internal failure.
        val customer =
            checkNotNull(customers.findById(transaction, inquiry.customerId)) {
                "Inquiry ${id.value} references a missing customer"
            }
        val state =
            try {
                lifecycle.read(transaction, id)
            } catch (failure: CommerceFailure.NotFound) {
                throw IllegalStateException("Inquiry ${id.value} references a missing canonical financial lineage", failure)
            }
        return InquiryDetails(inquiry, customer, requested, state)
    }
}
