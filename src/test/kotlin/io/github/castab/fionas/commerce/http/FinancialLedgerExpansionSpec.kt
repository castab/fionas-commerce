package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.financial.AllocatePayment
import io.github.castab.fionas.commerce.financial.FinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.http4k.core.Response
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fiona's first-snapshot creation, unapplied payments, and partial allocations through the complete handler. */
class FinancialLedgerExpansionSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0

        fun Response.document() = CommerceJson.asA(bodyString(), FinancialDocumentResponse.serializer())

        fun Response.payment() = CommerceJson.asA(bodyString(), PaymentRecordResponse.serializer())

        fun Response.allocation() = CommerceJson.asA(bodyString(), PaymentAllocationResponse.serializer())

        fun create(
            stage: String,
            inquiryId: String = application.createInquiry(),
            pricing: String = pricingBody(revision),
        ): Response = application.adminPost("/inquiries/$inquiryId/financial-documents", """{"stage":"$stage",${pricing.drop(1)}""")

        fun record(
            amount: String = "500.00",
            currency: String = "USD",
            extra: String = "",
        ): Response = application.adminPost("/payments", """{"amount":"$amount","currency":"$currency","method":"CARD"$extra}""")

        fun allocate(
            paymentId: String,
            documentId: String,
            version: Int,
            amount: String,
        ): Response =
            application.adminPost(
                "/payments/$paymentId/allocations",
                """{"documentId":"$documentId","documentVersion":$version,"amount":"$amount"}""",
            )

        fun rows(
            table: String,
            column: String,
            id: String,
        ) = application.database
            .strings("SELECT count(*) FROM $table WHERE $column = '$id'")
            .single()
            .toInt()

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
            application.adminCookie
        }
        afterSpec { application.close() }

        test("Estimate, Quote, and Invoice each begin a new inquiry-owned lineage at version 1") {
            val pricingSources = JdbiFinancialDocumentPricingRepository()
            listOf("ESTIMATE", "QUOTE", "INVOICE").forEach { stage ->
                val inquiryId = application.createInquiry()
                val response = create(stage, inquiryId, pricingBody(revision, extra = ",\"total\":\"0.01\",\"lines\":[]"))
                response.status shouldBe Status.CREATED
                val document = response.document()
                response.header("Location") shouldBe "/financial-documents/${document.id}"
                document.stage shouldBe stage
                document.version shouldBe 1
                document.previousVersion.shouldBeNull()
                document.inquiryId shouldBe inquiryId
                document.pricing.catalogRevision shouldBe revision
                document.pricing.guestCount shouldBe 75
                document.lines.isNotEmpty() shouldBe true
                document.total.toBigDecimal() shouldBe document.lines.map { it.total.toBigDecimal() }.reduce(BigDecimal::add)
                document.total shouldBe "681.25"
                document.reconciliation?.balance shouldBe document.total
                document.reconciliation?.grossAllocated shouldBe "0.00"
                application.transactor.inTransaction { transaction ->
                    val source = pricingSources.find(transaction, FinancialDocumentReference(UUID.fromString(document.id), Version.INITIAL))
                    source?.catalogRevision?.number shouldBe revision
                    source?.context?.guestCount shouldBe 75
                    application.context.financialLedger
                        .history(transaction, UUID.fromString(document.id))
                        .size shouldBe 1
                }
                rows("fionas.inquiry_financial_documents", "document_id", document.id) shouldBe 1
            }
        }

        test("direct Quote can become Invoice; direct Invoice has no earlier stage to transition from") {
            val quote = create("QUOTE").document()
            val invoice =
                application.adminPost("/financial-documents/${quote.id}/invoice", """{"expectedVersion":1}""")
            invoice.status shouldBe Status.OK
            invoice.document().version shouldBe 2
            invoice.document().previousVersion shouldBe 1

            val directInvoice = create("INVOICE").document()
            listOf("quote", "invoice").forEach { transition ->
                application
                    .adminPost("/financial-documents/${directInvoice.id}/$transition", """{"expectedVersion":1}""")
                    .status shouldBe Status.CONFLICT
            }
            val history = application.adminGet("/financial-documents/${directInvoice.id}/history")
            CommerceJson
                .asA(
                    history.bodyString(),
                    FinancialDocumentHistoryResponse.serializer(),
                ).versions
                .map { it.version } shouldContainExactly
                listOf(1)
        }

        test("creation rejects missing inquiries, missing catalog revisions, invalid pricing, and unknown stages") {
            create("QUOTE", UUID.randomUUID().toString()).status shouldBe Status.NOT_FOUND
            create("INVOICE", pricing = pricingBody(revision + 100)).status shouldBe Status.NOT_FOUND
            create("ESTIMATE", pricing = pricingBody(revision, guests = 0)).status shouldBe Status.UNPROCESSABLE_ENTITY
            create("OTHER").status shouldBe Status.UNPROCESSABLE_ENTITY
        }

        test("standalone recording persists a payment with no allocation and preserves exact receipt details") {
            val response =
                record(
                    "300.00",
                    "USD",
                    ",\"receivedAt\":\"2026-09-28T20:00:00Z\",\"externalReference\":{\"provider\":\"stripe\",\"reference\":\"pi_example\"}",
                )
            response.status shouldBe Status.CREATED
            val payment = response.payment()
            payment.amount shouldBe "300.00"
            payment.currency shouldBe "USD"
            payment.method shouldBe "CARD"
            payment.receivedAt shouldBe "2026-09-28T20:00:00Z"
            payment.externalReference shouldBe PaymentExternalReference("stripe", "pi_example")
            rows("commerce.payment_records", "payment_id", payment.paymentId) shouldBe 1
            rows("commerce.payment_allocations", "payment_id", payment.paymentId) shouldBe 0
            application.database.strings(
                "SELECT amount::text || ':' || currency || ':' || method FROM commerce.payment_records WHERE payment_id = '${payment.paymentId}'",
            ) shouldContainExactly listOf("300.00:USD:CARD")
            record("300.00", "USD", ",\"externalReference\":{\"provider\":\"stripe\",\"reference\":\"pi_example\"}")
                .status shouldBe Status.CONFLICT
        }

        test("standalone recording validates decimal, currency, minor units, and receipt fields") {
            listOf("-1", "bad", "1.001").forEach { record(it).status shouldBe Status.UNPROCESSABLE_ENTITY }
            record("10.00", "INVALID").status shouldBe Status.UNPROCESSABLE_ENTITY
            record("10.01", "JPY").status shouldBe Status.UNPROCESSABLE_ENTITY
            application
                .adminPost("/payments", """{"amount":300.00,"currency":"USD","method":"CARD"}""")
                .status shouldBe Status.BAD_REQUEST
            record("10.00", "USD", ",\"receivedAt\":\"not-a-time\"").status shouldBe Status.UNPROCESSABLE_ENTITY
            record("10.00", "USD", ",\"externalReference\":{\"provider\":\"\",\"reference\":\"x\"}")
                .status shouldBe Status.UNPROCESSABLE_ENTITY
        }

        test("one payment can be partially and repeatedly allocated across eligible documents") {
            val quote = create("QUOTE").document()
            val invoice = create("INVOICE").document()
            val payment = record().payment()
            val first = allocate(payment.paymentId, quote.id, 1, "150.00")
            first.status shouldBe Status.CREATED
            first.allocation().let {
                it.paymentId shouldBe payment.paymentId
                it.documentId shouldBe quote.id
                it.documentVersion shouldBe 1
                it.amount shouldBe "150.00"
                it.currency shouldBe "USD"
                it.reconciliation.grossAllocated shouldBe "150.00"
                it.reconciliation.netApplied shouldBe "150.00"
                it.reconciliation.balance.toBigDecimal() shouldBe quote.total.toBigDecimal() - BigDecimal("150.00")
            }
            allocate(payment.paymentId, invoice.id, 1, "200.00").status shouldBe Status.CREATED
            val third = allocate(payment.paymentId, quote.id, 1, "150.00")
            third.status shouldBe Status.CREATED
            third.allocation().reconciliation.grossAllocated shouldBe "300.00"
            rows("commerce.payment_allocations", "payment_id", payment.paymentId) shouldBe 3
            application
                .adminGet("/financial-documents/${invoice.id}")
                .document()
                .reconciliation
                ?.grossAllocated shouldBe "200.00"
            allocate(payment.paymentId, invoice.id, 1, "1.00").status shouldBe Status.UNPROCESSABLE_ENTITY
            rows("commerce.payment_allocations", "payment_id", payment.paymentId) shouldBe 3
        }

        test("allocation enforces payment existence, stage, latest version, and currency") {
            val estimate = create("ESTIMATE").document()
            val quote = create("QUOTE").document()
            val payment = record().payment()
            allocate(UUID.randomUUID().toString(), quote.id, 1, "10.00").status shouldBe Status.NOT_FOUND
            allocate(payment.paymentId, estimate.id, 1, "10.00").status shouldBe Status.UNPROCESSABLE_ENTITY
            application.adminPost("/financial-documents/${quote.id}/invoice", """{"expectedVersion":1}""").status shouldBe Status.OK
            allocate(payment.paymentId, quote.id, 1, "10.00").status shouldBe Status.CONFLICT
            val euroPayment = record("10.00", "EUR").payment()
            allocate(euroPayment.paymentId, quote.id, 2, "10.00").status shouldBe Status.UNPROCESSABLE_ENTITY
            rows("commerce.payment_allocations", "payment_id", payment.paymentId) shouldBe 0
            rows("commerce.payment_allocations", "payment_id", euroPayment.paymentId) shouldBe 0
        }

        test("competing allocations wait on the runtime payment lock and cannot exceed the receipt") {
            val firstDocument = create("QUOTE").document()
            val secondDocument = create("QUOTE").document()
            val payment = record().payment()
            val associations = JdbiInquiryFinancialDocumentRepository()
            val sources = JdbiFinancialDocumentPricingRepository()
            val firstBackendPid = AtomicInteger()
            val secondBackendPid = AtomicInteger()
            val firstInserted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondLineageLocked = CountDownLatch(1)
            val firstSources =
                object : FinancialDocumentPricingRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): FionasPricingInputs? {
                        // describeLocked runs after ledger.allocatePayment inserted the allocation,
                        // while this transaction still holds the payment row lock.
                        firstBackendPid.set(backendPid(transaction))
                        firstInserted.countDown()
                        check(releaseFirst.await(30, TimeUnit.SECONDS))
                        return sources.find(transaction, snapshot)
                    }
                }
            val secondAssociations =
                object : InquiryFinancialDocumentRepository by associations {
                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? {
                        val inquiryId = associations.lockInquiryOf(transaction, documentId)
                        secondBackendPid.set(backendPid(transaction))
                        secondLineageLocked.countDown()
                        return inquiryId
                    }
                }
            val firstAllocation =
                AllocatePayment(application.transactor, application.context.financialLedger, associations, firstSources, testClock)
            val secondAllocation =
                AllocatePayment(application.transactor, application.context.financialLedger, secondAssociations, sources, testClock)
            val executor = Executors.newFixedThreadPool(2)
            val first =
                CompletableFuture.supplyAsync(
                    {
                        firstAllocation(
                            AllocatePayment.Command(
                                UUID.fromString(payment.paymentId),
                                UUID.fromString(firstDocument.id),
                                Version.INITIAL,
                                BigDecimal("300.00"),
                            ),
                        )
                    },
                    executor,
                )
            try {
                firstInserted.await(30, TimeUnit.SECONDS) shouldBe true
                // The other document has a different association row, so its Fiona lock succeeds.
                val second =
                    CompletableFuture.supplyAsync(
                        {
                            secondAllocation(
                                AllocatePayment.Command(
                                    UUID.fromString(payment.paymentId),
                                    UUID.fromString(secondDocument.id),
                                    Version.INITIAL,
                                    BigDecimal("300.00"),
                                ),
                            )
                        },
                        executor,
                    )
                secondLineageLocked.await(30, TimeUnit.SECONDS) shouldBe true
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var blockedByFirst = false
                while (System.nanoTime() < deadline && !blockedByFirst) {
                    blockedByFirst =
                        firstBackendPid.get().toString() in
                        application.database.strings("SELECT unnest(pg_blocking_pids(${secondBackendPid.get()}))::text")
                    if (!blockedByFirst) Thread.sleep(10) // Poll only PostgreSQL's observed lock state.
                }
                blockedByFirst shouldBe true
                second.isDone shouldBe false
                releaseFirst.countDown()
                first
                    .get(30, TimeUnit.SECONDS)
                    .allocation.amount.amount shouldBe BigDecimal("300.00")
                val failure = shouldThrow<ExecutionException> { second.get(30, TimeUnit.SECONDS) }
                (failure.cause is CommerceFailure.InvariantViolated) shouldBe true
            } finally {
                releaseFirst.countDown()
                executor.shutdownNow()
            }
            rows("commerce.payment_allocations", "payment_id", payment.paymentId) shouldBe 1
            application.database
                .strings(
                    "SELECT COALESCE(sum(amount), 0)::text FROM commerce.payment_allocations WHERE payment_id = '${payment.paymentId}'",
                ).single()
                .toBigDecimal() shouldBe BigDecimal("300.00")
        }
    })

private fun backendPid(transaction: Transaction): Int =
    transaction.handle
        .createQuery("SELECT pg_backend_pid()")
        .mapTo(Int::class.javaObjectType)
        .one()
