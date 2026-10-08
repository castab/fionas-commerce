package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.financial.AllocatePayment
import io.github.castab.fionas.commerce.financial.FinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.LineAuthorship
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.linesJson
import io.github.castab.fionas.commerce.testing.proposalJson
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
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

        fun Response.document() = CommerceJson.asA(bodyString(), FinancialDocumentResponse.serializer())

        fun Response.payment() = CommerceJson.asA(bodyString(), PaymentRecordResponse.serializer())

        fun Response.allocation() = CommerceJson.asA(bodyString(), PaymentAllocationResponse.serializer())

        fun Response.refund() = CommerceJson.asA(bodyString(), RecordedRefundResponse.serializer())

        fun create(
            stage: String,
            inquiryId: String = application.createInquiry(),
            lines: String = linesJson(acceptanceLines()),
            extra: String = "",
        ): Response = application.adminPost("/inquiries/$inquiryId/financial-documents", """{"stage":"$stage","lines":$lines$extra}""")

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

        fun refund(
            paymentId: String,
            amount: String,
            allocations: String = "[]",
            extra: String = "",
        ): Response =
            application.adminPost(
                "/payments/$paymentId/refunds",
                """{"amount":"$amount","currency":"USD","method":"OTHER","allocations":$allocations$extra}""",
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
            application.adminCookie
        }
        afterSpec { application.close() }

        test("Estimate, Quote, and Invoice each begin a new inquiry-owned lineage at version 1") {
            val authorship = JdbiFinancialDocumentAuthorshipRepository()
            listOf("ESTIMATE", "QUOTE", "INVOICE").forEach { stage ->
                val inquiryId = application.createInquiry()
                // A caller-supplied total is never authoritative: it is ignored and derived from the lines.
                val response = create(stage, inquiryId, extra = ",\"total\":\"0.01\"")
                response.status shouldBe Status.CREATED
                val document = response.document()
                response.header("Location") shouldBe "/financial-documents/${document.id}"
                document.stage shouldBe stage
                document.version shouldBe 1
                document.previousVersion.shouldBeNull()
                document.inquiryId shouldBe inquiryId
                document.linesAuthoredBy?.principalKind shouldBe "USER"
                document.lines.isNotEmpty() shouldBe true
                document.total.toBigDecimal() shouldBe document.lines.map { it.total.toBigDecimal() }.reduce(BigDecimal::add)
                document.total shouldBe "681.25"
                document.reconciliation?.balance shouldBe document.total
                document.reconciliation?.grossAllocated shouldBe "0.00"
                application.transactor.inTransaction { transaction ->
                    val source = authorship.find(transaction, FinancialDocumentReference(UUID.fromString(document.id), Version.INITIAL))
                    source?.author shouldBe application.adminId
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

        test("creation rejects missing inquiries, invalid lines, and unknown stages, writing nothing") {
            val inquiryId = application.createInquiry()
            val before = application.database.count("commerce.financial_document_snapshots")
            create("QUOTE", UUID.randomUUID().toString()).status shouldBe Status.NOT_FOUND
            create("INVOICE", inquiryId, lines = "[]").status shouldBe Status.UNPROCESSABLE_ENTITY
            create("ESTIMATE", inquiryId, lines = linesJson(listOf(COURTESY_DISCOUNT))).status shouldBe Status.UNPROCESSABLE_ENTITY
            create("ESTIMATE", inquiryId, lines = linesJson(listOf(CHURROS.copy(unitPrice = "450.001")))).status shouldBe
                Status.UNPROCESSABLE_ENTITY
            create("OTHER", inquiryId).status shouldBe Status.UNPROCESSABLE_ENTITY
            application.database.count("commerce.financial_document_snapshots") shouldBe before
        }

        test("a bespoke RELATED document records arbitrary staff lines with no catalog, and a staff change order edits them") {
            val created = create("ESTIMATE", lines = linesJson(listOf(CHURROS, COURTESY_DISCOUNT))).document()
            created.total shouldBe "400.00"
            val ids = created.lines.map { it.id }
            val changed =
                application.adminPost(
                    "/financial-documents/${created.id}/change-orders",
                    """{"expectedVersion":1,"lines":${proposalJson(
                        CHURROS.copy(unitPrice = "500.00").existing(ids[0]),
                        TestLine("Travel", unitPrice = "25.00").new("travel"),
                    )}}""",
                )
            changed.status shouldBe Status.OK
            val revised = changed.document()
            revised.version shouldBe 2
            revised.lines.map { it.description } shouldContainExactly listOf("Churro catering service", "Travel")
            revised.lines.first().id shouldBe ids[0]
            revised.total shouldBe "525.00"
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

        test("an applied partial refund reopens the invoice and reconciles its payment") {
            val invoice = create("INVOICE").document()
            val payment = record("200.00").payment()
            val allocation = allocate(payment.paymentId, invoice.id, 1, "200.00").allocation()
            val response =
                refund(
                    payment.paymentId,
                    "50.00",
                    """[{"paymentAllocationId":"${allocation.allocationId}","amount":"50.00"}]""",
                )
            response.status shouldBe Status.CREATED
            response.refund().let {
                it.paymentId shouldBe payment.paymentId
                it.method shouldBe "OTHER" // The refund method may differ from the card payment.
                it.amount shouldBe "50.00"
                it.refundedAt shouldBe "2026-09-26T18:30:00.123456Z"
                it.allocations.single().paymentAllocationId shouldBe allocation.allocationId
                it.allocations.single().amount shouldBe "50.00"
                it.reconciliation.paymentAmount shouldBe "200.00"
                it.reconciliation.totalRefunded shouldBe "50.00"
                it.reconciliation.netReceived shouldBe "150.00"
                it.reconciliation.grossAllocated shouldBe "200.00"
                it.reconciliation.refundAllocations shouldBe "50.00"
                it.reconciliation.netAllocated shouldBe "150.00"
                it.reconciliation.unallocated shouldBe "0.00"
            }
            application.adminGet("/financial-documents/${invoice.id}").document().reconciliation?.let {
                it.grossAllocated shouldBe "200.00"
                it.netApplied shouldBe "150.00"
                it.balance.toBigDecimal() shouldBe invoice.total.toBigDecimal() - BigDecimal("150.00")
            }
            rows("commerce.refund_records", "payment_id", payment.paymentId) shouldBe 1
            rows("commerce.refund_allocations", "payment_allocation_id", allocation.allocationId) shouldBe 1
        }

        test("unapplied refunds leave documents alone; explicit portions can unwind two allocations") {
            val invoice = create("INVOICE").document()
            val unapplied = record("100.00").payment()
            refund(unapplied.paymentId, "25.00").let {
                it.status shouldBe Status.CREATED
                it.refund().allocations shouldContainExactly emptyList()
                it.refund().reconciliation.unallocated shouldBe "75.00"
            }
            application
                .adminGet("/financial-documents/${invoice.id}")
                .document()
                .reconciliation
                ?.grossAllocated shouldBe "0.00"

            val payment = record("200.00").payment()
            val first = allocate(payment.paymentId, invoice.id, 1, "100.00").allocation()
            val second = allocate(payment.paymentId, invoice.id, 1, "100.00").allocation()
            val portions =
                """[{"paymentAllocationId":"${first.allocationId}","amount":"20.00"},""" +
                    """{"paymentAllocationId":"${second.allocationId}","amount":"30.00"}]"""
            refund(payment.paymentId, "50.00", portions).let {
                it.status shouldBe Status.CREATED
                it.refund().allocations.size shouldBe 2
                it.refund().reconciliation.netAllocated shouldBe "150.00"
            }
            application
                .adminGet("/financial-documents/${invoice.id}")
                .document()
                .reconciliation
                ?.netApplied shouldBe "150.00"
        }

        test("invalid refunds leave the existing payment and invoice history unchanged") {
            val invoice = create("INVOICE").document()
            val payment = record("200.00").payment()
            val allocation = allocate(payment.paymentId, invoice.id, 1, "200.00").allocation()
            val other = record("100.00").payment()
            val otherAllocation = allocate(other.paymentId, invoice.id, 1, "100.00").allocation()
            refund(UUID.randomUUID().toString(), "10.00").status shouldBe Status.NOT_FOUND
            refund("not-a-uuid", "10.00").let {
                it.status shouldBe Status.BAD_REQUEST
                CommerceJson.asA(it.bodyString(), ErrorResponse.serializer()).let { error ->
                    error.code shouldBe "malformed_request"
                    error.message shouldBe "Malformed request: path 'paymentId'"
                }
            }
            refund(payment.paymentId, "10.00", """[{"paymentAllocationId":"not-a-uuid","amount":"10.00"}]""").let {
                it.status shouldBe Status.BAD_REQUEST
                CommerceJson.asA(it.bodyString(), ErrorResponse.serializer()).let { error ->
                    error.code shouldBe "malformed_request"
                    error.message shouldBe "Malformed request: body 'paymentAllocationId'"
                }
            }
            application.adminPost("/payments/${payment.paymentId}/refunds", """{"amount":10.00}""").status shouldBe Status.BAD_REQUEST
            refund(payment.paymentId, "10.00", """[{"paymentAllocationId":"${UUID.randomUUID()}","amount":"10.00"}]""")
                .status shouldBe Status.NOT_FOUND
            refund(payment.paymentId, "10.00", """[{"paymentAllocationId":"${otherAllocation.allocationId}","amount":"10.00"}]""")
                .status shouldBe Status.UNPROCESSABLE_ENTITY
            refund(payment.paymentId, "50.00").status shouldBe Status.UNPROCESSABLE_ENTITY
            refund(payment.paymentId, "10.00", """[{"paymentAllocationId":"${allocation.allocationId}","amount":"20.00"}]""")
                .status shouldBe Status.UNPROCESSABLE_ENTITY
            refund(
                payment.paymentId,
                "10.00",
                """[{"paymentAllocationId":"${allocation.allocationId}","amount":"10.00"}]""",
                ",\"externalReference\":{\"provider\":\"test\",\"reference\":\"duplicate\"}",
            ).status shouldBe Status.CREATED
            refund(
                payment.paymentId,
                "10.00",
                """[{"paymentAllocationId":"${allocation.allocationId}","amount":"10.00"}]""",
                ",\"externalReference\":{\"provider\":\"test\",\"reference\":\"duplicate\"}",
            ).status shouldBe Status.CONFLICT
            rows("commerce.refund_records", "payment_id", payment.paymentId) shouldBe 1
            application
                .adminGet("/financial-documents/${invoice.id}")
                .document()
                .reconciliation
                ?.netApplied shouldBe "290.00"
        }

        test("refunds require their own permission and a trusted browser Origin") {
            val payment = record("50.00").payment()
            val path = "/payments/${payment.paymentId}/refunds"
            val body = """{"amount":"10.00","currency":"USD","method":"OTHER"}"""
            application.http(Request(Method.POST, path).header("Origin", TEST_ORIGIN).body(body)).status shouldBe Status.UNAUTHORIZED
            application.http(Request(Method.POST, path).header("Cookie", application.adminCookie).body(body)).status shouldBe
                Status.FORBIDDEN
            val admin = checkNotNull(application.authorization.findUserByUsername("admin"))
            val paymentOnly = RoleKey("fionas.test.payment-only")
            val refundOnly = RoleKey("fionas.test.refund-only")
            application.authorization.createRole(
                RoleDefinition(paymentOnly, "Payment only", null, setOf(CommercePermissions.PaymentRecord)),
            )
            application.authorization.createRole(RoleDefinition(refundOnly, "Refund only", null, setOf(CommercePermissions.RefundRecord)))
            application.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
            application.authorization.assignRole(admin.id, paymentOnly)
            try {
                application.adminPost(path, body).status shouldBe Status.FORBIDDEN
                application.authorization.assignRole(admin.id, refundOnly)
                application.adminPost(path, body).status shouldBe Status.CREATED
            } finally {
                application.authorization.unassignRole(admin.id, paymentOnly)
                application.authorization.unassignRole(admin.id, refundOnly)
                application.authorization.assignRole(admin.id, CommerceRoles.Administrator)
            }
        }

        test("competing allocations wait on the runtime payment lock and cannot exceed the receipt") {
            val firstDocument = create("QUOTE").document()
            val secondDocument = create("QUOTE").document()
            val payment = record().payment()
            val associations = JdbiInquiryFinancialDocumentRepository()
            val sources = JdbiFinancialDocumentAuthorshipRepository()
            val firstBackendPid = AtomicInteger()
            val secondBackendPid = AtomicInteger()
            val firstInserted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondLineageLocked = CountDownLatch(1)
            val firstSources =
                object : FinancialDocumentAuthorshipRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): LineAuthorship? {
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
