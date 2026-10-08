package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.inquiry.InquiryId

class IssueInquiryProposal(
    private val transactor: Transactor,
    private val proposals: InquiryProposals,
) {
    data class Command(
        val inquiryId: InquiryId,
        val expectedDocumentVersion: Version,
        val terms: DepositTerms,
        val principalId: PrincipalId,
        /** Absent: issue the Estimate unchanged, as before. Present: atomically publish the reviewed composition. */
        val composition: ReviewedQuoteComposition? = null,
    )

    operator fun invoke(command: Command): IssuedInquiryProposal = transactor.inTransaction { proposals.issue(it, command) }
}
