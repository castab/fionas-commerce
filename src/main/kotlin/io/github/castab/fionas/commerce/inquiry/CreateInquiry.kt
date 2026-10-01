package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.financial.CreateInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryDocumentPurpose
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
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
 * Every Fiona inquiry is a request for configured ice cream service, so every command carries
 * pricing inputs. They must use the public form's categories and the current revision observed
 * by [pricing] in this transaction, and are priced exactly once and recorded with the inquiry.
 * Stale, hidden, or otherwise invalid inputs record nothing. Those exact priced lines materialize
 * the canonical initial Estimate v1; the inquiry retains the requested inputs, while the ledger
 * retains self-contained lines. Every write shares this operation's transaction, so the inquiry
 * and its initial Estimate commit together or not at all.
 *
 * The result is the recorded [Inquiry] alone. It never carries the customer's stored record,
 * so a caller who submits someone else's email learns nothing about that customer.
 *
 * The command key is claimed in this same transaction before any business validation.
 * A committed matching command returns its existing Inquiry immediately; different intent
 * conflicts. The deferred submission-to-inquiry FK prevents an incomplete claim from
 * committing. Any later failure releases the key with every other write.
 */
class CreateInquiry(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val inquiries: InquiryRepository,
    private val pricingInputs: InquiryPricingRepository,
    private val submissions: InquirySubmissionRepository,
    private val pricing: PublicInquiryPricing,
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
        val pricingInputs: FionasPricingInputs,
        val zipCode: ZipCode,
        val eventDate: EventDate,
        val eventType: EventType,
        val submissionKey: InquirySubmissionKey,
    )

    operator fun invoke(command: Command): Inquiry {
        // PostgreSQL stores microseconds; truncating keeps what is returned equal to what is stored.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val fingerprint = command.fingerprint()
        return transactor.inTransaction { transaction ->
            val inquiryId = newInquiryId()
            if (!submissions.claim(transaction, command.submissionKey, fingerprint, inquiryId, now)) {
                // A separate READ COMMITTED statement sees the committed owner after INSERT waited.
                val existing = checkNotNull(submissions.find(transaction, command.submissionKey))
                if (existing.fingerprint != fingerprint) {
                    throw CommerceFailure.Conflict(
                        "This key already represents a different successful inquiry submission",
                        IdempotencyKeyReused(),
                    )
                }
                return@inTransaction checkNotNull(inquiries.findById(transaction, existing.inquiryId))
            }
            val lines = pricing.price(transaction, command.pricingInputs).lineItems
            val customer =
                customers.findByEmail(transaction, command.email)
                    ?: Customer(newCustomerId(), command.name, command.email, now)
                        .also { customers.insert(transaction, it) }
            val inquiry = Inquiry(inquiryId, customer.id, command.message, now, command.zipCode, command.eventDate, command.eventType)
            inquiries.insert(transaction, inquiry)
            pricingInputs.insert(transaction, inquiry.id, command.pricingInputs)
            materialize.create(
                transaction,
                inquiry.id,
                CreateInquiryFinancialDocument.Stage.ESTIMATE,
                lines,
                InquiryDocumentPurpose.INITIAL_ESTIMATE,
            )
            inquiry
        }
    }
}
