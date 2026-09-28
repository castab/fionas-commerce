package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Issues the latest estimate of a Fiona lineage as a quote, without repricing: the quote is
 * a new immutable snapshot with the same lines, and Fiona records the estimate's exact
 * pricing source again for it.
 *
 * In one runtime transaction: the lineage must belong to an inquiry, its latest version must
 * be the one the caller acted on ([CommerceFailure.Conflict] otherwise), the runtime appends
 * the quote (`CommerceFailure.IllegalTransition` when the latest snapshot is not an
 * estimate), and the pricing source is copied to the new version.
 */
class IssueQuote(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    private val pricingSources: FinancialDocumentPricingRepository,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    operator fun invoke(
        documentId: UUID,
        expectedVersion: Version,
    ): InquiryFinancialDocument =
        transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, documentId, expectedVersion)
            val quote = ledger.issueQuote(transaction, documentId)
            pricingSources.copy(transaction, current.document.reference, quote.reference)
            documents.current(transaction, current.inquiryId, documentId)
        }
}
