package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasPricingInputs

class ReviseInquiryQuoteProposal(
    private val transactor: Transactor,
    private val proposals: InquiryProposals,
) {
    data class Command(
        val inquiryId: InquiryId,
        val expectedDocumentVersion: Version,
        val expectedDepositRequirementRevision: DepositRequirementRevision,
        val pricingInputs: FionasPricingInputs,
        val terms: DepositTerms,
        val principalId: PrincipalId,
    )

    operator fun invoke(command: Command): IssuedInquiryProposal = transactor.inTransaction { proposals.reviseQuote(it, command) }
}
