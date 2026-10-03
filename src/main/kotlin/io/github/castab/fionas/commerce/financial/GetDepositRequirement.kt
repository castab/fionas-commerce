package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/** Current requirement and runtime-derived satisfaction from one coherent, unlocked snapshot. */
class GetDepositRequirement(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
) {
    operator fun invoke(documentId: UUID): FinancialLineageView =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            associations.inquiryOf(transaction, documentId)
                ?: throw CommerceFailure.NotFound("Financial document $documentId was not found")
            ledger.financialLineages(transaction, listOf(documentId)).single()
        }
}
