package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.RecordInquiryCommunication
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.communicationHistory
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.testClock
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
import java.util.UUID

class StaffDashboardRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val path = "/staff/dashboard"
        val permissions = setOf(FionaPermissions.InquiriesRead, CommercePermissions.FinancialDocumentRead)

        beforeTest { app = TestApplication.create() }
        afterTest { app.close() }

        test("empty dashboard has the final three queues, one microsecond timestamp, and no-store") {
            val response = app.adminGet(path)
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            val json = Json.parseToJsonElement(response.bodyString()).jsonObject
            json.keys shouldBe setOf("asOf", "summary", "workQueue")
            json.getValue("asOf") shouldBe JsonPrimitive(STORED_INSTANT.toString())
            json.getValue("summary").jsonObject shouldBe Json.parseToJsonElement("""{"new":0,"quoted":0,"booked":0,"needsClosing":0}""")
            val queues = json.getValue("workQueue").jsonObject
            queues.keys shouldBe setOf("needsReply", "needsQuote", "needsResolution")
            queues.values.forEach {
                it.jsonObject shouldBe Json.parseToJsonElement("""{"items":[]}""")
            }
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
            requested.totalQualifier shouldBe DashboardTotalQualifierResponse.EXACT
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
                    "totalQualifier",
                    "balance",
                    "currency",
                    "inquiryCreatedAt",
                    "latestDocumentVersionAt",
                    "attentionSince",
                    "reasons",
                )
            app.issueProposal(InquiryId(UUID.fromString(id)))
            RecordInquiryCommunication(app.transactor, JdbiInquiryCommunicationRepository(), testClock)
                .customerEmailReceived(InquiryId(UUID.fromString(id)), STORED_INSTANT)
            val quote =
                read()
                    .workQueue.needsReply.items
                    .single()
            quote.stage shouldBe InquiryStageResponse.QUOTED
            quote.financialStage shouldBe "QUOTE"
            quote.totalQualifier shouldBe DashboardTotalQualifierResponse.EXACT
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
            val storedPricing = app.database.strings("SELECT pricing_inputs::text FROM fionas.inquiries WHERE id = '$id'").single()
            app.database.execute("UPDATE fionas.inquiries SET pricing_inputs = '{\"corrupt\":true}'::jsonb")
            val corruptPricing = app.adminGet(path)
            corruptPricing.status shouldBe Status.INTERNAL_SERVER_ERROR
            corruptPricing.bodyString().contains("pricing_inputs") shouldBe false
            corruptPricing.bodyString().contains("corrupt") shouldBe false
            app.transactor.inTransaction {
                it.handle
                    .createUpdate("UPDATE fionas.inquiries SET pricing_inputs = CAST(:inputs AS jsonb)")
                    .bind("inputs", storedPricing)
                    .execute()
            }
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

        test("unread inbound overlaps served balance resolution; acknowledgement clears reply with USER and SERVICE provenance") {
            app.createAcceptanceCatalog()
            val id = InquiryId(UUID.fromString(app.createInquiry()))
            val document = UUID.fromString(app.initialEstimateOf(id.value.toString()))
            app.transactor.inTransaction {
                app.context.financialLedger.issueQuote(it, document)
                app.context.financialLedger.issueInvoice(it, document)
            }
            app.adminPost("/inquiries/${id.value}/served").status shouldBe Status.OK
            val record =
                RecordInquiryCommunication(app.transactor, JdbiInquiryCommunicationRepository(), testClock)
            record.customerEmailReceived(id, STORED_INSTANT.minusSeconds(3600))
            record.customerEmailReceived(id, STORED_INSTANT.minusSeconds(60))

            fun read() = CommerceJson.asA(app.adminGet(path).bodyString(), StaffDashboardResponse.serializer())
            val before = read()
            before.workQueue.needsReply.items
                .single()
                .attentionSince shouldBe STORED_INSTANT.minusSeconds(3600).toString()
            before.workQueue.needsReply.items
                .single()
                .reasons shouldBe
                listOf(StaffAttentionReasonResponse.CUSTOMER_COMMUNICATION_UNACKNOWLEDGED)
            val balance =
                before.workQueue.needsResolution.items
                    .single()
            balance.reasons shouldBe listOf(StaffAttentionReasonResponse.SERVED_WITH_BALANCE_DUE)
            balance.attentionSince shouldBe STORED_INSTANT.toString()
            val acknowledge = "/inquiries/${id.value}/communications/acknowledge"
            app.http(Request(Method.POST, acknowledge)).status shouldBe Status.UNAUTHORIZED
            val service = app.provisionService("communications", setOf(FionaPermissions.InquiriesRead))
            val token = app.serviceToken(service)

            fun bearer(target: String = acknowledge) = app.http(Request(Method.POST, target).header("Authorization", "Bearer $token"))
            bearer().status shouldBe Status.FORBIDDEN
            app.authorization.replaceRolePermissions(service.role, setOf(FionaPermissions.CommunicationsAcknowledge))
            bearer("/inquiries/not-a-uuid/communications/acknowledge").status shouldBe Status.BAD_REQUEST
            bearer("/inquiries/${UUID.randomUUID()}/communications/acknowledge").status shouldBe Status.NOT_FOUND
            val response = bearer()
            response.status shouldBe Status.NO_CONTENT
            response.header("Cache-Control") shouldBe "no-store"
            bearer().status shouldBe Status.NO_CONTENT
            read().workQueue.needsReply.items shouldBe emptyList()
            read()
                .workQueue.needsResolution.items
                .single() shouldBe balance
            app.adminPost(acknowledge).status shouldBe Status.NO_CONTENT
            app.http(Request(Method.POST, acknowledge).header("Cookie", app.adminCookie)).status shouldBe Status.FORBIDDEN
            val facts = app.transactor.inTransaction { communicationHistory(it, id) }
            facts.filter { it.activity.kind.name == "STAFF_ACKNOWLEDGED" }.map { it.activity.principalId }.toSet() shouldBe
                setOf(service.id, app.authorization.findUserByUsername("admin")!!.id)
            // Read operations do not append activity or mutate the ledger.
            repeat(2) { read() }
            app.transactor.inTransaction { communicationHistory(it, id).size } shouldBe
                facts.size
            record.customerEmailReceived(id, STORED_INSTANT.minusSeconds(7200))
            read()
                .workQueue.needsReply.items
                .single()
                .attentionSince shouldBe STORED_INSTANT.minusSeconds(7200).toString()
            val cookie = app.adminCookie
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, permissions)
            app
                .http(
                    Request(Method.POST, acknowledge)
                        .header("Cookie", cookie)
                        .header("Origin", TEST_ORIGIN),
                ).status shouldBe Status.FORBIDDEN
        }
    })
