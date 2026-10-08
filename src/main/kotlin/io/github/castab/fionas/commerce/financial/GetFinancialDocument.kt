package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Reads the latest snapshot of a Fiona lineage, its line authorship, and its current
 * settlement, in one REPEATABLE READ transaction so all three describe one point-in-time
 * lineage state. A lineage no inquiry owns is
 * [CommerceFailure.NotFound], even when commerce-runtime's ledger holds it.
 */
class GetFinancialDocument(
    private val transactor: Transactor,
    ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, authorship)

    operator fun invoke(documentId: UUID): InquiryFinancialDocument =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            read(transaction, documentId)
        }

    /**
     * Reads the current financial view inside the caller-owned transaction without opening another.
     * The caller chooses isolation; [invoke] and ReadStaffRequest use REPEATABLE READ.
     */
    internal fun read(
        transaction: Transaction,
        documentId: UUID,
    ): InquiryFinancialDocument = documents.current(transaction, documentId)
}
