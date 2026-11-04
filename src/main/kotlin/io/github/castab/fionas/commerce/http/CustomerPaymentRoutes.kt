package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.payments.CheckoutCommand
import io.github.castab.fionas.commerce.payments.CheckoutRedirect
import io.github.castab.fionas.commerce.payments.CustomerPaymentSummary
import io.github.castab.fionas.commerce.payments.PaymentAccessAction
import io.github.castab.fionas.commerce.payments.PaymentBearer
import io.github.castab.fionas.commerce.payments.PaymentOperations
import io.github.castab.fionas.commerce.payments.StaffPaymentAccess
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Header
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import java.nio.charset.CodingErrorAction
import java.util.UUID

@Serializable
data class PaymentAccessResponse(
    val publicId: String? = null,
    val revoked: Boolean,
    val url: String? = null,
) {
    override fun toString() = "PaymentAccessResponse([redacted])"
}

@Serializable
data class CustomerPaymentDocumentResponse(
    val id: String,
    val version: Int,
    val stage: String,
    val businessRevision: Int,
    val acceptedQuoteRevision: Int? = null,
    val currency: String,
    @ApiProperty(pattern = SIGNED_DECIMAL) val totalAmount: String,
)

@Serializable
data class CustomerPaymentReceiptResponse(
    @ApiProperty(pattern = SIGNED_DECIMAL) val amount: String,
    @ApiProperty(pattern = SIGNED_DECIMAL) val refundedAmount: String,
    val receivedAt: String,
)

@Serializable
data class CustomerPaymentResponse(
    val inquiryId: String,
    val eventDate: String,
    val lifecycle: String,
    val document: CustomerPaymentDocumentResponse,
    val mode: String,
    @ApiProperty(pattern = SIGNED_DECIMAL) val requiredAmount: String,
    @ApiProperty(pattern = SIGNED_DECIMAL) val paidAmount: String,
    @ApiProperty(pattern = SIGNED_DECIMAL) val balance: String,
    val canPay: Boolean,
    val unavailableReason: String? = null,
    val paymentInProgress: Boolean,
    val reconciliationPending: Boolean,
    val history: List<CustomerPaymentReceiptResponse>,
)

@Serializable
data class CreateCheckoutRequest(
    val expectedDocumentVersion: Int,
    val expectedBusinessRevision: Int,
    val amountMinor: Long? = null,
)

@Serializable
data class CheckoutResponse(
    val attemptId: String,
    val url: String? = null,
    val state: String,
    val outcome: String,
) {
    override fun toString() = "CheckoutResponse([redacted])"
}

@Serializable
data class StripeAttemptResponse(
    val id: String,
    val purpose: String,
    val authorizedMinor: Long,
    val documentVersion: Int,
    val businessRevision: Int,
    val state: String,
    val outcome: String,
    val createdAt: String,
    val expiresAt: String,
    val sessionId: String? = null,
    val paymentIntentId: String? = null,
    val ledgerPaymentId: String? = null,
    val diagnostic: String? = null,
)

@Serializable
data class StripeAttemptsResponse(
    val items: List<StripeAttemptResponse>,
)

private val paymentErrors = jsonBody(ErrorResponse.serializer())
private val disabledStripe = ErrorResponse("stripe_disabled", "Online payments are disabled")
private val paymentTag = Tag("Customer payments", "Inquiry-scoped customer access and hosted Stripe Checkout.")
private val paymentSecret = Header.required("X-Fionas-Payment-Token")
private val checkoutKey = Header.required("Idempotency-Key")
private val stripeSignature = Header.required("Stripe-Signature")
private val exampleAccess =
    PaymentAccessResponse(
        "c755f7cd-1e28-4c75-a85f-d066ede7387d",
        false,
        "https://example.com/pay/c755f7cd-1e28-4c75-a85f-d066ede7387d#REDACTED",
    )
private val exampleCheckout =
    CheckoutResponse("aec8f5a3-9d32-470b-a519-55b17f0cfb27", "https://checkout.stripe.com/c/pay/example", "OPEN", "PENDING")
