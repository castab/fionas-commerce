package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.util.UUID

/** Retraction appends an immutable withdrawal; canonical Quotes require a future explicit cancellation workflow. */
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
            val inquiryId =
                associations.lockInquiryOf(transaction, command.documentId)
                    ?: throw CommerceFailure.NotFound("Financial document ${command.documentId} was not found")
            if (associations.initialEstimateOf(transaction, inquiryId) == command.documentId &&
                ledger.latest(transaction, command.documentId) is FinancialDocument.Quote
            ) {
                throw CommerceFailure.IllegalTransition("Canonical proposal withdrawal requires an explicit cancellation workflow")
            }
            ledger.withdrawDepositRequirement(transaction, command.documentId, command.expectedRequirementRevision)
        }
}
