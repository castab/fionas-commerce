package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.financial.IsCurrentPayableInquiryProposal
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.UUID

class InquiryProposalRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        var revision = 0
        val grants = setOf(CommercePermissions.FinancialDocumentCreate, CommercePermissions.DepositRequirementManage)
        val initial = """{"expectedDocumentVersion":1,"terms":{"type":"PERCENTAGE","percentage":"20"}}"""

        fun path(id: String) = "/staff/requests/$id/proposals"

        fun issue(id: String) = app.adminPost(path(id), initial)

        fun response(text: String) = CommerceJson.asA(text, IssuedInquiryProposalResponse.serializer())
        beforeSpec {
            app = TestApplication.create()
            revision = app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }

        test("staff read exposes policy and coherent exact pairs through Quote and deposit revisions and booking") {
            val id = app.createInquiry()

            fun read() = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())
            val requested = read()
            requested.proposal shouldBe null
            requested.suggestedDepositTerms shouldBe DepositTermsRequest.Percentage("20")
            requested.depositRequirement shouldBe CurrentDepositRequirementResponse.None(requested.financial.id)
            val first = issue(id)
            first.status shouldBe Status.OK
            first.header("Cache-Control") shouldBe "no-store"
            val a = response(first.bodyString())
            a.proposal.documentId shouldBe requested.financial.id
            a.proposal.documentVersion shouldBe 2
            a.proposal.depositRequirementRevision shouldBe 1
            a.proposal.principalKind shouldBe "USER"
            a.proposal.principalId shouldBe
                app.authorization
                    .findUserByUsername("admin")!!
                    .id.value
                    .toString()
            read().proposal shouldBe a.proposal
            val revised =
                app.adminPost(
                    path(id) + "/quote-revisions",
                    """{"expectedDocumentVersion":2,"expectedDepositRequirementRevision":1,"pricingInputs":${pricingBody(
                        revision,
                        guests = 100,
                    )},"terms":{"type":"PERCENTAGE","percentage":"20"}}""",
                )
            revised.status shouldBe Status.OK
            val b = response(revised.bodyString())
            b.proposal.documentVersion shouldBe 3
            b.proposal.depositRequirementRevision shouldBe 2
            read().proposal shouldBe b.proposal
            val changed =
                app.adminPost(
                    path(id) + "/deposit-revisions",
                    """{"expectedDocumentVersion":3,"expectedDepositRequirementRevision":2,"terms":{"type":"FIXED","amount":"300.00","currency":"USD"}}""",
                )
            changed.status shouldBe Status.OK
            val c = response(changed.bodyString())
            c.proposal.documentVersion shouldBe 3
            c.proposal.depositRequirementRevision shouldBe 3
            read().proposal shouldBe c.proposal
            app
                .adminPost(
                    "/financial-documents/${c.proposal.documentId}/payments",
                    """{"documentVersion":3,"amount":"300.00","method":"CHECK","expectedProposalId":"${c.proposal.id}"}""",
                ).status shouldBe
                Status.CREATED
            val booked = read()
            booked.inquiry.lifecycle.stage shouldBe InquiryStageResponse.BOOKED
            booked.financial.stage shouldBe "INVOICE"
            booked.proposal shouldBe c.proposal
        }
        test("canonical bypasses reject while Estimate and booked Invoice change orders and RELATED paths remain supported") {
            val id = app.createInquiry()
            val document = app.initialEstimateOf(id)
            val requested = app.adminGet("/staff/requests/$id").bodyString()
            app
                .adminRequest(
                    Method.PUT,
                    "/financial-documents/$document/deposit-requirement",
                    """{"expectedDocumentVersion":1,"terms":{"type":"FIXED","amount":"50.00","currency":"USD"}}""",
                ).status shouldBe Status.UNPROCESSABLE_ENTITY
            app
                .adminRequest(
                    Method.DELETE,
                    "/financial-documents/$document/deposit-requirement",
                    """{"expectedRequirementRevision":1}""",
                ).status shouldBe Status.NOT_FOUND
            app.adminGet("/staff/requests/$id").bodyString() shouldBe requested
            app.adminPost("/financial-documents/$document/quote", """{"expectedVersion":1}""").status shouldBe Status.CONFLICT
            app
                .adminPost(
                    "/financial-documents/$document/change-orders",
                    pricingBody(revision, guests = 80, expectedVersion = 1),
                ).status shouldBe
                Status.OK
            val publication =
                response(app.adminPost(path(id), initial.replace(":1", ":2")).also { it.status shouldBe Status.OK }.bodyString())
            val approvedAmount = (publication.depositRequirement as CurrentDepositRequirementResponse.Active).requiredAmount.amount
            val snapshots = app.database.count("commerce.financial_document_snapshots")
            app
                .adminPost(
                    "/financial-documents/$document/change-orders",
                    pricingBody(revision, guests = 100, expectedVersion = 3),
                ).status shouldBe
                Status.CONFLICT
            app
                .adminRequest(
                    Method.PUT,
                    "/financial-documents/$document/deposit-requirement",
                    """{"expectedDocumentVersion":3,"expectedRequirementRevision":1,"terms":{"type":"FIXED","amount":"100.00","currency":"USD"}}""",
                ).status shouldBe
                Status.CONFLICT
            app
                .adminRequest(
                    Method.DELETE,
                    "/financial-documents/$document/deposit-requirement",
                    """{"expectedRequirementRevision":1}""",
                ).status shouldBe
                Status.CONFLICT
            app.adminPost("/financial-documents/$document/invoice", """{"expectedVersion":3}""").status shouldBe Status.CONFLICT
            app.database.count("commerce.financial_document_snapshots") shouldBe snapshots
            app
                .adminPost(
                    "/financial-documents/$document/payments",
                    """{"documentVersion":3,"amount":"$approvedAmount","method":"CASH","expectedProposalId":"${publication.proposal.id}"}""",
                ).status shouldBe
                Status.CREATED
            app
                .adminPost(
                    "/financial-documents/$document/change-orders",
                    pricingBody(revision, guests = 100, expectedVersion = 4),
                ).status shouldBe
                Status.OK
            val related = app.adminPost("/inquiries/$id/estimates", pricingBody(revision))
            val relatedId = CommerceJson.asA(related.bodyString(), FinancialDocumentResponse.serializer()).id
            app.adminPost("/financial-documents/$relatedId/quote", """{"expectedVersion":1}""").status shouldBe Status.OK
            app
                .adminRequest(
                    Method.PUT,
                    "/financial-documents/$relatedId/deposit-requirement",
                    """{"expectedDocumentVersion":2,"terms":{"type":"FIXED","amount":"100.00","currency":"USD"}}""",
                ).status shouldBe
                Status.OK
            app
                .adminPost(
                    "/financial-documents/$relatedId/change-orders",
                    pricingBody(revision, guests = 100, expectedVersion = 2),
                ).status shouldBe
                Status.OK
            app
                .adminRequest(
                    Method.DELETE,
                    "/financial-documents/$relatedId/deposit-requirement",
                    """{"expectedRequirementRevision":1}""",
                ).status shouldBe
                Status.OK
            app.adminPost("/financial-documents/$relatedId/invoice", """{"expectedVersion":3}""").status shouldBe Status.OK
            app
                .adminRequest(
                    Method.PUT,
                    "/financial-documents/$relatedId/deposit-requirement",
                    """{"expectedDocumentVersion":4,"expectedRequirementRevision":2,"terms":{"type":"FIXED","amount":"125.00","currency":"USD"}}""",
                ).status shouldBe Status.OK
            app
                .adminRequest(
                    Method.PUT,
                    "/financial-documents/$relatedId/deposit-requirement",
                    """{"expectedDocumentVersion":4,"expectedRequirementRevision":3,"terms":{"type":"FIXED","amount":"150.00","currency":"USD"}}""",
                ).status shouldBe Status.OK
            app
                .adminRequest(
                    Method.DELETE,
                    "/financial-documents/$relatedId/deposit-requirement",
                    """{"expectedRequirementRevision":4}""",
                ).status shouldBe Status.OK
        }
        listOf(Method.PUT, Method.DELETE).forEach { method ->
            test("booked canonical $method deposit mutation rejects without changing acceptance or staff projection") {
                val id = app.createInquiry()
                val accepted = response(issue(id).bodyString())
                val document = UUID.fromString(accepted.proposal.documentId)
                val amount = (accepted.depositRequirement as CurrentDepositRequirementResponse.Active).requiredAmount.amount
                app
                    .adminPost(
                        "/financial-documents/$document/payments",
                        """{"documentVersion":2,"amount":"$amount","method":"CHECK","expectedProposalId":"${accepted.proposal.id}"}""",
                    ).status shouldBe Status.CREATED

                fun read() = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())
                val booked = read()
                booked.inquiry.lifecycle.stage shouldBe InquiryStageResponse.BOOKED
                booked.financial.stage shouldBe "INVOICE"
                booked.proposal shouldBe accepted.proposal
                val deposit = booked.depositRequirement as CurrentDepositRequirementResponse.Active
                deposit.revision shouldBe accepted.proposal.depositRequirementRevision
                deposit.approvalDocumentVersion shouldBe accepted.proposal.documentVersion
                val deposits =
                    app.context.financialLedger
                        .depositRequirementHistory(document)
                        .map { it.requirement to it.createdAt }
                val snapshots = app.context.financialLedger.history(document)
                val proposals = JdbiInquiryProposalRepository()
                val inquiry = InquiryId(UUID.fromString(id))
                val publications = app.transactor.inTransaction { proposals.history(it, inquiry) }
                val body =
                    if (method == Method.PUT) {
                        """{"expectedDocumentVersion":3,"expectedRequirementRevision":1,"terms":{"type":"FIXED","amount":"50.00","currency":"USD"}}"""
                    } else {
                        """{"expectedRequirementRevision":1}"""
                    }
                val rejected = app.adminRequest(method, "/financial-documents/$document/deposit-requirement", body)
                rejected.status shouldBe Status.CONFLICT
                Json
                    .parseToJsonElement(rejected.bodyString())
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content shouldBe
                    "illegal_transition"
                app.context.financialLedger
                    .depositRequirementHistory(document)
                    .map { it.requirement to it.createdAt } shouldBe deposits
                app.context.financialLedger.history(document) shouldBe snapshots
                app.transactor.inTransaction { proposals.history(it, inquiry) } shouldBe publications
                read() shouldBe booked
                IsCurrentPayableInquiryProposal(
                    app.transactor,
                    app.context.financialLedger,
                    JdbiInquiryFinancialDocumentRepository(),
                    proposals,
                )(
                    InquiryProposalId(UUID.fromString(accepted.proposal.id)),
                ) shouldBe false
            }
        }
        test("all mutations require the permission intersection for USER and SERVICE, trusted cookie Origin and authenticated provenance") {
            val id = app.createInquiry()
            val service = app.provisionService("proposal-publisher", grants)
            val token = app.serviceToken(service)
            val cookie = app.adminCookie
            val original = app.authorization.getRole(CommerceRoles.Administrator)!!.permissions
            val routes = listOf(path(id) to initial, path(id) + "/quote-revisions" to "{}", path(id) + "/deposit-revisions" to "{}")
            try {
                routes.forEach { (url, body) ->
                    app.http(Request(Method.POST, url).body(body)).status shouldBe Status.UNAUTHORIZED
                    app.http(Request(Method.POST, url).header("Cookie", cookie).body(body)).status shouldBe Status.FORBIDDEN
                    listOf(
                        emptySet(),
                        setOf(CommercePermissions.FinancialDocumentCreate),
                        setOf(CommercePermissions.DepositRequirementManage),
                    ).forEach { permissions ->
                        app.authorization.replaceRolePermissions(CommerceRoles.Administrator, permissions)
                        app.authorization.replaceRolePermissions(service.role, permissions)
                        app.adminPost(url, body).status shouldBe Status.FORBIDDEN
                        app.http(Request(Method.POST, url).withBearer(token).body(body)).status shouldBe Status.FORBIDDEN
                    }
                    app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
                }
                app.authorization.replaceRolePermissions(service.role, grants)
                val result = app.http(Request(Method.POST, path(id)).withBearer(token).body(initial))
                result.status shouldBe Status.OK
                val issuance = response(result.bodyString()).proposal
                issuance.principalKind shouldBe "SERVICE"
                issuance.principalId shouldBe service.id.value.toString()
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
            }
        }
        test("malformed identifiers and terms, invalid values, missing inquiry and stale tokens map without writes") {
            val id = app.createInquiry()
            listOf(
                "{}" to Status.BAD_REQUEST,
                initial.replace("20", "1e1") to Status.UNPROCESSABLE_ENTITY,
                initial.replace("20", "0") to Status.UNPROCESSABLE_ENTITY,
                initial.replace(":1", ":0") to Status.UNPROCESSABLE_ENTITY,
                initial.replace("\"percentage\":\"20\"", "\"percentage\":\"20\",\"amount\":\"1\"") to Status.BAD_REQUEST,
                initial.replace(":1", ":2") to Status.CONFLICT,
            ).forEach { (body, expected) ->
                val count = app.database.count("commerce.financial_document_snapshots")
                app.adminPost(path(id), body).status shouldBe expected
                app.database.count("commerce.financial_document_snapshots") shouldBe count
            }
            app.adminPost(path("invalid"), initial).status shouldBe Status.BAD_REQUEST
            app.adminPost(path(UUID.randomUUID().toString()), initial).status shouldBe Status.NOT_FOUND
            issue(id).status shouldBe Status.OK
            val stale =
                app.adminPost(
                    path(id) + "/deposit-revisions",
                    """{"expectedDocumentVersion":2,"expectedDepositRequirementRevision":2,"terms":{"type":"PERCENTAGE","percentage":"25"}}""",
                )
            stale.status shouldBe Status.CONFLICT
            Json
                .parseToJsonElement(stale.bodyString())
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content shouldBe "conflict"
            val noChange =
                app.adminPost(
                    path(id) + "/deposit-revisions",
                    """{"expectedDocumentVersion":2,"expectedDepositRequirementRevision":1,"terms":{"type":"PERCENTAGE","percentage":"20.00"}}""",
                )
            noChange.status shouldBe Status.UNPROCESSABLE_ENTITY
            app.adminGet(path(id)).status shouldBe Status.METHOD_NOT_ALLOWED
        }
        test("missing or mismatched canonical proposal/deposit facts return generic internal failure") {
            listOf("missing", "quote", "deposit").forEach { corruption ->
                val id = app.createInquiry()
                val a = response(issue(id).bodyString())
                when (corruption) {
                    "missing" -> app.database.execute("DELETE FROM fionas.inquiry_proposals WHERE inquiry_id = '$id'")
                    "quote" -> app.database.execute("UPDATE fionas.inquiry_proposals SET document_version = 3 WHERE inquiry_id = '$id'")
                    "deposit" ->
                        app.database.execute(
                            "UPDATE fionas.inquiry_proposals SET deposit_requirement_revision = 2 WHERE inquiry_id = '$id'",
                        )
                }
                app.adminGet("/staff/requests/$id").status shouldBe Status.INTERNAL_SERVER_ERROR
                app
                    .adminPost(
                        path(id) + "/deposit-revisions",
                        """{"expectedDocumentVersion":2,"expectedDepositRequirementRevision":1,"terms":{"type":"PERCENTAGE","percentage":"25"}}""",
                    ).status shouldBe
                    Status.INTERNAL_SERVER_ERROR
                app.context.financialLedger
                    .history(UUID.fromString(a.proposal.documentId))
                    .size shouldBe 2
            }
        }
    })
