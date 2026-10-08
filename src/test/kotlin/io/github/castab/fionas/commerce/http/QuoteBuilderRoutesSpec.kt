package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.proposalJson
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

/** Staff Quote preview and publication from staff-committed final lines through the complete handler. */
class QuoteBuilderRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val percentTerms = """{"type":"PERCENTAGE","percentage":"20"}"""
        val churroLines = proposalJson(CHURROS.new("churros"), COURTESY_DISCOUNT.new("courtesy"))
        val servicePlan =
            """{"description":"Churro catering for an evening reception","guestCount":100,"durationMinutes":120,""" +
                """"items":["Churros with chocolate sauce","Cinnamon sugar"],""" +
                """"lineNotes":[{"key":"courtesy","note":"Returning customer courtesy"}]}"""

        fun preview(id: String) = "/staff/requests/$id/quote-preview"

        fun proposals(id: String) = "/staff/requests/$id/proposals"

        fun request(id: String) = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())

        fun previewBody(
            lines: String = churroLines,
            plan: String? = servicePlan,
            version: Int = 1,
            terms: String = percentTerms,
        ) = """{"expectedDocumentVersion":$version,"lines":$lines""" + plan?.let { ""","servicePlan":$it""" }.orEmpty() +
            ""","terms":$terms}"""

        fun issueBody(
            token: String,
            lines: String = churroLines,
            plan: String? = servicePlan,
            terms: String = percentTerms,
        ) = """{"expectedDocumentVersion":1,"terms":$terms,"lines":$lines""" + plan?.let { ""","servicePlan":$it""" }.orEmpty() +
            ""","reviewToken":"$token"}"""

        fun previewed(response: Response) = CommerceJson.asA(response.bodyString(), InquiryQuotePreviewResponse.serializer())

        fun issued(response: Response) = CommerceJson.asA(response.bodyString(), IssuedInquiryProposalResponse.serializer())

        fun error(response: Response) = Json.parseToJsonElement(response.bodyString()).jsonObject

        fun codes(response: Response) =
            error(response)["violations"]?.jsonArray?.map {
                it.jsonObject
                    .getValue("code")
                    .jsonPrimitive.content
            } ?: emptyList()

        val tables =
            listOf(
                "commerce.financial_document_snapshots",
                "commerce.deposit_requirement_revisions",
                "fionas.inquiry_proposals",
                "fionas.inquiry_service_plans",
                "fionas.financial_document_authorship",
            )

        fun counts() = tables.map(app.database::count)

        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }

        test("a no-store preview writes nothing, and issuing exactly the reviewed lines publishes them with the service plan") {
            val id = app.createInquiry()
            val before = counts()
            val response = app.adminPost(preview(id), previewBody())
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            counts() shouldBe before
            val reviewed = previewed(response)
            reviewed.reviewedDocumentVersion shouldBe 1
            reviewed.estimateTotal shouldBe "681.25"
            reviewed.financialChange shouldBe true
            reviewed.quoteVersion shouldBe 3
            reviewed.lines.map { it.origin } shouldContainExactly listOf("NEW", "NEW")
            reviewed.lines.map { it.key } shouldContainExactly listOf("churros", "courtesy")
            reviewed.total shouldBe "400.00"
            reviewed.deposit.requiredAmount shouldBe DepositMoneyResponse("80.00", "USD")
            reviewed.servicePlan
                .shouldNotBeNull()
                .lineNotes
                .single()
                .lineItemId shouldBe reviewed.lines[1].id
            // The same request reviews to the same identity, with the same derived line ids.
            previewed(app.adminPost(preview(id), previewBody())).let {
                it.reviewToken shouldBe reviewed.reviewToken
                it.lines.map { line -> line.id } shouldContainExactly reviewed.lines.map { line -> line.id }
            }

            val published = app.adminPost(proposals(id), issueBody(reviewed.reviewToken))
            published.status shouldBe Status.OK
            published.header("Cache-Control") shouldBe "no-store"
            val result = issued(published)
            result.financial.version shouldBe 3
            result.financial.stage shouldBe "QUOTE"
            result.financial.lines.map { it.id } shouldContainExactly reviewed.lines.map { it.id }
            result.financial.total shouldBe "400.00"
            result.financial.linesAuthoredBy?.principalId shouldBe app.adminId.value.toString()
            result.proposal.issuedBy shouldBe app.adminId.value.toString()
            val plan = result.servicePlan.shouldNotBeNull()
            plan.documentVersion shouldBe 3
            plan.reviewedDocumentVersion shouldBe 1
            plan.description shouldBe "Churro catering for an evening reception"
            plan.items shouldContainExactly listOf("Churros with chocolate sauce", "Cinnamon sugar")
            plan.approvedBy shouldBe app.adminId.value.toString()
            request(id).servicePlan shouldBe plan
            request(id).inquiry.lifecycle.stage shouldBe InquiryStageResponse.QUOTED
            // Estimate v1 is unchanged in the history.
            val history =
                CommerceJson.asA(
                    app.adminGet("/financial-documents/${result.financial.id}/history").bodyString(),
                    FinancialDocumentHistoryResponse.serializer(),
                )
            history.versions.first().total shouldBe "681.25"
            history.versions
                .first()
                .linesAuthoredBy
                ?.principalKind shouldBe "SERVICE"
        }

        test("issuance without lines keeps the Estimate and records no plan") {
            val id = app.createInquiry()
            val response = app.adminPost(proposals(id), """{"expectedDocumentVersion":1,"terms":$percentTerms}""")
            response.status shouldBe Status.OK
            issued(response).financial.version shouldBe 2
            issued(response).servicePlan.shouldBeNull()
            request(id).servicePlan.shouldBeNull()
        }

        test("contradictory or unreadable shapes are malformed requests that write nothing") {
            val id = app.createInquiry()
            val estimateLine =
                request(id)
                    .financial.lines
                    .first()
                    .id
            val token = previewed(app.adminPost(preview(id), previewBody())).reviewToken
            val before = counts()
            listOf(
                preview(id) to previewBody(lines = """[{"lineItemId":"$estimateLine","key":"both",${CHURROS.json().drop(1)}]"""),
                preview(id) to previewBody(lines = "[${CHURROS.json()}]"),
                preview(id) to previewBody(lines = """[{"lineItemId":"not-a-uuid",${CHURROS.json().drop(1)}]"""),
                preview(id) to
                    previewBody(lines = """[{"key":"a","description":"Churros","unitPrice":450,"taxAmount":"0","currency":"USD"}]"""),
                preview(id) to previewBody(plan = """{"description":"x","lineNotes":[{"note":"no identity"}]}"""),
                preview(id) to previewBody(terms = """{"type":"FIXED","amount":"1.00","currency":"USD","percentage":"20"}"""),
                proposals(id) to """{"expectedDocumentVersion":1,"terms":$percentTerms,"lines":$churroLines}""",
                proposals(id) to """{"expectedDocumentVersion":1,"terms":$percentTerms,"reviewToken":"$token"}""",
                proposals(id) to """{"expectedDocumentVersion":1,"terms":$percentTerms,"servicePlan":$servicePlan}""",
            ).forEach { (path, body) ->
                val response = app.adminPost(path, body)
                response.status shouldBe Status.BAD_REQUEST
                error(response).getValue("code").jsonPrimitive.content shouldBe "malformed_request"
            }
            counts() shouldBe before
        }

        test("invalid values and proposals are 422 with stable codes, never partially written") {
            val id = app.createInquiry()
            val before = counts()
            listOf(
                previewBody(lines = proposalJson(COURTESY_DISCOUNT.new("credit"))) to listOf("NEGATIVE_DOCUMENT_TOTAL"),
                previewBody(lines = proposalJson(CHURROS.copy(unitPrice = "0.00").new("free"))) to listOf("QUOTE_TOTAL_NOT_POSITIVE"),
                previewBody(lines = proposalJson(CHURROS.existing("00000000-0000-0000-0000-000000000001"))) to
                    listOf("LINE_NOT_IN_REVIEWED_DOCUMENT"),
                previewBody(lines = proposalJson(CHURROS.copy(currency = "EUR").new("eur"))) to listOf("CURRENCY_MISMATCH"),
                previewBody(plan = """{"description":"x","lineNotes":[{"key":"nowhere","note":"Why"}]}""") to
                    listOf("SERVICE_PLAN_LINE_NOT_FOUND"),
                previewBody(lines = proposalJson(CHURROS.copy(unitPrice = "450.001").new("a"))) to emptyList(),
                previewBody(lines = "[]") to emptyList(),
                previewBody(plan = """{"description":"   "}""") to emptyList(),
                previewBody(terms = """{"type":"PERCENTAGE","percentage":"101"}""") to emptyList(),
                previewBody(terms = """{"type":"FIXED","amount":"1000.00","currency":"USD"}""") to emptyList(),
            ).forEach { (body, expected) ->
                val response = app.adminPost(preview(id), body)
                response.status shouldBe Status.UNPROCESSABLE_ENTITY
                if (expected.isNotEmpty()) codes(response) shouldBe expected
            }
            counts() shouldBe before
        }

        test("stale versions and changed reviews conflict with distinct no-store codes and write nothing") {
            val id = app.createInquiry()
            val token = previewed(app.adminPost(preview(id), previewBody())).reviewToken
            val before = counts()
            val changed = proposalJson(CHURROS.copy(unitPrice = "500.00").new("churros"), COURTESY_DISCOUNT.new("courtesy"))
            listOf(
                issueBody(token, lines = changed),
                issueBody(token, plan = null),
                issueBody(token, terms = """{"type":"PERCENTAGE","percentage":"25"}"""),
            ).forEach { body ->
                val response = app.adminPost(proposals(id), body)
                response.status shouldBe Status.CONFLICT
                response.header("Cache-Control") shouldBe "no-store"
                error(response).getValue("code").jsonPrimitive.content shouldBe "QUOTE_REVIEW_STALE"
            }
            counts() shouldBe before
            // The Estimate moved on: the reviewed version is stale, never rebased.
            val document = app.initialEstimateOf(id)
            app
                .adminPost(
                    "/financial-documents/$document/change-orders",
                    """{"expectedVersion":1,"lines":${proposalJson(CHURROS.new("first"))}}""",
                ).status shouldBe Status.OK
            val after = counts()
            listOf(app.adminPost(preview(id), previewBody()), app.adminPost(proposals(id), issueBody(token))).forEach {
                it.status shouldBe Status.CONFLICT
                error(it).getValue("code").jsonPrimitive.content shouldBe "conflict"
            }
            counts() shouldBe after
        }

        test("a corrupt or contradictory stored plan fails the staff read closed instead of being reinterpreted") {
            listOf(
                "jsonb_set(plan, '{lineNotes,0,lineItemId}', to_jsonb(gen_random_uuid()::text))",
                "plan || '{\"extra\": true}'::jsonb",
                "plan - 'items'",
            ).forEach { corruption ->
                val id = app.createInquiry()
                val token = previewed(app.adminPost(preview(id), previewBody())).reviewToken
                app.adminPost(proposals(id), issueBody(token)).status shouldBe Status.OK
                app.adminGet("/staff/requests/$id").status shouldBe Status.OK
                app.database.execute("UPDATE fionas.inquiry_service_plans SET plan = $corruption WHERE inquiry_id = '$id'")
                val read = app.adminGet("/staff/requests/$id")
                read.status shouldBe Status.INTERNAL_SERVER_ERROR
                read.bodyString() shouldNotContain "lineNotes"
            }
        }

        test("preview and publication need a staff USER holding every terms permission and a trusted cookie Origin") {
            val id = app.createInquiry()
            val body = previewBody()
            app.http(Request(Method.POST, preview(id)).body(body)).status shouldBe Status.UNAUTHORIZED
            app.http(Request(Method.POST, preview(id)).header("Cookie", app.adminCookie).body(body)).status shouldBe Status.FORBIDDEN
            app
                .http(
                    Request(Method.POST, preview(id)).header("Cookie", app.adminCookie).header("Origin", "https://evil.example").body(body),
                ).status shouldBe Status.FORBIDDEN
            val grants =
                setOf(
                    CommercePermissions.FinancialDocumentCreate,
                    CommercePermissions.DepositRequirementManage,
                    FionaPermissions.FinancialTermsManage,
                )
            val original = app.authorization.getRole(CommerceRoles.Administrator)!!.permissions
            try {
                grants.forEach { missing ->
                    app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original - missing)
                    app.adminPost(preview(id), body).status shouldBe Status.FORBIDDEN
                }
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
            }
            // A service holding every permission previews and publishes nothing: amounts need a verified staff user.
            val service = app.provisionService("quote-builder", grants)
            val token = app.serviceToken(service)
            val reviewed = previewed(app.adminPost(preview(id), body))
            listOf(preview(id) to body, proposals(id) to issueBody(reviewed.reviewToken)).forEach { (path, request) ->
                app.http(Request(Method.POST, path).withBearer(token).body(request)).let {
                    it.status shouldBe Status.FORBIDDEN
                    error(it).getValue("message").jsonPrimitive.content shouldBe STAFF_USER_REQUIRED
                }
            }
            // The verified staff user's publication records that user, never the service.
            val published = issued(app.adminPost(proposals(id), issueBody(reviewed.reviewToken)))
            published.servicePlan!!.approvedBy shouldBe app.adminId.value.toString()
            published.servicePlan.approvedBy shouldNotBe service.id.value.toString()
            app.http(Request(Method.POST, preview(id)).header("Origin", TEST_ORIGIN).body(body)).status shouldBe Status.UNAUTHORIZED
        }
    })
