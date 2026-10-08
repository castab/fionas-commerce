package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Manually issues the latest quote of a RELATED Fiona lineage as an invoice, without repricing: the invoice
 * is a new immutable snapshot with the same concrete lines. Fiona carries the line authorship
 * forward when present. There is no estimate-to-invoice shortcut.
 *
 * In one runtime transaction: the lineage must belong to an inquiry, its latest version must
 * be the one the caller acted on ([CommerceFailure.Conflict] otherwise), the runtime appends
 * the invoice (`CommerceFailure.IllegalTransition` when the latest snapshot is not a quote),
 * and the line authorship is carried to the new version. Payments applied to earlier
 * snapshots stay attached to them and still count toward the lineage's settlement.
 * Canonical INITIAL_ESTIMATE lineages reject manual invoicing; their deposit-satisfaction
 * booking policy reuses the same transaction-taking Invoice mechanics.
 */
class IssueInvoice(
    private val transactor: Transactor,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    operator fun invoke(
        documentId: UUID,
        expectedVersion: Version,
    ): InquiryFinancialDocument =
        transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, documentId, expectedVersion)
            if (documents.isCanonical(transaction, current)) {
                throw CommerceFailure.IllegalTransition(
                    "The canonical inquiry lineage is invoiced automatically when its active deposit is satisfied",
                )
            }
            documents.invoice(transaction, current)
            documents.describeLocked(transaction, current.inquiryId, documentId)
        }
}
