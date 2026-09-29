package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.pricingBody
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * `GET /financial-documents/{documentId}/payments` through the complete handler, over real
 * PostgreSQL: every id a later refund needs is rediscovered from the ledger after the
 * responses that recorded it are gone, and each payment is its complete history.
 */
class FinancialDocumentPaymentsSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0

        fun Response.document() = CommerceJson.asA(bodyString(), FinancialDocumentResponse.serializer())

        fun Response.histories() = CommerceJson.asA(bodyString(), FinancialDocumentPaymentsResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun create(stage: String): FinancialDocumentResponse =
            application
                .adminPost(
                    "/inquiries/${application.createInquiry()}/financial-documents",
                    """{"stage":"$stage",${pricingBody(revision).drop(1)}""",
                ).also { it.status shouldBe Status.CREATED }
                .document()

        /** Records a standalone payment and answers only its id, as a screen that just recorded it would know. */
        fun record(
            amount: String,
            receivedAt: String? = null,
        ): String =
            application
                .adminPost(
                    "/payments",
                    """{"amount":"$amount","currency":"USD","method":"CARD"${receivedAt?.let { ",\"receivedAt\":\"$it\"" } ?: ""}}""",
                ).also { it.status shouldBe Status.CREATED }
                .let { CommerceJson.asA(it.bodyString(), PaymentRecordResponse.serializer()).paymentId }

        fun allocate(
            paymentId: String,
            documentId: String,
            amount: String,
            version: Int = 1,
        ) = application
            .adminPost("/payments/$paymentId/allocations", """{"documentId":"$documentId","documentVersion":$version,"amount":"$amount"}""")
            .also { it.status shouldBe Status.CREATED }

        fun refund(
            paymentId: String,
            amount: String,
            portions: List<Pair<String, String>>,
            refundedAt: String? = null,
        ): Response {
            val time = refundedAt?.let { ",\"refundedAt\":\"$it\"" } ?: ""
            val unwinds = portions.joinToString(",") { (id, part) -> """{"paymentAllocationId":"$id","amount":"$part"}""" }
            return application
                .adminPost(
                    "/payments/$paymentId/refunds",
                    """{"amount":"$amount","currency":"USD","method":"OTHER"$time,"allocations":[$unwinds]}""",
                ).also { it.status shouldBe Status.CREATED }
        }

        fun payments(documentId: String) =
            application.adminGet("/financial-documents/$documentId/payments").also { it.status shouldBe Status.OK }.histories()

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        test("a document that never received a payment has an empty list") {
            val quote = create("QUOTE")
            application.adminGet("/financial-documents/${quote.id}/payments").let {
                it.status shouldBe Status.OK
                it.histories() shouldBe FinancialDocumentPaymentsResponse(quote.id, emptyList())
                it.bodyString() shouldBe """{"documentId":"${quote.id}","payments":[]}"""
            }
        }

        test("a lineage no Fiona inquiry owns is not found, even when commerce-runtime's ledger has it") {
            val foreign =
                application.context.financialLedger.create(
                    FinancialDocument.Estimate.create(
                        UUID.randomUUID(),
                        listOf(LineItem(UUID.randomUUID(), "Not Fiona's", null, null, usd("10.00"), usd("0.00"))),
                    ),
                )
            // The runtime would answer an empty list for this lineage; Fiona's ownership check answers 404.
            application.context.financialLedger
                .paymentHistoriesForLineage(foreign.id)
                .shouldBeEmpty()
            listOf(foreign.id.toString(), UUID.randomUUID().toString()).forEach { id ->
                application.adminGet("/financial-documents/$id/payments").let {
                    it.status shouldBe Status.NOT_FOUND
                    it.error() shouldBe ErrorResponse("not_found", "Financial document $id was not found")
                }
            }
            application.adminGet("/financial-documents/not-a-uuid/payments").let {
                it.status shouldBe Status.BAD_REQUEST
                it.error() shouldBe ErrorResponse("malformed_request", "Malformed request: path 'documentId'")
            }
        }

        test("reading requires a staff session and commerce.financial-document.read, never a payment write permission") {
            val invoice = create("INVOICE")
            val path = "/financial-documents/${invoice.id}/payments"
            application.http(Request(Method.GET, path)).status shouldBe Status.UNAUTHORIZED

            val admin = checkNotNull(application.authorization.findUserByUsername("admin"))
            val writer = RoleKey("fionas.test.payment-writer")
            val reader = RoleKey("fionas.test.document-reader")
            application.authorization.createRole(
                RoleDefinition(writer, "Payment writer", null, setOf(CommercePermissions.PaymentRecord, CommercePermissions.RefundRecord)),
            )
            application.authorization.createRole(
                RoleDefinition(reader, "Document reader", null, setOf(CommercePermissions.FinancialDocumentRead)),
            )
            application.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
            application.authorization.assignRole(admin.id, writer)
            try {
                application.adminGet(path).let {
                    it.status shouldBe Status.FORBIDDEN
                    it.error().code shouldBe "forbidden"
                }
                application.authorization.unassignRole(admin.id, writer)
                application.authorization.assignRole(admin.id, reader)
                application.adminGet(path).status shouldBe Status.OK
            } finally {
                application.authorization.unassignRole(admin.id, writer)
                application.authorization.unassignRole(admin.id, reader)
                application.authorization.assignRole(admin.id, CommerceRoles.Administrator)
            }
        }

        test("a payment recorded against a document is rediscovered without its recording response") {
            val invoice = create("INVOICE")
            val recorded =
                application
                    .adminPost(
                        "/financial-documents/${invoice.id}/payments",
                        """{"documentVersion":1,"amount":"300.00","method":"CHECK","receivedAt":"2026-09-20T15:00:00-07:00",""" +
                            """"externalReference":{"provider":"bank","reference":"chk-1042"}}""",
                    ).also { it.status shouldBe Status.CREATED }
                    .let { CommerceJson.asA(it.bodyString(), RecordedPaymentResponse.serializer()) }

            val history = payments(invoice.id).also { it.documentId shouldBe invoice.id }.payments.single()
            history.payment shouldBe
                PaymentRecordResponse(
                    recorded.paymentId,
                    "CHECK",
                    "300.00",
                    "USD",
                    "2026-09-20T22:00:00Z",
                    PaymentExternalReference("bank", "chk-1042"),
                )
            history.allocations shouldContainExactly
                listOf(
                    PaymentAllocationRecordResponse(
                        allocationId = recorded.allocationId,
                        paymentId = recorded.paymentId,
                        documentId = invoice.id,
                        documentVersion = 1,
                        amount = "300.00",
                        currency = "USD",
                        allocatedAt = STORED_INSTANT.toString(),
                    ),
                )
            history.refunds.shouldBeEmpty()
            history.refundAllocations.shouldBeEmpty()
            history.reconciliation shouldBe
                PaymentReconciliationResponse("300.00", "0.00", "300.00", "300.00", "0.00", "0.00", "300.00", "0.00", "USD")
        }

        test("the ids a refund needs, and the refund's own facts, survive every reload") {
            val invoice = create("INVOICE")
            // Recording responses are discarded: everything after this comes from the document's reads.
            allocate(record("200.00"), invoice.id, "200.00")

            val before = payments(invoice.id).payments.single()
            val paymentId = before.payment.paymentId
            val allocationId = before.allocations.single { it.documentId == invoice.id }.allocationId
            refund(paymentId, "50.00", listOf(allocationId to "50.00"), refundedAt = "2026-09-21T12:00:00Z")

            val after = payments(invoice.id).payments.single()
            after.payment.paymentId shouldBe paymentId
            after.allocations.map { it.allocationId } shouldContainExactly listOf(allocationId)
            val refund = after.refunds.single()
            refund shouldBe RefundRecordResponse(refund.refundId, paymentId, "50.00", "USD", "OTHER", "2026-09-21T12:00:00Z")
            after.refundAllocations.single().let {
                it.refundId shouldBe refund.refundId
                it.paymentAllocationId shouldBe allocationId
                it.amount shouldBe "50.00"
                it.currency shouldBe "USD"
                it.allocatedAt shouldBe STORED_INSTANT.toString()
            }
            after.reconciliation shouldBe
                PaymentReconciliationResponse("200.00", "50.00", "150.00", "200.00", "0.00", "50.00", "150.00", "0.00", "USD")
            // The rediscovered ids are the ledger's own.
            application.database.strings(
                "SELECT refund_id::text FROM commerce.refund_allocations WHERE payment_allocation_id = '$allocationId'",
            ) shouldContainExactly listOf(refund.refundId)
            // And they serve a second refund, prepared from this read alone.
            refund(paymentId, "25.00", listOf(allocationId to "25.00"))
            payments(invoice.id).payments.single().let {
                it.refunds.map { refund -> refund.amount } shouldContainExactly listOf("50.00", "25.00")
                it.reconciliation.netAllocated shouldBe "125.00"
            }
        }

        test("discovery is historical: a payment whose allocation here was fully unwound is still listed") {
            val invoice = create("INVOICE")
            val paymentId = record("100.00")
            allocate(paymentId, invoice.id, "100.00")
            val allocationId =
                payments(invoice.id)
                    .payments
                    .single()
                    .allocations
                    .single()
                    .allocationId
            refund(paymentId, "100.00", listOf(allocationId to "100.00"))

            val history = payments(invoice.id).payments.single()
            history.payment.paymentId shouldBe paymentId
            history.allocations.single().amount shouldBe "100.00"
            history.refundAllocations.single().paymentAllocationId shouldBe allocationId
            history.reconciliation shouldBe
                PaymentReconciliationResponse("100.00", "100.00", "0.00", "100.00", "0.00", "100.00", "0.00", "0.00", "USD")
            application
                .adminGet("/financial-documents/${invoice.id}")
                .document()
                .reconciliation
                ?.netApplied shouldBe "0.00"
        }

        test("a split payment is its whole history from either document, reconciled over the whole payment") {
            val first = create("QUOTE")
            val second = create("INVOICE")
            val paymentId = record("500.00")
            allocate(paymentId, first.id, "200.00")
            allocate(paymentId, second.id, "150.00")

            val fromFirst = payments(first.id).payments.single()
            fromFirst.payment.paymentId shouldBe paymentId
            // Both allocations, including the one to the other document, which the UI selects out by `documentId`.
            fromFirst.allocations.map { it.documentId to it.amount }.toSet() shouldBe setOf(first.id to "200.00", second.id to "150.00")
            // Never a document-filtered reconciliation: $350 of $500 is allocated, $150 unapplied.
            fromFirst.reconciliation shouldBe
                PaymentReconciliationResponse("500.00", "0.00", "500.00", "350.00", "0.00", "0.00", "350.00", "150.00", "USD")
            payments(second.id).payments shouldContainExactly listOf(fromFirst)
        }

        test("a standalone payment never allocated to the document is not one of its payments") {
            val invoice = create("INVOICE")
            val unapplied = record("75.00")
            val applied = record("25.00")
            allocate(applied, invoice.id, "25.00")
            val listed = payments(invoice.id).payments.map { it.payment.paymentId }
            listed shouldContainExactly listOf(applied)
            listed shouldNotContain unapplied
        }

        test("histories keep commerce-runtime's order: by time, with the id only breaking ties") {
            val invoice = create("INVOICE")
            val late = record("100.00", "2026-09-20T10:00:00Z")
            val tiedA = record("100.00", "2026-09-20T09:00:00Z")
            val tiedB = record("100.00", "2026-09-20T09:00:00Z")
            listOf(late, tiedA, tiedB).forEach { allocate(it, invoice.id, "10.00") }
            // Two more allocations of `late`, recorded at the same instant as its first.
            allocate(late, invoice.id, "20.00")
            allocate(late, invoice.id, "30.00")

            val histories = payments(invoice.id).payments
            histories.map { it.payment.paymentId } shouldContainExactly sortedBy(listOf(tiedA, tiedB)) + late

            val lateAllocations = histories.last().allocations.map { it.allocationId }
            lateAllocations shouldContainExactly sortedBy(lateAllocations)
            // A later refund recorded first, then an earlier one; the second splits over two allocations.
            refund(late, "5.00", listOf(lateAllocations[0] to "5.00"), refundedAt = "2026-09-22T00:00:00Z")
            refund(late, "15.00", listOf(lateAllocations[1] to "5.00", lateAllocations[2] to "10.00"), refundedAt = "2026-09-21T00:00:00Z")

            val refunded = payments(invoice.id).payments.last()
            refunded.refunds.map { it.amount to it.refundedAt } shouldContainExactly
                listOf("15.00" to "2026-09-21T00:00:00Z", "5.00" to "2026-09-22T00:00:00Z")
            // Every unwind was recorded at the same instant, so only their ids order them.
            val unwinds = refunded.refundAllocations.map { it.refundAllocationId }
            unwinds.size shouldBe 3
            unwinds shouldContainExactly sortedBy(unwinds)
        }
    })

/** Ids in commerce-runtime's tie-break order: [UUID.compareTo], not their text. */
private fun sortedBy(ids: List<String>): List<String> = ids.sortedWith(compareBy(UUID::fromString))

private val USD = Currency.getInstance("USD")

private fun usd(amount: String) = Money(BigDecimal(amount), USD)
