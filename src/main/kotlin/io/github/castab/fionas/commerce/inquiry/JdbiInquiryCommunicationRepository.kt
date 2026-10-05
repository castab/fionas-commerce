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
    ) {
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
            transaction.handle
                .createUpdate(
                    "INSERT INTO fionas.inquiry_communications (id, inquiry_id, kind, occurred_at, principal_kind, principal_id) " +
                        "VALUES (:id, :inquiry, :kind, :at, :principalKind, CAST(:principalId AS uuid))",
                ).bind("id", activity.id.value)
                .bind("inquiry", activity.inquiryId.value)
                .bind("kind", activity.kind.name)
                .bind("at", activity.occurredAt)
                .bind("principalKind", kind)
                .bindByType("principalId", actor, UUID::class.java)
                .execute()
        } catch (failure: Exception) {
            if (failure.isUniqueViolation()) throw CommerceFailure.Conflict("Communication activity already exists", failure)
            throw failure
        }
    }

    override fun findAll(
        transaction: Transaction,
        inquiryIds: Collection<InquiryId>,
    ): Map<InquiryId, List<InquiryCommunication>> {
        if (inquiryIds.isEmpty()) return emptyMap()
        return transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_communications WHERE inquiry_id IN (<ids>)")
            .bindList("ids", inquiryIds.map { it.value })
            .map { row, _ ->
                InquiryCommunication(
                    InquiryCommunicationId(row.getObject("id", UUID::class.java)),
                    InquiryId(row.getObject("inquiry_id", UUID::class.java)),
                    InquiryCommunicationKind.valueOf(row.getString("kind")),
                    row.getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
                    when (val kind = row.getString("principal_kind")) {
                        "USER" -> UserId(row.getObject("principal_id", UUID::class.java))
                        "SERVICE" -> ServiceId(row.getObject("principal_id", UUID::class.java))
                        null -> null
                        else -> error("Invalid communication principal kind: $kind")
                    },
                )
            }.list()
            .groupBy { it.inquiryId }
    }
}
