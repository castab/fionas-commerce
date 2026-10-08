package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.currentCatalogRevision
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

/** The quote builder's preview and composed initial issuance through the complete handler. */
class QuoteBuilderRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val grants = setOf(CommercePermissions.FinancialDocumentCreate, CommercePermissions.DepositRequirementManage)
        val fixedTerms = """{"type":"FIXED","amount":"105.00","currency":"USD"}"""

        fun preview(id: String) = "/staff/requests/$id/quote-preview"

        fun proposals(id: String) = "/staff/requests/$id/proposals"

        fun newInquiry() =
            app.createInquiry {
                pricingBody(it, guests = 40, softServe = listOf("vanilla"), toppings = TOPPINGS.take(4), cones = listOf("waffle-cone"))
            }

        fun request(id: String) = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())

        fun composition(iceCreamLine: String) =
            """{"pricing":{"mode":"KEEP_ESTIMATE"},
               "overrides":[{"target":{"type":"EXISTING_LINE","lineItemId":"$iceCreamLine"},"finalAmount":"145.00","currency":"USD","reason":"Negotiated package rate"}],
               "adjustments":[
                 {"clientKey":"travel-1","kind":"CHARGE","description":"Additional travel fee","amount":"25.00","currency":"USD","reason":"Outside normal service area"},
                 {"clientKey":"courtesy-1","kind":"DISCOUNT","description":"Courtesy discount","subDescription":"Thank you","amount":"20.00","currency":"USD","reason":"Customer accommodation"}]}"""

        fun previewBody(
            composition: String,
            version: Int = 1,
            terms: String = fixedTerms,
        ) = """{"expectedDocumentVersion":$version,"composition":$composition,"terms":$terms}"""

        fun issueBody(
            composition: String,
            token: String,
            terms: String = fixedTerms,
        ) = """{"expectedDocumentVersion":1,"terms":$terms,"composition":$composition,"reviewToken":"$token"}"""

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
            )

        fun counts() = tables.map(app.database::count)

        beforeSpec {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }

        test("a no-store preview writes nothing, and issuing its reviewed composition publishes it with the service plan") {
            val id = newInquiry()
            val estimate = request(id).financial
            val before = counts()
            val response = app.adminPost(preview(id), previewBody(composition(estimate.lines[1].id)))
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            counts() shouldBe before
            val result = previewed(response)
            result.inquiryId shouldBe id
            result.documentId shouldBe estimate.id
            result.reviewedDocumentVersion shouldBe 1
            result.estimateTotal shouldBe "440.00"
            result.pricingBasis shouldBe "KEEP_ESTIMATE"
            result.catalogRevision shouldBe app.currentCatalogRevision()
            result.financialChange shouldBe true
            result.quoteVersion shouldBe 3
            result.total shouldBe "430.00"
            result.deposit shouldBe
                QuoteDepositPreviewResponse(DepositTermsRequest.Fixed("105.00", "USD"), DepositMoneyResponse("105.00", "USD"))
            result.lines.map { it.lineItemId } shouldContainExactly estimate.lines.map { it.id } + listOf(null, null)
            result.lines.map { it.total } shouldContainExactly listOf("250.00", "145.00", "30.00", "25.00", "-20.00")
            result.lines[1].quantity.shouldBeNull()
            result.lines[1].override shouldBe QuoteLineOverrideResponse("Negotiated package rate", "40", "4.00", "160.00")
            result.lines[3].origin shouldBe
                QuoteLineOriginResponse.Adjustment(QuoteAdjustmentKindDto.CHARGE, "Outside normal service area", "travel-1")
            result.lines[4].subDescription shouldBe "Thank you"
            result.lines[0].origin shouldBe QuoteLineOriginResponse.EstimateLine()
            result.service.selections.map { it.displayName } shouldContainExactly listOf("Soft Serve", "Toppings", "Cones")
            // Equivalent previews have the same identity although new lines would get new ids.
            previewed(app.adminPost(preview(id), previewBody(composition(estimate.lines[1].id)))).reviewToken shouldBe result.reviewToken

            val published = app.adminPost(proposals(id), issueBody(composition(estimate.lines[1].id), result.reviewToken))
            published.status shouldBe Status.OK
            published.header("Cache-Control") shouldBe "no-store"
            val body = issued(published)
            body.financial.version shouldBe 3
            body.financial.stage shouldBe "QUOTE"
            body.financial.total shouldBe "430.00"
            body.proposal.documentVersion shouldBe 3
            body.proposal.issuanceKind shouldBe "INITIAL"
            val plan = body.servicePlan.shouldNotBeNull()
            plan.documentVersion shouldBe 3
            plan.reviewedDocumentVersion shouldBe 1
            plan.principalKind shouldBe "USER"
            plan.lines.map { it.lineItemId } shouldContainExactly body.financial.lines.map { it.id }
            plan.lines[1].overrideReason shouldBe "Negotiated package rate"
            plan.lines[4].origin shouldBe QuoteLineOriginResponse.Adjustment(QuoteAdjustmentKindDto.DISCOUNT, "Customer accommodation")
            (body.depositRequirement as CurrentDepositRequirementResponse.Active).requiredAmount shouldBe
                DepositMoneyResponse("105.00", "USD")
            val after = request(id)
            after.servicePlan shouldBe plan
            after.inquiry.lifecycle.stage shouldBe InquiryStageResponse.QUOTED
            // Published, so composing again is no longer possible.
            val again = app.adminPost(preview(id), previewBody(composition(estimate.lines[1].id), version = 3))
            again.status shouldBe Status.CONFLICT
            error(again)["code"]!!.jsonPrimitive.content shouldBe "illegal_transition"
        }

        test("deposit-only issuance keeps its request and response shape and records no plan") {
            val id = newInquiry()
            val response = app.adminPost(proposals(id), """{"expectedDocumentVersion":1,"terms":{"type":"PERCENTAGE","percentage":"20"}}""")
            response.status shouldBe Status.OK
            response.bodyString() shouldNotContain "servicePlan"
            issued(response).financial.version shouldBe 2
            request(id).servicePlan.shouldBeNull()
            app.adminGet("/staff/requests/$id").bodyString() shouldNotContain "servicePlan"
        }

        test("contradictory or unreadable shapes are malformed requests that write nothing") {
            val id = newInquiry()
            val line = request(id).financial.lines[1].id
            val keep = """{"pricing":{"mode":"KEEP_ESTIMATE"}}"""
            val token = previewed(app.adminPost(preview(id), previewBody(keep))).reviewToken
            val before = counts()
            listOf(
                proposals(id) to """{"expectedDocumentVersion":1,"terms":$fixedTerms,"composition":$keep}""",
                proposals(id) to """{"expectedDocumentVersion":1,"terms":$fixedTerms,"reviewToken":"$token"}""",
                preview(id) to previewBody("""{"pricing":{"mode":"KEEP_ESTIMATE","selections":[]}}"""),
                preview(id) to previewBody("""{"pricing":{"mode":"GUESS"}}"""),
                preview(id) to previewBody("""{"overrides":[]}"""),
                preview(id) to previewBody("""{"pricing":{"mode":"REVISE_SERVICE_SELECTIONS","catalogRevision":1}}"""),
                preview(id) to
                    previewBody(
                        """{"pricing":{"mode":"KEEP_ESTIMATE"},"overrides":[{"target":{"type":"EXISTING_LINE"},"finalAmount":"1.00","currency":"USD","reason":"r"}]}""",
                    ),
                preview(id) to
                    previewBody(
                        """{"pricing":{"mode":"KEEP_ESTIMATE"},"overrides":[{"target":{"type":"BASE_SERVICE","lineItemId":"$line"},"finalAmount":"1.00","currency":"USD","reason":"r"}]}""",
                    ),
                preview(id) to
                    previewBody(
                        """{"pricing":{"mode":"KEEP_ESTIMATE"},"overrides":[{"target":{"type":"EXISTING_LINE","lineItemId":"nope"},"finalAmount":"1.00","currency":"USD","reason":"r"}]}""",
                    ),
                preview(id) to
                    previewBody(
                        """{"pricing":{"mode":"KEEP_ESTIMATE"},"adjustments":[{"clientKey":"a","kind":"REFUND","description":"d","amount":"1.00","currency":"USD","reason":"r"}]}""",
                    ),
                preview("not-a-uuid") to previewBody(keep),
            ).forEach { (path, body) ->
                val response = app.adminPost(path, body)
                response.status shouldBe Status.BAD_REQUEST
                error(response)["code"]!!.jsonPrimitive.content shouldBe "malformed_request"
            }
            counts() shouldBe before
        }

        test("invalid values and compositions are 422 with stable codes, never partially written") {
            val id = newInquiry()
            val line = request(id).financial.lines[1].id

            fun override(
                target: String = """{"type":"EXISTING_LINE","lineItemId":"$line"}""",
                amount: String = "150.00",
                currency: String = "USD",
                reason: String = "Negotiated",
            ) =
                """{"pricing":{"mode":"KEEP_ESTIMATE"},"overrides":[{"target":$target,"finalAmount":"$amount","currency":"$currency","reason":"$reason"}]}"""

            fun adjustments(vararg items: String) = """{"pricing":{"mode":"KEEP_ESTIMATE"},"adjustments":[${items.joinToString(",")}]}"""

            fun item(
                key: String = "a",
                kind: String = "CHARGE",
                amount: String = "10.00",
                description: String = "Line",
            ) = """{"clientKey":"$key","kind":"$kind","description":"$description","amount":"$amount","currency":"USD","reason":"Why"}"""
            val before = counts()
            listOf(
                override(target = """{"type":"EXISTING_LINE","lineItemId":"00000000-0000-0000-0000-000000000001"}""") to
                    listOf("OVERRIDE_TARGET_NOT_FOUND"),
                override(target = """{"type":"BASE_SERVICE"}""") to listOf("OVERRIDE_TARGET_NOT_ALLOWED"),
                override(amount = "160.00") to listOf("OVERRIDE_UNCHANGED"),
                override(currency = "EUR") to listOf("CURRENCY_MISMATCH"),
                adjustments(item(), item()) to listOf("DUPLICATE_ADJUSTMENT_KEY"),
                adjustments(item(kind = "CREDIT", amount = "440.00")) to listOf("QUOTE_TOTAL_NOT_POSITIVE"),
                adjustments(item(kind = "CREDIT", amount = "440.01")) to listOf("NEGATIVE_DOCUMENT_TOTAL"),
                override(amount = "-1.00") to emptyList(),
                override(amount = "150.001") to emptyList(),
                override(amount = "1e2") to emptyList(),
                override(reason = "  ") to emptyList(),
                adjustments(item(amount = "0")) to emptyList(),
                adjustments(item(key = "has space")) to emptyList(),
                adjustments(item(description = "d".repeat(121))) to emptyList(),
            ).forEach { (composition, expected) ->
                val response = app.adminPost(preview(id), previewBody(composition))
                response.status shouldBe Status.UNPROCESSABLE_ENTITY
                error(response)["code"]!!.jsonPrimitive.content shouldBe "validation_failed"
                codes(response) shouldBe expected
            }
            app
                .adminPost(
                    preview(id),
                    previewBody(
                        """{"pricing":{"mode":"KEEP_ESTIMATE"}}""",
                        terms = """{"type":"FIXED","amount":"440.01","currency":"USD"}""",
                    ),
                ).status shouldBe Status.UNPROCESSABLE_ENTITY
            counts() shouldBe before
        }

        test("stale versions, stale catalog revisions and changed reviews conflict with distinct no-store codes") {
            val id = newInquiry()
            val keep = """{"pricing":{"mode":"KEEP_ESTIMATE"}}"""
            app.adminPost(preview(id), previewBody(keep, version = 2)).let {
                it.status shouldBe Status.CONFLICT
                error(it)["code"]!!.jsonPrimitive.content shouldBe "conflict"
            }
            val stale = app.currentCatalogRevision() - 1
            val reprice =
                """{"pricing":{"mode":"REPRICE_CONFIGURATION","catalogRevision":$stale,"guestCount":40,"durationMinutes":120,""" +
                    """"selections":[{"category":"soft-serve-flavor","offerings":["vanilla"]},{"category":"topping","offerings":[${TOPPINGS.take(
                        4,
                    ).joinToString(",") {
                        "\"$it\""
                    }}]},{"category":"cone-option","offerings":["cup"]}]}}"""
            app.adminPost(preview(id), previewBody(reprice)).let {
                it.status shouldBe Status.CONFLICT
                it.header("Cache-Control") shouldBe "no-store"
                error(it)["code"]!!.jsonPrimitive.content shouldBe "CATALOG_REVISION_STALE"
            }
            val before = counts()
            app.adminPost(proposals(id), issueBody(keep, "0".repeat(64))).let {
                it.status shouldBe Status.CONFLICT
                it.header("Cache-Control") shouldBe "no-store"
                error(it)["code"]!!.jsonPrimitive.content shouldBe "QUOTE_REVIEW_STALE"
            }
            app.adminPost(proposals(id), issueBody(reprice, "0".repeat(64))).let {
                it.status shouldBe Status.CONFLICT
                error(it)["code"]!!.jsonPrimitive.content shouldBe "CATALOG_REVISION_STALE"
            }
            counts() shouldBe before
            val current = reprice.replace("\"catalogRevision\":$stale", "\"catalogRevision\":${stale + 1}")
            val reviewed = previewed(app.adminPost(preview(id), previewBody(current)))
            reviewed.pricingBasis shouldBe "REPRICE_CONFIGURATION"
            reviewed.total shouldBe "410.00"
            reviewed.lines.map { it.lineItemId } shouldBe listOf(null, null)
            reviewed.lines.map { (it.origin as QuoteLineOriginResponse.Generated).source } shouldContainExactly
                listOf(ChargeSourceResponse.BaseService(), ChargeSourceResponse.IceCreamService())
            val published = issued(app.adminPost(proposals(id), issueBody(current, reviewed.reviewToken)))
            published.servicePlan!!.pricingBasis shouldBe "REPRICE_CONFIGURATION"
            published.servicePlan!!
                .service.selections
                .last()
                .offerings
                .single()
                .displayName shouldBe "Cups"
        }

        test("a corrupt or contradictory stored plan fails the staff read closed instead of being reinterpreted") {
            val keep = """{"pricing":{"mode":"KEEP_ESTIMATE"}}"""
            listOf(
                "jsonb_set(plan, '{lines,0,lineItemId}', to_jsonb(gen_random_uuid()::text))",
                "plan || '{\"extra\": true}'::jsonb",
                "jsonb_set(plan, '{lines}', (plan -> 'lines') - 0)",
            ).forEach { corruption ->
                val id = newInquiry()
                val token = previewed(app.adminPost(preview(id), previewBody(keep))).reviewToken
                app.adminPost(proposals(id), issueBody(keep, token)).status shouldBe Status.OK
                app.adminGet("/staff/requests/$id").status shouldBe Status.OK
                app.database.execute("UPDATE fionas.inquiry_service_plans SET plan = $corruption WHERE inquiry_id = '$id'")
                val read = app.adminGet("/staff/requests/$id")
                read.status shouldBe Status.INTERNAL_SERVER_ERROR
                read.bodyString() shouldNotContain "lineItemId"
            }
        }

        test("preview and composed issuance need the issuance permission intersection, any principal kind, and a trusted cookie Origin") {
            val id = newInquiry()
            val keep = """{"pricing":{"mode":"KEEP_ESTIMATE"}}"""
            val body = previewBody(keep)
            app.http(Request(Method.POST, preview(id)).body(body)).status shouldBe Status.UNAUTHORIZED
            app.http(Request(Method.POST, preview(id)).header("Cookie", app.adminCookie).body(body)).status shouldBe Status.FORBIDDEN
            app
                .http(
                    Request(Method.POST, preview(id)).header("Cookie", app.adminCookie).header("Origin", "https://evil.example").body(body),
                ).status shouldBe Status.FORBIDDEN
            val service = app.provisionService("quote-builder", setOf(CommercePermissions.FinancialDocumentCreate))
            val token = app.serviceToken(service)
            app.http(Request(Method.POST, preview(id)).withBearer(token).body(body)).status shouldBe Status.FORBIDDEN
            app.authorization.replaceRolePermissions(service.role, setOf(CommercePermissions.DepositRequirementManage))
            app.http(Request(Method.POST, preview(id)).withBearer(token).body(body)).status shouldBe Status.FORBIDDEN
            app.authorization.replaceRolePermissions(service.role, grants)
            val reviewed = app.http(Request(Method.POST, preview(id)).withBearer(token).body(body))
            reviewed.status shouldBe Status.OK
            val published =
                app.http(Request(Method.POST, proposals(id)).withBearer(token).body(issueBody(keep, previewed(reviewed).reviewToken)))
            published.status shouldBe Status.OK
            val plan = issued(published).servicePlan.shouldNotBeNull()
            plan.principalKind shouldBe "SERVICE"
            plan.principalId shouldBe service.id.value.toString()
        }
    })
