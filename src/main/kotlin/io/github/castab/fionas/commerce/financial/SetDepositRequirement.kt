package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.inquiry.InquiryDeclineRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryDeclineRepository
import java.util.UUID

/**
 * Standalone approval rejects canonical lineages with proposal history and every lineage of a declined
 * inquiry; booked acceptance deposits remain immutable.
 */
class SetDepositRequirement(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    authorship: FinancialDocumentAuthorshipRepository,
    proposals: InquiryProposalRepository,
    declines: InquiryDeclineRepository = JdbiInquiryDeclineRepository(),
) {
    data class Command(
        val documentId: UUID,
        val expectedDocumentVersion: Version,
        val expectedRequirementRevision: DepositRequirementRevision?,
        val terms: DepositTerms,
    )

    private val documents = FionaFinancialDocuments(ledger, associations, authorship)
    private val proposalDeposits = CanonicalProposalDepositPolicy(associations, proposals)
    private val declined = DeclinedInquiryPolicy(associations, declines)

    operator fun invoke(command: Command): FinancialLineageView =
        transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, command.documentId, command.expectedDocumentVersion)
            proposalDeposits.rejectStandaloneMutation(transaction, current.inquiryId, command.documentId)
            declined.rejectProgression(transaction, current.inquiryId, current.document.id)
            documents.approveDeposit(transaction, current.document, command.terms, command.expectedRequirementRevision)
            ledger.financialLineages(transaction, listOf(command.documentId)).single()
        }
}
