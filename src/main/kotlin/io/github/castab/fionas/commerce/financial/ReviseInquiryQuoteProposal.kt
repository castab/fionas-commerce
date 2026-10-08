package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId

class ReviseInquiryQuoteProposal(
    private val transactor: Transactor,
    private val proposals: InquiryProposals,
) {
    data class Command(
        val inquiryId: InquiryId,
        val expectedDocumentVersion: Version,
        val expectedDepositRequirementRevision: DepositRequirementRevision,
        /** The complete revised final lines, committed by [issuedBy]. */
        val lines: LineProposal,
        val terms: DepositTerms,
        val issuedBy: UserId,
        val servicePlan: ProposedServicePlan? = null,
    )

    operator fun invoke(command: Command): IssuedInquiryProposal = transactor.inTransaction { proposals.reviseQuote(it, command) }
}
