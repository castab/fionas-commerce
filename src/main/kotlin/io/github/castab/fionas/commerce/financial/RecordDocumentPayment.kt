package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.Version
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
 * - Canonical Quotes require the current proposal identity and one exact full deposit receipt,
 *   with no historical allocation. Successful acceptance immediately books the inquiry.
 * - The payment's currency is the document's, with at most its minor-unit digits. Invoice
 *   and RELATED payments retain partial and excessive amounts; a negative balance is derived.
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
    authorship: FinancialDocumentAuthorshipRepository,
    private val clock: Clock,
    proposals: InquiryProposalRepository,
    private val newPaymentId: () -> UUID = UUID::randomUUID,
    private val newAllocationId: () -> UUID = UUID::randomUUID,
) {
    private val documents = FionaFinancialDocuments(ledger, associations, authorship)
    private val deposits = CanonicalInquiryDepositPaymentPolicy(ledger, documents, proposals)

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
        val expectedProposalId: InquiryProposalId? = null,
    )

    operator fun invoke(command: Command): RecordedPayment {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val current = documents.expectLatest(transaction, command.documentId, command.documentVersion)
            val document = current.document
            document.requirePaymentDestination()
            val amount = paymentMoney(command.amount, document.currency)
            val accepted = deposits.validate(transaction, current, command.expectedProposalId, amount)
            val payment =
                validating { PaymentRecord(newPaymentId(), amount, command.method, command.receivedAt ?: now, command.externalReference) }
            val allocation =
                ledger.recordPaymentAgainstDocument(transaction, payment, newAllocationId(), document.reference, payment.amount, now)
            val view = documents.bookIfDepositSatisfied(transaction, current)
            accepted?.let { deposits.requireBooked(it, view) }
            val result =
                view?.let { documents.describeLocked(transaction, current.inquiryId, it) }
                    ?: documents.describeLocked(transaction, current.inquiryId, document.id)
            RecordedPayment(payment, allocation, result)
        }
    }
}
