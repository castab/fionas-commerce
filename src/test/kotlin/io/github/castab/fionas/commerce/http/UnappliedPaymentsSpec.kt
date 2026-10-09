package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.linesJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.UUID

class UnappliedPaymentsSpec :
    FunSpec({
        test("standalone and partly applied receipts stay discoverable until allocation or refund consumes their value") {
            TestApplication.create().use { app ->
                fun queue() =
                    CommerceJson
                        .asA(
                            app
                                .adminGet("/payments/unapplied")
                                .also {
                                    it.status shouldBe Status.OK
                                }.bodyString(),
                            UnappliedPaymentsResponse.serializer(),
                        ).payments

                fun receive(
                    amount: String,
                    time: String,
                ): PaymentRecordResponse =
                    CommerceJson.asA(
                        app
                            .adminPost("/payments", """{"amount":"$amount","currency":"USD","method":"OTHER","receivedAt":"$time"}""")
                            .also { it.status shouldBe Status.CREATED }
                            .bodyString(),
                        PaymentRecordResponse.serializer(),
                    )

                fun available(id: String) = queue().single { it.payment.paymentId == id }.reconciliation.unallocated
                queue() shouldBe emptyList()
                val later = receive("30.00", "2026-09-28T18:00:00Z")
                val first = receive("100.00", "2026-09-28T17:00:00Z")
                val tied = receive("50.00", "2026-09-28T17:00:00Z")
                // Fiona owns preservation of runtime order, including receipt-time ties.
                val expectedIds =
                    app.context.financialLedger
                        .unappliedPayments()
                        .map { it.payment.id.toString() }
                val actualIds = queue().map { it.payment.paymentId }
                actualIds shouldBe expectedIds
                actualIds.take(2).toSet() shouldBe setOf(first.paymentId, tied.paymentId)
                actualIds.last() shouldBe later.paymentId
                available(first.paymentId) shouldBe "100.00"
                queue().single { it.payment.paymentId == first.paymentId }.let {
                    it.payment shouldBe first
                    it.allocations shouldBe emptyList()
                    it.refunds shouldBe emptyList()
                }
                app.database.count("fionas.inquiries") shouldBe 0
                app.database.count("fionas.inquiry_financial_documents") shouldBe 0
                val inquiry = app.createInquiry()
                val document =
                    CommerceJson.asA(
                        app
                            .adminPost(
                                "/inquiries/$inquiry/financial-documents",
                                """{"stage":"INVOICE","lines":${linesJson(acceptanceLines())}}""",
                            ).also { it.status shouldBe Status.CREATED }
                            .bodyString(),
                        FinancialDocumentResponse.serializer(),
                    )

                fun allocate(amount: String) =
                    app
                        .adminPost(
                            "/payments/${first.paymentId}/allocations",
                            """{"documentId":"${document.id}","documentVersion":1,"amount":"$amount"}""",
                        ).also { it.status shouldBe Status.CREATED }
                allocate("40.00")
                available(first.paymentId) shouldBe "60.00"
                queue()
                    .single { it.payment.paymentId == first.paymentId }
                    .allocations
                    .single()
                    .amount shouldBe "40.00"
                app
                    .adminPost(
                        "/payments/${first.paymentId}/refunds",
                        """{"amount":"20.00","currency":"USD","method":"OTHER"}""",
                    ).status shouldBe Status.CREATED
                available(first.paymentId) shouldBe "40.00"
                queue().single { it.payment.paymentId == first.paymentId }.let {
                    it.refunds.single().amount shouldBe "20.00"
                    it.refundAllocations shouldBe emptyList()
                }
                allocate("40.00")
                queue().any { it.payment.paymentId == first.paymentId } shouldBe false
                app
                    .adminPost(
                        "/payments/${tied.paymentId}/refunds",
                        """{"amount":"50.00","currency":"USD","method":"OTHER"}""",
                    ).status shouldBe Status.CREATED
                queue().map { it.payment.paymentId } shouldBe listOf(later.paymentId)
                // Fiona returns exactly the runtime queue, in its order, without recomputing availability.
                queue().map { it.payment.paymentId } shouldBe
                    app.context.financialLedger
                        .unappliedPayments()
                        .map { it.payment.id.toString() }
            }
        }

        test("the operational queue requires payment-record permission and no document-read grant") {
            TestApplication.create().use { app ->
                app.http(Request(Method.GET, "/payments/unapplied")).status shouldBe Status.UNAUTHORIZED
                val cookie = app.adminCookie
                val admin = checkNotNull(app.authorization.findUserByUsername("admin"))
                app.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
                app.adminGet("/payments/unapplied").status shouldBe Status.FORBIDDEN
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, setOf(CommercePermissions.PaymentRecord))
                app.authorization.assignRole(admin.id, CommerceRoles.Administrator)
                app.http(Request(Method.GET, "/payments/unapplied").header("Cookie", cookie)).status shouldBe Status.OK
                app.adminGet("/financial-documents/${UUID.randomUUID()}/payments").status shouldBe Status.FORBIDDEN
            }
        }

        test("malformed allocation documentId is a body 400 while readable invalid values remain domain errors") {
            TestApplication.create().use { app ->
                val payment =
                    CommerceJson.asA(
                        app.adminPost("/payments", """{"amount":"10.00","currency":"USD","method":"CASH"}""").bodyString(),
                        PaymentRecordResponse.serializer(),
                    )

                fun allocation(
                    id: String,
                    version: Int,
                ) = app.adminPost(
                    "/payments/${payment.paymentId}/allocations",
                    """{"documentId":"$id","documentVersion":$version,"amount":"1.00"}""",
                )
                val malformed = allocation("unreadable", 1)
                malformed.status shouldBe Status.BAD_REQUEST
                CommerceJson.asA(malformed.bodyString(), ErrorResponse.serializer()) shouldBe
                    ErrorResponse("malformed_request", "Malformed request: body 'documentId'")
                val invalid = allocation(UUID.randomUUID().toString(), 0)
                invalid.status shouldBe Status.UNPROCESSABLE_ENTITY
                CommerceJson.asA(invalid.bodyString(), ErrorResponse.serializer()).code shouldBe "validation_failed"
                allocation(UUID.randomUUID().toString(), 1).status shouldBe Status.NOT_FOUND
            }
        }
    })
