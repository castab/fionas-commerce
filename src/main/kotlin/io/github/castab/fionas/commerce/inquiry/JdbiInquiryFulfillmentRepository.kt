package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import java.time.OffsetDateTime
import java.util.UUID

class JdbiInquiryFulfillmentRepository : InquiryFulfillmentRepository {
    override fun find(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryFulfillment? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_fulfillment WHERE inquiry_id = :id")
            .bind("id", inquiryId.value)
            .map { row, _ ->
                fun actor(prefix: String): PrincipalId =
                    when (val kind = row.getString("${prefix}_by_kind")) {
                        "USER" -> UserId(row.getObject("${prefix}_by_id", UUID::class.java))
                        "SERVICE" -> ServiceId(row.getObject("${prefix}_by_id", UUID::class.java))
                        else -> error("Invalid fulfillment principal kind: $kind")
                    }
                InquiryFulfillment(
                    InquiryMilestone(row.getObject("served_at", OffsetDateTime::class.java).toInstant(), actor("served")),
                    row.getObject("closed_at", OffsetDateTime::class.java)?.let { InquiryMilestone(it.toInstant(), actor("closed")) },
                )
            }.findOne()
            .orElse(null)

    override fun serve(
        transaction: Transaction,
        inquiryId: InquiryId,
        milestone: InquiryMilestone,
    ) {
        val (kind, id) = actor(milestone.principalId)
        try {
            transaction.handle
                .createUpdate(
                    "INSERT INTO fionas.inquiry_fulfillment (inquiry_id, served_at, served_by_kind, served_by_id) VALUES (:inquiry, :at, :kind, :actor)",
                ).bind("inquiry", inquiryId.value)
                .bind("at", milestone.occurredAt)
                .bind("kind", kind)
                .bind("actor", id)
                .execute()
        } catch (failure: Exception) {
            if (failure.isUniqueViolation()) throw CommerceFailure.Conflict("Inquiry has already been served", failure)
            throw failure
        }
    }

    override fun close(
        transaction: Transaction,
        inquiryId: InquiryId,
        milestone: InquiryMilestone,
    ) {
        val (kind, id) = actor(milestone.principalId)
        val changed =
            transaction.handle
                .createUpdate(
                    "UPDATE fionas.inquiry_fulfillment SET closed_at = :at, closed_by_kind = :kind, closed_by_id = :actor WHERE inquiry_id = :inquiry AND closed_at IS NULL",
                ).bind("inquiry", inquiryId.value)
                .bind("at", milestone.occurredAt)
                .bind("kind", kind)
                .bind("actor", id)
                .execute()
        if (changed != 1) throw CommerceFailure.Conflict("Inquiry is not served or is already closed")
    }

    private fun actor(principal: PrincipalId): Pair<String, UUID> =
        when (principal) {
            is UserId -> "USER" to principal.value
            is ServiceId -> "SERVICE" to principal.value
        }
}
