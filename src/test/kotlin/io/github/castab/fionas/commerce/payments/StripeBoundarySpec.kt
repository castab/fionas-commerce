package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.math.BigDecimal
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StripeBoundarySpec :
    FunSpec({
        val configuration = testStripeConfiguration()
        val gateway = StripeJavaGateway(configuration)
        val at = Instant.now().epochSecond
        val payload =
            """
            {"id":"evt_test","object":"event","api_version":"${StripeConfiguration.API_VERSION}",
            "type":"checkout.session.completed","created":$at,"livemode":false,"data":{"object":{"id":"cs_test",
            "object":"checkout.session","payment_status":"unpaid"}}}
            """.trimIndent()

        fun signature(
            body: String,
            timestamp: Long = Instant.now().epochSecond,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(configuration.webhookSecret.toByteArray(), "HmacSHA256"))
            val digest = mac.doFinal("$timestamp.$body".toByteArray()).joinToString("") { "%02x".format(it) }
            return "t=$timestamp,v1=$digest"
        }

        test("official SDK verifies the exact raw body, signature timestamp and deliberately pinned snapshot version") {
            gateway.verify(payload, signature(payload)).let {
                it.id shouldBe "evt_test"
                it.objectId shouldBe "cs_test"
            }
            shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify(payload + " ", signature(payload)) }
            shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify(payload, signature(payload, at - 600)) }
            shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify(payload, "invalid") }
            val old = payload.replace(StripeConfiguration.API_VERSION, "2024-06-20")
            shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify(old, signature(old)) }
            shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify("{", signature("{")) }
            val wrongType = payload.replace("\"livemode\":false", "\"livemode\":\"false\"")
            shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify(wrongType, signature(wrongType)) }
            val malformed = payload.replace('"', '\'')
            shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify(malformed, signature(malformed)) }
        }
        test("direct merchant snapshots accept absent account and reject Connect contexts and the wrong environment") {
            gateway.verify(payload, signature(payload)).account shouldBe null
            listOf(
                payload.replace("\"livemode\":false", "\"livemode\":true"),
                payload.replace("\"livemode\":false", "\"livemode\":false,\"account\":\"acct_other\""),
                payload.replace("\"livemode\":false", "\"livemode\":false,\"account\":\"${configuration.accountId}\""),
            ).forEach { shouldThrow<CommerceFailure.ValidationFailed> { gateway.verify(it, signature(it)) } }
        }
        test("the complete raw-body contract verifies an SDK-signed delivery without staff authentication or provider I/O") {
            TestApplication.create(stripe = configuration, stripeGateway = gateway).use { app ->
                val raw = " \n" + payload.replace("checkout.session.completed", "customer.updated")

                fun send(body: String) =
                    app.http(
                        Request(Method.POST, "/integrations/stripe/webhook")
                            .header("Content-Type", "application/json")
                            .header("Stripe-Signature", signature(raw))
                            .body(body),
                    )
                send(raw).status shouldBe Status.NO_CONTENT
                app.database.count("fionas.stripe_webhook_events") shouldBe 1
                send(raw + " ").status shouldBe Status.UNPROCESSABLE_ENTITY
                app.database.count("fionas.stripe_webhook_events") shouldBe 1
            }
        }
        test("USD conversion is exact with Stripe limits and no rounding") {
            stripeMinor(BigDecimal("0.50")) shouldBe 50L
            stripeMinor(BigDecimal("999999.99")) shouldBe 99999999L
            listOf("0", "-1", "0.49", "1.001", "1000000", "99999999999999999999999").forEach {
                shouldThrow<CommerceFailure.ValidationFailed> { stripeMinor(BigDecimal(it)) }
            }
        }
        test("disabled configuration needs no secrets and invalid enabled configuration fails without leaking values") {
            StripeConfiguration.fromEnvironment(emptyMap()) shouldBe null
            shouldThrow<IllegalStateException> { StripeConfiguration.fromEnvironment(mapOf("FIONAS_STRIPE_ENABLED" to "yes")) }
            shouldThrow<IllegalStateException> { StripeConfiguration.fromEnvironment(mapOf("FIONAS_STRIPE_ENABLED" to "true")) }
            val env =
                mapOf(
                    "FIONAS_STRIPE_ENABLED" to "true",
                    "STRIPE_SECRET_KEY" to configuration.secretKey,
                    "STRIPE_WEBHOOK_SECRET" to configuration.webhookSecret,
                    "FIONAS_PAYMENT_ACCESS_KEY" to Base64.getEncoder().encodeToString(configuration.accessKey),
                    "FIONAS_PUBLIC_BASE_URL" to configuration.publicOrigin,
                    "FIONAS_STRIPE_ACCOUNT_ID" to configuration.accountId,
                )
            StripeConfiguration.fromEnvironment(env)!!.checkoutMode shouldBe CheckoutMode.HOSTED
            mapOf("hosted" to CheckoutMode.HOSTED, "embedded" to CheckoutMode.EMBEDDED).forEach { (value, mode) ->
                StripeConfiguration.fromEnvironment(env + ("FIONAS_STRIPE_CHECKOUT_MODE" to value))!!.checkoutMode shouldBe mode
            }
            listOf("", "EMBEDDED", " embedded", "elements").forEach {
                shouldThrow<IllegalStateException> { StripeConfiguration.fromEnvironment(env + ("FIONAS_STRIPE_CHECKOUT_MODE" to it)) }
            }
            StripeConfiguration.fromEnvironment(env)!!.publicOrigin shouldBe "https://payments.test"
            shouldThrow<IllegalArgumentException> { StripeConfiguration.fromEnvironment(env + ("FIONAS_STRIPE_LIVE_MODE" to "true")) }
            listOf(
                "https://payments.test/",
                "https://user:password@payments.test",
                "https://payments.test?next=evil",
                "http://payments.test",
            ).forEach {
                shouldThrow<IllegalArgumentException> { StripeConfiguration.fromEnvironment(env + ("FIONAS_PUBLIC_BASE_URL" to it)) }
            }
            CheckoutRedirect(
                java.util.UUID.randomUUID(),
                null,
                StripeAttemptState.OPEN,
                StripeReconciliation.PENDING,
                CheckoutMode.EMBEDDED,
                "cs_secret_test",
            ).toString() shouldBe "CheckoutRedirect([redacted])"
            configuration.toString() shouldBe "StripeConfiguration([redacted])"
            PaymentBearer("secret").toString() shouldBe "PaymentBearer([redacted])"
        }
    })
