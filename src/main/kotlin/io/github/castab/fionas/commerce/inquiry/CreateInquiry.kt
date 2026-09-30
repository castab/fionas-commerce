package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.financial.CreateInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryDocumentPurpose
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
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
 *
 * Pricing inputs the customer configured are checked with Fiona's [pricing], from exactly the
 * catalog revision they name, read in the same transaction, and recorded with the inquiry.
 * Inputs the pricing rejects fail the request as they would fail an estimate preview, and
 * nothing is recorded. Those exact priced lines materialize the canonical initial Estimate;
 * the inquiry retains the requested inputs, while the ledger retains self-contained lines.
 * A plain inquiry creates no financial document. Every write shares this operation's transaction.
 *
 * The result is the recorded [Inquiry] alone. It never carries the customer's stored record,
 * so a caller who submits someone else's email learns nothing about that customer.
 */
class CreateInquiry(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val inquiries: InquiryRepository,
    private val pricingInputs: InquiryPricingRepository,
    private val pricing: FionasPricing,
    private val clock: Clock,
    private val materialize: MaterializeInquiryFinancialDocument,
    private val newCustomerId: () -> CustomerId = { CustomerId(UUID.randomUUID()) },
    private val newInquiryId: () -> InquiryId = { InquiryId(UUID.randomUUID()) },
) {
    /** A validated request to record an inquiry. */
    data class Command(
        val name: CustomerName,
        val email: Email,
        val message: InquiryMessage?,
        val pricingInputs: FionasPricingInputs? = null,
        val zipCode: ZipCode,
        val eventDate: EventDate,
        val eventType: EventType,
    )

    operator fun invoke(command: Command): Inquiry {
        // PostgreSQL stores microseconds; truncating keeps what is returned equal to what is stored.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val lines = command.pricingInputs?.let { pricing.price(transaction, it).lineItems }
            val customer =
                customers.findByEmail(transaction, command.email)
                    ?: Customer(newCustomerId(), command.name, command.email, now)
                        .also { customers.insert(transaction, it) }
            val inquiry = Inquiry(newInquiryId(), customer.id, command.message, now, command.zipCode, command.eventDate, command.eventType)
            inquiries.insert(transaction, inquiry)
            command.pricingInputs?.let { pricingInputs.insert(transaction, inquiry.id, it) }
            lines?.let {
                materialize.create(
                    transaction,
                    inquiry.id,
                    CreateInquiryFinancialDocument.Stage.ESTIMATE,
                    it,
                    InquiryDocumentPurpose.INITIAL_ESTIMATE,
                )
            }
            inquiry
        }
    }
}
