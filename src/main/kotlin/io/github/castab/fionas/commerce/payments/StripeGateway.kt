package io.github.castab.fionas.commerce.payments

import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.time.Instant
import java.util.UUID

enum class PaymentPurpose { DEPOSIT, INVOICE }

enum class StripeAttemptState { CREATING, OPEN, PROCESSING, EXPIRED, FAILED, SUCCEEDED }

enum class StripeReconciliation { PENDING, APPLIED, RECONCILIATION_REQUIRED }

data class StripeAttempt(
    val id: UUID,
    val inquiryId: InquiryId,
    val publicId: UUID,
    val requestKey: String,
    val requestFingerprint: String,
    val documentId: UUID,
    val documentVersion: Int,
    val purpose: PaymentPurpose,
    val businessRevision: Int,
    val proposalId: UUID?,
    val depositRevision: Int?,
    val authorizedMinor: Long,
    val currency: String,
    val stripeAccount: String,
    val liveMode: Boolean,
    val providerKey: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val sessionId: String? = null,
    val paymentIntentId: String? = null,
    val state: StripeAttemptState = StripeAttemptState.CREATING,
    val outcome: StripeReconciliation = StripeReconciliation.PENDING,
    val ledgerPaymentId: UUID? = null,
    val diagnostic: String? = null,
)

/** The URL is a short-lived bearer credential; never log this object. */
class StripeCheckout(
    val id: String,
    val attemptId: UUID,
    val amountMinor: Long,
    val currency: String,
    val liveMode: Boolean,
    val state: String,
    val paymentStatus: String,
    val paymentIntentId: String?,
    val expiresAt: Instant,
    val url: String?,
    val paidAt: Instant?,
    val authorizationProof: String = "",
) {
    override fun toString() = "StripeCheckout([redacted])"
}

data class StripeSignal(
    val id: String,
    val type: String,
    val objectId: String,
    val liveMode: Boolean,
    val account: String?,
    val paymentIntentId: String? = null,
)

/**
 * The configured secret key belongs to a different Stripe merchant than `FIONAS_STRIPE_ACCOUNT_ID`. A deployment
 * error, never a payment outcome: no provider request beyond the account lookup was made.
 */
class StripeMerchantMismatch : IllegalStateException("Stripe key does not belong to the configured merchant")

/** Fiona's hosted card Checkout boundary. No network call runs inside a database transaction. */
interface StripeGateway {
    /**
     * Confirms the key's direct merchant before a checkout command authorizes anything, so a misconfigured
     * deployment refuses payment without leaving an active attempt behind. Throws [StripeMerchantMismatch].
     */
    fun verifyMerchant()

    fun create(attempt: StripeAttempt): StripeCheckout

    fun retrieve(sessionId: String): StripeCheckout

    /** Bounded discovery for an ambiguous creation result. Never creates a replacement session. */
    fun discover(attempt: StripeAttempt): StripeCheckout?

    fun expire(sessionId: String): StripeCheckout

    fun verify(
        rawBody: String,
        signature: String,
    ): StripeSignal
}
