package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.payment.ExternalRefundReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentReconciliation
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.financial.RecordedRefund
import io.github.castab.commerce.runtime.financial.RefundAllocationPortion
import io.github.castab.commerce.runtime.persistence.Transactor
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.UUID

/** A recorded refund and the resulting derived history of its payment. */
data class ReconciledRefund(
    val recorded: RecordedRefund,
    val reconciliation: PaymentReconciliation,
)

/** Records returned money and the caller's explicit unwinds in one runtime transaction. */
class RecordRefund(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val clock: Clock,
    private val newRefundId: () -> UUID = UUID::randomUUID,
    private val newRefundAllocationId: () -> UUID = UUID::randomUUID,
) {
    data class AllocationCommand(
        val paymentAllocationId: UUID,
        val amount: BigDecimal,
    )

    data class Command(
        val paymentId: UUID,
        val amount: BigDecimal,
        val currency: Currency,
        val method: PaymentMethod,
        val refundedAt: Instant?,
        val externalReference: ExternalRefundReference?,
        val allocations: List<AllocationCommand>,
    )

    operator fun invoke(command: Command): ReconciledRefund {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val recorded =
                ledger.recordRefund(
                    transaction = transaction,
                    paymentId = command.paymentId,
                    refundId = newRefundId(),
                    amount = paymentMoney(command.amount, command.currency),
                    method = command.method,
                    refundedAt = command.refundedAt ?: now,
                    externalReference = command.externalReference,
                    allocations =
                        command.allocations.map {
                            RefundAllocationPortion(
                                newRefundAllocationId(),
                                it.paymentAllocationId,
                                paymentMoney(it.amount, command.currency),
                                now,
                            )
                        },
                )
            ReconciledRefund(recorded, ledger.reconcilePayment(transaction, command.paymentId))
        }
    }
}
