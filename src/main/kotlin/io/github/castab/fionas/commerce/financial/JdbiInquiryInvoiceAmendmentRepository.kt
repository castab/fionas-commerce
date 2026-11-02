package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.isUniqueViolation
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

class JdbiInquiryInvoiceAmendmentRepository : InquiryInvoiceAmendmentRepository {
    override fun append(
        transaction: Transaction,
        amendment: InquiryInvoiceAmendment,
    ) {
        transaction.handle
            .createQuery(
                "SELECT document_id FROM fionas.inquiry_financial_documents " +
                    "WHERE inquiry_id = :inquiry AND document_id = :document AND purpose = 'INITIAL_ESTIMATE' FOR UPDATE",
            ).bind("inquiry", amendment.inquiryId.value)
            .bind("document", amendment.documentReference.id)
            .mapTo(UUID::class.java)
            .findOne()
            .orElseThrow { CommerceFailure.NotFound("Canonical inquiry financial lineage was not found") }
        val previous = latest(transaction, amendment.inquiryId)
        check(
            if (previous == null) {
                amendment.kind == InvoiceAmendmentKind.INITIAL
            } else {
                amendment.kind == InvoiceAmendmentKind.AMENDED &&
                    amendment.invoiceRevision == Math.incrementExact(previous.invoiceRevision)
            },
        ) { "Invoice business revisions must be consecutive" }
        try {
            transaction.handle
                .createUpdate(
                    """
                    INSERT INTO fionas.inquiry_invoice_amendments
                        (id, inquiry_id, invoice_revision, document_id, document_version, service_plan_revision,
                         kind, approved_at, approved_by, financial_change, service_plan_change)
                    VALUES (:id, :inquiry, :revision, :document, :version, :plan, :kind, :at, CAST(:by AS uuid), :financial, :service)
                    """.trimIndent(),
                ).bind("id", amendment.id)
                .bind("inquiry", amendment.inquiryId.value)
                .bind("revision", amendment.invoiceRevision)
                .bind("document", amendment.documentReference.id)
                .bind("version", amendment.documentReference.version.number)
                .bind("plan", amendment.servicePlanRevision)
                .bind("kind", amendment.kind.name)
                .bind("at", amendment.approvedAt)
                .bindByType("by", amendment.approvedBy?.value, UUID::class.java)
                .bind("financial", amendment.financialChange)
                .bind("service", amendment.servicePlanChange)
                .execute()
        } catch (failure: Exception) {
            if (failure.isUniqueViolation()) throw CommerceFailure.Conflict("Invoice business revision already exists", failure)
            throw failure
        }
    }

    override fun latest(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryInvoiceAmendment? =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.inquiry_invoice_amendments WHERE inquiry_id = :inquiry ORDER BY invoice_revision DESC LIMIT 1",
            ).bind("inquiry", inquiryId.value)
            .map { row, _ -> restore(row) }
            .findOne()
            .orElse(null)

    override fun history(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): List<InquiryInvoiceAmendment> =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.inquiry_invoice_amendments WHERE inquiry_id = :inquiry ORDER BY invoice_revision",
            ).bind("inquiry", inquiryId.value)
            .map { row, _ -> restore(row) }
            .list()

    private fun restore(row: ResultSet) =
        InquiryInvoiceAmendment(
            row.getObject("id", UUID::class.java),
            InquiryId(row.getObject("inquiry_id", UUID::class.java)),
            row.getInt("invoice_revision"),
            FinancialDocumentReference(row.getObject("document_id", UUID::class.java), Version.of(row.getInt("document_version"))),
            row.getObject("service_plan_revision", Int::class.javaObjectType),
            InvoiceAmendmentKind.valueOf(row.getString("kind")),
            row.getObject("approved_at", OffsetDateTime::class.java).toInstant(),
            row.getObject("approved_by", UUID::class.java)?.let(::UserId),
            row.getBoolean("financial_change"),
            row.getBoolean("service_plan_change"),
        )
}
