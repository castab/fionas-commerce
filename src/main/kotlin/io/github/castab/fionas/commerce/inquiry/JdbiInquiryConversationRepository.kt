package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.persistence.Transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Durable source identities and establishment evidence, never a replica of provider conversation state. */
class JdbiInquiryConversationRepository {
    fun established(
        transaction: Transaction,
        inquiry: InquiryId,
    ): Boolean =
        transaction.handle
            .createQuery("SELECT EXISTS (SELECT 1 FROM fionas.inquiry_conversation_events WHERE inquiry_id = :inquiry)")
            .bind("inquiry", inquiry.value)
            .mapTo(Boolean::class.javaObjectType)
            .one()

    /** Caller holds the inquiry append lock; ON CONFLICT never leaves an aborted transaction. */
    fun claimEvent(
        transaction: Transaction,
        inquiry: InquiryId,
        event: UUID,
        conversation: UUID,
        sequence: Long,
        fingerprint: String,
    ): Boolean {
        val inserted =
            transaction.handle
                .createUpdate(
                    "INSERT INTO fionas.inquiry_conversation_events " +
                        "(event_id, inquiry_id, conversation_id, provider_sequence, fingerprint) " +
                        "VALUES (:event, :inquiry, :conversation, :sequence, :fingerprint) ON CONFLICT DO NOTHING",
                ).bind("event", event)
                .bind("inquiry", inquiry.value)
                .bind("conversation", conversation)
                .bind("sequence", sequence)
                .bind("fingerprint", fingerprint)
                .execute()
        if (inserted == 1) return true
        val matches =
            transaction.handle
                .createQuery(
                    "SELECT count(*) FROM fionas.inquiry_conversation_events WHERE event_id = :event AND inquiry_id = :inquiry " +
                        "AND conversation_id = :conversation AND provider_sequence = :sequence AND fingerprint = :fingerprint",
                ).bind("event", event)
                .bind("inquiry", inquiry.value)
                .bind("conversation", conversation)
                .bind("sequence", sequence)
                .bind("fingerprint", fingerprint)
                .mapTo(Int::class.javaObjectType)
                .one()
        check(matches == 1) { "Conversation source identity was reused" }
        return false
    }

    fun received(
        transaction: Transaction,
        inquiry: InquiryId,
        message: UUID,
        at: Instant,
    ): Boolean {
        val source =
            transaction.handle
                .createQuery(
                    "SELECT inquiry_id, occurred_at FROM fionas.inquiry_customer_email_sources WHERE message_id = :message",
                ).bind("message", message)
                .map { row, _ ->
                    row.getObject("inquiry_id", UUID::class.java) to row.getObject("occurred_at", OffsetDateTime::class.java).toInstant()
                }.findOne()
                .orElse(null) ?: return false
        check(source.first == inquiry.value && source.second == at) { "Customer message source identity was reused" }
        return true
    }

    fun recordSource(
        transaction: Transaction,
        inquiry: InquiryId,
        message: UUID,
        at: Instant,
        activity: InquiryCommunicationId,
    ) {
        transaction.handle
            .createUpdate(
                "INSERT INTO fionas.inquiry_customer_email_sources (message_id, inquiry_id, occurred_at, communication_id) " +
                    "VALUES (:message, :inquiry, :at, :activity)",
            ).bind("message", message)
            .bind("inquiry", inquiry.value)
            .bind("at", at)
            .bind("activity", activity.value)
            .execute()
    }
}
