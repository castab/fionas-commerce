package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

class StaffDashboardRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val path = "/staff/dashboard"
        val permissions = setOf(FionaPermissions.InquiriesRead, CommercePermissions.FinancialDocumentRead)

        beforeTest { app = TestApplication.create() }
        afterTest { app.close() }

        test("empty dashboard has the exact availability shape, one microsecond timestamp, and no-store") {
            val response = app.adminGet(path)
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            val json = Json.parseToJsonElement(response.bodyString()).jsonObject
            json.keys shouldBe setOf("asOf", "summary", "workQueue")
            json.getValue("asOf") shouldBe JsonPrimitive(STORED_INSTANT.toString())
            json.getValue("summary").jsonObject shouldBe Json.parseToJsonElement("""{"new":0,"quoted":0,"booked":0,"needsClosing":0}""")
            val queues = json.getValue("workQueue").jsonObject
            queues.keys shouldBe setOf("needsQuote", "awaitingQuoteReply", "needsClosing", "needsReply", "needsResolution")
            listOf("needsQuote", "awaitingQuoteReply", "needsClosing").forEach {
                queues.getValue(it).jsonObject shouldBe Json.parseToJsonElement("""{"available":true,"items":[]}""")
            }
            queues.getValue("needsReply") shouldBe
                Json.parseToJsonElement("""{"available":false,"items":[],"unavailableReason":"COMMUNICATIONS_NOT_IMPLEMENTED"}""")
            queues.getValue("needsResolution") shouldBe
                Json.parseToJsonElement("""{"available":false,"items":[],"unavailableReason":"RESOLUTION_POLICY_NOT_DEFINED"}""")
        }

        test("both live permissions gate USER and SERVICE before evaluation and retain session precedence") {
            app.createAcceptanceCatalog()
            val inquiry = app.createInquiry()
            // A corrupt population proves forbidden requests never invoke the read operation.
            app.database.execute("UPDATE fionas.inquiry_financial_documents SET purpose = 'RELATED'")
            val anonymous = app.http(Request(Method.GET, path))
            anonymous.status shouldBe Status.UNAUTHORIZED
            Json
                .parseToJsonElement(anonymous.bodyString())
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content shouldBe "unauthenticated"
            val service = app.provisionService("dashboard", permissions)
            val token = app.serviceToken(service)
            val cookie = app.adminCookie

            fun user() = app.http(Request(Method.GET, path).header("Cookie", cookie))

            fun bearer() = app.http(Request(Method.GET, path).header("Authorization", "Bearer $token"))
            listOf(emptySet(), setOf(FionaPermissions.InquiriesRead), setOf(CommercePermissions.FinancialDocumentRead)).forEach { grants ->
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
                app.authorization.replaceRolePermissions(service.role, grants)
                listOf(user(), bearer()).forEach {
                    it.status shouldBe Status.FORBIDDEN
                    Json
                        .parseToJsonElement(it.bodyString())
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content shouldBe "forbidden"
                }
            }
            app.authorization.replaceRolePermissions(service.role, permissions)
            app.http(Request(Method.GET, path).header("Cookie", cookie).header("Authorization", "Bearer $token")).status shouldBe
                Status.FORBIDDEN
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, permissions)
            listOf(user(), bearer()).forEach {
                it.status shouldBe Status.INTERNAL_SERVER_ERROR
                Json.parseToJsonElement(it.bodyString()).jsonObject shouldBe
                    Json.parseToJsonElement("""{"code":"internal_failure","message":"The request could not be completed"}""")
            }
            app.database.execute("UPDATE fionas.inquiry_financial_documents SET purpose = 'INITIAL_ESTIMATE' WHERE inquiry_id = '$inquiry'")
            listOf(user(), bearer()).forEach { it.status shouldBe Status.OK }
            app.authorization.replaceRolePermissions(service.role, setOf(FionaPermissions.InquiriesRead))
            bearer().status shouldBe Status.FORBIDDEN
            app.authorization.replaceRolePermissions(service.role, permissions)
            bearer().status shouldBe Status.OK
            // Safe cookie GET keeps the existing Origin behavior: no Origin or an untrusted Origin is allowed.
            app.http(Request(Method.GET, path).header("Cookie", cookie).header("Origin", "https://untrusted.example")).status shouldBe
                Status.OK
            app
                .http(
                    Request(Method.GET, path).header("Authorization", "Bearer $token").header("Origin", "https://untrusted.example"),
                ).status shouldBe
                Status.OK
            app.http(Request(Method.OPTIONS, path)).status shouldBe Status.METHOD_NOT_ALLOWED
            app.http(Request(Method.POST, path)).status shouldBe Status.METHOD_NOT_ALLOWED
        }

        test("requested and quoted records serialize navigation, enrichment, canonical facts and exact money without contact data") {
            app.createAcceptanceCatalog()
            val id = app.createInquiry()
            val document = app.initialEstimateOf(id)

            fun read() = CommerceJson.asA(app.adminGet(path).bodyString(), StaffDashboardResponse.serializer())
            val requested =
                read()
                    .workQueue.needsQuote.items
                    .single()
            requested.inquiryId shouldBe id
            requested.customerName shouldBe "Jane Doe"
            requested.eventDate shouldBe "2026-12-05"
            requested.eventType shouldBe InquiryEventType.BIRTHDAY
            requested.stage shouldBe InquiryStageResponse.REQUESTED
            requested.documentId shouldBe document
            requested.version shouldBe 1
            requested.financialStage shouldBe "ESTIMATE"
            requested.total shouldBe "681.25"
            requested.balance shouldBe "681.25"
            requested.currency shouldBe "USD"
            requested.inquiryCreatedAt shouldBe STORED_INSTANT.toString()
            requested.servedAt shouldBe null
            val json = Json.parseToJsonElement(app.adminGet(path).bodyString()).jsonObject
            val items =
                json
                    .getValue("workQueue")
                    .jsonObject
                    .getValue("needsQuote")
                    .jsonObject
                    .getValue("items") as JsonArray
            items.single().jsonObject.keys shouldBe
                setOf(
                    "inquiryId",
                    "customerId",
                    "customerName",
                    "eventDate",
                    "eventType",
                    "stage",
                    "documentId",
                    "version",
                    "financialStage",
                    "total",
                    "balance",
                    "currency",
                    "inquiryCreatedAt",
                    "latestDocumentVersionAt",
                )
            app.adminPost("/financial-documents/$document/quote", """{"expectedVersion":1}""").status shouldBe Status.OK
            val quote =
                read()
                    .workQueue.awaitingQuoteReply.items
                    .single()
            quote.stage shouldBe InquiryStageResponse.QUOTED
            quote.financialStage shouldBe "QUOTE"
            quote.version shouldBe 2
            read().workQueue.needsQuote.items shouldBe emptyList()
            quote.customerId shouldBe requested.customerId
            quote.total shouldBe requested.total
        }

        test("corrupt fulfillment and customer enrichment fail safely through the complete handler") {
            app.createAcceptanceCatalog()
            val id = app.createInquiry()
            app.database.execute("UPDATE fionas.customers SET name = '   '")
            val failure = app.adminGet(path)
            failure.status shouldBe Status.INTERNAL_SERVER_ERROR
            Json
                .parseToJsonElement(failure.bodyString())
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content shouldBe "internal_failure"
            failure.bodyString().contains("Dashboard") shouldBe false
            app.database.execute("UPDATE fionas.customers SET name = 'Jane Doe'")
            val actor =
                app.authorization
                    .findUserByUsername("admin")!!
                    .id.value
            app.database.execute(
                "INSERT INTO fionas.inquiry_fulfillment (inquiry_id, served_at, served_by_kind, served_by_id) " +
                    "VALUES ('$id', '${STORED_INSTANT}', 'USER', '$actor')",
            )
            app.adminGet(path).status shouldBe Status.INTERNAL_SERVER_ERROR
        }
    })
