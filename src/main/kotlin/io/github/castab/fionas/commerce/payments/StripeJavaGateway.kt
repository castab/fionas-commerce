package io.github.castab.fionas.commerce.payments

import com.stripe.Stripe
import com.stripe.StripeClient
import com.stripe.exception.SignatureVerificationException
import com.stripe.exception.StripeException
import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import com.stripe.param.checkout.SessionCreateParams.BrandingSettings
import com.stripe.param.checkout.SessionListParams
import io.github.castab.commerce.runtime.operation.CommerceFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.UUID

// Provider idempotency binds these values to every FIONAS_V1 attempt. Add a profile to change them.
private val fionasBrandingV1 =
    BrandingSettings
        .builder()
        .setBackgroundColor("#F6F1E4")
        .setButtonColor("#5F5B40")
        .setBorderStyle(BrandingSettings.BorderStyle.ROUNDED)
        .setFontFamily(BrandingSettings.FontFamily.MONTSERRAT)
        .setDisplayName("Fiona's Ice Cream")
        .build()

/** Official SDK adapter. Its JSON implementation is confined here and never replaces CommerceJson. */
class StripeJavaGateway internal constructor(
    private val configuration: StripeConfiguration,
    private val client: StripeClient,
) : StripeGateway {
    constructor(configuration: StripeConfiguration) : this(configuration, StripeClient(configuration.secretKey))

    // Verify the key's direct merchant once per adapter, never during construction or inside PostgreSQL.
    private val verifiedMerchant: Unit by lazy {
        val account = client.v1().accounts().retrieveCurrent(options())
        if (account.id != configuration.accountId) throw StripeMerchantMismatch()
    }

    override fun verifyMerchant() = call {}

    private fun options(key: String? = null): RequestOptions =
        RequestOptions
            .builder()
            .setIdempotencyKey(key)
            .setConnectTimeout(5_000)
            .setReadTimeout(10_000)
            .setMaxNetworkRetries(0)
            .build()

    init {
        check(Stripe.API_VERSION == StripeConfiguration.API_VERSION) { "Stripe SDK and snapshot API versions must be upgraded together" }
    }

    override fun create(attempt: StripeAttempt): StripeCheckout =
        call {
            requireMerchantAuthorization(attempt)
            val line =
                SessionCreateParams.LineItem
                    .builder()
                    .setQuantity(1L)
                    .setPriceData(
                        SessionCreateParams.LineItem.PriceData
                            .builder()
                            .setCurrency("usd")
                            .setUnitAmount(attempt.authorizedMinor)
                            .setProductData(
                                SessionCreateParams.LineItem.PriceData.ProductData
                                    .builder()
                                    .setName(
                                        if (attempt.purpose ==
                                            PaymentPurpose.DEPOSIT
                                        ) {
                                            "Fiona's catering deposit"
                                        } else {
                                            "Fiona's catering Invoice payment"
                                        },
                                    ).build(),
                            ).build(),
                    ).build()
            val returnUrl = "${configuration.publicOrigin}/pay/${attempt.publicId}"
            val params =
                SessionCreateParams
                    .builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                    .setAdaptivePricing(
                        SessionCreateParams.AdaptivePricing
                            .builder()
                            .setEnabled(false)
                            .build(),
                    ).setAutomaticTax(
                        SessionCreateParams.AutomaticTax
                            .builder()
                            .setEnabled(false)
                            .build(),
                    ).setAllowPromotionCodes(false)
                    .addLineItem(line)
                    .setClientReferenceId(attempt.id.toString())
                    .putMetadata("fionas_authorization", configuration.authorizationProof(attempt))
                    .setExpiresAt(attempt.expiresAt.epochSecond)
                    .apply {
                        when (attempt.brandingProfile) {
                            CheckoutBrandingProfile.NONE -> Unit
                            CheckoutBrandingProfile.FIONAS_V1 -> setBrandingSettings(fionasBrandingV1)
                        }
                        when (attempt.checkoutMode) {
                            // Leave ui_mode implicit: historical hosted idempotency keys must replay identical parameters.
                            CheckoutMode.HOSTED -> {
                                setSuccessUrl("$returnUrl?checkout=returned")
                                setCancelUrl("$returnUrl?checkout=cancelled")
                            }
                            CheckoutMode.EMBEDDED -> {
                                setUiMode(SessionCreateParams.UiMode.EMBEDDED_PAGE)
                                setRedirectOnCompletion(SessionCreateParams.RedirectOnCompletion.IF_REQUIRED)
                                setReturnUrl("$returnUrl?checkout=returned")
                            }
                        }
                    }.build()
            describe(
                client
                    .v1()
                    .checkout()
                    .sessions()
                    .create(params, options(attempt.providerKey)),
            )
        }

    override fun retrieve(sessionId: String): StripeCheckout =
        call {
            describe(
                client
                    .v1()
                    .checkout()
                    .sessions()
                    .retrieve(sessionId, options())
                    .also { check(it.id == sessionId) { "Stripe returned a different Checkout identity" } },
            )
        }

    override fun expire(sessionId: String): StripeCheckout =
        call {
            describe(
                client
                    .v1()
                    .checkout()
                    .sessions()
                    .expire(sessionId, options())
                    .also { check(it.id == sessionId) { "Stripe returned a different Checkout identity" } },
            )
        }

    override fun discover(attempt: StripeAttempt): StripeCheckout? =
        call {
            requireMerchantAuthorization(attempt)
            // Ten pages bound one recovery call. Refuse ambiguity or truncation; never guess absence.
            var after: String? = null
            val found = mutableListOf<StripeCheckout>()
            repeat(10) {
                val params =
                    SessionListParams
                        .builder()
                        .setLimit(100L)
                        .setStartingAfter(after)
                        .setCreated(
                            SessionListParams.Created
                                .builder()
                                .setGte(attempt.createdAt.minusSeconds(60).epochSecond)
                                .setLte(attempt.expiresAt.epochSecond)
                                .build(),
                        ).build()
                val page =
                    client
                        .v1()
                        .checkout()
                        .sessions()
                        .list(params, options())
                page.data
                    .filter {
                        it.clientReferenceId == attempt.id.toString() &&
                            it.metadata["fionas_authorization"] == configuration.authorizationProof(attempt)
                    }.forEach { found += describe(it) }
                if (!page.hasMore) {
                    check(found.size <= 1) { "Multiple Stripe sessions for one durable authorization" }
                    return@call found.singleOrNull()
                }
                after = page.data.last().id
            }
            throw CommerceFailure.Conflict("STRIPE_DISCOVERY_LIMIT_REQUIRES_RECONCILIATION")
        }

    override fun verify(
        rawBody: String,
        signature: String,
    ): StripeSignal {
        val event =
            try {
                Webhook.constructEvent(rawBody, signature, configuration.webhookSecret)
            } catch (_: SignatureVerificationException) {
                throw CommerceFailure.ValidationFailed("Invalid Stripe signature")
            } catch (_: RuntimeException) {
                throw CommerceFailure.ValidationFailed("Malformed Stripe event")
            }
        if (event.apiVersion !=
            StripeConfiguration.API_VERSION
        ) {
            throw CommerceFailure.ValidationFailed("Unsupported Stripe snapshot API version")
        }
        // Inspect only verified SDK JSON; no unsafe deserialization or fallback API version.
        return try {
            // Validate original JSON too: an SDK parser must not quietly coerce malformed wire types.
            val envelope = Json.parseToJsonElement(rawBody).jsonObject

            fun text(name: String): String {
                val value = checkNotNull(envelope[name]?.jsonPrimitive)
                require(value.isString)
                return value.content
            }
            require(
                text("object") == "event" &&
                    text("id") == event.id &&
                    text("type") == event.type &&
                    text("api_version") == StripeConfiguration.API_VERSION,
            )
            require(event.account == null) { "Connected-account events are not supported" }
            val mode = checkNotNull(envelope["livemode"]?.jsonPrimitive)
            require(!mode.isString && mode.booleanOrNull == event.livemode && event.livemode == configuration.liveMode)
            val created = checkNotNull(envelope["created"]?.jsonPrimitive)
            require(!created.isString && (created.longOrNull ?: -1) >= 0)
            require(event.id?.matches(Regex("evt_[A-Za-z0-9_]{1,200}")) == true)
            require(!event.type.isNullOrBlank() && event.type.length <= 128 && event.livemode != null)
            val objectData = checkNotNull(envelope["data"]?.jsonObject?.get("object")?.jsonObject)
            val objectIdentity = checkNotNull(objectData["id"]?.jsonPrimitive)
            require(objectIdentity.isString)
            val objectId = objectIdentity.content
            require(objectId.matches(Regex("[A-Za-z0-9_]{1,255}")))
            val intent =
                objectData["payment_intent"]?.let {
                    (it as? kotlinx.serialization.json.JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                        ?: (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }
            StripeSignal(event.id, event.type, objectId, event.livemode, event.account, intent)
        } catch (_: RuntimeException) {
            throw CommerceFailure.ValidationFailed("Malformed Stripe event identity")
        }
    }

    private fun describe(session: Session): StripeCheckout {
        check(session.livemode == configuration.liveMode) { "Stripe Checkout environment disagrees with deployment" }
        check(session.mode == "payment" && session.paymentMethodTypes == listOf("card")) { "Unexpected Stripe checkout configuration" }
        val intent =
            if (session.paymentStatus == "paid") {
                val id = checkNotNull(session.paymentIntent) { "Paid Stripe session has no PaymentIntent" }
                client.v1().paymentIntents().retrieve(id, options()).also {
                    check(
                        it.id == id &&
                            it.status == "succeeded" &&
                            it.currency == session.currency &&
                            it.amountReceived == session.amountTotal &&
                            it.livemode == session.livemode,
                    ) { "Stripe PaymentIntent disagrees with paid Checkout" }
                }
            } else {
                null
            }
        val charge =
            intent?.let {
                client.v1().charges().retrieve(checkNotNull(it.latestCharge), options()).also { captured ->
                    check(
                        captured.id == it.latestCharge &&
                            captured.paid &&
                            captured.captured &&
                            captured.amountCaptured == session.amountTotal &&
                            captured.currency == session.currency &&
                            captured.paymentIntent == it.id &&
                            captured.livemode == session.livemode,
                    ) {
                        "Stripe charge disagrees with the captured Checkout payment"
                    }
                }
            }
        return StripeCheckout(
            session.id,
            UUID.fromString(session.clientReferenceId),
            checkNotNull(session.amountTotal),
            checkNotNull(session.currency),
            session.livemode,
            session.status,
            session.paymentStatus,
            session.paymentIntent,
            Instant.ofEpochSecond(session.expiresAt),
            session.url,
            charge?.let { Instant.ofEpochSecond(it.created) },
            session.metadata["fionas_authorization"] ?: "",
            when (session.uiMode) {
                "hosted_page" -> CheckoutMode.HOSTED
                "embedded_page" -> CheckoutMode.EMBEDDED
                else -> error("Unexpected Stripe Checkout UI mode")
            },
            session.clientSecret,
        )
    }

    private fun requireMerchantAuthorization(attempt: StripeAttempt) {
        check(attempt.stripeAccount == configuration.accountId && attempt.liveMode == configuration.liveMode && attempt.currency == "USD") {
            "Stripe authorization merchant or environment disagrees with deployment"
        }
    }

    private fun <T> call(block: () -> T): T =
        try {
            verifiedMerchant
            block()
        } catch (_: StripeException) {
            // SDK exceptions can include request parameters and private URLs. Do not attach their causes.
            throw IllegalStateException("Stripe request failed; retry the same durable operation")
        }
}
