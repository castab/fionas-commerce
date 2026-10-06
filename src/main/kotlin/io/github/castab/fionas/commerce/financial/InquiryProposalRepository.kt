package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId

/** Append-only publication history in the caller's transaction. Append locks the owning association. */
interface InquiryProposalRepository {
    fun append(
        transaction: Transaction,
        proposal: InquiryProposal,
    )

    fun latest(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): InquiryProposal?

    fun history(
        transaction: Transaction,
        inquiryId: InquiryId,
    ): List<InquiryProposal>

    fun find(
        transaction: Transaction,
        id: InquiryProposalId,
    ): InquiryProposal?
}
