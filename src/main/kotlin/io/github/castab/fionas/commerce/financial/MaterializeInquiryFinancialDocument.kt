package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Persists already-materialized financial lines and their inquiry relationship in the caller's
 * transaction. No catalog, pricing inputs, or pricing-source persistence crosses this boundary.
 */
class MaterializeInquiryFinancialDocument(
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val clock: Clock,
    private val newDocumentId: () -> UUID = UUID::randomUUID,
) {
    fun create(
        transaction: Transaction,
        inquiryId: InquiryId,
        stage: CreateInquiryFinancialDocument.Stage,
        lines: List<LineItem>,
        purpose: InquiryDocumentPurpose = InquiryDocumentPurpose.RELATED,
    ): FinancialDocument {
        require(purpose != InquiryDocumentPurpose.INITIAL_ESTIMATE || stage == CreateInquiryFinancialDocument.Stage.ESTIMATE) {
            "An initial estimate must start at Estimate"
        }
        val id = newDocumentId()
        val first =
            when (stage) {
                CreateInquiryFinancialDocument.Stage.ESTIMATE -> FinancialDocument.Estimate.create(id, lines)
                CreateInquiryFinancialDocument.Stage.QUOTE -> FinancialDocument.Quote.create(id, lines)
                CreateInquiryFinancialDocument.Stage.INVOICE -> FinancialDocument.Invoice.create(id, lines)
            }
        val created = ledger.create(transaction, first)
        associations.associate(
            transaction,
            InquiryDocumentAssociation(inquiryId, created.id, clock.instant().truncatedTo(ChronoUnit.MICROS), purpose),
        )
        return created
    }
}
