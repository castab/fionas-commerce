package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.http.CheckoutResponse
import io.github.castab.fionas.commerce.http.CustomerPaymentResponse
import io.github.castab.fionas.commerce.http.PaymentAccessResponse
import io.github.castab.fionas.commerce.http.StaffRequestResponse
import io.github.castab.fionas.commerce.http.StripeAttemptsResponse
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TestApplication
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

class StripeRecoverySpec :
    FunSpec({
        val config = testStripeConfiguration()
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
            fake.timeoutNext = false
        }

        data class Scenario(
            val id: String,
            val link: PaymentAccessResponse,
        )

        fun scenario(): Scenario {
            val id = app.createInquiry()
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
        ): Response {
            val amountJson = amount?.let { ",\"amountMinor\":$it" } ?: ""
            return instance.http(
                Request(Method.POST, "/public/payments/${s.link.publicId}/checkout-sessions")
                    .header("X-Fionas-Payment-Token", token(s))
                    .header("Idempotency-Key", key)
                    .header("Content-Type", "application/json")
                    .body(
                        """
                        {"expectedDocumentVersion":${snapshot.document.version},
                        "expectedBusinessRevision":${snapshot.document.businessRevision}$amountJson}
                        """.trimIndent(),
                    ),
            )
        }

        fun redirect(response: Response): CheckoutResponse {
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), CheckoutResponse.serializer())
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

        fun restart() {
            val database = app.database
            app.runtime.close()
            app = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake, database = database)
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
            fake.change(session(old).id, paid = "unpaid")
            clock.instant = clock.instant.plusSeconds(259200)
            code(checkout(s)) shouldBe "payment_processing"
            attempts(s).single().state shouldBe "PROCESSING"
            attempts(s).single().ledgerPaymentId shouldBe null
            webhook(session(old).id, "checkout.session.async_payment_failed").status shouldBe Status.NO_CONTENT
            attempts(s).single().state shouldBe "FAILED"
            redirect(checkout(s)).attemptId shouldNotBe old.attemptId
        }
        test("a lost creation response reuses the original provider key inside its safe window") {
            val s = scenario()
            fake.timeoutNext = true
            checkout(s).status shouldBe Status.INTERNAL_SERVER_ERROR
            val old = attempts(s).single()
            old.state shouldBe "CREATING"
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
            val other = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake, database = app.database)
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
    })
