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
    private val communications: InquiryCommunicationRepository,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    data class Acknowledge(
        val inquiryId: InquiryId,
        val principalId: PrincipalId,
        val throughRecordedOrder: Long,
    ) {
        init {
            require(throughRecordedOrder >= 0) { "Communication watermark must be nonnegative" }
        }
    }

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
        transactor.inTransaction { transaction ->
            communications.lock(transaction, command.inquiryId)
            val history = communications.history(transaction, command.inquiryId)
            require(command.throughRecordedOrder == 0L || history.any { it.recordedOrder == command.throughRecordedOrder }) {
                "Communication watermark does not belong to this inquiry"
            }
            if (history.any {
                    it.activity.kind == InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED &&
                        it.recordedOrder > command.throughRecordedOrder
                }
            ) {
                throw CommerceFailure.Conflict("Customer communication has changed; reload before acknowledging", ConversationChanged())
            }
            communications.append(
                transaction,
                InquiryCommunication(
                    InquiryCommunicationId(newId()),
                    command.inquiryId,
                    InquiryCommunicationKind.STAFF_ACKNOWLEDGED,
                    clock.instant().truncatedTo(ChronoUnit.MICROS),
                    command.principalId,
                    command.throughRecordedOrder,
                ),
            )
        }
    }

    private fun record(
        inquiryId: InquiryId,
        kind: InquiryCommunicationKind,
        occurredAt: Instant?,
        principalId: PrincipalId?,
    ): InquiryCommunication =
        transactor.inTransaction { transaction ->
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
