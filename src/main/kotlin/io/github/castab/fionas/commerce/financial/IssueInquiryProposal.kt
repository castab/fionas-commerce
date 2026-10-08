package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId

class IssueInquiryProposal(
    private val transactor: Transactor,
    private val proposals: InquiryProposals,
) {
    data class Command(
        val inquiryId: InquiryId,
        val expectedDocumentVersion: Version,
        val terms: DepositTerms,
        /** The verified staff user publishing the proposal, from authentication, never from the request body. */
        val issuedBy: UserId,
        /** Absent: issue the Estimate unchanged. Present: atomically publish exactly the reviewed final lines. */
        val composition: ReviewedQuoteComposition? = null,
    )

    operator fun invoke(command: Command): IssuedInquiryProposal = transactor.inTransaction { proposals.issue(it, command) }
}
