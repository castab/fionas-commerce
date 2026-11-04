package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.http.CheckoutResponse
import io.github.castab.fionas.commerce.http.CustomerPaymentResponse
import io.github.castab.fionas.commerce.http.PaymentAccessResponse
import io.github.castab.fionas.commerce.http.PaymentRecordResponse
import io.github.castab.fionas.commerce.http.StaffRequestResponse
import io.github.castab.fionas.commerce.http.StripeAttemptsResponse
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TEST_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal fun testStripeConfiguration() =
    StripeConfiguration(
        "sk_test_fake_no_network_12345",
        "whsec_fake_no_network_12345",
        ByteArray(32) { it.toByte() },
        "https://payments.test",
        "acct_test",
        false,
    )

internal class PaymentTestClock : Clock() {
    var instant: Instant = TEST_INSTANT

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = instant
}

internal class FakeStripe(
    private val configuration: StripeConfiguration,
) : StripeGateway {
    val authorizations = ConcurrentHashMap<UUID, StripeAttempt>()
    val sessions = ConcurrentHashMap<String, StripeCheckout>()
    val created = AtomicInteger()
    var timeoutNext = false
    var onCreate: (() -> Unit)? = null
    var onRetrieve: ((StripeCheckout) -> Unit)? = null
    var onDiscover: (() -> Unit)? = null
    val retrieves = AtomicInteger()
    val discoveries = AtomicInteger()

    override fun create(attempt: StripeAttempt): StripeCheckout {
        onCreate?.invoke()
        authorizations[attempt.id] = attempt
        val session =
            sessions.computeIfAbsent("cs_test_${attempt.id}") {
                created.incrementAndGet()
                StripeCheckout(
                    it,
                    attempt.id,
                    attempt.authorizedMinor,
                    "usd",
                    false,
                    "open",
                    "unpaid",
                    null,
                    attempt.expiresAt,
                    "https://checkout.stripe.com/c/pay/$it",
                    null,
                    configuration.authorizationProof(attempt),
                )
            }
        if (timeoutNext) {
            timeoutNext = false
            error("Simulated lost Stripe response")
        }
        return session
    }

    override fun retrieve(sessionId: String): StripeCheckout {
        retrieves.incrementAndGet()
        val result = checkNotNull(sessions[sessionId])
        onRetrieve?.invoke(result)
        return result
    }

    override fun discover(attempt: StripeAttempt): StripeCheckout? {
        discoveries.incrementAndGet()
        onDiscover?.invoke()
        return sessions.values.singleOrNull { it.attemptId == attempt.id }
    }

    override fun expire(sessionId: String): StripeCheckout = change(sessionId, "expired", "unpaid")

    fun change(
        sessionId: String,
        state: String = "complete",
        paid: String = "paid",
    ): StripeCheckout {
        val original = checkNotNull(sessions[sessionId])
        return StripeCheckout(
            original.id,
            original.attemptId,
            original.amountMinor,
            original.currency,
            original.liveMode,
            state,
            paid,
            if (state == "complete") "pi_${original.attemptId}" else original.paymentIntentId,
            original.expiresAt,
            if (state == "open") original.url else null,
            TEST_INSTANT,
            original.authorizationProof,
        ).also { sessions[sessionId] = it }
    }

    override fun verify(
        rawBody: String,
        signature: String,
    ): StripeSignal {
        if (signature != "valid") throw CommerceFailure.ValidationFailed("Invalid Stripe signature")
        val parts = rawBody.split('|')
        return StripeSignal(parts[0], parts[1], parts[2], false, null)
    }
}

