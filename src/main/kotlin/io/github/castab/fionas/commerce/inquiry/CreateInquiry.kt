package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.financial.FirstSnapshotStage
import io.github.castab.fionas.commerce.financial.InquiryDocumentPurpose
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.PricedLine
import io.github.castab.fionas.commerce.financial.requireDocumentLines
import io.github.castab.fionas.commerce.financial.requireNonnegativeTotal
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Records a prospective customer's inquiry, establishing the customer first, and commits the
 * already-priced lines its public pricing authority submitted as the inquiry's canonical
 * initial Estimate.
 *
 * The pricing authority is the web server's SERVICE principal ([Command.submittedBy]): it owns
 * the public catalog, selection rules, availability and prices, evaluates the customer's
 * untrusted choices, and submits exact lines. Fiona never reprices, checks a catalog revision or
 * option eligibility, or infers a price from the guest count or the requested items; it records
 * the lines exactly, checks only that they form a valid, nonnegative document, and records who
 * authored them.
 *
 * Customer matching (see AGENTS.md): the customer who already has the submitted normalized
 * [Email] is reused as is; a differing submitted name does not overwrite theirs. Otherwise a new
 * customer is created.
 *
 * The command key is claimed first, in this same transaction. A committed command with the same
 * fingerprint (all intent, including every line's values in order) returns its existing Inquiry
 * immediately, writing nothing; different intent under that key conflicts. Customer, inquiry,
 * requested service, Estimate v1 and its association and authorship then commit together or not
 * at all, releasing the key on any failure.
 *
 * The result is the recorded [Inquiry] alone. It never carries the customer's stored record,
 * so a caller who submits someone else's email learns nothing about that customer.
 */
class CreateInquiry(
    private val transactor: Transactor,
    private val customers: CustomerRepository,
    private val inquiries: InquiryRepository,
    private val submissions: InquirySubmissionRepository,
    private val clock: Clock,
    private val materialize: MaterializeInquiryFinancialDocument,
    private val newCustomerId: () -> CustomerId = { CustomerId(UUID.randomUUID()) },
    private val newInquiryId: () -> InquiryId = { InquiryId(UUID.randomUUID()) },
) {
    /** A validated request to record an inquiry, with the exact lines its pricing authority committed. */
    data class Command(
        val name: CustomerName,
        val email: Email,
        val message: InquiryMessage?,
        val requestedService: RequestedService,
        val lines: List<PricedLine>,
        val zipCode: ZipCode,
        val eventDate: EventDate,
        val eventType: EventType,
        val submissionKey: InquirySubmissionKey,
        /** The authenticated SERVICE principal whose authority the lines rest on. */
        val submittedBy: ServiceId,
    ) {
        init {
            requireDocumentLines(lines)
            requireNonnegativeTotal(lines)
        }
    }

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
            val customer =
                customers.findByEmail(transaction, command.email)
                    ?: Customer(newCustomerId(), command.name, command.email, now)
                        .also { customers.insert(transaction, it) }
            val inquiry = Inquiry(inquiryId, customer.id, command.message, now, command.zipCode, command.eventDate, command.eventType)
            inquiries.insert(transaction, inquiry, command.requestedService)
            materialize.create(
                transaction,
                inquiry.id,
                FirstSnapshotStage.ESTIMATE,
                command.lines,
                command.submittedBy,
                now,
                InquiryDocumentPurpose.INITIAL_ESTIMATE,
            )
            inquiry
        }
    }
}
