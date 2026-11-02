package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Instant
import java.util.UUID

enum class InvoiceAmendmentKind { INITIAL, AMENDED }

/** Immutable customer-facing Invoice identity. INITIAL is automatic booking, not a staff approval. */
data class InquiryInvoiceAmendment(
    val id: UUID,
    val inquiryId: InquiryId,
    val invoiceRevision: Int,
    val documentReference: FinancialDocumentReference,
    val servicePlanRevision: Int?,
    val kind: InvoiceAmendmentKind,
    val approvedAt: Instant,
    val approvedBy: UserId?,
    val financialChange: Boolean,
    val servicePlanChange: Boolean,
) {
    init {
        require(invoiceRevision > 0)
        require(documentReference.version.number >= 3)
        require(servicePlanRevision == null || servicePlanRevision > 0)
        when (kind) {
            InvoiceAmendmentKind.INITIAL -> {
                require(approvedBy == null && !financialChange && !servicePlanChange)
                require(invoiceRevision == documentReference.version.number)
            }
            InvoiceAmendmentKind.AMENDED -> require(approvedBy != null && (financialChange || servicePlanChange))
        }
    }
}

/** Shared by both canonical amendment paths. The caller already holds the association lock. */
internal fun InquiryInvoiceAmendmentRepository.recordAmendment(
    transaction: Transaction,
    id: UUID,
    inquiryId: InquiryId,
    reviewed: FinancialDocument.Invoice,
    invoice: FinancialDocument.Invoice,
    plan: InquiryServicePlan?,
    approvedAt: Instant,
    approvedBy: UserId,
    servicePlanChange: Boolean,
): InquiryInvoiceAmendment {
    val current = checkNotNull(latest(transaction, inquiryId)) { "Booked inquiry has no Invoice business identity" }
    check(current.documentReference == reviewed.reference) { "Invoice business and financial histories disagree" }
    return InquiryInvoiceAmendment(
        id,
        inquiryId,
        Math.incrementExact(current.invoiceRevision),
        invoice.reference,
        plan?.revision,
        InvoiceAmendmentKind.AMENDED,
        approvedAt,
        approvedBy,
        reviewed.reference != invoice.reference,
        servicePlanChange,
    ).also { append(transaction, it) }
}
