package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import java.util.UUID

/** All inquiries in one unlocked REPEATABLE_READ snapshot, using only set-based reads. */
class ReadInquiryOperationalStates(
    private val transactor: Transactor,
    private val inquiries: InquiryRepository,
    private val associations: InquiryFinancialDocumentRepository,
    ledger: FinancialLedger,
    private val fulfillment: InquiryFulfillmentRepository,
    private val readLineages: (Transaction, Collection<UUID>) -> List<FinancialLineageView> = ledger::financialLineages,
) {
    operator fun invoke(): InquiryOperationalSnapshot =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val ids = inquiries.ids(transaction)
            val canonical = associations.initialEstimates(transaction)
            check(canonical.keys == ids) { "Inquiry population does not match canonical initial Estimate relationships" }
            check(canonical.values.toSet().size == canonical.size) { "Canonical financial lineage belongs to multiple inquiries" }
            val views = readLineages(transaction, canonical.values)
            val financial = views.associateBy { it.latestVersion.document.id }
            check(financial.size == views.size && financial.keys == canonical.values.toSet()) {
                "Canonical financial lineages are incomplete or corrupt"
            }
            val facts = fulfillment.findAll(transaction, ids)
            check(ids.containsAll(facts.keys)) { "Fulfillment returned an inquiry outside the operational population" }
            InquiryOperationalSnapshot(
                canonical.map { (inquiry, document) -> InquiryOperationalState(inquiry, financial.getValue(document), facts[inquiry]) },
            )
        }
}
