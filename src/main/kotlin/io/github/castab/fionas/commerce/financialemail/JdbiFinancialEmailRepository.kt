package io.github.castab.fionas.commerce.financialemail

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.conversation.StaffReplyKey
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class JdbiFinancialEmailRepository {
    fun history(
        transaction: Transaction,
        inquiry: InquiryId,
    ): List<FinancialEmailRecord> =
        transaction.handle
            .createQuery(
                "SELECT e.*, c.occurred_at AS accepted_at FROM fionas.inquiry_financial_emails e " +
                    "LEFT JOIN fionas.inquiry_communications c ON c.id = e.communication_id " +
                    "WHERE e.inquiry_id = :inquiry ORDER BY e.requested_at, e.id",
            ).bind("inquiry", inquiry.value)
            .map { row, _ -> restore(row) }
            .list()

    fun find(
        transaction: Transaction,
        inquiry: InquiryId,
        key: StaffReplyKey,
    ): FinancialEmailRecord? = history(transaction, inquiry).singleOrNull { it.key == key }

    /** Caller holds the canonical association lock and has checked the key and outstanding reservation. */
    fun insert(
        transaction: Transaction,
        record: FinancialEmailRecord,
    ) {
        transaction.handle
            .createUpdate(
                """
                 INSERT INTO fionas.inquiry_financial_emails
                   (id, inquiry_id, idempotency_key, fingerprint, sent_by, kind, document_id, document_version,
                    business_revision, proposal_id, recovery_digest, provider_request, signature_name, opening,
                    requested_at, observed_through, conversation_id)
                 VALUES (:id, :inquiry, :key, :fingerprint, :by, :kind, :document, :version, :revision,
                CAST(:proposal AS uuid), :recovery, :provider, :signature, :opening, :at, :observed, CAST(:conversation AS uuid))
                """.trimIndent(),
            ).bind("id", record.id.value)
            .bind("inquiry", record.inquiryId.value)
            .bind("key", record.key.value)
            .bind("fingerprint", record.fingerprint)
            .bind("by", record.sentBy.value)
            .bind("kind", record.kind.name)
            .bind("document", record.documentId)
            .bind("version", record.documentVersion)
            .bind("revision", record.businessRevision)
            .bind("proposal", record.proposalId?.value)
            .bind("recovery", record.recoveryDigest)
            .bind("provider", record.providerRequest)
            .bind("signature", record.signature)
            .bind("opening", record.opening)
            .bind("at", record.requestedAt)
            .bind("observed", record.observedThrough)
            .bind("conversation", record.conversationId)
            .execute()
    }

    fun accept(
        transaction: Transaction,
        record: FinancialEmailRecord,
        conversation: UUID,
        message: UUID,
        state: String,
        activity: InquiryCommunicationId,
    ) {
        check(
            transaction.handle
                .createUpdate(
                    "UPDATE fionas.inquiry_financial_emails SET outcome = 'ACCEPTED', conversation_id = :conversation, " +
                        "provider_message_id = :message, provider_state = :state, communication_id = " +
                        ":activity WHERE id = :id AND outcome IS NULL " +
                        "AND (conversation_id IS NULL OR conversation_id = :conversation) " +
                        "AND (provider_message_id IS NULL OR provider_message_id = :message)",
                ).bind("id", record.id.value)
                .bind("conversation", conversation)
                .bind("message", message)
                .bind("state", state)
                .bind("activity", activity.value)
                .execute() == 1,
        ) { "Financial email acceptance disagrees with its command" }
    }

    fun beginDispatch(
        transaction: Transaction,
        id: FinancialEmailId,
    ): Int =
        transaction.handle
            .createQuery(
                "UPDATE fionas.inquiry_financial_emails SET dispatch_count = dispatch_count + 1 WHERE id = :id " +
                    "AND outcome IS NULL RETURNING dispatch_count",
            ).bind("id", id.value)
            .mapTo(Int::class.javaObjectType)
            .one()

    fun observeIndeterminate(
        transaction: Transaction,
        id: FinancialEmailId,
        conversation: UUID,
        message: UUID,
    ) {
        check(
            transaction.handle
                .createUpdate(
                    "UPDATE fionas.inquiry_financial_emails SET conversation_id = :conversation, provider_message_id = :message, " +
                        "provider_state = 'indeterminate' WHERE id = :id AND outcome IS NULL AND provider_message_id IS NULL " +
                        "AND (conversation_id IS NULL OR conversation_id = :conversation)",
                ).bind("id", id.value)
                .bind("conversation", conversation)
                .bind("message", message)
                .execute() == 1,
        ) {
            "Financial email provider identity disagrees with its command"
        }
    }

    fun reject(
        transaction: Transaction,
        id: FinancialEmailId,
    ) {
        transaction.handle
            .createUpdate(
                "UPDATE fionas.inquiry_financial_emails SET outcome = 'REJECTED' WHERE id = :id AND outcome IS NULL",
            ).bind("id", id.value)
            .execute()
    }

    fun established(
        transaction: Transaction,
        inquiry: InquiryId,
    ): Boolean =
        transaction.handle
            .createQuery(
                "SELECT EXISTS (SELECT 1 FROM fionas.inquiry_financial_emails WHERE inquiry_id = :inquiry " +
                    "AND conversation_id IS NOT NULL)",
            ).bind("inquiry", inquiry.value)
            .mapTo(Boolean::class.javaObjectType)
            .one()

    private fun restore(row: ResultSet) =
        FinancialEmailRecord(
            FinancialEmailId(row.getObject("id", UUID::class.java)),
            InquiryId(row.getObject("inquiry_id", UUID::class.java)),
            StaffReplyKey(row.getString("idempotency_key")),
            row.getString("fingerprint"),
            UserId(row.getObject("sent_by", UUID::class.java)),
            FinancialEmailKind.valueOf(row.getString("kind")),
            row.getObject("document_id", UUID::class.java),
            row.getInt("document_version"),
            row.getInt("business_revision"),
            row.getObject("proposal_id", UUID::class.java)?.let(::InquiryProposalId),
            row.getString("recovery_digest"),
            row.getString("provider_request"),
            row.getString("signature_name"),
            row.getBoolean("opening"),
            row.getObject("requested_at", OffsetDateTime::class.java).toInstant(),
            row.getLong("observed_through"),
            row.getObject("conversation_id", UUID::class.java),
            row.getString("outcome")?.let(FinancialEmailOutcome::valueOf) ?: FinancialEmailOutcome.UNKNOWN,
            row.getObject("provider_message_id", UUID::class.java),
            row.getString("provider_state"),
            row.getObject("accepted_at", OffsetDateTime::class.java)?.toInstant(),
            row.getInt("dispatch_count"),
        )
}