private val exampleSummary =
    CustomerPaymentResponse(
        "c755f7cd-1e28-4c75-a85f-d066ede7387d",
        "2027-05-01",
        "QUOTED",
        CustomerPaymentDocumentResponse("aec8f5a3-9d32-470b-a519-55b17f0cfb27", 2, "QUOTE", 2, currency = "USD", totalAmount = "1200.00"),
        "DEPOSIT",
        "240.00",
        "0.00",
        "1200.00",
        true,
        paymentInProgress = false,
        reconciliationPending = false,
        history = emptyList(),
    )

private fun RouteMetaDsl.paymentMetadata(
    id: String,
    title: String,
    staff: Boolean = false,
) {
    operationId = id
    summary = title
    tags += paymentTag
    description =
        "Disabled by default. All responses use Cache-Control: no-store. No raw bearer token in paths, logs or public responses. " +
        if (staff) {
            "Requires a staff USER and fionas.payment-access.manage, fionas.inquiries.read, commerce.financial-document.read; " +
                "no role is granted automatically."
        } else {
            "Customer access uses X-Fionas-Payment-Token, not staff authentication. " +
                "Webhooks instead require Stripe-Signature on the original raw UTF-8 body."
        }
    if (staff) {
        principalAuthentication()
        security = staffSessionSecurity
        returningError(ErrorCategory.FORBIDDEN, "Missing staff USER, permission or trusted Origin.", "Not permitted")
    }
    returningError(ErrorCategory.MALFORMED_REQUEST, "Invalid request shape, UUID or required header.", "Malformed request")
    returningError(ErrorCategory.NOT_FOUND, "Unknown or inaccessible inquiry, payment access or attempt.", "Payment access was not found")
    returningError(
        ErrorCategory.CONFLICT,
        "Terms changed, checkout already active, changed idempotent input or provider unavailable; see message.",
        "PAYMENT_TERMS_CHANGED",
    )
    returningError(ErrorCategory.VALIDATION_FAILED, "Invalid amount or Stripe signature/snapshot.", "Invalid payment request")
    returningError(
        ErrorCategory.INTERNAL_FAILURE,
        "Unexpected or transient failure. Webhook sender must retry; no committed receipt is lost.",
        INTERNAL_FAILURE,
    )
    returning(Status.SERVICE_UNAVAILABLE, paymentErrors to disabledStripe, "`stripe_disabled`: Online payments are disabled.")
}

private fun AccessControl.paymentStaff(): Filter =
    requirePermission(FionaPermissions.PaymentAccessManage)
        .then(requirePermission(FionaPermissions.InquiriesRead))
        .then(requirePermission(CommercePermissions.FinancialDocumentRead))
        .then(requireStaffPaymentManager)

private val noPaymentCache =
    Filter { next ->
        { request ->
            next(
                request,
            ).header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").header("X-Robots-Tag", "noindex, nofollow")
        }
    }

private fun paymentEnabled(operations: PaymentOperations?) =
    Filter { next ->
        { request ->
            if (operations == null) Response(Status.SERVICE_UNAVAILABLE).with(paymentErrors of disabledStripe) else next(request)
        }
    }

