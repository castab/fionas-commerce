package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.PaymentHistory
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/**
 * Reads the complete history of every payment ever allocated to any version of a Fiona
 * lineage, in commerce-runtime's order (received time, then payment id), from one REPEATABLE
 * READ transaction that also proves Fiona owns the lineage.
 *
 * Discovery is historical: a payment whose allocations here were fully unwound by refunds is
 * still listed. Each history is the whole payment, including allocations to other lineages,
 * because its reconciliation depends on all of them; nothing is filtered or recomputed. A
 * payment never allocated to this lineage is not listed. A lineage no inquiry owns is
 * [CommerceFailure.NotFound], even when commerce-runtime's ledger holds it.
 */
class ListFinancialDocumentPaymentHistories(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
) {
    operator fun invoke(documentId: UUID): List<PaymentHistory> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            associations.inquiryOf(transaction, documentId)
                ?: throw CommerceFailure.NotFound("Financial document $documentId was not found")
            ledger.paymentHistoriesForLineage(transaction, documentId)
        }
}
