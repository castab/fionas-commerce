package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryRepository

/**
 * Reads the financial-document lineages an inquiry owns, oldest first, each at its latest
 * snapshot with its pricing source and current settlement, from one REPEATABLE READ
 * transaction snapshot.
 * An unknown inquiry is [CommerceFailure.NotFound]; one without documents has none.
 */
class ListInquiryFinancialDocuments(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    pricingSources: FinancialDocumentPricingRepository,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    operator fun invoke(inquiryId: InquiryId): List<InquiryFinancialDocument> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            inquiries.findById(transaction, inquiryId) ?: throw CommerceFailure.NotFound("Inquiry ${inquiryId.value} was not found")
            associations.documentsOf(transaction, inquiryId).map { documents.current(transaction, it) }
        }
}
