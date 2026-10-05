package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import java.time.OffsetDateTime
import java.util.UUID

class JdbiInquiryCommunicationRepository : InquiryCommunicationRepository {
    override fun append(
        transaction: Transaction,
        activity: InquiryCommunication,
    ): RecordedInquiryCommunication {
        // Lock before allocating the identity: same-inquiry append order must follow transaction visibility.
        transaction.handle
            .createQuery("SELECT id FROM fionas.inquiries WHERE id = :inquiry FOR UPDATE")
            .bind("inquiry", activity.inquiryId.value)
            .mapTo(UUID::class.java)
            .findOne()
            .orElseThrow { CommerceFailure.NotFound("Inquiry was not found") }
        val principal = activity.principalId
        val kind =
            when (principal) {
                is UserId -> "USER"
                is ServiceId -> "SERVICE"
                null -> null
            }
        val actor =
            when (principal) {
                is UserId -> principal.value
                is ServiceId -> principal.value
                null -> null
            }
        try {
            val order =
                transaction.handle
                    .createQuery(
                        "INSERT INTO fionas.inquiry_communications (id, inquiry_id, kind, occurred_at, principal_kind, principal_id) " +
                            "VALUES (:id, :inquiry, :kind, :at, :principalKind, CAST(:principalId AS uuid)) RETURNING recorded_order",
                    ).bind("id", activity.id.value)
                    .bind("inquiry", activity.inquiryId.value)
                    .bind("kind", activity.kind.name)
                    .bind("at", activity.occurredAt)
                    .bind("principalKind", kind)
                    .bindByType("principalId", actor, UUID::class.java)
                    .mapTo(Long::class.javaObjectType)
                    .one()
            return RecordedInquiryCommunication(activity, order)
        } catch (failure: Exception) {
            if (failure.isUniqueViolation()) throw CommerceFailure.Conflict("Communication activity already exists", failure)
            throw failure
        }
    }

    override fun attentionFor(
        transaction: Transaction,
        inquiryIds: Collection<InquiryId>,
    ): Map<InquiryId, InquiryCommunicationAttention> {
        if (inquiryIds.isEmpty()) return emptyMap()
        return transaction.handle
            .createQuery(
                """
                WITH scoped AS (
                    SELECT inquiry_id, kind, occurred_at, recorded_order,
                           max(recorded_order) FILTER (WHERE kind IN ('STAFF_EMAIL_SENT', 'STAFF_ACKNOWLEDGED'))
                               OVER (PARTITION BY inquiry_id) AS clearing_order
                    FROM fionas.inquiry_communications WHERE inquiry_id = ANY(:ids)
                )
                SELECT inquiry_id,
                       min(occurred_at) FILTER (
                           WHERE kind = 'CUSTOMER_EMAIL_RECEIVED'
                             AND (clearing_order IS NULL OR recorded_order > clearing_order)
                       ) AS unacknowledged_since,
                       max(occurred_at) FILTER (WHERE kind IN ('CUSTOMER_EMAIL_RECEIVED', 'STAFF_EMAIL_SENT')) AS latest_email_at
                FROM scoped GROUP BY inquiry_id
                """.trimIndent(),
            ).bindArray("ids", UUID::class.java, inquiryIds.map { it.value })
            .map { row, _ ->
                InquiryId(row.getObject("inquiry_id", UUID::class.java)) to
                    InquiryCommunicationAttention(
                        row.getObject("unacknowledged_since", OffsetDateTime::class.java)?.toInstant(),
                        row.getObject("latest_email_at", OffsetDateTime::class.java)?.toInstant(),
                    )
            }.list()
            .toMap()
    }
}
