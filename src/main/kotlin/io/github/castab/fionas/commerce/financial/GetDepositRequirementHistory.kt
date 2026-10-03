package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/** Immutable Active/Withdrawn revisions, oldest first, with no historical satisfaction. */
class GetDepositRequirementHistory(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
) {
    operator fun invoke(documentId: UUID): List<DepositRequirementVersion> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            associations.inquiryOf(transaction, documentId)
                ?: throw CommerceFailure.NotFound("Financial document $documentId was not found")
            ledger.depositRequirementHistory(transaction, documentId)
        }
}
