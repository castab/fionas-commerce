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
            json.keys shouldBe setOf("inquiry", "financial", "suggestedDepositTerms", "depositRequirement")
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
                }
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
            }
        }
    })
