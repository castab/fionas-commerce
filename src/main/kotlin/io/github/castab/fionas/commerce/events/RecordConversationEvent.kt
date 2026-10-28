package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.temporal.ChronoUnit
import java.util.UUID

private val conversationLogger = KotlinLogging.logger {}

/** Decodes before entering the shared operation transaction; ACK belongs to the worker after return. */
class RecordConversationEvent(
    private val transactor: Transactor,
    private val communications: InquiryCommunicationRepository,
    private val sources: JdbiInquiryConversationRepository,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    operator fun invoke(bytes: ByteArray) {
        val event = conversationEvent(bytes)
        val inquiry = event.inquiryId ?: return
        transactor.inTransaction { transaction ->
            try {
                communications.lock(transaction, inquiry)
            } catch (_: CommerceFailure.NotFound) {
                conversationLogger.warn { "event=conversation_ignored code=unknown_inquiry" }
                return@inTransaction
            }
            if (!sources.claimEvent(
                    transaction,
                    inquiry,
                    event.id,
                    event.conversationId,
                    event.sequence,
                    event.fingerprint,
                )
            ) {
                return@inTransaction
            }
            // Provider service/operator actors are not Fiona users. Outbound and delivery events never invent staff provenance.
            if (event.type != "conversation.message.received") return@inTransaction
            val message = checkNotNull(event.messageId)
            val at = event.occurredAt.truncatedTo(ChronoUnit.MICROS)
            if (sources.received(transaction, inquiry, message, at)) return@inTransaction
            val activity =
                InquiryCommunication(InquiryCommunicationId(newId()), inquiry, InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED, at, null)
            communications.append(transaction, activity)
            sources.recordSource(transaction, inquiry, message, at, activity.id)
        }
    }
}
