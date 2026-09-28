package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Issues the latest quote of a Fiona lineage as an invoice, without repricing: the invoice
 * is a new immutable snapshot with the same lines, and Fiona records the quote's exact
 * pricing source again for it. There is no estimate-to-invoice shortcut.
 *
 * In one runtime transaction: the lineage must belong to an inquiry, its latest version must
 * be the one the caller acted on ([CommerceFailure.Conflict] otherwise), the runtime appends
 * the invoice (`CommerceFailure.IllegalTransition` when the latest snapshot is not a quote),
 * and the pricing source is copied to the new version. Payments applied to earlier
 * snapshots stay attached to them and still count toward the lineage's settlement.
 */
class IssueInvoice(
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
            val invoice = ledger.issueInvoice(transaction, documentId)
            pricingSources.copy(transaction, current.document.reference, invoice.reference)
            documents.current(transaction, current.inquiryId, documentId)
        }
}
