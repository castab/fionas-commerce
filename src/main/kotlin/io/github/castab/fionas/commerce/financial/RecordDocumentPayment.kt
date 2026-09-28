package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.ExternalPaymentReference
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.persistence.Transactor
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Records money Fiona received and applies all of it to one financial document: "we received
 * this payment, and the whole payment is for this document."
 *
 * Fiona's payment policy for this slice:
 *
 * - The payment is applied to the lineage's latest snapshot, and the caller names that exact
 *   version ([Command.documentVersion]); a lineage that has moved on is a
 *   [CommerceFailure.Conflict], so money never lands on a snapshot its caller never saw.
 * - Only a quote (a deposit) or an invoice accepts a payment, never an estimate
 *   ([CommerceFailure.InvariantViolated]).
 * - The payment's currency is the document's, and its amount has at most the currency's
 *   minor-unit digits. Over-application is allowed: a negative balance is a derived fact.
 *
 * In one runtime transaction, commerce-runtime's ledger records the `PaymentRecord` and its
 * `PaymentAllocation` to that exact snapshot, and the lineage's settlement is derived again.
 * The allocation stays attached to that snapshot when the document later advances. A
 * duplicate external payment reference is the runtime's [CommerceFailure.Conflict].
 */
class RecordDocumentPayment(
    private val transactor: Transactor,
    private val ledger: FinancialLedger,
    associations: InquiryFinancialDocumentRepository,
    pricingSources: FinancialDocumentPricingRepository,
    private val clock: Clock,
    private val newPaymentId: () -> UUID = UUID::randomUUID,
    private val newAllocationId: () -> UUID = UUID::randomUUID,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, pricingSources)

    /**
     * A payment to record against [documentId] at [documentVersion]. [receivedAt] defaults to
     * now, from the injected clock.
     */
    data class Command(
        val documentId: UUID,
        val documentVersion: Version,
        val amount: BigDecimal,
        val method: PaymentMethod,
        val receivedAt: Instant?,
        val externalReference: ExternalPaymentReference?,
    )

    operator fun invoke(command: Command): RecordedPayment {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, command.documentId, command.documentVersion)
            val document = current.document
            if (document is FinancialDocument.Estimate) {
                throw CommerceFailure.InvariantViolated(
                    "Financial document ${document.id} is an estimate; payments are accepted against a quote or an invoice",
                )
            }
            val currency = document.currency
            if (command.amount.stripTrailingZeros().scale() > currency.defaultFractionDigits) {
                throw CommerceFailure.ValidationFailed(
                    "A ${currency.currencyCode} payment amount has at most ${currency.defaultFractionDigits} decimal places",
                )
            }
            val amount = Money(command.amount, currency)
            val payment =
                validating { PaymentRecord(newPaymentId(), amount, command.method, command.receivedAt ?: now, command.externalReference) }
            val allocation =
                ledger.recordPaymentAgainstDocument(transaction, payment, newAllocationId(), document.reference, payment.amount, now)
            RecordedPayment(payment, allocation, documents.describeLocked(transaction, current.inquiryId, command.documentId))
        }
    }
}
