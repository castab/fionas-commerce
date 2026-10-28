package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.OffsetDateTime
import java.util.UUID

/** Staff reply command records. Callers hold the inquiry append lock for every write. */
class JdbiStaffReplyRepository {
    fun find(
        transaction: Transaction,
        key: StaffReplyKey,
    ): StaffReplyRecord? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_staff_replies WHERE idempotency_key = :key")
            .bind("key", key.value)
            .map { row, _ ->
                StaffReplyRecord(
                    StaffReplyKey(row.getString("idempotency_key")),
                    InquiryId(row.getObject("inquiry_id", UUID::class.java)),
                    row.getString("fingerprint"),
                    UserId(row.getObject("sent_by", UUID::class.java)),
                    StaffReplyMode.valueOf(row.getString("mode")),
                    row.getString("provider_request"),
                    row.getString("signature_name"),
                    row.getObject("requested_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("conversation_id", UUID::class.java),
                    row.getString("outcome")?.let(StaffReplyOutcome::valueOf),
                    row.getObject("provider_message_id", UUID::class.java),
                    row.getString("provider_state"),
                    row.getObject("communication_id", UUID::class.java)?.let(::InquiryCommunicationId),
                )
            }.findOne()
            .orElse(null)

    /** Unique-key insert; false when a concurrent command already claimed the key (never an aborted transaction). */
    fun claim(
        transaction: Transaction,
        record: StaffReplyRecord,
    ): Boolean =
        transaction.handle
            .createUpdate(
                "INSERT INTO fionas.inquiry_staff_replies " +
                    "(idempotency_key, inquiry_id, fingerprint, sent_by, mode, provider_request, signature_name, requested_at, " +
                    "conversation_id) VALUES (:key, :inquiry, :fingerprint, :sentBy, :mode, :request, :signature, :at, " +
                    "CAST(:conversation AS uuid)) " +
                    "ON CONFLICT DO NOTHING",
            ).bind("key", record.key.value)
            .bind("inquiry", record.inquiryId.value)
            .bind("fingerprint", record.fingerprint)
            .bind("sentBy", record.sentBy.value)
            .bind("mode", record.mode.name)
            .bind("request", record.providerRequest)
            .bind("signature", record.signatureName)
            .bind("at", record.requestedAt)
            .bind("conversation", record.conversationId)
            .execute() == 1

    fun accept(
        transaction: Transaction,
        key: StaffReplyKey,
        conversationId: UUID,
        messageId: UUID,
        state: String,
        communication: InquiryCommunicationId,
    ) {
        val updated =
            transaction.handle
                .createUpdate(
                    "UPDATE fionas.inquiry_staff_replies SET outcome = 'ACCEPTED', conversation_id = :conversation, " +
                        "provider_message_id = :message, provider_state = :state, communication_id = :communication " +
                        "WHERE idempotency_key = :key AND outcome IS NULL AND (conversation_id IS NULL OR conversation_id = :conversation)",
                ).bind("key", key.value)
                .bind("conversation", conversationId)
                .bind("message", messageId)
                .bind("state", state)
                .bind("communication", communication.value)
                .execute()
        check(updated == 1) { "Staff reply acceptance disagrees with its command record" }
    }

    fun reject(
        transaction: Transaction,
        key: StaffReplyKey,
    ) {
        transaction.handle
            .createUpdate("UPDATE fionas.inquiry_staff_replies SET outcome = 'REJECTED' WHERE idempotency_key = :key AND outcome IS NULL")
            .bind("key", key.value)
            .execute()
    }

    /** Whether a provider conversation was ever confirmed for this inquiry by an accepted staff reply. */
    fun established(
        transaction: Transaction,
        inquiry: InquiryId,
    ): Boolean =
        transaction.handle
            .createQuery(
                "SELECT EXISTS (SELECT 1 FROM fionas.inquiry_staff_replies WHERE inquiry_id = :inquiry AND outcome = 'ACCEPTED')",
            ).bind("inquiry", inquiry.value)
            .mapTo(Boolean::class.javaObjectType)
            .one()
}
