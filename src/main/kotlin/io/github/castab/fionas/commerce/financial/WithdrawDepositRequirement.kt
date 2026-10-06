package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/** Retraction appends a withdrawal only outside canonical published proposal history, which remains immutable after booking. */
class WithdrawDepositRequirement(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
    proposals: InquiryProposalRepository,
) {
    private val proposalDeposits = CanonicalProposalDepositPolicy(associations, proposals)

    data class Command(
        val documentId: UUID,
        val expectedRequirementRevision: DepositRequirementRevision,
    )

    operator fun invoke(command: Command): DepositRequirementVersion =
        transactor.inTransaction { transaction ->
            val inquiryId =
                associations.lockInquiryOf(transaction, command.documentId)
                    ?: throw CommerceFailure.NotFound("Financial document ${command.documentId} was not found")
            proposalDeposits.rejectStandaloneMutation(transaction, inquiryId, command.documentId)
            ledger.withdrawDepositRequirement(transaction, command.documentId, command.expectedRequirementRevision)
        }
}
