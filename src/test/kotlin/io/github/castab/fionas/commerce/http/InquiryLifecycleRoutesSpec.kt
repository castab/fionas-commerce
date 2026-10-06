package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.pricingBody
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.UUID

class InquiryLifecycleRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        var catalogRevision = 0
        beforeSpec {
            app = TestApplication.create()
            catalogRevision = app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }
        test("staff detail and business actions compose lifecycle; auth, Origin and SERVICE provenance use the existing runtime") {
            val id = app.createInquiry()
            val document = app.initialEstimateOf(id)

            fun detail() = CommerceJson.asA(app.adminGet("/inquiries/$id").bodyString(), InquiryResponse.serializer())
            detail().lifecycle.stage shouldBe InquiryStageResponse.REQUESTED
            val noPermissions = app.provisionService("lifecycle-no-grants", emptySet())
            val noToken = app.serviceToken(noPermissions)
            val manager = app.provisionService("lifecycle-manager", setOf(FionaPermissions.InquiriesManage))
            val token = app.serviceToken(manager)

            fun service(action: String) = app.http(Request(Method.POST, "/inquiries/$id/$action").header("Authorization", "Bearer $token"))
            listOf("served", "close").forEach { action ->
                app.http(Request(Method.POST, "/inquiries/$id/$action")).status shouldBe Status.UNAUTHORIZED
                app.http(Request(Method.POST, "/inquiries/$id/$action").header("Authorization", "Bearer $noToken")).status shouldBe
                    Status.FORBIDDEN
                app.http(Request(Method.POST, "/inquiries/$id/$action").header("Cookie", app.adminCookie)).status shouldBe Status.FORBIDDEN
                service(action).status shouldBe Status.CONFLICT
            }
            app
                .adminPost(
                    "/staff/requests/$id/proposals",
                    """{"expectedDocumentVersion":1,"terms":{"type":"FIXED","amount":"50","currency":"USD"}}""",
                ).status shouldBe
                Status.OK
            detail().lifecycle.stage shouldBe InquiryStageResponse.QUOTED
            app.adminPost("/financial-documents/$document/invoice", """{"expectedVersion":2}""").status shouldBe Status.CONFLICT
            app
                .adminRequest(
                    Method.PUT,
                    "/financial-documents/$document/deposit-requirement",
                    """{"expectedDocumentVersion":2,"terms":{"type":"FIXED","amount":"50","currency":"USD"}}""",
                ).status shouldBe Status.CONFLICT
            val paid = app.adminPost("/financial-documents/$document/payments", """{"documentVersion":2,"amount":"50","method":"CARD"}""")
            paid.status shouldBe Status.CREATED
            detail().lifecycle.stage shouldBe InquiryStageResponse.BOOKED
            app
                .adminPost(
                    "/financial-documents/$document/change-orders",
                    pricingBody(catalogRevision, guests = 80, expectedVersion = 3),
                ).status shouldBe Status.OK
            detail().lifecycle.stage shouldBe InquiryStageResponse.BOOKED
            service("close").status shouldBe Status.CONFLICT
            val servedResponse = service("served")
            servedResponse.status shouldBe Status.OK
            val served = CommerceJson.asA(servedResponse.bodyString(), InquiryLifecycleResponse.serializer())
            served.stage shouldBe InquiryStageResponse.SERVED
            served.served!!.principalKind shouldBe InquiryActorKind.SERVICE
            served.served.principalId shouldBe manager.id.value.toString()
            served.served.occurredAt shouldBe STORED_INSTANT.toString()
            detail().lifecycle shouldBe served
            service("served").status shouldBe Status.CONFLICT
            service("close").status shouldBe Status.CONFLICT
            val balance =
                app.context.financialLedger
                    .reconcileLatest(UUID.fromString(document))
                    .balance.amount
                    .toPlainString()
            app
                .adminPost(
                    "/financial-documents/$document/payments",
                    """{"documentVersion":4,"amount":"$balance","method":"CARD"}""",
                ).status shouldBe
                Status.CREATED
            detail().lifecycle.stage shouldBe InquiryStageResponse.SERVED // Never automatic close.
            app.authorization.replaceRolePermissions(manager.role, emptySet())
            listOf("served", "close").forEach { service(it).status shouldBe Status.FORBIDDEN }
            val adminGrants = app.authorization.getRole(CommerceRoles.Administrator)!!.permissions
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, setOf(FionaPermissions.InquiriesRead))
            app.adminPost("/inquiries/$id/close").status shouldBe Status.FORBIDDEN
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, setOf(FionaPermissions.InquiriesManage))
            app.adminGet("/inquiries/$id").status shouldBe Status.FORBIDDEN
            val closedResponse = app.adminPost("/inquiries/$id/close")
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, adminGrants)
            closedResponse.status shouldBe Status.OK
            val closed = CommerceJson.asA(closedResponse.bodyString(), InquiryLifecycleResponse.serializer())
            closed.stage shouldBe InquiryStageResponse.CLOSED
            closed.served shouldBe served.served
            closed.closed!!.principalKind shouldBe InquiryActorKind.USER
            closed.closed.principalId shouldBe
                app.authorization
                    .findUserByUsername("admin")!!
                    .id.value
                    .toString()
            detail().lifecycle shouldBe closed
            app.adminPost("/inquiries/$id/close").status shouldBe Status.CONFLICT
            app.authorization.replaceRolePermissions(manager.role, setOf(FionaPermissions.InquiriesManage))
            app.http(Request(Method.POST, "/inquiries/not-a-uuid/served").header("Authorization", "Bearer $token")).status shouldBe
                Status.BAD_REQUEST
            app.http(Request(Method.POST, "/inquiries/${UUID.randomUUID()}/close").header("Authorization", "Bearer $token")).status shouldBe
                Status.NOT_FOUND
            app
                .http(
                    Request(Method.PATCH, "/inquiries/$id").header("Authorization", "Bearer $token").body("""{"state":"SERVED"}"""),
                ).status shouldBe
                Status.METHOD_NOT_ALLOWED
        }
    })
