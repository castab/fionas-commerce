package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/** Fiona ownership alongside objective runtime financial facts; no pricing metadata or workflow interpretation. */
data class InquiryFinancialLineage(
    val inquiryId: InquiryId,
    val financial: FinancialLineageView,
)

/** One set-based ownership lookup and one runtime bulk read, in request order and without locks. */
class QueryFinancialLineages(
    private val transactor: Transactor,
    ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val readLineages: (Transaction, Collection<UUID>) -> List<FinancialLineageView> =
        ledger::financialLineages,
) {
    data class Command(
        val documentIds: List<UUID>,
    )

    operator fun invoke(command: Command): List<InquiryFinancialLineage> =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val ids = command.documentIds
            if (ids.toSet().size != ids.size) throw CommerceFailure.ValidationFailed("Financial lineage ids must not repeat")
            val owners = associations.inquiriesOf(transaction, ids)
            ids.firstOrNull { it !in owners }?.let { throw CommerceFailure.NotFound("Financial document $it was not found") }
            readLineages(transaction, ids).map { InquiryFinancialLineage(owners.getValue(it.latestVersion.document.id), it) }
        }
}
