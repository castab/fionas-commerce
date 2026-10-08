package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Reads every immutable snapshot of a Fiona lineage, oldest first, each with its line
 * authorship, in one REPEATABLE READ transaction. Historical snapshots are not
 * reconciled: an allocation made to a later snapshot cannot be reconciled against an
 * earlier one. A lineage no inquiry owns is [CommerceFailure.NotFound].
 */
class GetFinancialDocumentHistory(
    private val transactor: Transactor,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    operator fun invoke(documentId: UUID): InquiryFinancialDocumentHistory =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            documents.history(transaction, documentId)
        }
}
