package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Adapter-facing email facts and the explicit staff acknowledgement action; no financial mutations. */
class RecordInquiryCommunication(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    private val communications: InquiryCommunicationRepository,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    data class Acknowledge(
        val inquiryId: InquiryId,
        val principalId: PrincipalId,
    )

    fun customerEmailReceived(
        inquiryId: InquiryId,
        occurredAt: Instant,
    ): InquiryCommunication = record(inquiryId, InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, occurredAt, null)

    fun staffEmailSent(
        inquiryId: InquiryId,
        occurredAt: Instant,
        principalId: PrincipalId,
    ): InquiryCommunication = record(inquiryId, InquiryCommunicationKind.STAFF_EMAIL_SENT, occurredAt, principalId)

    /** Repeated acknowledgement succeeds and appends a fresh source fact, even with no outstanding inbound. */
    fun acknowledge(command: Acknowledge) {
        record(command.inquiryId, InquiryCommunicationKind.STAFF_ACKNOWLEDGED, null, command.principalId)
    }

    private fun record(
        inquiryId: InquiryId,
        kind: InquiryCommunicationKind,
        occurredAt: Instant?,
        principalId: PrincipalId?,
    ): InquiryCommunication =
        transactor.inTransaction { transaction ->
            inquiries.findById(transaction, inquiryId) ?: throw CommerceFailure.NotFound("Inquiry was not found")
            val activity =
                InquiryCommunication(
                    InquiryCommunicationId(newId()),
                    inquiryId,
                    kind,
                    (occurredAt ?: clock.instant()).truncatedTo(ChronoUnit.MICROS),
                    principalId,
                )
            communications.append(transaction, activity)
            activity
        }
}