/** All endpoints, including raw-body webhook verification, remain executable contract routes. */
internal fun paymentRoutes(
    operations: PaymentOperations?,
    access: AccessControl,
): List<ContractRoute> {
    val accessBody = jsonBody(PaymentAccessResponse.serializer())
    val summaryBody = jsonBody(CustomerPaymentResponse.serializer())
    val checkoutBody = jsonBody(CreateCheckoutRequest.serializer())
    val redirectBody = jsonBody(CheckoutResponse.serializer())
    val attemptsBody = jsonBody(StripeAttemptsResponse.serializer())
    val routes = mutableListOf<ContractRoute>()
    routes += "/staff/requests" / inquiryPath / "payment-access" meta {
        paymentMetadata("getInquiryPaymentAccess", "Retrieve the stable customer payment link", true)
        returning(Status.OK, accessBody to exampleAccess)
    } bindContract Method.GET to { id: String, _: String ->
        noPaymentCache.then(access.paymentStaff()).then(paymentEnabled(operations)).then { _: Request ->
            Response(Status.OK).with(accessBody of checkNotNull(operations).access(inquiryId(id)).response())
        }
    }
    listOf(
        Triple("", PaymentAccessAction.ISSUE, "issueInquiryPaymentAccess"),
        Triple("/rotate", PaymentAccessAction.ROTATE, "rotateInquiryPaymentAccess"),
        Triple("/revoke", PaymentAccessAction.REVOKE, "revokeInquiryPaymentAccess"),
    ).forEach { (suffix, action, operation) ->
        val metadata: RouteMetaDsl.() -> Unit = {
            paymentMetadata(operation, "${action.name.lowercase()} customer payment access", true)
            returning(Status.OK, accessBody to exampleAccess)
        }

        fun handler(id: String): org.http4k.core.HttpHandler =
            noPaymentCache.then(access.paymentStaff()).then(paymentEnabled(operations)).then { request: Request ->
                Response(
                    Status.OK,
                ).with(accessBody of checkNotNull(operations).manage(inquiryId(id), action, staffUser(request)).response())
            }
        routes +=
            if (suffix.isEmpty()) {
                "/staff/requests" / inquiryPath / "payment-access" meta metadata bindContract Method.POST to
                    { id: String, _: String -> handler(id) }
            } else {
                "/staff/requests" / inquiryPath / "payment-access" / suffix.removePrefix("/") meta metadata bindContract Method.POST to
                    { id: String, _: String, _: String -> handler(id) }
            }
    }
    routes += "/public/payments" / publicPaymentPath meta {
        paymentMetadata("readCustomerPaymentSummary", "Read the current customer-safe payment state")
        headers += paymentSecret
        returning(Status.OK, summaryBody to exampleSummary)
    } bindContract Method.GET to { id: String ->
        noPaymentCache.then(paymentEnabled(operations)).then { request: Request ->
            Response(Status.OK).with(summaryBody of checkNotNull(operations).summary(paymentUuid(id), paymentBearer(request)).response())
        }
    }
    routes += "/public/payments" / publicPaymentPath / "checkout-sessions" meta {
        paymentMetadata("createCustomerCheckoutSession", "Create or recover one hosted Checkout attempt")
        headers += paymentSecret
        headers += checkoutKey
        receiving(checkoutBody to CreateCheckoutRequest(2, 2))
        returning(Status.OK, redirectBody to exampleCheckout)
    } bindContract Method.POST to { id: String, _: String ->
        noPaymentCache.then(paymentEnabled(operations)).then { request: Request ->
            val input = checkoutBody(request)
            val key = oneHeader(request, "Idempotency-Key")
            if (!key.matches(Regex("[A-Za-z0-9_-]{1,128}"))) throw LensFailure(Invalid(checkoutKey.meta))
            val command =
                validating {
                    require(input.expectedDocumentVersion > 0 && input.expectedBusinessRevision > 0)
                    CheckoutCommand(
                        paymentUuid(id),
                        paymentBearer(request),
                        key,
                        input.expectedDocumentVersion,
                        input.expectedBusinessRevision,
                        input.amountMinor,
                    )
                }
            Response(Status.OK).with(redirectBody of checkNotNull(operations).checkout(command).response())
        }
    }
    routes += "/staff/requests" / inquiryPath / "payment-attempts" meta {
        paymentMetadata("listInquiryPaymentAttempts", "Inspect provider attempts and reconciliation exceptions", true)
        returning(Status.OK, attemptsBody to StripeAttemptsResponse(emptyList()))
    } bindContract Method.GET to { id: String, _: String ->
        noPaymentCache.then(access.paymentStaff()).then(paymentEnabled(operations)).then { _: Request ->
            Response(Status.OK).with(
                attemptsBody of
                    StripeAttemptsResponse(
                        checkNotNull(operations).attempts(inquiryId(id)).map {
                            StripeAttemptResponse(
                                it.id.toString(),
                                it.purpose.name,
                                it.authorizedMinor,
                                it.documentVersion,
                                it.businessRevision,
                                it.state.name,
                                it.outcome.name,
                                it.createdAt.toString(),
                                it.expiresAt.toString(),
                                it.sessionId,
                                it.paymentIntentId,
                                it.ledgerPaymentId?.toString(),
                                it.diagnostic,
                            )
                        },
                    ),
            )
        }
    }
    routes += "/staff/requests" / inquiryPath / "payment-attempts" / stripeAttemptPath / "reconcile" meta {
        paymentMetadata(
            "reconcileInquiryPaymentAttempt",
            "Recover an ambiguous provider result without creating another authorization",
            true,
        )
        returning(Status.OK, redirectBody to exampleCheckout)
    } bindContract Method.POST to { id: String, _: String, attempt: String, _: String ->
        noPaymentCache.then(access.paymentStaff()).then(paymentEnabled(operations)).then { _: Request ->
            Response(Status.OK).with(redirectBody of checkNotNull(operations).recover(inquiryId(id), paymentUuid(attempt)).response())
        }
    }
    routes += "/integrations/stripe/webhook" meta {
        paymentMetadata("receiveStripeWebhook", "Verify and synchronously reconcile a signed Stripe snapshot event")
        headers += stripeSignature
        returning(Status.NO_CONTENT)
    } bindContract Method.POST to
        noPaymentCache.then(paymentEnabled(operations)).then { request: Request ->
            val bytes = request.body.payload.asReadOnlyBuffer()
            if (bytes.remaining() > 262144) throw LensFailure(Invalid(stripeSignature.meta))
            val raw =
                try {
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(
                            CodingErrorAction.REPORT,
                        ).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(bytes)
                        .toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    throw LensFailure(Invalid(stripeSignature.meta))
                }
            checkNotNull(operations).webhook(raw, oneHeader(request, "Stripe-Signature"))
            Response(Status.NO_CONTENT)
        }
    return routes
}

