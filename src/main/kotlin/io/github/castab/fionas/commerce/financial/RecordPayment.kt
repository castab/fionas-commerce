package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.Transactor
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.UUID

/** Records money received even when its document allocation is not yet known. */
class RecordPayment(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    private val clock: Clock,
    private val newPaymentId: () -> UUID = UUID::randomUUID,
) {
    data class Command(
        val amount: BigDecimal,
        val currency: Currency,
        val method: PaymentMethod,
        val receivedAt: Instant?,
        val externalReference: ExternalPaymentReference?,
    )

    operator fun invoke(command: Command): PaymentRecord {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val amount = paymentMoney(command.amount, command.currency)
            val payment =
                validating { PaymentRecord(newPaymentId(), amount, command.method, command.receivedAt ?: now, command.externalReference) }
            ledger.recordPayment(transaction, payment)
        }
    }
}
