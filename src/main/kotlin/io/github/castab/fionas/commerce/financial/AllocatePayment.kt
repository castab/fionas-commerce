package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentAllocation
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import java.math.BigDecimal
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** An allocation and the exact lineage's derived settlement after it. */
data class AllocatedPayment(
    val allocation: PaymentAllocation,
    val document: InquiryFinancialDocument,
)

/** Allocates a recorded payment to a Fiona-owned latest Quote or Invoice snapshot. */
class AllocatePayment(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    pricingSources: FinancialDocumentPricingRepository,
    private val clock: Clock,
    private val newAllocationId: () -> UUID = UUID::randomUUID,
) {
    data class Command(
        val paymentId: UUID,
        val documentId: UUID,
        val documentVersion: Version,
        val amount: BigDecimal,
    )

    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    operator fun invoke(command: Command): AllocatedPayment {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, command.documentId, command.documentVersion)
            val document = current.document
            document.requirePaymentDestination()
            if (document is FinancialDocument.Quote && documents.isCanonical(transaction, current)) {
                throw CommerceFailure.IllegalTransition("Canonical deposits require one exact payment against the current proposal")
            }
            val amount = paymentMoney(command.amount, document.currency)
            val allocation =
                ledger.allocatePayment(transaction, command.paymentId, newAllocationId(), document.reference, amount, now)
            val view = documents.bookIfDepositSatisfied(transaction, current)
            val result =
                view?.let { documents.describeLocked(transaction, current.inquiryId, it) }
                    ?: documents.describeLocked(transaction, current.inquiryId, document.id)
            AllocatedPayment(allocation, result)
        }
    }
}
