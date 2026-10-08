package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.ValidationErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.changeOrderBody
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.linesJson
import io.github.castab.fionas.commerce.testing.proposalJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * Fiona's persisted financial documents and payments through the complete fionas-commerce
 * HTTP handler, over real PostgreSQL: staff-authored estimates, staff line edits, quotes,
 * invoices, and payments, all recorded by commerce-runtime's ledger, with Fiona's inquiry
 * association and line authorship beside them. Nothing is priced or checked against a catalog.
 */
class FinancialDocumentRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication

        fun Response.document() = CommerceJson.asA(bodyString(), FinancialDocumentResponse.serializer())

        fun Response.history() = CommerceJson.asA(bodyString(), FinancialDocumentHistoryResponse.serializer())

        fun Response.payment() = CommerceJson.asA(bodyString(), RecordedPaymentResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun linesBody(lines: List<TestLine> = acceptanceLines()) = """{"lines":${linesJson(lines)}}"""

        fun estimate(
            inquiryId: String,
            body: String = linesBody(),
        ) = application.adminPost("/inquiries/$inquiryId/estimates", body)

        fun newEstimate(body: String = linesBody()): FinancialDocumentResponse =
            estimate(application.createInquiry(), body).also { it.status shouldBe Status.CREATED }.document()

        fun changeOrder(
            id: String,
            body: String,
        ) = application.adminPost("/financial-documents/$id/change-orders", body)

        fun quote(
            id: String,
            expected: Int,
        ) = application.adminPost("/financial-documents/$id/quote", """{"expectedVersion":$expected}""")

        fun invoice(
            id: String,
            expected: Int,
        ) = application.adminPost("/financial-documents/$id/invoice", """{"expectedVersion":$expected}""")

        fun pay(
            id: String,
            version: Int,
            amount: String,
            method: String = "CARD",
            extra: String = "",
        ) = application.adminPost(
            "/financial-documents/$id/payments",
            """{"documentVersion":$version,"amount":"$amount","method":"$method"$extra}""",
        )

        fun get(path: String) = application.adminGet(path)

        /** A line as the customer sees it, without its id. */
        fun FinancialDocumentLine.charge() = listOf(description, subDescription, quantity, unitPrice, subtotal, taxAmount, total, currency)

        /** A change-order body keeping [document]'s lines by id with [edit] applied, plus [added] new lines. */
        fun edited(
            document: FinancialDocumentResponse,
            added: List<String> = emptyList(),
            edit: (FinancialDocumentLine) -> TestLine? = { it.test() },
        ) = """{"expectedVersion":${document.version},"lines":""" +
            proposalJson(*(document.lines.mapNotNull { line -> edit(line)?.existing(line.id) } + added).toTypedArray()) + "}"

        fun count(sql: String) =
            application.database
                .strings(sql)
                .single()
                .toInt()

        fun allocatedVersions(documentId: String) =
            application.database.strings(
                "SELECT document_version FROM commerce.payment_allocations WHERE document_id = '$documentId' ORDER BY document_version",
            )

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("an inquiry gains a staff estimate, line edits, a quote, a deposit, an invoice, and a final payment") {
            val inquiryId = application.createInquiry()

            // D/v1: the staff user's exact lines, recorded as committed, with the user as their author.
            val created = estimate(inquiryId)
            created.status shouldBe Status.CREATED
            val v1 = created.document()
            created.header("Location") shouldBe "/financial-documents/${v1.id}"
            v1.version shouldBe 1
            v1.previousVersion.shouldBeNull()
            v1.stage shouldBe "ESTIMATE"
            v1.inquiryId shouldBe inquiryId
            v1.lines.map { it.description } shouldContainExactly acceptanceLines().map { it.description }
            v1.total shouldBe "681.25"
            v1.subtotal shouldBe "681.25"
            v1.taxAmount shouldBe "0.00"
            v1.currency shouldBe "USD"
            v1.linesAuthoredBy.shouldNotBeNull().let {
                it.principalKind shouldBe "USER"
                it.principalId shouldBe application.adminId.value.toString()
            }
            v1.reconciliation shouldBe DocumentReconciliation("0.00", "0.00", "681.25", "USD")
            val id = v1.id
            application.database.strings(
                "SELECT inquiry_id FROM fionas.inquiry_financial_documents WHERE document_id = '$id'",
            ) shouldContainExactly listOf(inquiryId)

            // D/v2: the guest count changes; staff override each per-guest line in place, under its id.
            val hundred = acceptanceLines(guests = 100).associateBy { it.description.substringBefore(" (") }
            val v2 =
                changeOrder(id, edited(v1) { hundred.getValue(it.description.substringBefore(" (")) })
                    .also { it.status shouldBe Status.OK }
                    .document()
            v2.version shouldBe 2
            v2.previousVersion shouldBe 1
            v2.stage shouldBe "ESTIMATE"
            v2.total shouldBe "825.00"
            // Overrides keep every line's identity; the predecessor is untouched.
            v2.lines.map { it.id } shouldContainExactly v1.lines.map { it.id }
            get("/financial-documents/$id/history").history().versions.first() shouldBe v1.copy(reconciliation = null)

            // D/v3: the quote keeps the estimate's lines and their authorship; nothing is repriced.
            val v3 = quote(id, 2).also { it.status shouldBe Status.OK }.document()
            v3.version shouldBe 3
            v3.stage shouldBe "QUOTE"
            v3.lines shouldBe v2.lines
            v3.linesAuthoredBy shouldBe v2.linesAuthoredBy
            v3.reconciliation shouldBe DocumentReconciliation("0.00", "0.00", "825.00", "USD")

            // A $300 deposit against the quote.
            val depositResponse = pay(id, 3, "300.00", extra = ""","receivedAt":"2026-09-27T17:05:00Z"""")
            depositResponse.status shouldBe Status.CREATED
            val deposit = depositResponse.payment()
            deposit.documentId shouldBe id
            deposit.documentVersion shouldBe 3
            deposit.amount shouldBe "300.00"
            deposit.currency shouldBe "USD"
            deposit.method shouldBe "CARD"
            deposit.receivedAt shouldBe "2026-09-27T17:05:00Z"
            deposit.externalReference.shouldBeNull()
            deposit.reconciliation shouldBe DocumentReconciliation("300.00", "300.00", "525.00", "USD")
            application.database.strings(
                "SELECT document_id || ' ' || document_version FROM commerce.payment_allocations WHERE payment_id = '${deposit.paymentId}'",
            ) shouldContainExactly listOf("$id 3")

            // D/v4: the invoice; the deposit stays attached to D/v3 and still counts.
            val v4 = invoice(id, 3).also { it.status shouldBe Status.OK }.document()
            v4.version shouldBe 4
            v4.stage shouldBe "INVOICE"
            v4.lines shouldBe v3.lines
            v4.linesAuthoredBy shouldBe v3.linesAuthoredBy
            v4.reconciliation shouldBe DocumentReconciliation("300.00", "300.00", "525.00", "USD")
            allocatedVersions(id) shouldContainExactly listOf("3")

            // D/v5: service time is added to the invoice; the balance follows the new total.
            val longer = acceptanceLines(guests = 100, minutes = 150).first()
            val v5 =
                changeOrder(id, edited(v4) { if (it.description == "Base service") longer else it.test() })
                    .also { it.status shouldBe Status.OK }
                    .document()
            v5.version shouldBe 5
            v5.stage shouldBe "INVOICE"
            v5.total shouldBe "850.00"
            v5.lines.first().charge() shouldBe
                listOf("Base service", "2.5 hours · setup, staff & local travel", null, "275.00", "275.00", "0.00", "275.00", "USD")
            v5.lines.first().id shouldBe v4.lines.first().id
            v5.reconciliation shouldBe DocumentReconciliation("300.00", "300.00", "550.00", "USD")
            allocatedVersions(id) shouldContainExactly listOf("3")

            // The final payment, received now by the application's clock.
            val settled = pay(id, 5, "550.00", method = "CASH").also { it.status shouldBe Status.CREATED }.payment()
            settled.documentVersion shouldBe 5
            settled.method shouldBe "CASH"
            settled.receivedAt shouldBe "2026-09-26T18:30:00.123456Z"
            settled.allocatedAt shouldBe "2026-09-26T18:30:00.123456Z"
            settled.reconciliation shouldBe DocumentReconciliation("850.00", "850.00", "0.00", "USD")
            allocatedVersions(id) shouldContainExactly listOf("3", "5")

            // GET D: the latest immutable invoice with its settlement.
            val latest = get("/financial-documents/$id").also { it.status shouldBe Status.OK }.document()
            latest shouldBe v5.copy(reconciliation = DocumentReconciliation("850.00", "850.00", "0.00", "USD"))

            // The full history, each version with its authorship and no settlement.
            val history = get("/financial-documents/$id/history").also { it.status shouldBe Status.OK }.history()
            history.id shouldBe id
            history.inquiryId shouldBe inquiryId
            history.versions.map { it.version } shouldContainExactly listOf(1, 2, 3, 4, 5)
            history.versions.map { it.stage } shouldContainExactly listOf("ESTIMATE", "ESTIMATE", "QUOTE", "INVOICE", "INVOICE")
            history.versions.map { it.total } shouldContainExactly listOf("681.25", "825.00", "825.00", "825.00", "850.00")
            history.versions.map { it.linesAuthoredBy?.principalKind }.toSet() shouldBe setOf("USER")
            history.versions.forEach { it.reconciliation.shouldBeNull() }
            // Every mutation response carries the runtime's metadata for that exact persisted version.
            val written = listOf(v1, v2, v3, v4, v5)
            written.forEach { response ->
                val reference = FinancialDocumentReference(UUID.fromString(response.id), Version.of(response.version))
                val persisted = application.context.financialLedger.version(reference)
                response.createdAt shouldBe persisted.createdAt.toString()
                java.time.Instant.parse(response.createdAt) shouldBe persisted.createdAt
                // The fixed Fiona clock writes the association; it cannot supply runtime metadata.
                response.createdAt shouldNotBe "2026-09-26T18:30:00.123456Z"
            }
            history.versions.map { it.createdAt } shouldBe written.map { it.createdAt }
            get("/financial-documents/$id/history").history().versions.map { it.createdAt } shouldBe written.map { it.createdAt }
            get("/financial-documents/$id").document().createdAt shouldBe v5.createdAt
            // D/v1 is exactly as it was created.
            history.versions.first() shouldBe v1.copy(reconciliation = null)

            // The inquiry's documents.
            val listed =
                CommerceJson.asA(
                    get("/inquiries/$inquiryId/financial-documents").also { it.status shouldBe Status.OK }.bodyString(),
                    InquiryFinancialDocumentsResponse.serializer(),
                )
            listed.inquiryId shouldBe inquiryId
            // Beside the inquiry's own initial Estimate, created with it at the same fixed instant.
            listed.documents.map { it.id } shouldContainExactlyInAnyOrder listOf(application.initialEstimateOf(inquiryId), latest.id)
            listed.documents.single { it.id == latest.id } shouldBe latest
        }

        test("an inquiry may own several lineages") {
            val inquiryId = application.createInquiry()
            val first = estimate(inquiryId).document()
            val second = estimate(inquiryId, linesBody(listOf(CHURROS))).document()

            first.id shouldNotBe second.id
            val listed =
                CommerceJson.asA(
                    get("/inquiries/$inquiryId/financial-documents").bodyString(),
                    InquiryFinancialDocumentsResponse.serializer(),
                )
            // Both were created at the fixed test instant, so their order is not asserted.
            listed.documents.map { it.id } shouldContainExactlyInAnyOrder
                listOf(application.initialEstimateOf(inquiryId), first.id, second.id)
            // A new inquiry owns exactly its initial Estimate.
            val fresh = application.createInquiry()
            CommerceJson
                .asA(
                    get("/inquiries/$fresh/financial-documents").bodyString(),
                    InquiryFinancialDocumentsResponse.serializer(),
                ).documents
                .map { it.id } shouldContainExactly listOf(application.initialEstimateOf(fresh))
        }

        test("a caller-supplied total is never authoritative: the total is always derived from the committed lines") {
            val created = newEstimate(linesBody(listOf(CHURROS, COURTESY_DISCOUNT)).dropLast(1) + ""","total":"1.00","subtotal":"1.00"}""")
            created.total shouldBe "400.00"
            created.lines.map { it.unitPrice } shouldContainExactly listOf("450.00", "-50.00")
        }

        test("staff edits carry, override, remove, add and reorder lines, keeping every existing line's identity") {
            val created = newEstimate(linesBody(listOf(CHURROS, COURTESY_DISCOUNT, TestLine("Napkins", unitPrice = "5.00"))))
            val (churros, discount, napkins) = created.lines
            val revised =
                changeOrder(
                    created.id,
                    """{"expectedVersion":1,"lines":${proposalJson(
                        TestLine("Travel", "Outside the local area", null, "75.00").new("travel"),
                        COURTESY_DISCOUNT.copy(unitPrice = "-25.00").existing(discount.id),
                        CHURROS.existing(churros.id),
                    )}}""",
                ).also { it.status shouldBe Status.OK }.document()
            revised.lines.map { it.description } shouldContainExactly listOf("Travel", "Courtesy discount", "Churro catering service")
            revised.lines[1].id shouldBe discount.id
            revised.lines[2].id shouldBe churros.id
            revised.lines.map { it.id }.contains(napkins.id) shouldBe false
            revised.total shouldBe "500.00"
            // The predecessor is immutable.
            get("/financial-documents/${created.id}/history")
                .history()
                .versions
                .first()
                .lines shouldBe created.lines
        }

        test("change orders commit staff lines in the current stage: estimate, quote, and invoice") {
            val estimate = newEstimate()
            val v2 = changeOrder(estimate.id, changeOrderBody(1, acceptanceLines(guests = 80))).document()
            v2.stage shouldBe "ESTIMATE"
            quote(estimate.id, 2).status shouldBe Status.OK
            val v4 = changeOrder(estimate.id, changeOrderBody(3, acceptanceLines(guests = 80, waffleCones = false))).document()
            v4.stage shouldBe "QUOTE"
            invoice(estimate.id, 4).status shouldBe Status.OK
            changeOrder(estimate.id, changeOrderBody(5, acceptanceLines(guests = 90, waffleCones = false))).document().let {
                it.stage shouldBe "INVOICE"
                it.version shouldBe 6
                it.lines.single { line -> line.description == "Ice cream service" }.quantity shouldBe "90"
            }
        }

        test("invalid staff line proposals are rejected with stable codes and append nothing") {
            val estimate = newEstimate()

            fun rejected(
                body: String,
                status: Status = Status.UNPROCESSABLE_ENTITY,
            ) = changeOrder(estimate.id, body).also { it.status shouldBe status }

            fun Response.codes() =
                CommerceJson
                    .asA(bodyString(), ValidationErrorResponse.serializer())
                    .violations
                    .orEmpty()
                    .map { it.code }

            rejected(edited(estimate)).codes() shouldBe listOf("NO_FINANCIAL_CHANGE")
            rejected(edited(estimate, listOf(TestLine("Credit", unitPrice = "-1000.00").new("credit")))).codes() shouldBe
                listOf("NEGATIVE_DOCUMENT_TOTAL")
            rejected("""{"expectedVersion":1,"lines":${proposalJson(CHURROS.existing(UUID.randomUUID().toString()))}}""").codes() shouldBe
                listOf("LINE_NOT_IN_REVIEWED_DOCUMENT")
            rejected("""{"expectedVersion":1,"lines":${proposalJson(CHURROS.copy(currency = "EUR").new("eur"))}}""").codes() shouldBe
                listOf("CURRENCY_MISMATCH")
            rejected("""{"expectedVersion":1,"lines":[]}""")
            rejected("""{"expectedVersion":1,"lines":${proposalJson(CHURROS.new("a"), CHURROS.new("a"))}}""")
            rejected("""{"expectedVersion":1,"lines":${proposalJson(CHURROS.new("bad key"))}}""")
            rejected("""{"expectedVersion":1,"lines":${proposalJson(CHURROS.copy(unitPrice = "4.505").new("a"))}}""")
            // A line naming both or neither identity, or an unreadable id, is malformed.
            rejected(
                """{"expectedVersion":1,"lines":[{"lineItemId":"${estimate.lines.first().id}","key":"a",${CHURROS.json().drop(1)}]}""",
                Status.BAD_REQUEST,
            )
            rejected("""{"expectedVersion":1,"lines":[${CHURROS.json()}]}""", Status.BAD_REQUEST)
            rejected("""{"expectedVersion":1,"lines":[${CHURROS.existing("not-a-uuid")}]}""", Status.BAD_REQUEST)
            get("/financial-documents/${estimate.id}/history").history().versions shouldHaveSize 1
        }

        test("an action on a version that is no longer the latest is a conflict, and appends nothing") {
            val estimate = newEstimate()
            changeOrder(estimate.id, changeOrderBody(1, acceptanceLines(guests = 80))).status shouldBe Status.OK

            listOf(
                quote(estimate.id, 1),
                changeOrder(estimate.id, changeOrderBody(1, acceptanceLines(guests = 90))),
                changeOrder(estimate.id, changeOrderBody(3, acceptanceLines(guests = 90))),
            ).forEach {
                it.status shouldBe Status.CONFLICT
                it.error().code shouldBe "conflict"
                it.error().message shouldContain "is at v2"
            }
            get("/financial-documents/${estimate.id}/history").history().versions shouldHaveSize 2
            quote(estimate.id, 0).status shouldBe Status.UNPROCESSABLE_ENTITY
        }

        test("a transition the latest stage does not have is commerce-runtime's illegal transition") {
            val estimate = newEstimate()
            invoice(estimate.id, 1).let {
                it.status shouldBe Status.CONFLICT
                it.error() shouldBe ErrorResponse("illegal_transition", "Financial document ${estimate.id} is not a quote")
            }
            quote(estimate.id, 1).status shouldBe Status.OK
            quote(estimate.id, 2).let {
                it.status shouldBe Status.CONFLICT
                it.error() shouldBe ErrorResponse("illegal_transition", "Financial document ${estimate.id} is not an estimate")
            }
            invoice(estimate.id, 2).status shouldBe Status.OK
            quote(estimate.id, 3).error().code shouldBe "illegal_transition"
            invoice(estimate.id, 3).error().code shouldBe "illegal_transition"
            get("/financial-documents/${estimate.id}/history").history().versions.map { it.stage } shouldContainExactly
                listOf("ESTIMATE", "QUOTE", "INVOICE")
        }

        test("a payment is accepted only against the latest version, a quote or an invoice") {
            val estimate = newEstimate()
            val payments = application.database.count("commerce.payment_records")

            pay(estimate.id, 1, "100.00").let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error().code shouldBe "invariant_violated"
            }
            quote(estimate.id, 1).status shouldBe Status.OK
            pay(estimate.id, 1, "100.00").let {
                it.status shouldBe Status.CONFLICT
                it.error().code shouldBe "conflict"
            }
            application.database.count("commerce.payment_records") shouldBe payments

            // Over-application is a derived fact, not a rejection.
            pay(estimate.id, 2, "700.00").payment().reconciliation shouldBe
                DocumentReconciliation("700.00", "700.00", "-18.75", "USD")
        }

        test("payment values are validated: exact decimal amounts, known methods, RFC 3339 times, complete references") {
            val quoted = newEstimate().also { quote(it.id, 1).status shouldBe Status.OK }
            val payments = application.database.count("commerce.payment_records")

            listOf(
                pay(quoted.id, 2, "0.00"),
                pay(quoted.id, 2, "-5.00"),
                pay(quoted.id, 2, "12.345"),
                pay(quoted.id, 2, "1e3"),
                pay(quoted.id, 2, "twelve"),
                pay(quoted.id, 2, "10.00", method = "BITCOIN"),
                pay(quoted.id, 2, "10.00", extra = ""","receivedAt":"yesterday""""),
                pay(quoted.id, 2, "10.00", extra = ""","externalReference":{"provider":" ","reference":"x"}"""),
            ).forEach {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error().code shouldBe "validation_failed"
            }
            // A reference needs both of its values.
            pay(quoted.id, 2, "10.00", extra = ""","externalReference":{"provider":"square"}""").status shouldBe Status.BAD_REQUEST
            application.database.count("commerce.payment_records") shouldBe payments

            // An offset timestamp is accepted and recorded as the instant it names.
            pay(quoted.id, 2, "10.5", extra = ""","receivedAt":"2026-09-27T10:05:00-07:00"""").payment().let {
                it.receivedAt shouldBe "2026-09-27T17:05:00Z"
                it.amount shouldBe "10.50"
            }
        }

        test("an external payment reference is recorded once; a duplicate is commerce-runtime's conflict") {
            val first = newEstimate().also { quote(it.id, 1).status shouldBe Status.OK }
            val second = newEstimate().also { quote(it.id, 1).status shouldBe Status.OK }
            val reference = ""","externalReference":{"provider":"square","reference":"pay-${UUID.randomUUID()}"}"""

            pay(first.id, 2, "100.00", extra = reference).payment().externalReference.shouldNotBeNull()
            val payments = application.database.count("commerce.payment_records")
            val allocations = application.database.count("commerce.payment_allocations")
            listOf(pay(first.id, 2, "100.00", extra = reference), pay(second.id, 2, "100.00", extra = reference)).forEach {
                it.status shouldBe Status.CONFLICT
                it.error().code shouldBe "conflict"
            }
            application.database.count("commerce.payment_records") shouldBe payments
            application.database.count("commerce.payment_allocations") shouldBe allocations
            get("/financial-documents/${second.id}").document().reconciliation?.netApplied shouldBe "0.00"
        }

        test("a document no Fiona inquiry owns is not found, even when commerce-runtime's ledger has it") {
            val foreign =
                application.context.financialLedger.create(
                    FinancialDocument.Estimate.create(
                        UUID.randomUUID(),
                        listOf(LineItem(UUID.randomUUID(), "Not Fiona's", null, null, usd("10.00"), usd("0.00"))),
                    ),
                )
            listOf(foreign.id.toString(), UUID.randomUUID().toString()).forEach { id ->
                listOf(
                    get("/financial-documents/$id"),
                    get("/financial-documents/$id/history"),
                    get("/financial-documents/$id/payments"),
                    quote(id, 1),
                    invoice(id, 1),
                    changeOrder(id, changeOrderBody(1, acceptanceLines(guests = 80))),
                    pay(id, 1, "10.00"),
                ).forEach {
                    it.status shouldBe Status.NOT_FOUND
                    it.error() shouldBe ErrorResponse("not_found", "Financial document $id was not found")
                }
            }
            application.context.financialLedger
                .latest(foreign.id)
                .version.number shouldBe 1
            count("SELECT count(*) FROM commerce.payment_allocations WHERE document_id = '${foreign.id}'") shouldBe 0
        }

        test("unknown inquiries are not found, and identifiers that are not UUIDs are malformed") {
            val unknown = UUID.randomUUID()
            estimate(unknown.toString()).let {
                it.status shouldBe Status.NOT_FOUND
                it.error() shouldBe ErrorResponse("not_found", "Inquiry $unknown was not found")
            }
            get("/inquiries/$unknown/financial-documents").status shouldBe Status.NOT_FOUND
            listOf(
                estimate("not-a-uuid"),
                get("/inquiries/not-a-uuid/financial-documents"),
                get("/financial-documents/not-a-uuid"),
                get("/financial-documents/not-a-uuid/history"),
                get("/financial-documents/not-a-uuid/payments"),
                quote("not-a-uuid", 1),
                pay("not-a-uuid", 1, "1.00"),
            ).forEach {
                it.status shouldBe Status.BAD_REQUEST
                it.error().code shouldBe "malformed_request"
            }
            estimate(application.createInquiry(), "{}").status shouldBe Status.BAD_REQUEST
            estimate(application.createInquiry(), linesBody(listOf(COURTESY_DISCOUNT))).let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error().message shouldContain "must not be negative"
            }
        }

        test("every financial route is staff-only, with commerce-runtime's permissions checked live") {
            val estimate = newEstimate()
            val id = estimate.id
            val inquiryId = estimate.inquiryId
            val routes =
                listOf(
                    Method.POST to "/inquiries/$inquiryId/estimates",
                    Method.POST to "/inquiries/$inquiryId/financial-documents",
                    Method.GET to "/inquiries/$inquiryId/financial-documents",
                    Method.GET to "/financial-documents/$id",
                    Method.GET to "/financial-documents/$id/history",
                    Method.POST to "/financial-documents/$id/quote",
                    Method.POST to "/financial-documents/$id/invoice",
                    Method.POST to "/financial-documents/$id/change-orders",
                    Method.POST to "/financial-documents/$id/payments",
                    Method.GET to "/financial-documents/$id/payments",
                    Method.POST to "/payments",
                    Method.POST to "/payments/${UUID.randomUUID()}/allocations",
                )
            routes.forEach { (method, path) ->
                application
                    .http(Request(method, path).header("Origin", TEST_ORIGIN).header("Content-Type", "application/json").body("{}"))
                    .status shouldBe Status.UNAUTHORIZED
            }
            // Unsafe cookie-authenticated requests need a trusted browser origin.
            application
                .http(Request(Method.POST, "/financial-documents/$id/quote").header("Cookie", application.adminCookie).body("{}"))
                .status shouldBe Status.FORBIDDEN

            val admin = checkNotNull(application.authorization.findUserByUsername("admin"))
            val reader = RoleKey("fionas.test.financial-reader")
            application.authorization.createRole(
                RoleDefinition(reader, "Financial reader", null, setOf(CommercePermissions.FinancialDocumentRead)),
            )
            application.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
            application.authorization.assignRole(admin.id, reader)
            try {
                routes.forEach { (method, path) ->
                    val status = application.adminRequest(method, path, "{}").status
                    if (method == Method.GET) status shouldBe Status.OK else status shouldBe Status.FORBIDDEN
                }
            } finally {
                application.authorization.unassignRole(admin.id, reader)
                application.authorization.assignRole(admin.id, CommerceRoles.Administrator)
            }
            // Creating documents is not authority over negotiated amounts: lines need fionas.financial-terms.manage too.
            val creator = RoleKey("fionas.test.financial-creator")
            application.authorization.createRole(
                RoleDefinition(
                    creator,
                    "Financial creator",
                    null,
                    setOf(CommercePermissions.FinancialDocumentCreate, CommercePermissions.FinancialDocumentRead),
                ),
            )
            application.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
            application.authorization.assignRole(admin.id, creator)
            try {
                estimate(inquiryId).status shouldBe Status.FORBIDDEN
                changeOrder(id, changeOrderBody(1, listOf(CHURROS))).status shouldBe Status.FORBIDDEN
                application
                    .adminPost(
                        "/inquiries/$inquiryId/financial-documents",
                        """{"stage":"QUOTE","lines":${linesJson(listOf(CHURROS))}}""",
                    ).status shouldBe Status.FORBIDDEN
                // Transitions commit no new amounts and keep their ordinary permission.
                quote(id, 1).status shouldBe Status.OK
            } finally {
                application.authorization.unassignRole(admin.id, creator)
                application.authorization.assignRole(admin.id, CommerceRoles.Administrator)
            }
        }

        test("only the declared methods are allowed") {
            val id = newEstimate().id
            listOf(Method.PUT, Method.DELETE, Method.OPTIONS).forEach { method ->
                application.adminRequest(method, "/financial-documents/$id").status shouldBe Status.METHOD_NOT_ALLOWED
                application.adminRequest(method, "/financial-documents/$id/payments").status shouldBe Status.METHOD_NOT_ALLOWED
            }
            application.adminGet("/financial-documents/$id/quote").status shouldBe Status.METHOD_NOT_ALLOWED
        }
    })

private val USD = Currency.getInstance("USD")

/** This response line as a test line, with its exact committed values. */
private fun FinancialDocumentLine.test() = TestLine(description, subDescription, quantity, unitPrice, taxAmount, currency)

private fun usd(amount: String) = Money(BigDecimal(amount), USD)
