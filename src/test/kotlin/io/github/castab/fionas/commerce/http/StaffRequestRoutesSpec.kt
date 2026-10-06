package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.UUID

class StaffRequestRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val permissions = setOf(FionaPermissions.InquiriesRead, CommercePermissions.FinancialDocumentRead)
        beforeSpec {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }

        test("composed response reuses exact contracts and supplies atomic proposal concurrency input") {
            val id = app.createInquiry()
            val document = app.initialEstimateOf(id)
            val path = "/staff/requests/$id"
            val response = app.adminGet(path)
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            val json = Json.parseToJsonElement(response.bodyString()).jsonObject
            json.keys shouldBe setOf("inquiry", "financial", "suggestedDepositTerms", "depositRequirement", "payments")
            json.getValue("payments") shouldBe Json.parseToJsonElement("[]")
            json.getValue("inquiry") shouldBe Json.parseToJsonElement(app.adminGet("/inquiries/$id").bodyString())
            json.getValue("financial") shouldBe Json.parseToJsonElement(app.adminGet("/financial-documents/$document").bodyString())
            val view = CommerceJson.asA(response.bodyString(), StaffRequestResponse.serializer())
            view.inquiry.id shouldBe view.financial.inquiryId
            view.inquiry.lifecycle.documentId shouldBe view.financial.id
            view.financial.inquiryId shouldBe id
            view.financial.reconciliation.shouldNotBeNull()
            view.financial.stage shouldBe "ESTIMATE"
            view.financial.version shouldBe 1
            app
                .adminPost(
                    "/staff/requests/$id/proposals",
                    """{"expectedDocumentVersion":${view.financial.version},"terms":{"type":"PERCENTAGE","percentage":"20"}}""",
                ).status shouldBe
                Status.OK
            val quoted = CommerceJson.asA(app.adminGet(path).bodyString(), StaffRequestResponse.serializer())
            quoted.inquiry.id shouldBe quoted.financial.inquiryId
            quoted.inquiry.lifecycle.documentId shouldBe quoted.financial.id
            quoted.financial.reconciliation.shouldNotBeNull()
            quoted.inquiry.lifecycle.stage shouldBe InquiryStageResponse.QUOTED
            quoted.financial.stage shouldBe "QUOTE"
            quoted.financial.id shouldBe document
            quoted.financial.version shouldBe 2
            app.adminPost("/financial-documents/$document/quote", """{"expectedVersion":1}""").status shouldBe Status.CONFLICT
            app.adminPost("/inquiries/$id/estimates", pricingBody(view.inquiry.pricingInputs.catalogRevision)).status shouldBe
                Status.CREATED
            CommerceJson.asA(app.adminGet(path).bodyString(), StaffRequestResponse.serializer()).financial.id shouldBe document
        }

        test("malformed id and unknown inquiry retain runtime error envelopes") {
            listOf(
                "invalid" to (Status.BAD_REQUEST to "malformed_request"),
                UUID.randomUUID().toString() to (Status.NOT_FOUND to "not_found"),
            ).forEach { (id, expected) ->
                val response = app.adminGet("/staff/requests/$id")
                response.status shouldBe expected.first
                Json
                    .parseToJsonElement(response.bodyString())
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content shouldBe
                    expected.second
            }
        }

        test("400 received is a 300 Quote deposit then a separate 100 Invoice receipt, all visible in one workspace") {
            val id = app.createInquiry()
            val path = "/staff/requests/$id"

            fun workspace(): StaffRequestResponse {
                val response = app.adminGet(path)
                response.status shouldBe Status.OK
                response.header("Cache-Control") shouldBe "no-store"
                return CommerceJson.asA(response.bodyString(), StaffRequestResponse.serializer())
            }
            val estimate = workspace()
            app
                .adminPost(
                    "$path/proposals",
                    """{"expectedDocumentVersion":${estimate.financial.version},"terms":{"type":"FIXED","amount":"300.00","currency":"USD"}}""",
                ).status shouldBe Status.OK
            val quoted = workspace()
            quoted.inquiry.lifecycle.stage shouldBe InquiryStageResponse.QUOTED
            quoted.financial.stage shouldBe "QUOTE"
            quoted.payments shouldBe emptyList()
            val proposal = quoted.proposal.shouldNotBeNull()
            proposal.documentId shouldBe quoted.financial.id
            proposal.documentVersion shouldBe quoted.financial.version
            val deposit = quoted.depositRequirement as CurrentDepositRequirementResponse.Active
            deposit.approvalDocumentVersion shouldBe quoted.financial.version
            deposit.revision shouldBe proposal.depositRequirementRevision
            deposit.requiredAmount shouldBe DepositMoneyResponse("300.00", "USD")
            deposit.satisfied shouldBe false
            val paymentsPath = "/financial-documents/${quoted.financial.id}/payments"
            app
                .adminPost(
                    paymentsPath,
                    """{"documentVersion":${quoted.financial.version},"amount":"400","method":"CASH","expectedProposalId":"${proposal.id}"}""",
                ).status shouldBe Status.UNPROCESSABLE_ENTITY
            workspace() shouldBe quoted
            val received =
                app.adminPost(
                    paymentsPath,
                    """{"documentVersion":${quoted.financial.version},"amount":"${deposit.requiredAmount.amount}","method":"CHECK","expectedProposalId":"${proposal.id}","receivedAt":"2026-10-05T10:00:00-07:00","externalReference":{"provider":"bank","reference":"workspace-deposit"}}""",
                )
            received.status shouldBe Status.CREATED
            val receipt = CommerceJson.asA(received.bodyString(), RecordedPaymentResponse.serializer())
            val booked = workspace()
            booked.inquiry.lifecycle.stage shouldBe InquiryStageResponse.BOOKED
            booked.financial.stage shouldBe "INVOICE"
            booked.financial.version shouldBe quoted.financial.version + 1
            booked.proposal shouldBe proposal
            (booked.depositRequirement as CurrentDepositRequirementResponse.Active).satisfied shouldBe true
            val history = booked.payments.single()
            history.payment shouldBe
                PaymentRecordResponse(
                    receipt.paymentId,
                    "CHECK",
                    "300.00",
                    "USD",
                    "2026-10-05T17:00:00Z",
                    PaymentExternalReference("bank", "workspace-deposit"),
                )
            history.allocations.single().allocationId shouldBe receipt.allocationId
            history.allocations.single().documentVersion shouldBe quoted.financial.version
            history.reconciliation.netAllocated shouldBe "300.00"
            booked.financial.reconciliation!!.netApplied shouldBe "300.00"
            val extra =
                app.adminPost(
                    paymentsPath,
                    """{"documentVersion":${booked.financial.version},"amount":"100","method":"CASH"}""",
                )
            extra.status shouldBe Status.CREATED
            val extraReceipt = CommerceJson.asA(extra.bodyString(), RecordedPaymentResponse.serializer())
            val final = workspace()
            final.inquiry.lifecycle.stage shouldBe InquiryStageResponse.BOOKED
            final.financial.stage shouldBe "INVOICE"
            final.financial.version shouldBe booked.financial.version
            final.payments.size shouldBe 2
            final.payments.map { it.payment.paymentId }.toSet() shouldBe setOf(receipt.paymentId, extraReceipt.paymentId)
            final.payments
                .single { it.payment.paymentId == receipt.paymentId }
                .allocations
                .single()
                .documentVersion shouldBe
                quoted.financial.version
            final.payments
                .single { it.payment.paymentId == extraReceipt.paymentId }
                .allocations
                .single()
                .documentVersion shouldBe
                booked.financial.version
            final.financial.reconciliation!!.netApplied shouldBe "400.00"
            final.payments shouldBe
                CommerceJson.asA(app.adminGet(paymentsPath).bodyString(), FinancialDocumentPaymentsResponse.serializer()).payments
        }

        test("workspace retains whole split histories and fully refunded deposit allocations without reopening booking") {
            val id = app.createInquiry()
            val document = app.initialEstimateOf(id)
            val path = "/staff/requests/$id"
            app
                .adminPost(
                    "$path/proposals",
                    """{"expectedDocumentVersion":1,"terms":{"type":"FIXED","amount":"300","currency":"USD"}}""",
                ).status shouldBe Status.OK
            val quoted = CommerceJson.asA(app.adminGet(path).bodyString(), StaffRequestResponse.serializer())
            val receipt =
                app
                    .adminPost(
                        "/financial-documents/$document/payments",
                        """{"documentVersion":2,"amount":"300","method":"CASH","expectedProposalId":"${quoted.proposal!!.id}"}""",
                    ).also { it.status shouldBe Status.CREATED }
                    .let { CommerceJson.asA(it.bodyString(), RecordedPaymentResponse.serializer()) }
            val related =
                app
                    .adminPost(
                        "/inquiries/$id/financial-documents",
                        """{"stage":"INVOICE",${pricingBody(quoted.inquiry.pricingInputs.catalogRevision).drop(1)}""",
                    ).also { it.status shouldBe Status.CREATED }
                    .let { CommerceJson.asA(it.bodyString(), FinancialDocumentResponse.serializer()) }
            val standalone =
                app
                    .adminPost("/payments", """{"amount":"200","currency":"USD","method":"CARD"}""")
                    .also { it.status shouldBe Status.CREATED }
                    .let { CommerceJson.asA(it.bodyString(), PaymentRecordResponse.serializer()) }
            listOf(document to 3, related.id to related.version).forEach { (target, version) ->
                app
                    .adminPost(
                        "/payments/${standalone.paymentId}/allocations",
                        """{"documentId":"$target","documentVersion":$version,"amount":"50"}""",
                    ).status shouldBe Status.CREATED
            }
            app
                .adminPost(
                    "/payments/${receipt.paymentId}/refunds",
                    """{"amount":"300","currency":"USD","method":"CASH","allocations":[{"paymentAllocationId":"${receipt.allocationId}","amount":"300"}]}""",
                ).status shouldBe Status.CREATED
            val final = CommerceJson.asA(app.adminGet(path).bodyString(), StaffRequestResponse.serializer())
            final.inquiry.lifecycle.stage shouldBe InquiryStageResponse.BOOKED
            final.financial.stage shouldBe "INVOICE"
            final.proposal shouldBe quoted.proposal
            (final.depositRequirement as CurrentDepositRequirementResponse.Active).satisfied shouldBe false
            final.financial.reconciliation!!.netApplied shouldBe "50.00"
            val refunded = final.payments.single { it.payment.paymentId == receipt.paymentId }
            refunded.allocations.single().documentVersion shouldBe 2
            refunded.refunds.single().amount shouldBe "300.00"
            refunded.refundAllocations.single().paymentAllocationId shouldBe receipt.allocationId
            refunded.refundAllocations.single().refundId shouldBe refunded.refunds.single().refundId
            refunded.reconciliation.netAllocated shouldBe "0.00"
            val split = final.payments.single { it.payment.paymentId == standalone.paymentId }
            split.allocations.map { it.documentId }.toSet() shouldBe setOf(document, related.id)
            split.reconciliation.netAllocated shouldBe "100.00"
            split.reconciliation.unallocated shouldBe "100.00"
            final.payments shouldBe
                CommerceJson
                    .asA(
                        app.adminGet("/financial-documents/$document/payments").bodyString(),
                        FinancialDocumentPaymentsResponse.serializer(),
                    ).payments
            split shouldBe
                CommerceJson
                    .asA(
                        app.adminGet("/financial-documents/${related.id}/payments").bodyString(),
                        FinancialDocumentPaymentsResponse.serializer(),
                    ).payments
                    .single()
        }

        test("corrupt canonical data yields a generic internal failure with no diagnostics") {
            val id = app.createInquiry()
            app.database.execute("UPDATE fionas.inquiry_financial_documents SET purpose = 'RELATED' WHERE inquiry_id = '$id'")
            val response = app.adminGet("/staff/requests/$id")
            response.status shouldBe Status.INTERNAL_SERVER_ERROR
            Json.parseToJsonElement(response.bodyString()) shouldBe
                Json.parseToJsonElement("""{"code":"internal_failure","message":"The request could not be completed"}""")
        }

        test("both live permissions gate USER and SERVICE and safe GET needs no trusted Origin") {
            val id = app.createInquiry()
            val path = "/staff/requests/$id"
            app
                .adminPost(
                    "$path/proposals",
                    """{"expectedDocumentVersion":1,"terms":{"type":"FIXED","amount":"300","currency":"USD"}}""",
                ).status shouldBe Status.OK
            val quote = CommerceJson.asA(app.adminGet(path).bodyString(), StaffRequestResponse.serializer())
            app
                .adminPost(
                    "/financial-documents/${quote.financial.id}/payments",
                    """{"documentVersion":2,"amount":"300","method":"CASH","expectedProposalId":"${quote.proposal!!.id}"}""",
                ).status shouldBe Status.CREATED
            val anonymous = app.http(Request(Method.GET, path))
            anonymous.status shouldBe Status.UNAUTHORIZED
            val service = app.provisionService("request-reader", permissions)
            val token = app.serviceToken(service)
            val cookie = app.adminCookie
            val original = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions

            fun user(origin: String? = null) = app.http(Request(Method.GET, path).header("Cookie", cookie).header("Origin", origin))

            fun bearer() = app.http(Request(Method.GET, path).withBearer(token))
            try {
                val incomplete = listOf(emptySet(), setOf(FionaPermissions.InquiriesRead), setOf(CommercePermissions.FinancialDocumentRead))
                incomplete.forEach { grants ->
                    app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
                    app.authorization.replaceRolePermissions(service.role, grants)
                    listOf(user(), bearer()).forEach { response ->
                        response.status shouldBe Status.FORBIDDEN
                        Json
                            .parseToJsonElement(response.bodyString())
                            .jsonObject
                            .getValue("code")
                            .jsonPrimitive.content shouldBe
                            "forbidden"
                    }
                }
                app.authorization.replaceRolePermissions(service.role, permissions)
                app.http(Request(Method.GET, path).header("Cookie", cookie).withBearer(token)).status shouldBe Status.FORBIDDEN
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, permissions)
                listOf(user(), user("https://untrusted.example"), bearer()).forEach {
                    it.status shouldBe Status.OK
                    it.header("Cache-Control") shouldBe "no-store"
                    CommerceJson
                        .asA(it.bodyString(), StaffRequestResponse.serializer())
                        .payments
                        .single()
                        .payment.amount shouldBe "300.00"
                }
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
            }
        }
    })
