package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.staff.StaffRequest
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CustomerPaymentHistory(
    val amount: BigDecimal,
    val refunded: BigDecimal,
    val receivedAt: String,
)

data class CustomerPaymentSummary(
    val inquiryId: UUID,
    val eventDate: LocalDate,
    val lifecycle: InquiryStage,
    val documentId: UUID,
    val documentVersion: Int,
    val documentStage: String,
    val businessRevision: Int,
    val acceptedQuoteRevision: Int?,
    val currency: String,
    val total: BigDecimal,
    val paid: BigDecimal,
    val balance: BigDecimal,
    val required: BigDecimal,
    val purpose: PaymentPurpose,
    val canPay: Boolean,
    val unavailableReason: String?,
    val inProgress: Boolean,
    val reconciliationPending: Boolean,
    val history: List<CustomerPaymentHistory>,
)

internal fun customerPaymentSummary(
    request: StaffRequest,
    attempts: List<StripeAttempt>,
): CustomerPaymentSummary {
    val document = request.financial.latest.document
    val deposit = request.deposit.depositRequirement?.requirement as? DepositRequirement.Active
    val purpose = if (document is FinancialDocument.Quote) PaymentPurpose.DEPOSIT else PaymentPurpose.INVOICE
    val required =
        if (purpose ==
            PaymentPurpose.DEPOSIT
        ) {
            checkNotNull(deposit).requiredAmount.amount
        } else {
            request.financial.reconciliation.balance.amount
        }
    val inProgress = attempts.any { it.state in activeStripeStates }
    val pending = attempts.any { it.outcome == StripeReconciliation.RECONCILIATION_REQUIRED }
    val reason =
        when {
            request.inquiry.lifecycle.stage == InquiryStage.CLOSED -> "CLOSED"
            document is FinancialDocument.Estimate -> "NOT_PUBLISHED"
            document.currency.currencyCode != "USD" -> "UNSUPPORTED_CURRENCY"
            pending -> "RECONCILIATION_PENDING"
            inProgress -> "PAYMENT_IN_PROGRESS"
            required.signum() <= 0 -> "NO_PAYMENT_DUE"
            required.movePointRight(2) < BigDecimal(50) -> "BELOW_STRIPE_MINIMUM"
            purpose == PaymentPurpose.DEPOSIT && required.movePointRight(2) > BigDecimal(99_999_999) -> "ABOVE_STRIPE_MAXIMUM"
            else -> null
        }
    return CustomerPaymentSummary(
        request.inquiry.inquiry.id.value,
        request.inquiry.inquiry.eventDate.value,
        request.inquiry.lifecycle.stage,
        document.id,
        document.version.number,
        if (document is FinancialDocument.Quote) {
            "QUOTE"
        } else if (document is FinancialDocument.Invoice) {
            "INVOICE"
        } else {
            "ESTIMATE"
        },
        if (document is FinancialDocument.Quote) {
            checkNotNull(
                request.currentQuoteRevision,
            )
        } else {
            request.currentInvoiceRevision ?: 0
        },
        request.acceptedQuoteRevision,
        document.currency.currencyCode,
        document.total.amount,
        request.financial.reconciliation.netApplied.amount,
        request.financial.reconciliation.balance.amount,
        required,
        purpose,
        reason == null,
        reason,
        inProgress,
        pending,
        request.payments.map { history ->
            // A manual receipt may fund several inquiries. Show only this lineage's portion.
            val applied = history.allocations.filter { it.financialDocumentReference.id == document.id }
            val ids = applied.map { it.id }.toSet()
            CustomerPaymentHistory(
                applied.fold(BigDecimal.ZERO) { total, allocation -> total + allocation.amount.amount },
                history.refundAllocations
                    .filter { it.paymentAllocationReference in ids }
                    .fold(BigDecimal.ZERO) { total, refund -> total + refund.amount.amount },
                history.payment.receivedAt.toString(),
            )
        },
    )
}

internal val activeStripeStates = setOf(StripeAttemptState.CREATING, StripeAttemptState.OPEN, StripeAttemptState.PROCESSING)

internal fun stripeMinor(amount: BigDecimal): Long =
    try {
        amount.movePointRight(2).longValueExact().also {
            if (it !in 50..99_999_999) throw CommerceFailure.ValidationFailed("Stripe USD payments must be between 0.50 and 999999.99")
        }
    } catch (_: ArithmeticException) {
        throw CommerceFailure.ValidationFailed("Stripe payment must be an exact USD minor-unit amount")
    }
