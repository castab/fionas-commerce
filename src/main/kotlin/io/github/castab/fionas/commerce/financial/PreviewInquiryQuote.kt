package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryId

/**
 * The write-free preview of an initial canonical Quote composition: exactly what
 * [IssueInquiryProposal] would publish for the same intent, including the resolved deposit and
 * the review token its approval must present.
 *
 * One unlocked REPEATABLE READ snapshot reads the canonical Estimate, the inquiry, the optional
 * pricing source and the current catalog; nothing is written, even on failure. The same
 * eligibility as issuance applies: the canonical lineage at its exact reviewed Estimate version
 * with no proposal or deposit history.
 */
class PreviewInquiryQuote(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    private val proposals: InquiryProposalRepository,
    private val compositions: InquiryQuoteComposition,
) {
    data class Command(
        val inquiryId: InquiryId,
        val expectedDocumentVersion: Version,
        val composition: QuoteComposition,
        val terms: DepositTerms,
    )

    operator fun invoke(command: Command): ComposedQuote =
        transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
            val documentId =
                associations.initialEstimateOf(transaction, command.inquiryId)
                    ?: throw CommerceFailure.NotFound("Canonical inquiry financial lineage was not found")
            val estimate = ledger.latest(transaction, documentId)
            if (estimate.version != command.expectedDocumentVersion) {
                throw CommerceFailure.Conflict(
                    "Financial document $documentId is at ${estimate.version}, not the expected " +
                        "${command.expectedDocumentVersion}; reload it and retry",
                )
            }
            if (estimate !is FinancialDocument.Estimate) throw CommerceFailure.IllegalTransition("Initial proposal requires an Estimate")
            val view = ledger.financialLineages(transaction, listOf(documentId)).single()
            requireCoherentProposal(command.inquiryId, proposals.latest(transaction, command.inquiryId), view)
            compositions.compose(transaction, command.inquiryId, estimate, command.composition, command.terms)
        }
}
