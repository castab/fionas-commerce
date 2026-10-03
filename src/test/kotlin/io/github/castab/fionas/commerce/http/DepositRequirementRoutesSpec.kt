package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.financial.InquiryDocumentAssociation
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.testClock
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
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
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

class DepositRequirementRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val owners = JdbiInquiryFinancialDocumentRepository()

        fun money(value: String) = Money(BigDecimal(value), Currency.getInstance("USD"))

        fun newDocument(
            stage: String = "QUOTE",
            total: String = "200.02",
            owned: Boolean = true,
        ): UUID {
            val inquiry = if (owned) InquiryId(UUID.fromString(app.createInquiry())) else null
            val id = UUID.randomUUID()
            val lines = listOf(LineItem(UUID.randomUUID(), "Service", null, null, money(total), money("0.00")))
            app.transactor.inTransaction { transaction ->
                val document =
                    when (stage) {
                        "ESTIMATE" -> FinancialDocument.Estimate.create(id, lines)
                        "INVOICE" -> FinancialDocument.Invoice.create(id, lines)
                        else -> FinancialDocument.Quote.create(id, lines)
                    }
                app.context.financialLedger.create(transaction, document)
                if (inquiry != null) owners.associate(transaction, InquiryDocumentAssociation(inquiry, id, testClock.instant()))
            }
            return id
        }

        fun path(id: UUID) = "/financial-documents/$id/deposit-requirement"

        fun fixed(
            amount: String = "100.00",
            currency: String = "USD",
        ) = """{"type":"FIXED","amount":"$amount","currency":"$currency"}"""

        fun percent(value: String = "25") = """{"type":"PERCENTAGE","percentage":"$value"}"""

        fun setBody(
            version: Int = 1,
            revision: Int? = null,
            terms: String = fixed(),
        ) = """{"expectedDocumentVersion":$version,"expectedRequirementRevision":$revision,"terms":$terms}"""

        fun set(
            id: UUID,
            version: Int = 1,
            revision: Int? = null,
            terms: String = fixed(),
        ) = app.adminRequest(Method.PUT, path(id), setBody(version, revision, terms))

        fun withdraw(
            id: UUID,
            revision: Int = 1,
        ) = app.adminRequest(Method.DELETE, path(id), """{"expectedRequirementRevision":$revision}""")

        fun current(id: UUID) = app.adminGet(path(id))

        fun Response.state() = CommerceJson.asA(bodyString(), CurrentDepositRequirementResponse.serializer())

        fun Response.active() = state() as CurrentDepositRequirementResponse.Active

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun query(vararg ids: UUID) = app.adminPost("/financial-documents/query", """{"documentIds":[${ids.joinToString { "\"$it\"" }}]}""")

        fun Response.lineages() = CommerceJson.asA(bodyString(), FinancialLineagesResponse.serializer()).lineages

        fun revisions(id: UUID) =
            CommerceJson.asA(app.adminGet(path(id) + "/history").bodyString(), DepositRequirementHistoryResponse.serializer()).revisions

        fun depositRows() = app.database.count("commerce.deposit_requirement_revisions")

        beforeSpec {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
            app.adminCookie
        }
        afterSpec { app.close() }

        test("current NONE and empty history read no writes") {
            val id = newDocument()
            val count = depositRows()
            current(id).status shouldBe Status.OK
            current(id).state() shouldBe CurrentDepositRequirementResponse.None(id.toString())
            revisions(id) shouldBe emptyList()
            depositRows() shouldBe count
        }

        test("fixed replacement withdrawal and percentage reactivation preserve exact immutable history") {
            val id = newDocument()
            val first = set(id).also { it.status shouldBe Status.OK }.active()
            first.revision shouldBe 1
            first.approvalDocumentVersion shouldBe 1
            first.requiredAmount shouldBe DepositMoneyResponse("100.00", "USD")
            first.satisfied shouldBe false
            set(id, revision = 1, terms = percent()).active().requiredAmount shouldBe DepositMoneyResponse("50.01", "USD")
            val retracted = withdraw(id, 2).also { it.status shouldBe Status.OK }.state() as CurrentDepositRequirementResponse.Withdrawn
            retracted.revision shouldBe 3
            retracted.previousRevision shouldBe 2
            current(id).state() shouldBe retracted
            current(id).bodyString().shouldNotContain("terms")
            current(id).bodyString().shouldNotContain("satisfied")
            app.adminPost("/financial-documents/$id/invoice", """{"expectedVersion":1}""").status shouldBe Status.OK
            val again = set(id, version = 2, revision = 3, terms = percent("12.5000")).active()
            again.revision shouldBe 4
            again.approvalDocumentVersion shouldBe 2
            again.terms shouldBe DepositTermsRequest.Percentage("12.5000")
            again.requiredAmount shouldBe DepositMoneyResponse("25.00", "USD")
            val history = revisions(id)
            history.map {
                when (it) {
                    is HistoricalDepositRequirementResponse.Active -> it.revision
                    is HistoricalDepositRequirementResponse.Withdrawn -> it.revision
                }
            } shouldContainExactly
                listOf(1, 2, 3, 4)
            (history[0] as HistoricalDepositRequirementResponse.Active).createdAt shouldBe first.createdAt
            (history[2] as HistoricalDepositRequirementResponse.Withdrawn).createdAt shouldBe retracted.createdAt
            (history[0] as HistoricalDepositRequirementResponse.Active).requiredAmount shouldBe first.requiredAmount
            (history[3] as HistoricalDepositRequirementResponse.Active).terms shouldBe again.terms
            app.adminGet(path(id) + "/history").bodyString().shouldNotContain("satisfied")
            query(id).lineages().single().depositRequirement shouldBe again
        }

        test("current satisfaction changes at the exact threshold and after a refund unwind") {
            val id = newDocument()
            set(id).status shouldBe Status.OK

            fun pay(amount: String) =
                app.adminPost("/financial-documents/$id/payments", """{"documentVersion":1,"amount":"$amount","method":"CASH"}""")
            pay("99.99").status shouldBe Status.CREATED
            current(id).active().satisfied shouldBe false
            val receipt = CommerceJson.asA(pay("0.01").bodyString(), RecordedPaymentResponse.serializer())
            current(id).active().satisfied shouldBe true
            app
                .adminPost(
                    "/payments/${receipt.paymentId}/refunds",
                    """{"amount":"0.01","currency":"USD","method":"CASH","allocations":[{"paymentAllocationId":"${receipt.allocationId}","amount":"0.01"}]}""",
                ).status shouldBe
                Status.CREATED
            current(id).active().satisfied shouldBe false
            query(id)
                .lineages()
                .single()
                .reconciliation.netApplied shouldBe "99.99"
            query(id)
                .lineages()
                .single()
                .reconciliation.refundAllocations shouldBe "0.01"
        }

        test("frozen approval survives a later document version and withdrawal requires no document version") {
            val id = newDocument()
            val active = set(id, terms = percent()).active()
            app.transactor.inTransaction { transaction ->
                app.context.financialLedger.changeOrder(
                    transaction,
                    id,
                    ChangeOrder(
                        listOf(
                            ChangeOrder.Change.AddLineItem(
                                LineItem(UUID.randomUUID(), "More", null, null, money("100.00"), money("0.00")),
                            ),
                        ),
                    ),
                )
            }
            current(id).active() shouldBe active
            app.adminPost("/financial-documents/$id/invoice", """{"expectedVersion":2}""").status shouldBe Status.OK
            withdraw(id).status shouldBe Status.OK
            revisions(id).size shouldBe 2
        }

        test("exact document and requirement concurrency tokens reject stale and null-after-history commands without writes") {
            val id = newDocument()
            set(id).status shouldBe Status.OK
            val count = depositRows()
            listOf(set(id, version = 2, revision = 1), set(id), set(id, revision = 2), withdraw(id, 2)).forEach {
                it.status shouldBe Status.CONFLICT
                it.error().code shouldBe "conflict"
            }
            depositRows() shouldBe count
            withdraw(id).status shouldBe Status.OK
            set(id).status shouldBe Status.CONFLICT
            withdraw(id, 2).also {
                it.status shouldBe Status.CONFLICT
                it.error().code shouldBe "illegal_transition"
            }
            withdraw(newDocument()).status shouldBe Status.NOT_FOUND
        }

        listOf("ESTIMATE" to Status.UNPROCESSABLE_ENTITY, "QUOTE" to Status.OK, "INVOICE" to Status.OK).forEach { (stage, status) ->
            test("Fiona deposit stage policy for $stage") {
                val id = newDocument(stage)
                val before = depositRows()
                set(id).also {
                    it.status shouldBe status
                    if (stage == "ESTIMATE") it.error().code shouldBe "invariant_violated"
                }
                depositRows() shouldBe before + if (status == Status.OK) 1 else 0
            }
        }

        listOf(
            fixed("0"),
            fixed("-1"),
            fixed("201.00"),
            fixed("1.001"),
            fixed(currency = "EUR"),
            fixed(currency = "bad"),
            percent("0"),
            percent("-1"),
            percent("100.01"),
            percent("0.0000001"),
            percent("abc"),
            fixed("1e2"),
        ).forEachIndexed { index, terms ->
            test("invalid deposit terms $index leave no revisions") {
                val id = newDocument()
                val count = depositRows()
                set(id, terms = terms).also {
                    it.status shouldBe Status.UNPROCESSABLE_ENTITY
                    it.error().code shouldBe "validation_failed"
                }
                depositRows() shouldBe count
                current(id).state() shouldBe CurrentDepositRequirementResponse.None(id.toString())
            }
        }

        listOf(
            """{"type":"OTHER","amount":"10"}""",
            """{"type":"FIXED","percentage":"25"}""",
            """{"type":"PERCENTAGE","percentage":"25","amount":"10"}""",
            """{"type":"FIXED","amount":"10","currency":"USD","percentage":null}""",
            """{"type":"FIXED","amount":10,"currency":"USD"}""",
            "null",
        ).forEachIndexed { index, terms ->
            test("malformed discriminator or union shape $index is 400") {
                val id = newDocument()
                val count = depositRows()
                set(id, terms = terms).also {
                    it.status shouldBe Status.BAD_REQUEST
                    it.error().code shouldBe "malformed_request"
                }
                depositRows() shouldBe count
            }
        }

        test("unreadable UUIDs bodies and invalid revision values use the established error mappings") {
            app.adminGet("/financial-documents/bad/deposit-requirement").status shouldBe Status.BAD_REQUEST
            app.adminGet("/financial-documents/bad/deposit-requirement/history").status shouldBe Status.BAD_REQUEST
            app.adminRequest(Method.PUT, "/financial-documents/bad/deposit-requirement", setBody()).status shouldBe Status.BAD_REQUEST
            app
                .adminRequest(
                    Method.DELETE,
                    "/financial-documents/bad/deposit-requirement",
                    """{"expectedRequirementRevision":1}""",
                ).status shouldBe
                Status.BAD_REQUEST
            app.adminPost("/financial-documents/query", """{"documentIds":["bad"]}""").status shouldBe Status.BAD_REQUEST
            val id = newDocument()
            set(id, version = 0).status shouldBe Status.UNPROCESSABLE_ENTITY
            set(id, revision = 0).status shouldBe Status.UNPROCESSABLE_ENTITY
            withdraw(id, 0).also {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error().code shouldBe "validation_failed"
            }
            app.adminRequest(Method.PUT, path(id), "{}").status shouldBe Status.BAD_REQUEST
            app.adminRequest(Method.DELETE, path(id), "{}").status shouldBe Status.BAD_REQUEST
        }

        test("all new routes conceal unowned runtime facts exactly like missing lineages") {
            val foreign = newDocument(owned = false)
            val missing = UUID.randomUUID()
            val count = depositRows()
            listOf(foreign, missing).forEach { id ->
                listOf(current(id), app.adminGet(path(id) + "/history"), set(id), withdraw(id), query(id)).forEach {
                    it.status shouldBe Status.NOT_FOUND
                    it.error().code shouldBe "not_found"
                }
            }
            depositRows() shouldBe count
        }

        test("bulk query preserves order and all facts for NONE ACTIVE and WITHDRAWN; rejects the whole invalid collection") {
            val none = newDocument("ESTIMATE")
            val active = newDocument()
            val withdrawn = newDocument("INVOICE")
            set(active, terms = percent()).status shouldBe Status.OK
            set(withdrawn).status shouldBe Status.OK
            withdraw(withdrawn).status shouldBe Status.OK
            query().also { it.status shouldBe Status.OK }.lineages() shouldBe emptyList()
            query(active).lineages().size shouldBe 1
            val results = query(withdrawn, none, active).also { it.status shouldBe Status.OK }.lineages()
            results.map { it.documentId } shouldContainExactly listOf(withdrawn, none, active).map(UUID::toString)
            results.map { it.stage } shouldContainExactly listOf("INVOICE", "ESTIMATE", "QUOTE")
            results.map { it.version } shouldContainExactly listOf(1, 1, 1)
            results.map { it.total } shouldContainExactly listOf("200.02", "200.02", "200.02")
            results.map { it.currency }.distinct() shouldBe listOf("USD")
            (results[0].depositRequirement is CurrentDepositRequirementResponse.Withdrawn) shouldBe true
            (results[1].depositRequirement is CurrentDepositRequirementResponse.None) shouldBe true
            results[2].depositRequirement shouldBe current(active).state()
            results.forEach { result ->
                result.reconciliation shouldBe FinancialLineageReconciliationResponse("0.00", "0.00", "0.00", "200.02", "USD")
                app.transactor.inTransaction { tx ->
                    owners.inquiryOf(tx, UUID.fromString(result.documentId))?.value.toString() shouldBe result.inquiryId
                    val facts =
                        app.context.financialLedger
                            .financialLineages(tx, listOf(UUID.fromString(result.documentId)))
                            .single()
                    result.activity.latestDocumentVersionAt shouldBe facts.latestVersion.createdAt.toString()
                    result.activity.latestDepositRequirementAt shouldBe facts.depositRequirement?.createdAt?.toString()
                }
            }
            query(active, active).also {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error().code shouldBe "validation_failed"
            }
            query(none, UUID.randomUUID()).status shouldBe Status.NOT_FOUND
            query(none, newDocument(owned = false)).status shouldBe Status.NOT_FOUND
        }

        test("bulk activity maps allocation and refund-unwind times and excludes unrelated receipt and refund") {
            val id = newDocument()
            val unrelated = newDocument()
            val before = query(id, unrelated).lineages()
            val receipt =
                CommerceJson.asA(
                    app
                        .adminPost(
                            "/payments",
                            """{"amount":"20.00","currency":"USD","method":"CASH","receivedAt":"2099-01-01T00:00:00Z"}""",
                        ).bodyString(),
                    PaymentRecordResponse.serializer(),
                )
            query(id, unrelated).lineages() shouldBe before
            val allocation =
                CommerceJson.asA(
                    app
                        .adminPost(
                            "/payments/${receipt.paymentId}/allocations",
                            """{"documentId":"$id","documentVersion":1,"amount":"10.00"}""",
                        ).bodyString(),
                    PaymentAllocationResponse.serializer(),
                )
            val allocated = query(id).lineages().single()
            allocated.activity.latestPaymentAllocationAt shouldBe allocation.allocatedAt
            // Standalone refund returns only unapplied value and cannot affect either lineage.
            app
                .adminPost(
                    "/payments/${receipt.paymentId}/refunds",
                    """{"amount":"5.00","currency":"USD","method":"CASH","refundedAt":"2099-02-01T00:00:00Z"}""",
                ).status shouldBe
                Status.CREATED
            query(id).lineages().single() shouldBe allocated
            query(unrelated).lineages().single() shouldBe before[1]
            val refund =
                CommerceJson.asA(
                    app
                        .adminPost(
                            "/payments/${receipt.paymentId}/refunds",
                            """{"amount":"1.00","currency":"USD","method":"CASH","allocations":[{"paymentAllocationId":"${allocation.allocationId}","amount":"1.00"}]}""",
                        ).bodyString(),
                    RecordedRefundResponse.serializer(),
                )
            val result = query(id).lineages().single()
            result.activity.latestRefundAllocationAt shouldBe refund.allocations.single().allocatedAt
            result.reconciliation.netApplied shouldBe "9.00"
            app.transactor.inTransaction { tx ->
                result.activity.latestFinancialActivityAt shouldBe
                    app.context.financialLedger
                        .financialLineages(tx, listOf(id))
                        .single()
                        .activity.latestFinancialActivityAt
                        .toString()
            }
        }

        test("read and manage permissions are independent for USER sessions and SERVICE tokens with shared Origin policy") {
            val id = newDocument()
            val admin = checkNotNull(app.authorization.findUserByUsername("admin"))
            val original = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            val modes =
                listOf(
                    setOf(CommercePermissions.FinancialDocumentRead),
                    setOf(CommercePermissions.DepositRequirementManage),
                    setOf(CommercePermissions.FinancialDocumentCreate),
                    emptySet(),
                )
            try {
                modes.forEachIndexed { index, grants ->
                    app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
                    val service = app.provisionService("deposit-$index", grants, RoleKey("test.deposit-$index"))
                    val token = app.serviceToken(service)

                    fun request(
                        method: Method,
                        url: String,
                        body: String,
                        user: Boolean,
                    ): Response =
                        if (user) {
                            app.adminRequest(
                                method,
                                url,
                                body,
                            )
                        } else {
                            app.http(
                                Request(
                                    method,
                                    url,
                                ).withBearer(
                                    token,
                                ).header("Content-Type", "application/json")
                                    .header("Origin", "https://untrusted.test")
                                    .body(body),
                            )
                        }
                    listOf(true, false).forEach { user ->
                        listOf(
                            Method.GET to path(id),
                            Method.GET to (path(id) + "/history"),
                            Method.POST to "/financial-documents/query",
                        ).forEach { (method, url) ->
                            request(method, url, """{"documentIds":["$id"]}""", user).status shouldBe
                                if (CommercePermissions.FinancialDocumentRead in grants) Status.OK else Status.FORBIDDEN
                        }
                        val latest =
                            app.context.financialLedger
                                .latestDepositRequirement(id)
                                ?.requirement
                                ?.revision
                                ?.number
                        request(Method.PUT, path(id), setBody(revision = latest), user).status shouldBe
                            if (CommercePermissions.DepositRequirementManage in grants) Status.OK else Status.FORBIDDEN
                        val revision =
                            app.context.financialLedger
                                .latestDepositRequirement(id)
                                ?.requirement
                                ?.revision
                                ?.number ?: 1
                        request(Method.DELETE, path(id), """{"expectedRequirementRevision":$revision}""", user).status shouldBe
                            if (CommercePermissions.DepositRequirementManage in grants) Status.OK else Status.FORBIDDEN
                    }
                }
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
            }
            admin.id shouldBe app.authorization.findUserByUsername("admin")?.id
            listOf(
                Method.GET to path(id),
                Method.GET to (path(id) + "/history"),
                Method.PUT to path(id),
                Method.DELETE to path(id),
                Method.POST to "/financial-documents/query",
            ).forEach { (method, url) ->
                app.http(Request(method, url)).status shouldBe Status.UNAUTHORIZED
                if (method !=
                    Method.GET
                ) {
                    app
                        .http(
                            Request(method, url).header("Cookie", app.adminCookie).header("Content-Type", "application/json").body("{}"),
                        ).status shouldBe
                        Status.FORBIDDEN
                }
            }
            val catalog = Json.parseToJsonElement(app.adminGet("/admin/access/permissions").bodyString()).jsonObject
            catalog["permissions"]!!.jsonArray.any {
                it.jsonObject["key"]?.jsonPrimitive?.content == "commerce.deposit-requirement.manage"
            } shouldBe
                true
        }
    })
