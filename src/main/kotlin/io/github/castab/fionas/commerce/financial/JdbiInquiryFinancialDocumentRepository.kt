package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/** [InquiryFinancialDocumentRepository] on `fionas.inquiry_financial_documents`, through the transaction's JDBI handle. */
class JdbiInquiryFinancialDocumentRepository : InquiryFinancialDocumentRepository {
    override fun associate(
        transaction: Transaction,
        association: InquiryDocumentAssociation,
    ) {
        try {
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_financial_documents (document_id, inquiry_id, created_at, purpose)
                    VALUES (:documentId, :inquiryId, :createdAt, :purpose)
                    """.trimIndent(),
                ).bind("documentId", association.documentId)
                .bind("inquiryId", association.inquiryId.value)
                .bind("createdAt", association.createdAt)
                .bind("purpose", association.purpose.name)
                .execute()
        } catch (e: Exception) {
            if (e.isUniqueViolation()) {
                val message =
                    when (association.purpose) {
                        InquiryDocumentPurpose.INITIAL_ESTIMATE ->
                            "Inquiry already has an initial estimate or the document already belongs to an inquiry"
                        InquiryDocumentPurpose.RELATED -> "Financial document ${association.documentId} already belongs to an inquiry"
                    }
                throw CommerceFailure.Conflict(message, e)
            }
            throw e
        }
    }

    override fun initialEstimateOf(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): UUID? =
        transaction.handle
            .createQuery(
                """
                SELECT document_id FROM fionas.inquiry_financial_documents
                WHERE inquiry_id = :inquiryId AND purpose = 'INITIAL_ESTIMATE'
                """.trimIndent(),
            ).bind("inquiryId", inquiryId.value)
            .map { row, _ -> row.getObject("document_id", UUID::class.java) }
            .findOne()
            .orElse(null)

    override fun inquiryOf(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryId? = inquiryOf(transaction, documentId, "")

    override fun lockInquiryOf(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryId? = inquiryOf(transaction, documentId, " FOR UPDATE")

    private fun inquiryOf(
        transaction: Transaction,
        documentId: UUID,
        locking: String,
    ): InquiryId? =
        transaction.handle
            .createQuery("SELECT inquiry_id FROM fionas.inquiry_financial_documents WHERE document_id = :documentId$locking")
            .bind("documentId", documentId)
            .map { row, _ -> InquiryId(row.getObject("inquiry_id", UUID::class.java)) }
            .findOne()
            .orElse(null)

    override fun documentsOf(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): List<UUID> =
        transaction.handle
            .createQuery(
                """
                SELECT document_id FROM fionas.inquiry_financial_documents
                WHERE inquiry_id = :inquiryId
                ORDER BY created_at, document_id
                """.trimIndent(),
            ).bind("inquiryId", inquiryId.value)
            .map { row, _ -> row.getObject("document_id", UUID::class.java) }
            .list()
}