private val inquiryPath =
    org.http4k.lens.Path
        .of("inquiryId", "Nonsecret identity", mapOf("schema" to mapOf("format" to "uuid")))
private val publicPaymentPath =
    org.http4k.lens.Path
        .of("publicId", "Nonsecret identity", mapOf("schema" to mapOf("format" to "uuid")))
private val stripeAttemptPath =
    org.http4k.lens.Path
        .of("attemptId", "Nonsecret identity", mapOf("schema" to mapOf("format" to "uuid")))

private fun paymentUuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw LensFailure(Invalid(publicPaymentPath.meta))
    }

private fun oneHeader(
    request: Request,
    name: String,
): String {
    if (request.headerValues(name).size != 1) throw LensFailure(Invalid(Header.required(name).meta))
    return checkNotNull(request.header(name))
}

private fun paymentBearer(request: Request) = PaymentBearer(oneHeader(request, "X-Fionas-Payment-Token"))

private fun StaffPaymentAccess.response() = PaymentAccessResponse(publicId?.toString(), revoked, url)

private fun CheckoutRedirect.response() = CheckoutResponse(attemptId.toString(), url, state.name, outcome.name)

private fun CustomerPaymentSummary.response() =
    CustomerPaymentResponse(
        inquiryId.toString(),
        eventDate.toString(),
        lifecycle.name,
        CustomerPaymentDocumentResponse(
            documentId.toString(),
            documentVersion,
            documentStage,
            businessRevision,
            acceptedQuoteRevision,
            currency,
            total.toPlainString(),
        ),
        purpose.name,
        required.toPlainString(),
        paid.toPlainString(),
        balance.toPlainString(),
        canPay,
        unavailableReason,
        inProgress,
        reconciliationPending,
        history.map { CustomerPaymentReceiptResponse(it.amount.toPlainString(), it.refunded.toPlainString(), it.receivedAt) },
    )
