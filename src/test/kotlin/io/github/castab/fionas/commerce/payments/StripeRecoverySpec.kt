package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.http.CheckoutModeResponse
import io.github.castab.fionas.commerce.http.CheckoutResponse
import io.github.castab.fionas.commerce.http.CustomerPaymentResponse
import io.github.castab.fionas.commerce.http.PaymentAccessResponse
import io.github.castab.fionas.commerce.http.StaffRequestResponse
import io.github.castab.fionas.commerce.http.StripeAttemptsResponse
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.createInquiry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class StripeRecoverySpec : FunSpec(stripeRecoveryTests(CheckoutMode.HOSTED))

class StripeEmbeddedRecoverySpec : FunSpec(stripeRecoveryTests(CheckoutMode.EMBEDDED))

class StripeElementsRecoverySpec : FunSpec(stripeRecoveryTests(CheckoutMode.ELEMENTS))

private fun stripeRecoveryTests(mode: CheckoutMode): FunSpec.() -> Unit =
    {
        val config = testStripeConfiguration(mode)
        val clock = PaymentTestClock()
        val fake = FakeStripe(config)
        lateinit var app: TestApplication
        beforeSpec {
            app = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake)
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants + FionaPermissions.PaymentAccessManage)
        }
        afterSpec { app.close() }
        afterTest {
            fake.onCreate = null
            fake.onRetrieve = null
            fake.onDiscover = null
            fake.onExpire = null
            fake.timeoutNext = false
            fake.merchantMismatch = false
        }

        data class Scenario(
            val id: String,
            val link: PaymentAccessResponse,
        )

        fun scenario(): Scenario {
            val id = app.createInquiry(lines = listOf(TestLine("Catering", unitPrice = "625.00")))
            app
                .adminPost(
                    "/staff/requests/$id/proposals",
                    """{"expectedDocumentVersion":1,"terms":{"type":"PERCENTAGE","percentage":"20"}}""",
                ).status shouldBe Status.OK
            val access = app.adminPost("/staff/requests/$id/payment-access")
            access.status shouldBe Status.OK
            return Scenario(id, CommerceJson.asA(access.bodyString(), PaymentAccessResponse.serializer()))
        }

        fun token(s: Scenario) = checkNotNull(s.link.url).substringAfter('#')

        fun summary(s: Scenario): CustomerPaymentResponse {
            val response =
                app.http(
                    Request(Method.GET, "/public/payments/${s.link.publicId}")
                        .header("X-Fionas-Payment-Token", token(s)),
                )
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), CustomerPaymentResponse.serializer())
        }

        fun checkout(
            s: Scenario,
            key: String = UUID.randomUUID().toString(),
            amount: Long? = null,
            snapshot: CustomerPaymentResponse = summary(s),
            instance: TestApplication = app,
            restartToken: String? = null,
        ): Response {
            val amountJson = amount?.let { ",\"amountMinor\":$it" } ?: ""
            val restartJson = restartToken?.let { ",\"restartToken\":\"$it\"" } ?: ""
            return instance.http(
                Request(Method.POST, "/public/payments/${s.link.publicId}/checkout-sessions")
                    .header("X-Fionas-Payment-Token", token(s))
                    .header("Idempotency-Key", key)
                    .header("Content-Type", "application/json")
                    .body(
                        """
                        {"expectedDocumentVersion":${snapshot.document.version},
                        "expectedBusinessRevision":${snapshot.document.businessRevision}$amountJson$restartJson}
                        """.trimIndent(),
                    ),
            )
        }

        fun redirect(response: Response): CheckoutResponse {
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), CheckoutResponse.serializer()).also {
                if (it.state == "OPEN") {
                    (it.url != null) shouldBe (it.uiMode == CheckoutModeResponse.HOSTED)
                    (it.clientSecret != null) shouldBe (it.uiMode != CheckoutModeResponse.HOSTED)
                } else {
                    it.url shouldBe null
                    it.clientSecret shouldBe null
                }
            }
        }

        fun session(checkout: CheckoutResponse) = fake.sessions.values.single { it.attemptId.toString() == checkout.attemptId }

        fun attempts(s: Scenario) =
            CommerceJson
                .asA(
                    app.adminGet("/staff/requests/${s.id}/payment-attempts").bodyString(),
                    StripeAttemptsResponse.serializer(),
                ).items

        fun code(response: Response): String {
            response.status shouldBe Status.CONFLICT
            response.header("Cache-Control") shouldBe "no-store"
            return CommerceJson.asA(response.bodyString(), ErrorResponse.serializer()).code
        }

        fun webhook(
            id: String,
            type: String = "checkout.session.completed",
            event: String = "evt_${UUID.randomUUID()}",
        ) = app.http(Request(Method.POST, "/integrations/stripe/webhook").header("Stripe-Signature", "valid").body("$event|$type|$id"))

        fun book(s: Scenario) {
            val initial = redirect(checkout(s))
            fake.change(session(initial).id)
            webhook(session(initial).id).status shouldBe Status.NO_CONTENT
            summary(s).lifecycle shouldBe "BOOKED"
        }

        fun restart(configuration: StripeConfiguration = config) {
            val database = app.database
            app.runtime.close()
            app = TestApplication.create(clock = clock, stripe = configuration, stripeGateway = fake, database = database)
        }

        fun restartCheckout(
            s: Scenario,
            snapshot: CustomerPaymentResponse,
            amount: Long? = null,
            key: String = UUID.randomUUID().toString(),
            instance: TestApplication = app,
        ) = checkout(s, key, amount, snapshot, instance, checkNotNull(snapshot.checkoutRestartToken))

        test("customer restarts a 400 dollar Invoice Checkout for 250 dollars and old keys remain pinned") {
            val s = scenario()
            book(s)
            summary(s).balance.toBigDecimal().compareTo(BigDecimal("500")) shouldBe 0
            val original = summary(s)
            val oldKey = UUID.randomUUID().toString()
            val old = redirect(checkout(s, oldKey, amount = 40000, snapshot = original))
            old.replacedAttemptId shouldBe null
            val review = summary(s)
            review.canPay shouldBe false
            review.checkoutRestartToken shouldNotBe null
            session(old).state shouldBe "open"
            code(checkout(s, amount = 25000, snapshot = review)) shouldBe "checkout_in_progress"
            session(old).state shouldBe "open"

            val key = UUID.randomUUID().toString()
            val replacement = redirect(restartCheckout(s, review, 25000, key))
            replacement.replacedAttemptId shouldBe old.attemptId
            session(replacement).amountMinor shouldBe 25000
            session(old).state shouldBe "expired"
            session(replacement).state shouldBe "open"
            redirect(restartCheckout(s, review, 25000, key)).attemptId shouldBe replacement.attemptId
            code(restartCheckout(s, review, 30000, key)) shouldBe "checkout_key_reused"
            code(checkout(s, key, amount = 25000, snapshot = review)) shouldBe "checkout_key_reused"
            code(restartCheckout(s, review, 25000)) shouldBe "checkout_recovery_conflict"
            redirect(checkout(s, oldKey, amount = 40000, snapshot = original)).state shouldBe "EXPIRED"
            webhook(session(old).id, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            fake.change(session(replacement).id)
            repeat(2) { webhook(session(replacement).id).status shouldBe Status.NO_CONTENT }
            attempts(s).size shouldBe 3
            summary(s).history.size shouldBe 2
            summary(s).balance.toBigDecimal().compareTo(BigDecimal("250")) shouldBe 0
        }

        test("restart preserves the full approved deposit and permits the same amount immediately") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            review.unavailableReason shouldBe "PAYMENT_IN_PROGRESS"
            review.checkoutRestartToken shouldNotBe null
            restartCheckout(s, review, 12499).status shouldBe Status.UNPROCESSABLE_ENTITY
            session(old).state shouldBe "open"
            val replacement = redirect(restartCheckout(s, review, 12500))
            replacement.replacedAttemptId shouldBe old.attemptId
            session(old).state shouldBe "expired"
            session(replacement).amountMinor shouldBe 12500
        }

        test("restart of an already reconciled expiry verifies the merchant before selecting or claiming a key") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            val key = UUID.randomUUID().toString()
            val created = fake.created.get()
            val receipts = app.database.count("commerce.payment_records")
            fake.expire(session(old).id)
            webhook(session(old).id, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            attempts(s).single().state shouldBe "EXPIRED"
            summary(s).checkoutRestartToken shouldBe null

            fake.merchantMismatch = true
            val stale = review.copy(document = review.document.copy(version = review.document.version + 1))
            // Even a request that selection would reject must encounter the merchant check first.
            listOf(restartCheckout(s, review, key = key), restartCheckout(s, stale)).forEach { refused ->
                refused.status shouldBe Status.SERVICE_UNAVAILABLE
                refused.header("Cache-Control") shouldBe "no-store"
                CommerceJson.asA(refused.bodyString(), ErrorResponse.serializer()).code shouldBe "stripe_disabled"
            }
            attempts(s).single().id shouldBe old.attemptId
            attempts(s).single().state shouldBe "EXPIRED"
            fake.created.get() shouldBe created
            app.database.count("commerce.payment_records") shouldBe receipts
            app.database.strings("SELECT request_key FROM fionas.stripe_payment_attempts WHERE request_key = '$key'") shouldBe emptyList()

            fake.merchantMismatch = false
            val replacement = redirect(restartCheckout(s, review, key = key))
            replacement.replacedAttemptId shouldBe old.attemptId
            replacement.state shouldBe "OPEN"
            attempts(s).size shouldBe 2
            fake.created.get() shouldBe created + 1
        }

        test("a locally open Invoice attempt cannot hide an ineligible balance or a closed booking") {
            val s = scenario()
            book(s)
            val old = redirect(checkout(s, amount = 40000))
            val review = summary(s)
            review.unavailableReason shouldBe "PAYMENT_IN_PROGRESS"
            review.checkoutRestartToken shouldNotBe null

            fun recordManual(amount: String) {
                app
                    .adminPost(
                        "/financial-documents/${review.document.id}/payments",
                        """{"documentVersion":${review.document.version},"amount":"$amount","method":"CASH"}""",
                    ).status shouldBe Status.CREATED
            }

            fun assertBlocked(reason: String) {
                val current = summary(s)
                current.canPay shouldBe false
                current.paymentInProgress shouldBe true
                current.checkoutRecoveryAvailable shouldBe true
                current.unavailableReason shouldBe reason
                current.checkoutRestartToken shouldBe null
            }

            val retrieves = fake.retrieves.get()
            recordManual("499.75")
            assertBlocked("BELOW_STRIPE_MINIMUM")
            recordManual("0.25")
            assertBlocked("NO_PAYMENT_DUE")
            app.adminPost("/inquiries/${s.id}/served").status shouldBe Status.OK
            app.adminPost("/inquiries/${s.id}/close").status shouldBe Status.OK
            assertBlocked("CLOSED")
            fake.retrieves.get() shouldBe retrieves
            code(restartCheckout(s, review, 25000)) shouldBe "payment_terms_changed"
            session(old).state shouldBe "open"
        }

        test("an unapplied receipt suppresses restart while a later Invoice Checkout is open") {
            val s = scenario()
            val oldDeposit = redirect(checkout(s))
            fake.expire(session(oldDeposit).id)
            webhook(session(oldDeposit).id, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            book(s)
            val invoice = redirect(checkout(s, amount = 40000))
            val review = summary(s)
            review.checkoutRestartToken shouldNotBe null

            // A late capture on the earlier deposit can no longer be allocated to its approved terms.
            fake.change(session(oldDeposit).id)
            webhook(session(oldDeposit).id).status shouldBe Status.NO_CONTENT
            val blocked = summary(s)
            blocked.paymentInProgress shouldBe true
            blocked.reconciliationPending shouldBe true
            blocked.unavailableReason shouldBe "RECONCILIATION_PENDING"
            blocked.checkoutRestartToken shouldBe null
            code(restartCheckout(s, review, 25000)) shouldBe "checkout_reconciliation_required"
            session(invoice).state shouldBe "open"
        }

        test("restart cannot expire a processing payment and captures a completed payment exactly once") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            fake.change(session(old).id, paid = "unpaid")
            var expirations = 0
            fake.onExpire = { expirations++ }
            code(restartCheckout(s, review)) shouldBe "payment_processing"
            summary(s).checkoutRestartToken shouldBe null
            fake.change(session(old).id)
            code(restartCheckout(s, review)) shouldBe "payment_state_changed"
            webhook(session(old).id, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            webhook(session(old).id).status shouldBe Status.NO_CONTENT
            attempts(s).single().state shouldBe "SUCCEEDED"
            summary(s).history.size shouldBe 1
            expirations shouldBe 0
        }

        test("completion and a webhook during the expiry request win over customer restart") {
            listOf(false, true).forEach { deliverWebhook ->
                val s = scenario()
                val old = redirect(checkout(s))
                val review = summary(s)
                fake.onExpire = {
                    fake.change(session(old).id)
                    if (deliverWebhook) webhook(session(old).id).status shouldBe Status.NO_CONTENT
                }
                code(restartCheckout(s, review)) shouldBe "payment_state_changed"
                attempts(s).single().state shouldBe "SUCCEEDED"
                webhook(session(old).id).status shouldBe Status.NO_CONTENT
                summary(s).history.size shouldBe 1
            }
        }

        test("unconfirmed expiration and provider retrieval failures keep the reservation") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            val key = UUID.randomUUID().toString()
            fake.onRetrieve = { error("Simulated provider retrieval failure") }
            code(restartCheckout(s, review, key = key)) shouldBe "checkout_reconciliation_required"
            fake.onRetrieve = null
            fake.onExpire = { error("Simulated expiry timeout without provider mutation") }
            code(restartCheckout(s, review, key = key)) shouldBe "checkout_reconciliation_required"
            session(old).state shouldBe "open"
            attempts(s).single().state shouldBe "OPEN"
            fake.onExpire = null
            redirect(restartCheckout(s, review, key = key)).replacedAttemptId shouldBe old.attemptId
        }

        test("a lost expiry response and process restart recover the same target, then creation retries retain their key") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            val key = UUID.randomUUID().toString()
            fake.onExpire = {
                fake.change(session(old).id, "expired", "unpaid")
                fake.onRetrieve = { error("Provider unavailable after expiry") }
                error("Simulated lost expiry response")
            }
            code(restartCheckout(s, review, key = key)) shouldBe "checkout_reconciliation_required"
            attempts(s).single().state shouldBe "OPEN"
            fake.onExpire = null
            fake.onRetrieve = null
            val restarted = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake, database = app.database)
            try {
                fake.timeoutNext = true
                restartCheckout(s, review, key = key, instance = restarted).status shouldBe Status.INTERNAL_SERVER_ERROR
                val pending = attempts(s).single { it.id != old.attemptId }
                pending.state shouldBe "CREATING"
                val recovered = redirect(restartCheckout(s, review, key = key, instance = restarted))
                recovered.attemptId shouldBe pending.id
                recovered.replacedAttemptId shouldBe old.attemptId
                attempts(s).size shouldBe 2
            } finally {
                restarted.runtime.close()
            }
        }

        test("an unverified provider Session cannot be expired or replaced") {
            val s = scenario()
            val old = redirect(checkout(s))
            val provider = session(old)
            val review = summary(s)
            fake.sessions[provider.id] =
                StripeCheckout(
                    provider.id,
                    provider.attemptId,
                    provider.amountMinor,
                    provider.currency,
                    provider.liveMode,
                    "open",
                    "unpaid",
                    null,
                    provider.expiresAt,
                    null,
                    null,
                    "invalid-proof",
                    provider.uiMode,
                )
            var expirations = 0
            fake.onExpire = { expirations++ }
            code(restartCheckout(s, review)) shouldBe "checkout_reconciliation_required"
            attempts(s).single().state shouldBe "OPEN"
            expirations shouldBe 0
        }

        test("distinct keys and same-key replicas converge on one replacement of the reviewed target") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            val entered = CountDownLatch(3)
            val release = CountDownLatch(1)
            fake.onExpire = {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
            val other = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake, database = app.database)
            val shared = UUID.randomUUID().toString()
            val competing = UUID.randomUUID().toString()
            try {
                val calls =
                    listOf(shared to app, shared to other, competing to other).map { (key, instance) ->
                        CompletableFuture.supplyAsync { restartCheckout(s, review, key = key, instance = instance) }
                    }
                entered.await(10, TimeUnit.SECONDS) shouldBe true
                release.countDown()
                val responses = calls.map { it.get(20, TimeUnit.SECONDS) }
                val successful = responses.filter { it.status == Status.OK }.map(::redirect)
                successful.map { it.attemptId }.distinct().size shouldBe 1
                successful.all { it.replacedAttemptId == old.attemptId } shouldBe true
                responses.filter { it.status != Status.OK }.map(::code).distinct() shouldBe listOf("checkout_recovery_conflict")
                responses[0].status shouldBe responses[1].status
                attempts(s).size shouldBe 2
                session(old).state shouldBe "expired"
            } finally {
                release.countDown()
                other.runtime.close()
            }
        }

        test("manual payment during expiration requires renewed review even when the selected partial remains affordable") {
            val s = scenario()
            book(s)
            val old = redirect(checkout(s, amount = 40000))
            val review = summary(s)
            fake.onExpire = {
                app
                    .adminPost(
                        "/financial-documents/${review.document.id}/payments",
                        """{"documentVersion":${review.document.version},"amount":"10.00","method":"CASH"}""",
                    ).status shouldBe Status.CREATED
            }
            code(restartCheckout(s, review, 25000)) shouldBe "payment_terms_changed"
            session(old).state shouldBe "expired"
            attempts(s).size shouldBe 2
            code(restartCheckout(s, review, 25000)) shouldBe "payment_terms_changed"
            checkout(s, amount = 50000).status shouldBe Status.UNPROCESSABLE_ENTITY
            summary(s).history.size shouldBe 2
        }

        test("Invoice amendment invalidates the reviewed restart before or during expiration") {
            listOf(false, true).forEach { duringExpiry ->
                val s = scenario()
                book(s)
                val old = redirect(checkout(s, amount = 40000))
                val review = summary(s)
                val amend: () -> Unit = {
                    app
                        .adminPost(
                            "/financial-documents/${review.document.id}/change-orders",
                            """
                            {"expectedVersion":${review.document.version},"expectedInvoiceRevision":${review.document.businessRevision},
                            "lines":[${TestLine("Amended catering", unitPrice = "600.00").new("amended")}]}
                            """.trimIndent(),
                        ).status shouldBe Status.OK
                }
                if (duringExpiry) fake.onExpire = amend else amend()
                code(restartCheckout(s, review, 25000)) shouldBe "payment_terms_changed"
                session(old).state shouldBe if (duringExpiry) "expired" else "open"
                attempts(s).size shouldBe 2
                val current = summary(s)
                if (duringExpiry) {
                    current.checkoutRestartToken shouldBe null
                } else {
                    current.checkoutRestartToken shouldNotBe null
                    current.checkoutRestartToken shouldNotBe review.checkoutRestartToken
                    code(checkout(s, amount = 25000, snapshot = current, restartToken = review.checkoutRestartToken)) shouldBe
                        "payment_terms_changed"
                    redirect(restartCheckout(s, current, 25000)).replacedAttemptId shouldBe old.attemptId
                }
            }
        }

        test("a deposit revision during expiration requires review and a newly approved full deposit") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            fake.onExpire = {
                app
                    .adminPost(
                        "/staff/requests/${s.id}/proposals/deposit-revisions",
                        """
                        {"expectedDocumentVersion":${review.document.version},"expectedDepositRequirementRevision":1,
                        "expectedQuoteRevision":${review.document.businessRevision},"terms":{"type":"PERCENTAGE","percentage":"25"}}
                        """.trimIndent(),
                    ).status shouldBe Status.OK
            }
            code(restartCheckout(s, review)) shouldBe "payment_terms_changed"
            session(old).state shouldBe "expired"
            attempts(s).size shouldBe 1
            checkout(s, amount = 12500).status shouldBe Status.UNPROCESSABLE_ENTITY
            session(redirect(checkout(s))).amountMinor shouldBe 15625
        }

        test("rotated or revoked payment access during expiration prevents replacement") {
            listOf("rotate", "revoke").forEach { action ->
                val s = scenario()
                val old = redirect(checkout(s))
                val review = summary(s)
                fake.onExpire = {
                    app.adminPost("/staff/requests/${s.id}/payment-access/$action").status shouldBe Status.OK
                }
                restartCheckout(s, review).status shouldBe Status.NOT_FOUND
                session(old).state shouldBe "expired"
                attempts(s).size shouldBe 1
            }
        }

        test("restart and rollout preserve modes and branding on ambiguous and attached attempts") {
            val originalApp = app
            app = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake)
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants + FionaPermissions.PaymentAccessManage)
            try {
                // Provision all inquiries before restarting: the harness's web SERVICE is created lazily per instance.
                val cases = listOf(false, true).flatMap { lost -> listOf(false, true).map { Triple(lost, it, scenario()) } }
                val other = if (mode == CheckoutMode.ELEMENTS) CheckoutMode.EMBEDDED else CheckoutMode.ELEMENTS
                val brand = if (mode == CheckoutMode.ELEMENTS) CheckoutBrandingProfile.NONE else CheckoutBrandingProfile.FIONAS_V1
                for ((lost, discovery, s) in cases) {
                    restart(config)
                    val snapshot = summary(s)
                    val key = UUID.randomUUID().toString()
                    fake.timeoutNext = lost
                    val first = checkout(s, key, snapshot = snapshot)
                    first.status shouldBe if (lost) Status.INTERNAL_SERVER_ERROR else Status.OK
                    val original = attempts(s).single()
                    fake.authorizations.getValue(UUID.fromString(original.id)).brandingProfile shouldBe brand
                    restart(testStripeConfiguration(other))
                    if (discovery) clock.instant = clock.instant.plusSeconds(1501)
                    val recovered = redirect(checkout(s, key, snapshot = snapshot))
                    recovered.attemptId shouldBe original.id
                    recovered.uiMode.name shouldBe mode.name
                    (recovered.url != null) shouldBe (mode == CheckoutMode.HOSTED)
                    (recovered.clientSecret != null) shouldBe (mode != CheckoutMode.HOSTED)
                    app.database.strings(
                        "SELECT checkout_mode || ' ' || checkout_branding_profile FROM fionas.stripe_payment_attempts WHERE id = '${original.id}'",
                    ) shouldBe listOf("${mode.name} ${brand.name}")
                    code(checkout(s, snapshot = snapshot)) shouldBe "checkout_in_progress"
                    attempts(s).size shouldBe 1
                    fake.sessions.values.count { it.attemptId.toString() == original.id } shouldBe 1
                    fake.expire(session(recovered).id)
                    redirect(checkout(s, snapshot = snapshot)).uiMode.name shouldBe other.name
                }
            } finally {
                app.close()
                app = originalApp
            }
        }
        test("a historical unbranded attempt keeps its provider key and profile on creation replay") {
            val s = scenario()
            val key = UUID.randomUUID().toString()
            val snapshot = summary(s)
            // Leave an authorized attempt without a provider response, as on an interrupted pre-V13 creation.
            fake.onCreate = { error("Simulated interruption before Stripe creation") }
            checkout(s, key, snapshot = snapshot).status shouldBe Status.INTERNAL_SERVER_ERROR
            fake.onCreate = null
            val original = attempts(s).single()
            app.database.execute(
                "UPDATE fionas.stripe_payment_attempts SET checkout_branding_profile = 'NONE' WHERE id = '${original.id}'",
            )

            val recovered = redirect(checkout(s, key, snapshot = snapshot))
            recovered.attemptId shouldBe original.id
            recovered.uiMode.name shouldBe mode.name
            val authorization = fake.authorizations.getValue(UUID.fromString(original.id))
            authorization.brandingProfile shouldBe CheckoutBrandingProfile.NONE
            authorization.providerKey shouldBe "fionas-checkout:${original.id}"
            attempts(s).size shouldBe 1
        }
        test("a new browser key recovers confirmed expiration without a webhook and old keys remain pinned") {
            val s = scenario()
            val original = summary(s)
            val oldKey = UUID.randomUUID().toString()
            val old = redirect(checkout(s, oldKey, snapshot = original))
            fake.expire(session(old).id)
            clock.instant = clock.instant.plusSeconds(259200)
            summary(s).checkoutRecoveryAvailable shouldBe true
            val replacement = redirect(checkout(s))
            replacement.attemptId shouldNotBe old.attemptId
            attempts(s).size shouldBe 2
            attempts(s).single { it.id == old.attemptId }.state shouldBe "EXPIRED"
            redirect(checkout(s, oldKey, snapshot = original)).state shouldBe "EXPIRED"
            attempts(s).size shouldBe 2
            webhook(session(old).id, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            fake.change(session(replacement).id)
            val event = "evt_${UUID.randomUUID()}"
            webhook(session(replacement).id, event = event).status shouldBe Status.NO_CONTENT
            webhook(session(replacement).id, event = event).status shouldBe Status.NO_CONTENT
            summary(s).history.size shouldBe 1
            summary(s).lifecycle shouldBe "BOOKED"
            // A capture already racing provider expiration is never discarded or applied as another deposit.
            fake.change(session(old).id)
            webhook(session(old).id).status shouldBe Status.NO_CONTENT
            attempts(s).all { it.ledgerPaymentId != null } shouldBe true
            attempts(s).map { it.outcome }.sorted() shouldBe listOf("APPLIED", "RECONCILIATION_REQUIRED")
            summary(s).history.size shouldBe 1
        }
        test("local expiry never proves provider expiry and GET remains side-effect-free") {
            val s = scenario()
            val old = redirect(checkout(s))
            clock.instant = clock.instant.plusSeconds(259200)
            val before = fake.retrieves.get()
            repeat(3) { summary(s).checkoutRecoveryAvailable shouldBe true }
            fake.retrieves.get() shouldBe before
            code(checkout(s)) shouldBe "checkout_in_progress"
            attempts(s).single().id shouldBe old.attemptId
            attempts(s).single().state shouldBe "OPEN"
        }
        test("paid deposit with delayed webhook books once during on-demand recovery without another authorization") {
            val s = scenario()
            val old = redirect(checkout(s))
            fake.change(session(old).id)
            code(checkout(s)) shouldBe "payment_state_changed"
            attempts(s).size shouldBe 1
            attempts(s).single().ledgerPaymentId shouldNotBe null
            summary(s).lifecycle shouldBe "BOOKED"
            webhook(session(old).id).status shouldBe Status.NO_CONTENT
            summary(s).history.size shouldBe 1
        }
        test("recovered partial Invoice success demands a fresh decision even though document versions still match") {
            val s = scenario()
            book(s)
            val before = summary(s)
            val partial = redirect(checkout(s, amount = 100))
            fake.change(session(partial).id)
            code(checkout(s, amount = 100, snapshot = before)) shouldBe "payment_state_changed"
            attempts(s).size shouldBe 2
            summary(s).document shouldBe before.document
            BigDecimal(before.balance) - BigDecimal(summary(s).balance) shouldBe BigDecimal("1.00")
            summary(s).history.size shouldBe 2
        }
        test("unsettled processing remains protected after elapsed time and signed failure must commit before replacement") {
            val s = scenario()
            val old = redirect(checkout(s))
            val review = summary(s)
            fake.change(session(old).id, paid = "unpaid")
            clock.instant = clock.instant.plusSeconds(259200)
            code(checkout(s)) shouldBe "payment_processing"
            attempts(s).single().state shouldBe "PROCESSING"
            attempts(s).single().ledgerPaymentId shouldBe null
            webhook(session(old).id, "checkout.session.async_payment_failed").status shouldBe Status.NO_CONTENT
            attempts(s).single().state shouldBe "FAILED"
            summary(s).checkoutRestartToken shouldBe null
            code(restartCheckout(s, review)) shouldBe "checkout_recovery_conflict"
            redirect(checkout(s)).attemptId shouldNotBe old.attemptId
        }
        test("a lost creation response reuses the original provider key inside its safe window") {
            val s = scenario()
            fake.timeoutNext = true
            checkout(s).status shouldBe Status.INTERNAL_SERVER_ERROR
            val old = attempts(s).single()
            old.state shouldBe "CREATING"
            summary(s).checkoutRestartToken shouldNotBe null
            val count = fake.created.get()
            code(checkout(s)) shouldBe "checkout_in_progress"
            fake.created.get() shouldBe count
            attempts(s).single().id shouldBe old.id
            attempts(s).single().sessionId shouldNotBe null
        }
        test("late creation recovery discovers the original Session then creates a new one only after confirmed expiration") {
            val s = scenario()
            fake.timeoutNext = true
            checkout(s).status shouldBe Status.INTERNAL_SERVER_ERROR
            val old = attempts(s).single()
            val provider = fake.sessions.values.single { it.attemptId.toString() == old.id }
            fake.expire(provider.id)
            clock.instant = clock.instant.plusSeconds(259200)
            val discoveries = fake.discoveries.get()
            val created = fake.created.get()
            val replacement = redirect(checkout(s))
            fake.discoveries.get() shouldBe discoveries + 1
            fake.created.get() shouldBe created + 1
            replacement.attemptId shouldNotBe old.id
            attempts(s).single { it.id == old.id }.sessionId shouldBe provider.id
        }
        test("ambiguous creation absence beyond replay retention remains reserved and produces a stable reconciliation code") {
            val s = scenario()
            val original = summary(s)
            val key = UUID.randomUUID().toString()
            fake.onCreate = { error("Simulated crash before provider response") }
            checkout(s, key, snapshot = original).status shouldBe Status.INTERNAL_SERVER_ERROR
            fake.onCreate = null
            clock.instant = clock.instant.plusSeconds(259200)
            code(checkout(s)) shouldBe "checkout_reconciliation_required"
            code(checkout(s, key, snapshot = original)) shouldBe "checkout_reconciliation_required"
            attempts(s).single().state shouldBe "CREATING"
            attempts(s).single().sessionId shouldBe null
        }
        test("provider discovery failure is retryable without releasing or replacing an uncertain authorization") {
            val s = scenario()
            fake.timeoutNext = true
            checkout(s).status shouldBe Status.INTERNAL_SERVER_ERROR
            val old = attempts(s).single()
            clock.instant = clock.instant.plusSeconds(259200)
            fake.onDiscover = { error("Simulated provider outage") }
            checkout(s).status shouldBe Status.INTERNAL_SERVER_ERROR
            attempts(s).single().state shouldBe "CREATING"
            fake.onDiscover = null
            fake.expire(
                fake.sessions.values
                    .single { it.attemptId.toString() == old.id }
                    .id,
            )
            redirect(checkout(s)).attemptId shouldNotBe old.id
        }
        test("invalid provider authorization cannot turn a locally active attempt into conclusive expiration") {
            val s = scenario()
            val old = redirect(checkout(s))
            val provider = fake.expire(session(old).id)
            fake.sessions[provider.id] =
                StripeCheckout(
                    provider.id,
                    provider.attemptId,
                    provider.amountMinor + 1,
                    provider.currency,
                    provider.liveMode,
                    "expired",
                    "unpaid",
                    null,
                    provider.expiresAt,
                    null,
                    null,
                    provider.authorizationProof,
                )
            checkout(s).status shouldBe Status.INTERNAL_SERVER_ERROR
            attempts(s).single().state shouldBe "OPEN"
            attempts(s).size shouldBe 1
        }
        test("recovery holding an old open snapshot cannot undo a concurrently committed webhook receipt") {
            val s = scenario()
            val old = redirect(checkout(s))
            val snapshot = summary(s)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val once = AtomicBoolean()
            fake.onRetrieve = {
                if (once.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            val recovering = CompletableFuture.supplyAsync { checkout(s, snapshot = snapshot) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            try {
                fake.change(session(old).id)
                webhook(session(old).id).status shouldBe Status.NO_CONTENT
            } finally {
                release.countDown()
            }
            code(recovering.get(20, TimeUnit.SECONDS)) shouldBe "payment_state_changed"
            attempts(s).single().state shouldBe "SUCCEEDED"
            summary(s).history.size shouldBe 1
        }
        test("two instances recovering one expiry authorize at most one replacement") {
            val s = scenario()
            val old = redirect(checkout(s))
            val snapshot = summary(s)
            fake.expire(session(old).id)
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            fake.onRetrieve = {
                if (it.id == session(old).id) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            val otherMode = if (mode == CheckoutMode.HOSTED) CheckoutMode.EMBEDDED else CheckoutMode.HOSTED
            val other =
                TestApplication.create(
                    clock = clock,
                    stripe = testStripeConfiguration(otherMode),
                    stripeGateway = fake,
                    database = app.database,
                )
            try {
                val a = CompletableFuture.supplyAsync { checkout(s, snapshot = snapshot) }
                val b = CompletableFuture.supplyAsync { checkout(s, snapshot = snapshot, instance = other) }
                entered.await(10, TimeUnit.SECONDS) shouldBe true
                release.countDown()
                val responses = listOf(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))
                responses.map { it.status.code }.sorted() shouldBe listOf(200, 409)
                code(responses.single { it.status == Status.CONFLICT }) shouldBe "checkout_recovery_conflict"
                attempts(s).size shouldBe 2
            } finally {
                release.countDown()
                other.runtime.close()
            }
        }
        test("a competing replacement that already settled is still detected before another partial Invoice charge") {
            val s = scenario()
            book(s)
            val old = redirect(checkout(s, amount = 100))
            val snapshot = summary(s)
            fake.expire(session(old).id)
            val entered = CountDownLatch(2)
            val first = CountDownLatch(1)
            val second = CountDownLatch(1)
            val order = AtomicInteger()
            fake.onRetrieve = {
                if (it.id == session(old).id) {
                    val release = if (order.incrementAndGet() == 1) first else second
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            val a = CompletableFuture.supplyAsync { checkout(s, amount = 100, snapshot = snapshot) }
            val b = CompletableFuture.supplyAsync { checkout(s, amount = 100, snapshot = snapshot) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            try {
                first.countDown()
                val replacement = redirect(CompletableFuture.anyOf(a, b).get(15, TimeUnit.SECONDS) as Response)
                fake.change(session(replacement).id)
                webhook(session(replacement).id).status shouldBe Status.NO_CONTENT
            } finally {
                first.countDown()
                second.countDown()
            }
            val responses = listOf(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))
            code(responses.single { it.status == Status.CONFLICT }) shouldBe "checkout_recovery_conflict"
            attempts(s).size shouldBe 3
            summary(s).history.size shouldBe 2
        }
        test("a concurrent manual deposit wins without a lock across Stripe and recovered money remains unapplied") {
            val s = scenario()
            val before = summary(s)
            val old = redirect(checkout(s))
            fake.change(session(old).id)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fake.onRetrieve = {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
            val recovering = CompletableFuture.supplyAsync { checkout(s, snapshot = before) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            try {
                val state = CommerceJson.asA(app.adminGet("/staff/requests/${s.id}").bodyString(), StaffRequestResponse.serializer())
                app
                    .adminPost(
                        "/financial-documents/${before.document.id}/payments",
                        """
                        {"documentVersion":${before.document.version},"amount":"${before.requiredAmount}","method":"CHECK",
                        "expectedProposalId":"${state.proposal!!.id}"}
                        """.trimIndent(),
                    ).status shouldBe Status.CREATED
            } finally {
                release.countDown()
            }
            code(recovering.get(20, TimeUnit.SECONDS)) shouldBe "payment_state_changed"
            attempts(s).single().outcome shouldBe "RECONCILIATION_REQUIRED"
            attempts(s).single().ledgerPaymentId shouldNotBe null
            summary(s).history.size shouldBe 1
            summary(s).reconciliationPending shouldBe true
        }
        test("a restarted instance recovers a stale active Session without the original browser key") {
            val s = scenario()
            val old = redirect(checkout(s))
            fake.expire(session(old).id)
            restart()
            redirect(checkout(s)).attemptId shouldNotBe old.attemptId
            attempts(s).size shouldBe 2
        }
    }
