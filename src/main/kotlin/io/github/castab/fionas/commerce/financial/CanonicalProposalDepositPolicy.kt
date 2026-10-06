package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.UUID

/** Published acceptance history controls deposit eligibility, including after booking. */
internal class CanonicalProposalDepositPolicy(
    private val associations: InquiryFinancialDocumentRepository,
    private val proposals: InquiryProposalRepository,
) {
    /** Caller holds the lineage's association lock; reads share its mutation transaction. */
    fun rejectStandaloneMutation(
        transaction: Transaction,
        inquiryId: InquiryId,
        documentId: UUID,
    ) {
        if (associations.initialEstimateOf(transaction, inquiryId) == documentId &&
            proposals.latest(transaction, inquiryId) != null
        ) {
            throw CommerceFailure.IllegalTransition(
                "Canonical proposal deposit history cannot be changed through the standalone deposit operation",
            )
        }
    }
}
