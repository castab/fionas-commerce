package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/** Approves explicit terms on the exact latest Fiona Quote/Invoice; never causes workflow transitions. */
class SetDepositRequirement(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    pricingSources: FinancialDocumentPricingRepository,
) {
    data class Command(
        val documentId: UUID,
        val expectedDocumentVersion: Version,
        val expectedRequirementRevision: DepositRequirementRevision?,
        val terms: DepositTerms,
    )

    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    operator fun invoke(command: Command): FinancialLineageView =
        transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, command.documentId, command.expectedDocumentVersion)
            current.document.requirePaymentDestination()
            if (command.terms is DepositTerms.Fixed) {
                paymentMoney(command.terms.amount.amount, command.terms.amount.currency)
            }
            ledger.activateDepositRequirement(
                transaction,
                command.documentId,
                command.expectedDocumentVersion,
                command.terms,
                command.expectedRequirementRevision,
            )
            ledger.financialLineages(transaction, listOf(command.documentId)).single()
        }
}
