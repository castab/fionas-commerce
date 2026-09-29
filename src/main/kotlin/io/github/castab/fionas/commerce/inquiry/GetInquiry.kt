package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.CustomerRepository

/**
 * Reads one inquiry, the customer who made it, and the pricing inputs requested with it, in
 * one consistent transaction.
 */
class GetInquiry(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val inquiries: InquiryRepository,
    private val pricingInputs: InquiryPricingRepository,
) {
    /** Fails with [CommerceFailure.NotFound] when no inquiry has [id]. */
    operator fun invoke(id: InquiryId): InquiryDetails =
        transactor.inTransaction { transaction ->
            val inquiry =
                inquiries.findById(transaction, id)
                    ?: throw CommerceFailure.NotFound("Inquiry ${id.value} was not found")
            // The foreign key guarantees the customer exists; its absence is an internal failure.
            val customer =
                checkNotNull(customers.findById(transaction, inquiry.customerId)) {
                    "Inquiry ${id.value} references a missing customer"
                }
            InquiryDetails(inquiry, customer, pricingInputs.find(transaction, id))
        }
}
