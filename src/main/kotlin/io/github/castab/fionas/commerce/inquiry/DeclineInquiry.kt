package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.outbox.Outbox
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.events.inquiryDeclinedDraft
import io.github.castab.fionas.commerce.events.inquiryDeclinedPayload
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Records a verified staff user's terminal decision not to pursue a REQUESTED inquiry.
 *
 * Decline is an operational fact beside the canonical lineage, never a financial mutation: the
 * customer, inquiry, requested service and canonical Estimate stay exactly as they are, and the
 * lifecycle projects DECLINED. Only REQUESTED → DECLINED exists. A QUOTED inquiry would need a
 * separate Quote withdrawal, a BOOKED one a cancellation; neither is this, and nothing reopens.
 *
 * In one READ COMMITTED transaction the canonical association row is locked first, as every
 * canonical mutation and the decline guard of commercial progression do, so a decline and an
 * initial Quote publication of one inquiry can never both commit: whichever waits observes the
 * other's committed result. The caller names the canonical version it reviewed; a stale version
 * conflicts and is never rebased. The decline fact and its InquiryDeclined v1 outbox event commit
 * together or not at all; a rejected decline appends nothing.
 */
class DeclineInquiry(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    private val customers: CustomerRepository,
    private val associations: InquiryFinancialDocumentRepository,
    private val ledger: FinancialLedger,
    private val fulfillment: InquiryFulfillmentRepository,
    private val declines: InquiryDeclineRepository,
    private val outbox: Outbox,
    private val clock: Clock,
    private val newEventId: () -> UUID = UUID::randomUUID,
) {
    data class Command(
        val inquiryId: InquiryId,
        /** The canonical Estimate version the staff user reviewed. */
        val expectedDocumentVersion: Version,
        val reason: DeclineReason,
        /** The verified staff user deciding, from authentication, never from the request body. */
        val declinedBy: UserId,
    )

    operator fun invoke(command: Command): InquiryLifecycle =
        transactor.inTransaction { transaction ->
            val id = command.inquiryId
            val (inquiry, requested) =
                inquiries.findRequested(transaction, id) ?: throw CommerceFailure.NotFound("Inquiry ${id.value} was not found")
            val documentId =
                checkNotNull(associations.initialEstimateOf(transaction, id)) { "Inquiry ${id.value} has no canonical lineage" }
            check(associations.lockInquiryOf(transaction, documentId) == id) { "Canonical inquiry ownership changed" }
            val document = ledger.latest(transaction, documentId)
            if (document.version != command.expectedDocumentVersion) {
                throw CommerceFailure.Conflict(
                    "Financial document $documentId is at ${document.version}, not the expected " +
                        "${command.expectedDocumentVersion}; reload it and retry",
                )
            }
            val current = InquiryLifecycle.project(document, fulfillment.find(transaction, id), declines.find(transaction, id))
            if (current.stage != InquiryStage.REQUESTED) {
                throw CommerceFailure.IllegalTransition("Only a requested inquiry can be declined")
            }
            val decline =
                InquiryDecline(id, document.reference, clock.instant().truncatedTo(ChronoUnit.MICROS), command.declinedBy, command.reason)
            declines.insert(transaction, decline)
            // The foreign key guarantees the customer exists; its absence is an internal failure.
            val customer = checkNotNull(customers.findById(transaction, inquiry.customerId)) { "Inquiry ${id.value} has no customer" }
            val draft = inquiryDeclinedDraft(newEventId(), decline)
            outbox.append(transaction, draft) { sequence ->
                inquiryDeclinedPayload(draft, sequence, inquiry, customer, requested, document as FinancialDocument.Estimate, decline)
            }
            InquiryLifecycle.project(document, null, decline)
        }
}
