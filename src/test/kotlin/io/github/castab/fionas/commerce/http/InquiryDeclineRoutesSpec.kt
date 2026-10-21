package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.util.UUID

class InquiryDeclineRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun body(
            version: Int = 1,
            reason: String = "  Requested date is unavailable.  ",
        ) = """{"expectedDocumentVersion":$version,"reason":"$reason"}"""

        fun declines() = app.database.count("fionas.inquiry_declines")

        fun staffCookie(permissions: Set<PermissionKey>): String {
            val id = UserId(UUID.randomUUID())
            app.authorization.createUser(User(id, "staff-${id.value}", null, null, "Staff", PrincipalStatus.ACTIVE, emptySet()))
            if (permissions.isNotEmpty()) {
                val role = RoleKey("test.decline-${id.value}")
                app.authorization.createRole(RoleDefinition(role, "Staff", null, permissions))
                app.authorization.assignRole(id, role)
            }
            return "$STAFF_SESSION_COOKIE=${app.sessions.create(id).token.value}"
        }

        fun post(
            id: String,
            json: String = body(),
            cookie: String? = app.adminCookie,
            origin: String? = TEST_ORIGIN,
            token: String? = null,
        ): Response {
            var request = Request(Method.POST, "/inquiries/$id/decline").header("Content-Type", "application/json").body(json)
            cookie?.let { request = request.header("Cookie", it) }
            origin?.let { request = request.header("Origin", it) }
            token?.let { request = request.withBearer(it) }
            return app.http(request)
        }

        test("a staff user declines a requested inquiry; detail, staff request and list read it back without inventing facts") {
            val id = app.createInquiry()
            val response = post(id)
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            val lifecycle = CommerceJson.asA(response.bodyString(), InquiryLifecycleResponse.serializer())
            val expected =
                InquiryLifecycleResponse(
                    app.initialEstimateOf(id),
                    InquiryStageResponse.DECLINED,
                    declined =
                        InquiryDeclineResponse(
                            STORED_INSTANT.toString(),
                            InquiryActorKind.USER,
                            app.adminId.value.toString(),
                            "Requested date is unavailable.",
                            1,
                        ),
                )
            lifecycle shouldBe expected
            CommerceJson.asA(app.adminGet("/inquiries/$id").bodyString(), InquiryResponse.serializer()).lifecycle shouldBe expected
            val staff = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())
            staff.inquiry.lifecycle shouldBe expected
            staff.financial.stage shouldBe "ESTIMATE"
            staff.financial.version shouldBe 1
            staff.financial.total shouldBe "681.25"
            staff.financial.reconciliation!!.balance shouldBe "681.25"
            staff.proposal shouldBe null
            app.adminGet("/inquiries?limit=100").bodyString().contains(id) shouldBe true
        }

        test("a repeated decline is an illegal transition and a stale version a conflict, writing nothing") {
            val id = app.createInquiry()
            post(id).status shouldBe Status.OK
            val before = declines()
            val repeated = post(id, body(reason = "Another reason"))
            repeated.status shouldBe Status.CONFLICT
            repeated.error().code shouldBe "illegal_transition"
            val other = app.createInquiry()
            val stale = post(other, body(version = 2))
            stale.status shouldBe Status.CONFLICT
            stale.error().code shouldBe "conflict"
            declines() shouldBe before
            app
                .adminPost(
                    "/staff/requests/$other/proposals",
                    """{"expectedDocumentVersion":1,"terms":{"type":"FIXED","amount":"50","currency":"USD"}}""",
                ).status shouldBe Status.OK
            val quoted = post(other, body(version = 2))
            quoted.status shouldBe Status.CONFLICT
            quoted.error().code shouldBe "illegal_transition"
            // After decline, the initial proposal route rejects publication.
            val declined = app.createInquiry()
            post(declined).status shouldBe Status.OK
            val proposal =
                app.adminPost(
                    "/staff/requests/$declined/proposals",
                    """{"expectedDocumentVersion":1,"terms":{"type":"FIXED","amount":"50","currency":"USD"}}""",
                )
            proposal.status shouldBe Status.CONFLICT
            proposal.error().code shouldBe "illegal_transition"
        }

        test("authentication, permission, staff USER and Origin are required; a SERVICE never decides") {
            val id = app.createInquiry()
            val before = declines()
            post(id, cookie = null).status shouldBe Status.UNAUTHORIZED
            post(id, cookie = staffCookie(setOf(FionaPermissions.InquiriesRead, CommercePermissions.FinancialDocumentRead))).status shouldBe
                Status.FORBIDDEN
            post(id, origin = "https://evil.test").status shouldBe Status.FORBIDDEN
            post(id, origin = null).status shouldBe Status.FORBIDDEN
            val manager = app.provisionService("decline-manager", setOf(FionaPermissions.InquiriesManage, FionaPermissions.InquiriesRead))
            val service = post(id, cookie = null, origin = null, token = app.serviceToken(manager))
            service.status shouldBe Status.FORBIDDEN
            service.error().message shouldBe STAFF_DECISION_REQUIRED
            declines() shouldBe before
            val staff = staffCookie(setOf(FionaPermissions.InquiriesManage))
            post(id, cookie = staff).status shouldBe Status.OK
        }

        test("malformed identifiers and bodies are 400, invalid values 422, unknown inquiries 404; provenance never comes from the body") {
            val id = app.createInquiry()
            val before = declines()
            post("not-a-uuid").status shouldBe Status.BAD_REQUEST
            post(id, """{"expectedDocumentVersion":1}""").status shouldBe Status.BAD_REQUEST
            post(id, """{"reason":"x"}""").status shouldBe Status.BAD_REQUEST
            post(id, "not json").status shouldBe Status.BAD_REQUEST
            listOf(body(reason = "   "), body(reason = "x".repeat(501)), body(version = 0)).forEach { invalid ->
                val response = post(id, invalid)
                response.status shouldBe Status.UNPROCESSABLE_ENTITY
                response.error().code shouldBe "validation_failed"
            }
            post(UUID.randomUUID().toString()).status shouldBe Status.NOT_FOUND
            declines() shouldBe before
            val impostor = UUID.randomUUID()
            val response =
                post(
                    id,
                    """{"expectedDocumentVersion":1,"reason":"x","declinedBy":"$impostor","declinedAt":"2020-01-01T00:00:00Z"}""",
                )
            // Client-supplied provenance is ignored like any other unknown property: authentication and the server clock decide.
            response.status shouldBe Status.OK
            val declined = CommerceJson.asA(response.bodyString(), InquiryLifecycleResponse.serializer()).declined!!
            declined.principalId shouldBe app.adminId.value.toString()
            declined.occurredAt shouldBe STORED_INSTANT.toString()
            declines() shouldBe before + 1
        }
    })
