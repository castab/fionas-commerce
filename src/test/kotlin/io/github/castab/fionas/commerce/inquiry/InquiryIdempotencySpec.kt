package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceErrorHandling
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokenAuthenticator
import io.github.castab.fionas.commerce.financial.InquiryDocumentAssociation
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.http.CreateInquiryRequest
import io.github.castab.fionas.commerce.http.InquiryEventType
import io.github.castab.fionas.commerce.http.InquiryReceiptResponse
import io.github.castab.fionas.commerce.http.PricedLineRequest
import io.github.castab.fionas.commerce.http.createInquiryRoute
import io.github.castab.fionas.commerce.http.fionaOpenApi
import io.github.castab.fionas.commerce.http.toResponse
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.github.castab.fionas.commerce.testing.testClock
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.contract.contract
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.core.then
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real PostgreSQL claims with forced, observed lock contention, never a JVM production lock. */
class InquiryIdempotencySpec :
    FunSpec({
        lateinit var app: TestApplication
        val submissions = JdbiInquirySubmissionRepository()
        val associations = JdbiInquiryFinancialDocumentRepository()
        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }
        val tables =
            listOf(
                "fionas.customers",
                "fionas.inquiries",
                "fionas.inquiry_submissions",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
            )

        fun counts() = tables.associateWith(app.database::count)

        fun command(key: InquirySubmissionKey = InquirySubmissionKey(UUID.randomUUID().toString())) =
            app.inquiryCommand(email = "race-${UUID.randomUUID()}@example.com", name = "Jane", message = "Birthday", key = key.value)

        fun operation(
            claims: InquirySubmissionRepository = submissions,
            owners: InquiryFinancialDocumentRepository = associations,
            lineIds: () -> UUID = UUID::randomUUID,
        ) = app.createInquiryOperation(
            submissions = claims,
            materialize =
                MaterializeInquiryFinancialDocument(
                    app.context.financialLedger,
                    owners,
                    JdbiFinancialDocumentAuthorshipRepository(),
                    newLineId = lineIds,
                ),
        )

        fun request(command: CreateInquiry.Command): Request {
            val dto =
                CreateInquiryRequest(
                    command.name.value,
                    command.email.value,
                    command.message?.value,
                    command.requestedService.toResponse(),
                    command.lines.map {
                        PricedLineRequest(
                            it.description,
                            it.subDescription,
                            it.quantity?.toPlainString(),
                            it.unitPrice.amount.toPlainString(),
                            it.taxAmount.amount.toPlainString(),
                            it.currency.currencyCode,
                        )
                    },
                    command.zipCode.value,
                    command.eventDate.value.toString(),
                    InquiryEventType.valueOf(command.eventType.name),
                )
            return Request(Method.POST, "/inquiries")
                .asFionasWeb(app)
                .withSubmissionKey(command.submissionKey.value)
                .header("Content-Type", "application/json")
                .body(CommerceJson.json.encodeToString(CreateInquiryRequest.serializer(), dto))
        }

        fun handler(operation: CreateInquiry) =
            CommerceErrorHandling.then(
                contract {
                    renderer = fionaOpenApi("test")
                    routes +=
                        createInquiryRoute(
                            operation::invoke,
                            AccessControl(
                                authentication(ServiceAccessTokenAuthenticator(app.context.serviceAccessTokens)),
                                app.authorization,
                            ),
                        )
                },
            )

        fun awaitDatabaseWait() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                if (app.database
                        .strings(
                            "SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND wait_event = 'transactionid' " +
                                "AND query LIKE '%inquiry_submissions%'",
                        ).isNotEmpty()
                ) {
                    return
                }
                Thread.sleep(10)
            }
            error("Expected a PostgreSQL waiter on the owner's unique submission key")
        }

        test("lost response is recovered through the full application HTTP path without generating or writing anything") {
            val input = command()
            val lineIds = AtomicInteger()
            val create =
                operation(lineIds = {
                    lineIds.incrementAndGet()
                    UUID.randomUUID()
                })
            val inquiry = create(input) // Committed result, as if its transport response was lost.
            val committed = counts()
            val generated = lineIds.get()
            generated shouldBe input.lines.size
            create(input) shouldBe inquiry
            lineIds.get() shouldBe generated
            val recovered = app.http(request(input))
            recovered.status shouldBe Status.CREATED
            recovered.header("Location") shouldBe "/inquiries/${inquiry.id.value}"
            CommerceJson.asA(recovered.bodyString(), InquiryReceiptResponse.serializer()) shouldBe
                InquiryReceiptResponse(inquiry.id.value.toString(), inquiry.createdAt.toString())
            counts() shouldBe committed
        }

        listOf(false, true).forEach { different ->
            test("overlapping ${if (different) "different" else "identical"} commands serialize to one committed result") {
                val start = CountDownLatch(1)
                val attempted = CountDownLatch(2)
                val claimed = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val pausing =
                    object : InquirySubmissionRepository by submissions {
                        override fun claim(
                            transaction: Transaction,
                            key: InquirySubmissionKey,
                            fingerprint: String,
                            inquiryId: InquiryId,
                            createdAt: java.time.Instant,
                        ): Boolean {
                            attempted.countDown()
                            val owned = submissions.claim(transaction, key, fingerprint, inquiryId, createdAt)
                            if (owned) {
                                claimed.countDown()
                                check(resume.await(30, TimeUnit.SECONDS))
                            }
                            return owned
                        }
                    }
                val http = handler(operation(claims = pausing))
                val first = command()
                // A different command differs only in one committed amount: the key can never swap commercial terms.
                val second =
                    if (different) {
                        first.copy(
                            lines =
                                first.lines.map {
                                    if (it.description == "Horchata") it.copy(unitPrice = Money(BigDecimal("0.55"), it.currency)) else it
                                },
                        )
                    } else {
                        first
                    }
                val before = counts()
                val calls =
                    listOf(first, second).map { input ->
                        CompletableFuture.supplyAsync {
                            check(start.await(30, TimeUnit.SECONDS))
                            http(request(input))
                        }
                    }
                try {
                    start.countDown()
                    attempted.await(30, TimeUnit.SECONDS) shouldBe true
                    claimed.await(30, TimeUnit.SECONDS) shouldBe true
                    awaitDatabaseWait()
                    calls.forEach { it.isDone shouldBe false }
                } finally {
                    resume.countDown()
                }
                val responses = calls.map { it.get(30, TimeUnit.SECONDS) }
                if (different) {
                    responses.count { it.status == Status.CREATED } shouldBe 1
                    val loser = responses.single { it.status == Status.CONFLICT }
                    CommerceJson.asA(loser.bodyString(), ErrorResponse.serializer()).code shouldBe "IDEMPOTENCY_KEY_REUSED"
                    val winnerIndex = responses.indexOfFirst { it.status == Status.CREATED }
                    val expected = listOf(first, second)[winnerIndex]
                    val winner = CommerceJson.asA(responses[winnerIndex].bodyString(), InquiryReceiptResponse.serializer()).id
                    val estimate =
                        app.transactor.inTransaction {
                            val id = checkNotNull(associations.initialEstimateOf(it, InquiryId(UUID.fromString(winner))))
                            app.context.financialLedger.latest(it, id)
                        }
                    estimate.lineItems.map { it.price.amount } shouldBe expected.lines.map { it.unitPrice.amount }
                } else {
                    responses.map { it.status } shouldBe listOf(Status.CREATED, Status.CREATED)
                    responses[0].bodyString() shouldBe responses[1].bodyString()
                    responses[0].header("Location") shouldBe responses[1].header("Location")
                }
                val after = counts()
                listOf(
                    "fionas.customers",
                    "fionas.inquiries",
                    "fionas.inquiry_submissions",
                    "commerce.financial_document_snapshots",
                    "fionas.inquiry_financial_documents",
                ).forEach {
                    after.getValue(it) shouldBe before.getValue(it) + 1
                }
            }
        }

        test("a waiting contender becomes claimant after the first owner's late failure rolls back") {
            val input = command()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val failingOwners =
                object : InquiryFinancialDocumentRepository by associations {
                    override fun associate(
                        transaction: Transaction,
                        association: InquiryDocumentAssociation,
                    ) {
                        associations.associate(transaction, association)
                        entered.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                        error("Injected failure after every business write")
                    }
                }
            val before = counts()
            val owner = CompletableFuture.supplyAsync { runCatching { operation(owners = failingOwners)(input) } }
            lateinit var waiter: CompletableFuture<Inquiry>
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                counts() shouldBe before
                waiter = CompletableFuture.supplyAsync { operation()(input) }
                awaitDatabaseWait()
                waiter.isDone shouldBe false
            } finally {
                resume.countDown()
            }
            owner.get(30, TimeUnit.SECONDS).isFailure shouldBe true
            val inquiry = waiter.get(30, TimeUnit.SECONDS)
            operation()(input) shouldBe inquiry
            app.database.count("fionas.inquiry_submissions") shouldBe before.getValue("fionas.inquiry_submissions") + 1
            app.database.count("fionas.inquiries") shouldBe before.getValue("fionas.inquiries") + 1
            app.database.count("commerce.financial_document_snapshots") shouldBe
                before.getValue("commerce.financial_document_snapshots") + 1
        }

        test("late failure releases its key and an incomplete claim cannot commit even with incorrect application code") {
            val input = command()
            val failing =
                object : InquiryFinancialDocumentRepository by associations {
                    override fun associate(
                        transaction: Transaction,
                        association: InquiryDocumentAssociation,
                    ) {
                        associations.associate(transaction, association)
                        error("Injected final write failure")
                    }
                }
            val before = counts()
            shouldThrowAny { operation(owners = failing)(input) }
            counts() shouldBe before
            shouldThrowAny {
                app.transactor.inTransaction {
                    submissions.claim(
                        it,
                        input.submissionKey,
                        input.fingerprint(),
                        InquiryId(UUID.randomUUID()),
                        testClock.instant(),
                    ) shouldBe
                        true
                }
            }
            counts() shouldBe before
            operation()(input)
            app.database.count("fionas.inquiry_submissions") shouldBe before.getValue("fionas.inquiry_submissions") + 1
        }
    })
