package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.staff.UserId
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** [InquiryDeclineRepository] on `fionas.inquiry_declines`; the decider references the runtime's published `commerce.users`. */
class JdbiInquiryDeclineRepository : InquiryDeclineRepository {
    override fun find(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryDecline? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_declines WHERE inquiry_id = :id")
            .bind("id", inquiryId.value)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    override fun findAll(
        transaction: Transaction,
        inquiryIds: Collection<InquiryId>,
    ): Map<InquiryId, InquiryDecline> {
        if (inquiryIds.isEmpty()) return emptyMap()
        return transaction.handle
            .createQuery("SELECT * FROM fionas.inquiry_declines WHERE inquiry_id IN (<ids>)")
            .bindList("ids", inquiryIds.map { it.value })
            .map { row, _ -> restore(row) }
            .list()
            .associateBy { it.inquiryId }
    }

    override fun insert(
        transaction: Transaction,
        decline: InquiryDecline,
    ) {
        try {
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_declines (inquiry_id, document_id, document_version, declined_at, declined_by, reason)
                    VALUES (:inquiry, :document, :version, :at, :by, :reason)
                    """.trimIndent(),
                ).bind("inquiry", decline.inquiryId.value)
                .bind("document", decline.estimate.id)
                .bind("version", decline.estimate.version.number)
                .bind("at", decline.declinedAt)
                .bind("by", decline.declinedBy.value)
                .bind("reason", decline.reason.value)
                .execute()
        } catch (failure: Exception) {
            if (failure.isUniqueViolation()) throw CommerceFailure.Conflict("Inquiry has already been declined", failure)
            throw failure
        }
    }

    private fun restore(row: ResultSet) =
        InquiryDecline(
            InquiryId(row.getObject("inquiry_id", UUID::class.java)),
            FinancialDocumentReference(row.getObject("document_id", UUID::class.java), Version.of(row.getInt("document_version"))),
            row.getObject("declined_at", OffsetDateTime::class.java).toInstant(),
            UserId(row.getObject("declined_by", UUID::class.java)),
            DeclineReason(row.getString("reason")),
        )
}
