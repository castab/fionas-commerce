package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.Email
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Records a prospective customer's inquiry, establishing the customer first.
 *
 * Customer matching (see AGENTS.md): the customer who already has the submitted
 * normalized [Email] is reused as is. A differing submitted name does not overwrite the
 * existing customer's name. Otherwise a new customer is created. Both writes happen in one
 * runtime transaction, so an inquiry is never recorded without its customer, and a new
 * customer is never recorded without the inquiry that introduced them.
 */
class CreateInquiry(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val inquiries: InquiryRepository,
    private val clock: Clock,
    private val newCustomerId: () -> CustomerId = { CustomerId(UUID.randomUUID()) },
    private val newInquiryId: () -> InquiryId = { InquiryId(UUID.randomUUID()) },
) {
    /** A validated request to record an inquiry. */
    data class Command(
        val name: CustomerName,
        val email: Email,
        val message: InquiryMessage?,
    )

    operator fun invoke(command: Command): InquiryDetails {
        // PostgreSQL stores microseconds; truncating keeps what is returned equal to what is stored.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val customer =
                customers.findByEmail(transaction, command.email)
                    ?: Customer(newCustomerId(), command.name, command.email, now)
                        .also { customers.insert(transaction, it) }
            val inquiry = Inquiry(newInquiryId(), customer.id, command.message, now)
            inquiries.insert(transaction, inquiry)
            InquiryDetails(inquiry, customer)
        }
    }
}
