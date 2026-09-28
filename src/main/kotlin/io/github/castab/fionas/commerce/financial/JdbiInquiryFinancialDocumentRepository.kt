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
                    INSERT INTO fionas.inquiry_financial_documents (document_id, inquiry_id, created_at)
                    VALUES (:documentId, :inquiryId, :createdAt)
                    """.trimIndent(),
                ).bind("documentId", association.documentId)
                .bind("inquiryId", association.inquiryId.value)
                .bind("createdAt", association.createdAt)
                .execute()
        } catch (e: Exception) {
            if (e.isUniqueViolation()) {
                throw CommerceFailure.Conflict("Financial document ${association.documentId} already belongs to an inquiry", e)
            }
            throw e
        }
    }

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
