package io.github.castab.fionas.commerce.payments

import com.stripe.StripeClient
import com.stripe.net.HttpClient
import com.stripe.net.HttpHeaders
import com.stripe.net.StripeRequest
import com.stripe.net.StripeResponse
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/** Actual SDK requests and response verification, with a deterministic transport and no network. */
class StripeSdkRequestsSpec :
    FunSpec({
        val config = testStripeConfiguration()
        val at = Instant.parse("2026-11-04T00:00:00Z")
        val attemptId = UUID.randomUUID()
        val attempt =
            StripeAttempt(
                attemptId,
                InquiryId(UUID.randomUUID()),
                UUID.randomUUID(),
                "request",
                "v1:2:2:BALANCE",
                UUID.randomUUID(),
                2,
                PaymentPurpose.DEPOSIT,
                2,
                UUID.randomUUID(),
                1,
                10000,
                "USD",
                config.accountId,
                false,
                "fionas-checkout:$attemptId",
                at,
                at.plusSeconds(3600),
            )

        class Transport : HttpClient() {
            val requests = mutableListOf<StripeRequest>()
            var merchant = config.accountId
            var live = false
            var paid = false
            var intentAmount = 10000
            var chargeAmount = 10000
            var chargeIntent = "pi_payment"
            var captured = true
            var expired = false
            var currency = "usd"
            var uiMode = "hosted_page"

            fun session() =
                """
                {"id":"cs_test_payment","object":"checkout.session","client_reference_id":"$attemptId",
                "ui_mode":"$uiMode","client_secret":"cs_secret_test","mode":"payment","payment_method_types":["card"],"amount_total":10000,"currency":"$currency",
                "livemode":$live,"status":"${if (paid) {
                    "complete"
                } else if (expired) {
                    "expired"
                } else {
                    "open"
                }}",
                "payment_status":"${if (paid) "paid" else "unpaid"}","payment_intent":${if (paid) "\"pi_payment\"" else "null"},
                "expires_at":${attempt.expiresAt.epochSecond},"url":"https://checkout.stripe.com/c/pay/test",
                "metadata":{"fionas_authorization":"${config.authorizationProof(attempt)}"}}
                """.trimIndent()

            override fun request(request: StripeRequest): StripeResponse {
                requests += request
                val json =
                    when (request.url().path) {
                        "/v1/account" -> """{"id":"$merchant","object":"account"}"""
                        "/v1/checkout/sessions" ->
                            if (request.method().name == "POST") {
                                session()
                            } else {
                                """{"object":"list","url":"/v1/checkout/sessions","has_more":false,"data":[${session()}]}"""
                            }
                        "/v1/checkout/sessions/cs_test_payment" -> session()
                        "/v1/checkout/sessions/cs_test_payment/expire" -> {
                            expired = true
                            session()
                        }
                        "/v1/payment_intents/pi_payment" ->
                            """
                            {"id":"pi_payment","object":"payment_intent","status":"succeeded","amount_received":$intentAmount,
                            "currency":"usd","livemode":$live,"latest_charge":"ch_payment"}
                            """.trimIndent()
                        "/v1/charges/ch_payment" ->
                            """
                            {"id":"ch_payment","object":"charge","paid":true,"captured":$captured,"amount_captured":$chargeAmount,
                            "currency":"usd","livemode":$live,"payment_intent":"$chargeIntent","created":${at.epochSecond}}
                            """.trimIndent()
                        else -> error("Unexpected SDK operation")
                    }
                return StripeResponse(200, HttpHeaders.of(mapOf("Request-Id" to listOf("req_test"))), json)
            }
        }

        fun gateway(transport: Transport) =
            StripeJavaGateway(
                config,
                StripeClient
                    .builder()
                    .setApiKey(config.secretKey)
                    .setHttpClient(transport)
                    .build(),
            )

        test("every merchant operation uses the configured key and pinned version without Connect context") {
            val transport = Transport()
            val gateway = gateway(transport)
            transport.requests.size shouldBe 0
            gateway.create(attempt).id shouldBe "cs_test_payment"
            gateway.retrieve("cs_test_payment")
            gateway.expire("cs_test_payment")
            gateway.discover(attempt)!!.id shouldBe "cs_test_payment"
            transport.paid = true
            gateway.retrieve("cs_test_payment").paidAt shouldBe at
            transport.requests.count { it.url().path == "/v1/account" } shouldBe 1
            transport.requests.forEach {
                it.headers().firstValue("Stripe-Account").isPresent shouldBe false
                it.headers().firstValue("Stripe-Context").isPresent shouldBe false
                it.headers().firstValue("Authorization").orElse("") shouldBe "Bearer ${config.secretKey}"
                it.headers().firstValue("Stripe-Version").orElse("") shouldBe StripeConfiguration.API_VERSION
            }
            val creation = transport.requests.single { it.url().path == "/v1/checkout/sessions" && it.method().name == "POST" }
            creation.headers().firstValue("Idempotency-Key").orElse("") shouldBe attempt.providerKey
            creation.params()["allowed_payment_method_types"] shouldBe listOf("card")
            creation.params()["client_reference_id"] shouldBe attemptId.toString()
        }
        test("embedded requests preserve financial parameters and use only embedded return behavior") {
            val transport = Transport().apply { uiMode = "embedded_page" }
            val embedded = attempt.copy(checkoutMode = CheckoutMode.EMBEDDED)
            val result = gateway(transport).create(embedded)
            result.uiMode shouldBe CheckoutMode.EMBEDDED
            result.clientSecret shouldBe "cs_secret_test"
            val params = transport.requests.last().params()
            params["ui_mode"] shouldBe "embedded_page"
            params["redirect_on_completion"] shouldBe "if_required"
            params["return_url"] shouldBe "${config.publicOrigin}/pay/${attempt.publicId}?checkout=returned"
            params.containsKey("success_url") shouldBe false
            params.containsKey("cancel_url") shouldBe false
            val hosted = Transport()
            gateway(hosted).create(attempt)
            val hostedParams = hosted.requests.last().params()
            hostedParams.containsKey("ui_mode") shouldBe false
            hostedParams.containsKey("return_url") shouldBe false
            hostedParams.containsKey("redirect_on_completion") shouldBe false
            hostedParams["success_url"] shouldBe "${config.publicOrigin}/pay/${attempt.publicId}?checkout=returned"
            hostedParams["cancel_url"] shouldBe "${config.publicOrigin}/pay/${attempt.publicId}?checkout=cancelled"
            val presentation = setOf("ui_mode", "redirect_on_completion", "return_url", "success_url", "cancel_url")
            params.filterKeys { it !in presentation } shouldBe hostedParams.filterKeys { it !in presentation }
            gateway(transport).create(embedded)
            transport.requests
                .filter { it.method().name == "POST" }
                .map { it.params() }
                .distinct()
                .size shouldBe 1
            result.toString() shouldBe "StripeCheckout([redacted])"
        }
        test("missing or unknown provider UI modes are rejected without echoing provider values") {
            listOf("", "elements", "secret_unknown").forEach { mode ->
                val error = shouldThrow<IllegalStateException> { gateway(Transport().apply { uiMode = mode }).create(attempt) }
                error.message shouldBe "Unexpected Stripe Checkout UI mode"
            }
        }
        test("the secret key must own the configured merchant before any Checkout request") {
            val transport = Transport().apply { merchant = "acct_unexpected" }
            val gateway = gateway(transport)
            shouldThrow<StripeMerchantMismatch> { gateway.verifyMerchant() }
            shouldThrow<StripeMerchantMismatch> { gateway.create(attempt) }
            // A failed check is not cached: each operation asks again and never reaches Checkout.
            transport.requests.map { it.url().path } shouldBe listOf("/v1/account", "/v1/account")
        }
        test("an explicit merchant check is cached with every later provider operation") {
            val transport = Transport()
            val gateway = gateway(transport)
            gateway.verifyMerchant()
            gateway.verifyMerchant()
            gateway.create(attempt)
            transport.requests.count { it.url().path == "/v1/account" } shouldBe 1
        }
        test("authorization account and live mode cannot select another API context") {
            val transport = Transport()
            val gateway = gateway(transport)
            shouldThrow<IllegalStateException> { gateway.create(attempt.copy(stripeAccount = "acct_other")) }
            shouldThrow<IllegalStateException> { gateway.create(attempt.copy(liveMode = true)) }
            transport.requests.map { it.url().path } shouldBe listOf("/v1/account")
        }
        test("wrong environment, currency, captured amounts and payment identities fail closed") {
            listOf<(Transport) -> Unit>(
                { it.live = true },
                { it.currency = "eur" },
                { it.intentAmount = 9999 },
                { it.chargeAmount = 9999 },
                { it.chargeIntent = "pi_other" },
                { it.captured = false },
            ).forEach { corrupt ->
                val transport =
                    Transport().apply {
                        paid = true
                        corrupt(this)
                    }
                shouldThrow<IllegalStateException> { gateway(transport).retrieve("cs_test_payment") }
            }
        }
    })
