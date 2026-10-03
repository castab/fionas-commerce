package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/** Retraction is allowed after any document change; the runtime appends an immutable withdrawal. */
class WithdrawDepositRequirement(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val associations: InquiryFinancialDocumentRepository,
) {
    data class Command(
        val documentId: UUID,
        val expectedRequirementRevision: DepositRequirementRevision,
    )

    operator fun invoke(command: Command): DepositRequirementVersion =
        transactor.inTransaction { transaction ->
            associations.lockInquiryOf(transaction, command.documentId)
                ?: throw CommerceFailure.NotFound("Financial document ${command.documentId} was not found")
            ledger.withdrawDepositRequirement(transaction, command.documentId, command.expectedRequirementRevision)
        }
}