class StripePaymentsSpec :
    FunSpec({
        lateinit var app: TestApplication
        lateinit var fake: FakeStripe
        lateinit var clock: PaymentTestClock
        val config = testStripeConfiguration()
        beforeSpec {
            fake = FakeStripe(config)
            clock = PaymentTestClock()
            app = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake)
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            (FionaPermissions.PaymentAccessManage in grants) shouldBe false
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants + FionaPermissions.PaymentAccessManage)
        }
        afterSpec { app.close() }
        afterTest { fake.onCreate = null }

        fun initial(email: String = "stripe-${UUID.randomUUID()}@example.com"): String {
            val id = app.createInquiry(email = email)
            app
                .adminPost(
                    "/staff/requests/$id/proposals",
                    """{"expectedDocumentVersion":1,"terms":{"type":"PERCENTAGE","percentage":"20"}}""",
                ).status shouldBe Status.OK
            return id
        }

        fun staff(id: String) = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())

        fun link(id: String): PaymentAccessResponse {
            val response = app.adminPost("/staff/requests/$id/payment-access")
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), PaymentAccessResponse.serializer())
        }

        fun secret(link: PaymentAccessResponse) = checkNotNull(link.url).substringAfter('#')

        fun publicRequest(
            link: PaymentAccessResponse,
            method: Method = Method.GET,
            suffix: String = "",
            body: String = "",
            key: String? = null,
        ): Response {
            var request =
                Request(method, "/public/payments/${link.publicId}$suffix")
                    .header("X-Fionas-Payment-Token", secret(link))
                    .header("Content-Type", "application/json")
                    .body(body)
            key?.let { request = request.header("Idempotency-Key", it) }
            return app.http(request)
        }

        fun summary(link: PaymentAccessResponse): CustomerPaymentResponse {
            val response = publicRequest(link)
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            return CommerceJson.asA(response.bodyString(), CustomerPaymentResponse.serializer())
        }

        fun checkout(
            link: PaymentAccessResponse,
            key: String = UUID.randomUUID().toString(),
            amount: Long? = null,
            snapshot: CustomerPaymentResponse = summary(link),
        ): Response {
            val amountJson = amount?.let { ",\"amountMinor\":$it" } ?: ""
            return publicRequest(
                link,
                Method.POST,
                "/checkout-sessions",
                """
                {"expectedDocumentVersion":${snapshot.document.version},
                "expectedBusinessRevision":${snapshot.document.businessRevision}$amountJson}
                """.trimIndent(),
                key,
            )
        }

        fun redirect(response: Response): CheckoutResponse {
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), CheckoutResponse.serializer())
        }

        fun session(redirect: CheckoutResponse) = fake.sessions.values.single { it.attemptId.toString() == redirect.attemptId }

        fun webhook(
            sessionId: String,
            type: String = "checkout.session.completed",
            event: String = "evt_${UUID.randomUUID()}",
            signature: String = "valid",
        ) = app.http(
            Request(Method.POST, "/integrations/stripe/webhook").header("Stripe-Signature", signature).body("$event|$type|$sessionId"),
        )

        fun paid(link: PaymentAccessResponse): CheckoutResponse {
            val redirect = redirect(checkout(link))
            fake.change(session(redirect).id)
            webhook(session(redirect).id).status shouldBe Status.NO_CONTENT
            return redirect
        }

        fun attempts(id: String): StripeAttemptsResponse =
            CommerceJson.asA(app.adminGet("/staff/requests/$id/payment-attempts").bodyString(), StripeAttemptsResponse.serializer())

        fun reviseDeposit(id: String) {
            val state = staff(id)
            app
                .adminPost(
                    "/staff/requests/$id/proposals/deposit-revisions",
                    """
                    {"expectedDocumentVersion":${state.financial.version},"expectedDepositRequirementRevision":1,
                    "expectedQuoteRevision":${state.currentQuoteRevision},"terms":{"type":"PERCENTAGE","percentage":"25"}}
                    """.trimIndent(),
                ).status shouldBe
                Status.OK
        }

        test("stable retrieval stores only a hash and inquiry-specific capabilities separate returning customers") {
            val email = "same-${UUID.randomUUID()}@example.com"
            val first = initial(email)
            val firstLink = link(first)
            link(first) shouldBe firstLink
            CommerceJson.asA(
                app.adminGet("/staff/requests/$first/payment-access").bodyString(),
                PaymentAccessResponse.serializer(),
            ) shouldBe
                firstLink
            val secondLink = link(initial(email))
            firstLink.publicId shouldNotBe secondLink.publicId
            secret(firstLink) shouldNotBe secret(secondLink)
            app.database
                .strings("SELECT token_hash FROM fionas.inquiry_payment_access WHERE inquiry_id = '$first'")
                .single()
                .length shouldBe 64
            publicRequest(firstLink).bodyString().let { body ->
                listOf(email, "issuedBy", "servicePlan", "requestedService", "externalReference", secret(firstLink)).forEach {
                    body shouldNotContain
                        it
                }
            }
        }
        test("rotation and revocation immediately remove public access but keep the public identifier") {
            val id = initial()
            val old = link(id)
            val rotated =
                CommerceJson.asA(
                    app.adminPost("/staff/requests/$id/payment-access/rotate").bodyString(),
                    PaymentAccessResponse.serializer(),
                )
            rotated.publicId shouldBe old.publicId
            secret(rotated) shouldNotBe secret(old)
            publicRequest(old).status shouldBe Status.NOT_FOUND
            app.adminPost("/staff/requests/$id/payment-access/revoke").status shouldBe Status.OK
            publicRequest(rotated).status shouldBe Status.NOT_FOUND
            app.adminGet("/staff/requests/$id/payment-access").bodyString() shouldNotContain "https://payments.test"
        }
        test("management requires the new explicit permission and a USER even for a privileged SERVICE") {
            val id = initial()
            app.http(Request(Method.GET, "/staff/requests/$id/payment-access")).status shouldBe Status.UNAUTHORIZED
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants - FionaPermissions.PaymentAccessManage)
            try {
                app.adminGet("/staff/requests/$id/payment-access").status shouldBe Status.FORBIDDEN
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
            }
            val service = app.provisionService("stripe-${UUID.randomUUID()}", grants)
            app.http(Request(Method.POST, "/staff/requests/$id/payment-access").withBearer(app.serviceToken(service))).status shouldBe
                Status.FORBIDDEN
        }
        test("an Estimate is ineligible and GET never issues access") {
            val id = app.createInquiry()
            app.adminGet("/staff/requests/$id/payment-access").status shouldBe Status.OK
            app.database.strings("SELECT inquiry_id FROM fionas.inquiry_payment_access WHERE inquiry_id = '$id'").size shouldBe 0
            app.adminPost("/staff/requests/$id/payment-access").status shouldBe Status.CONFLICT
        }
        test("exact deposit books once, retains business evidence and the same link takes partial and final Invoice payments") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val deposit = paid(link)
            val booked = summary(link)
            booked.lifecycle shouldBe "BOOKED"
            booked.document.stage shouldBe "INVOICE"
            booked.document.acceptedQuoteRevision shouldBe before.document.businessRevision
            BigDecimal(booked.paidAmount).compareTo(BigDecimal(before.requiredAmount)) shouldBe 0
            webhook(session(deposit).id, event = "evt_other_${UUID.randomUUID()}").status shouldBe Status.NO_CONTENT
            summary(link).history.size shouldBe 1
            val partial = redirect(checkout(link, amount = 100))
            fake.change(session(partial).id)
            webhook(session(partial).id).status shouldBe Status.NO_CONTENT
            summary(link).history.size shouldBe 2
            paid(link)
            summary(link).balance.toBigDecimal().signum() shouldBe 0
            summary(link).canPay shouldBe false
            summary(link).lifecycle shouldBe "BOOKED"
            attempts(id).items.size shouldBe 3
        }
        test("complete deposit is server-authorized; partial, excessive, zero and stale inputs write no attempt") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val required = BigDecimal(before.requiredAmount).movePointRight(2).longValueExact()
            listOf(0L, -1L, required - 1, required + 1).forEach { checkout(link, amount = it).status shouldBe Status.UNPROCESSABLE_ENTITY }
            checkout(link, snapshot = before.copy(document = before.document.copy(businessRevision = 999))).status shouldBe Status.CONFLICT
            attempts(id).items.size shouldBe 0
        }
        test("durable idempotency returns one session and rejects changed input and a second active key") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val key = UUID.randomUUID().toString()
            val first = redirect(checkout(link, key, snapshot = before))
            redirect(checkout(link, key, snapshot = before)) shouldBe first
            checkout(link, key, amount = 100, snapshot = before).status shouldBe Status.CONFLICT
            checkout(link, snapshot = before).status shouldBe Status.CONFLICT
            attempts(id).items.size shouldBe 1
            summary(link).unavailableReason shouldBe "PAYMENT_IN_PROGRESS"
        }
        test("timeout after provider creation recovers the same session and committed authorization") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val key = UUID.randomUUID().toString()
            fake.timeoutNext = true
            checkout(link, key, snapshot = before).status shouldBe Status.INTERNAL_SERVER_ERROR
            attempts(id).items.single().state shouldBe "CREATING"
            val recovered = redirect(checkout(link, key, snapshot = before))
            attempts(id).items.single().sessionId shouldBe session(recovered).id
        }
        test("two instances racing the same key cannot create two active sessions") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val key = UUID.randomUUID().toString()
            val other = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake, database = app.database)
            try {
                val first = CompletableFuture.supplyAsync { checkout(link, key, snapshot = before) }
                val second =
                    CompletableFuture.supplyAsync {
                        other.http(
                            Request(Method.POST, "/public/payments/${link.publicId}/checkout-sessions")
                                .header("X-Fionas-Payment-Token", secret(link))
                                .header("Idempotency-Key", key)
                                .header("Content-Type", "application/json")
                                .body(
                                    """
                                    {"expectedDocumentVersion":${before.document.version},
                                    "expectedBusinessRevision":${before.document.businessRevision}}
                                    """.trimIndent(),
                                ),
                        )
                    }
                redirect(first.get(20, TimeUnit.SECONDS)).attemptId shouldBe redirect(second.get(20, TimeUnit.SECONDS)).attemptId
            } finally {
                other.runtime.close()
            }
            attempts(id).items.size shouldBe 1
        }
        test("network call holds no association transaction and a concurrent republication yields an unapplied receipt") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fake.onCreate = {
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
            val creating = CompletableFuture.supplyAsync { checkout(link, snapshot = before) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            try {
                reviseDeposit(id)
            } finally {
                release.countDown()
            }
            val redirect = redirect(creating.get(20, TimeUnit.SECONDS))
            fake.onCreate = null
            fake.change(session(redirect).id)
            webhook(session(redirect).id).status shouldBe Status.NO_CONTENT
            summary(link).lifecycle shouldBe "QUOTED"
            summary(link).reconciliationPending shouldBe true
            attempts(id).items.single().outcome shouldBe "RECONCILIATION_REQUIRED"
            app.adminGet("/payments/unapplied").status shouldBe Status.OK
        }
        test("pending completion never records money; later success, duplicate events and old expiry cannot undo booking") {
            val id = initial()
            val link = link(id)
            val redirect = redirect(checkout(link))
            val session = session(redirect).id
            fake.change(session, paid = "unpaid")
            webhook(session).status shouldBe Status.NO_CONTENT
            summary(link).history.size shouldBe 0
            attempts(id).items.single().state shouldBe "PROCESSING"
            fake.change(session)
            val event = "evt_${UUID.randomUUID()}"
            webhook(session, "checkout.session.async_payment_succeeded", event).status shouldBe Status.NO_CONTENT
            webhook(session, "checkout.session.async_payment_succeeded", event).status shouldBe Status.NO_CONTENT
            webhook(session, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            summary(link).history.size shouldBe 1
            summary(link).lifecycle shouldBe "BOOKED"
        }
        test("expiry is confirmed by provider before a new session can be authorized") {
            val id = initial()
            val link = link(id)
            val redirect = redirect(checkout(link))
            fake.expire(session(redirect).id)
            webhook(session(redirect).id, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            attempts(id).items.single().state shouldBe "EXPIRED"
            summary(link).canPay shouldBe true
            redirect(checkout(link)).attemptId shouldNotBe redirect.attemptId
        }
        test("webhook settles an already initiated deposit after link revocation") {
            val id = initial()
            val link = link(id)
            val redirect = redirect(checkout(link))
            app.adminPost("/staff/requests/$id/payment-access/revoke").status shouldBe Status.OK
            fake.change(session(redirect).id)
            webhook(session(redirect).id).status shouldBe Status.NO_CONTENT
            staff(id)
                .inquiry.lifecycle.stage.name shouldBe "BOOKED"
            publicRequest(link).status shouldBe Status.NOT_FOUND
        }
        test("manual payment while Checkout is open prevents a second deposit booking but retains captured money") {
            val id = initial()
            val link = link(id)
            val summary = summary(link)
            val redirect = redirect(checkout(link))
            val staff = staff(id)
            app
                .adminPost(
                    "/financial-documents/${staff.financial.id}/payments",
                    """
                    {"documentVersion":${staff.financial.version},"amount":"${summary.requiredAmount}","method":"CHECK",
                    "expectedProposalId":"${staff.proposal!!.id}"}
                    """.trimIndent(),
                ).status shouldBe
                Status.CREATED
            fake.change(session(redirect).id)
            webhook(session(redirect).id).status shouldBe Status.NO_CONTENT
            attempts(id).items.single().outcome shouldBe "RECONCILIATION_REQUIRED"
            summary(link).history.size shouldBe 1
        }
        test("database failure returns non-success and a retry records the captured payment exactly once") {
            val id = initial()
            val link = link(id)
            val redirect = redirect(checkout(link))
            fake.change(session(redirect).id)
            app.database.execute(
                "CREATE FUNCTION fionas.fail_stripe_test() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test failure''; END'",
            )
            app.database.execute(
                "CREATE TRIGGER fail_stripe_test BEFORE UPDATE ON fionas.stripe_payment_attempts FOR EACH ROW EXECUTE FUNCTION fionas.fail_stripe_test()",
            )
            val event = "evt_${UUID.randomUUID()}"
            try {
                webhook(session(redirect).id, event = event).status shouldBe Status.INTERNAL_SERVER_ERROR
                summary(link).lifecycle shouldBe "QUOTED"
                summary(link).history.size shouldBe 0
            } finally {
                app.database.execute("DROP TRIGGER fail_stripe_test ON fionas.stripe_payment_attempts")
                app.database.execute("DROP FUNCTION fionas.fail_stripe_test()")
            }
            webhook(session(redirect).id, event = event).status shouldBe Status.NO_CONTENT
            summary(link).history.size shouldBe 1
        }
        test("invalid signature writes nothing; external adjustments are durable exceptions and do not fabricate refunds") {
            val id = initial()
            val link = link(id)
            val redirect = paid(link)
            val before = app.database.count("fionas.stripe_webhook_events")
            webhook(session(redirect).id, signature = "wrong").status shouldBe Status.UNPROCESSABLE_ENTITY
            app.database.count("fionas.stripe_webhook_events") shouldBe before
            webhook("re_test", "refund.created").status shouldBe Status.NO_CONTENT
            app.database.strings("SELECT disposition FROM fionas.stripe_webhook_events WHERE object_id = 're_test'").last() shouldBe
                "EXTERNAL_ADJUSTMENT"
            summary(link)
                .history
                .single()
                .refundedAmount
                .toBigDecimal()
                .signum() shouldBe 0
        }
        test("a split manual receipt reveals only this inquiry's allocated and refunded portions") {
            val a = initial()
            val b = initial()
            val aLink = link(a)
            val bLink = link(b)
            paid(aLink)
            paid(bLink)
            val payment =
                CommerceJson.asA(
                    app
                        .adminPost(
                            "/payments",
                            """{"amount":"10.00","currency":"USD","method":"CASH"}""",
                        ).bodyString(),
                    PaymentRecordResponse.serializer(),
                )

            fun allocate(
                id: String,
                amount: String,
            ) {
                val state = staff(id)
                app
                    .adminPost(
                        "/payments/${payment.paymentId}/allocations",
                        """
                        {"documentId":"${state.financial.id}","documentVersion":${state.financial.version},"amount":"$amount"}
                        """.trimIndent(),
                    ).status shouldBe Status.CREATED
            }
            allocate(a, "1.00")
            allocate(b, "9.00")
            summary(aLink).history.map { it.amount.toBigDecimal().stripTrailingZeros() }.contains(BigDecimal.ONE) shouldBe true
            summary(aLink).history.none { it.amount.toBigDecimal().compareTo(BigDecimal.TEN) == 0 } shouldBe true
            val history = app.context.financialLedger.paymentHistory(UUID.fromString(payment.paymentId))
            val allocation = history.allocations.single { it.financialDocumentReference.id.toString() == staff(b).financial.id }
            app
                .adminPost(
                    "/payments/${payment.paymentId}/refunds",
                    """
                    {"amount":"9.00","currency":"USD","method":"CASH","allocations":[{"paymentAllocationId":"${allocation.id}",
                    "amount":"9.00"}]}
                    """.trimIndent(),
                ).status shouldBe Status.CREATED
            summary(aLink).history.all { it.refundedAmount.toBigDecimal().signum() == 0 } shouldBe true
            summary(bLink).history.any { it.refundedAmount.toBigDecimal().compareTo(BigDecimal("9.00")) == 0 } shouldBe true
        }
        test("a verified full refund resolves the collection block through runtime facts while preserving the exception") {
            val id = initial()
            val link = link(id)
            val pending = redirect(checkout(link))
            reviseDeposit(id)
            fake.change(session(pending).id)
            webhook(session(pending).id).status shouldBe Status.NO_CONTENT
            summary(link).reconciliationPending shouldBe true
            val attempt = attempts(id).items.single()
            val amount = BigDecimal.valueOf(attempt.authorizedMinor, 2).toPlainString()
            app
                .adminPost(
                    "/payments/${attempt.ledgerPaymentId}/refunds",
                    """
                    {"amount":"$amount","currency":"USD","method":"CARD","externalReference":{"provider":"stripe",
                    "reference":"re_${UUID.randomUUID()}"}}
                    """.trimIndent(),
                ).status shouldBe Status.CREATED
            summary(link).reconciliationPending shouldBe false
            summary(link).canPay shouldBe true
            attempts(id).items.single().outcome shouldBe "RECONCILIATION_REQUIRED"
        }
        test("Invoice amendments invalidate an open authorization and the stable link follows new business obligations") {
            val id = initial()
            val link = link(id)
            paid(link)
            val before = summary(link)
            val pending = redirect(checkout(link, amount = 100))
            val state = staff(id)
            app
                .adminPost(
                    "/financial-documents/${state.financial.id}/change-orders",
                    """
                    {"expectedVersion":${state.financial.version},"expectedInvoiceRevision":${state.currentInvoiceRevision},
                    "lines":[{"key":"amended","description":"Amended service","unitPrice":"2000.00","taxAmount":"0",
                    "currency":"USD"}]}
                    """.trimIndent(),
                ).status shouldBe
                Status.OK
            summary(link).document.businessRevision shouldBe before.document.businessRevision + 1
            fake.change(session(pending).id)
            webhook(session(pending).id).status shouldBe Status.NO_CONTENT
            summary(link).reconciliationPending shouldBe true
            attempts(id).items.single { it.id == pending.attemptId }.outcome shouldBe "RECONCILIATION_REQUIRED"
            summary(link).history.size shouldBe 1
        }
        test("Invoice settlement by staff while Checkout is open retains a late Stripe capture as unapplied") {
            val id = initial()
            val link = link(id)
            paid(link)
            val before = summary(link)
            val pending = redirect(checkout(link, amount = 100))
            app
                .adminPost(
                    "/financial-documents/${before.document.id}/payments",
                    """{"documentVersion":${before.document.version},"amount":"${before.balance}","method":"CASH"}""",
                ).status shouldBe
                Status.CREATED
            fake.change(session(pending).id)
            webhook(session(pending).id).status shouldBe Status.NO_CONTENT
            summary(link).balance.toBigDecimal().signum() shouldBe 0
            summary(link).history.size shouldBe 2
            attempts(id).items.single { it.id == pending.attemptId }.outcome shouldBe "RECONCILIATION_REQUIRED"
        }
        test("two captured authorizations can accept only one canonical deposit") {
            val id = initial()
            val link = link(id)
            val first = redirect(checkout(link))
            fake.expire(session(first).id)
            webhook(session(first).id, "checkout.session.expired").status shouldBe Status.NO_CONTENT
            val second = redirect(checkout(link))
            fake.change(session(first).id)
            fake.change(session(second).id)
            val a = CompletableFuture.supplyAsync { webhook(session(first).id) }
            val b = CompletableFuture.supplyAsync { webhook(session(second).id) }
            a.get(20, TimeUnit.SECONDS).status shouldBe Status.NO_CONTENT
            b.get(20, TimeUnit.SECONDS).status shouldBe Status.NO_CONTENT
            attempts(id).items.map { it.outcome }.sorted() shouldBe listOf("APPLIED", "RECONCILIATION_REQUIRED")
            summary(link).history.size shouldBe 1
            summary(link).lifecycle shouldBe "BOOKED"
        }
        test("unknown creation after replay retention never creates a replacement or releases its reservation") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val key = UUID.randomUUID().toString()
            fake.onCreate = { error("Simulated crash before provider call") }
            checkout(link, key, snapshot = before).status shouldBe Status.INTERNAL_SERVER_ERROR
            fake.onCreate = null
            val count = fake.created.get()
            clock.instant = clock.instant.plusSeconds(172800)
            checkout(link, key, snapshot = before).status shouldBe Status.CONFLICT
            fake.created.get() shouldBe count
            attempts(id).items.single().state shouldBe "CREATING"
        }
        test("disabled Stripe leaves existing inquiry and staff workflows available") {
            TestApplication.create().use { disabled ->
                val id = disabled.createInquiry()
                disabled.adminGet("/staff/requests/$id").status shouldBe Status.OK
                disabled
                    .http(
                        Request(Method.GET, "/public/payments/${UUID.randomUUID()}").header("X-Fionas-Payment-Token", "test"),
                    ).status shouldBe
                    Status.SERVICE_UNAVAILABLE
                disabled.http(Request(Method.POST, "/integrations/stripe/webhook").header("Stripe-Signature", "test")).status shouldBe
                    Status.SERVICE_UNAVAILABLE
            }
        }
        test("SERVED can pay but CLOSED can only read or revoke existing access") {
            val id = initial()
            val link = link(id)
            paid(link)
            app.adminPost("/inquiries/$id/served").status shouldBe Status.OK
            summary(link).lifecycle shouldBe "SERVED"
            summary(link).canPay shouldBe true
            paid(link)
            summary(link).lifecycle shouldBe "SERVED"
            app.adminPost("/inquiries/$id/close").status shouldBe Status.OK
            summary(link).lifecycle shouldBe "CLOSED"
            summary(link).unavailableReason shouldBe "CLOSED"
            checkout(link).status shouldBe Status.CONFLICT
            app.adminGet("/staff/requests/$id/payment-access").status shouldBe Status.OK
            app.adminPost("/staff/requests/$id/payment-access/rotate").status shouldBe Status.CONFLICT
            app.adminPost("/staff/requests/$id/payment-access/revoke").status shouldBe Status.OK
            app.adminPost("/staff/requests/$id/payment-access").status shouldBe Status.CONFLICT
        }
        test("restart after ambiguous creation preserves authorization and discovers rather than creating after retention") {
            val id = initial()
            val link = link(id)
            val before = summary(link)
            val key = UUID.randomUUID().toString()
            fake.timeoutNext = true
            checkout(link, key, snapshot = before).status shouldBe Status.INTERNAL_SERVER_ERROR
            val count = fake.created.get()
            app.runtime.close()
            clock.instant = clock.instant.plusSeconds(172800)
            app = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake, database = app.database)
            val recovered = redirect(checkout(link, key, snapshot = before))
            fake.created.get() shouldBe count
            fake.change(session(recovered).id)
            val event = "evt_restart_${UUID.randomUUID()}"
            webhook(session(recovered).id, event = event).status shouldBe Status.NO_CONTENT
            summary(link).lifecycle shouldBe "BOOKED"
            val paymentId = attempts(id).items.single().ledgerPaymentId
            app.runtime.close()
            app = TestApplication.create(clock = clock, stripe = config, stripeGateway = fake, database = app.database)
            webhook(session(recovered).id, event = event).status shouldBe Status.NO_CONTENT
            summary(link).history.size shouldBe 1
            attempts(id).items.single().ledgerPaymentId shouldBe paymentId
        }
    })
