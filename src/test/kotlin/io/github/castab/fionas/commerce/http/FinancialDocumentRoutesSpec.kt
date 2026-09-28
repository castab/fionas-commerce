package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.perGuest
import io.github.castab.fionas.commerce.testing.pricingBody
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
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
 * HTTP handler, over real PostgreSQL: estimates priced by the server from an exact catalog
 * revision, change orders, quotes, invoices, and payments, all recorded by commerce-runtime's
 * ledger, with Fiona's inquiry association and pricing source beside them.
 */
class FinancialDocumentRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication

        // The catalog revision the acceptance estimate is priced from.
        var revision = 0

        fun Response.document() = CommerceJson.asA(bodyString(), FinancialDocumentResponse.serializer())

        fun Response.history() = CommerceJson.asA(bodyString(), FinancialDocumentHistoryResponse.serializer())

        fun Response.payment() = CommerceJson.asA(bodyString(), RecordedPaymentResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun estimate(
            inquiryId: String,
            body: String = pricingBody(revision),
        ) = application.adminPost("/inquiries/$inquiryId/estimates", body)

        fun newEstimate(body: String = pricingBody(revision)): FinancialDocumentResponse =
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

        fun EstimatePreviewLine.charge() = listOf(description, subDescription, quantity, unitPrice, subtotal, taxAmount, total, currency)

        fun count(sql: String) =
            application.database
                .strings(sql)
                .single()
                .toInt()

        fun allocatedVersions(documentId: String) =
            application.database.strings(
                "SELECT document_version FROM commerce.payment_allocations WHERE document_id = '$documentId' ORDER BY document_version",
            )

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        test("an inquiry becomes a persisted estimate, a quote, a deposit, an invoice, change orders, and a final payment") {
            val inquiryId = application.createInquiry()

            // The public preview prices the same inputs and records nothing.
            val snapshotsBefore = application.database.count("commerce.financial_document_snapshots")
            val preview =
                application.http(
                    Request(Method.POST, "/estimate-preview").header("Content-Type", "application/json").body(pricingBody(revision)),
                )
            preview.status shouldBe Status.OK
            val previewed = CommerceJson.asA(preview.bodyString(), EstimatePreviewResponse.serializer())
            previewed.total shouldBe "681.25"
            application.database.count("commerce.financial_document_snapshots") shouldBe snapshotsBefore

            // D/v1: the server prices the inputs exactly as the preview did, and persists them.
            val created = estimate(inquiryId)
            created.status shouldBe Status.CREATED
            val v1 = created.document()
            created.header("Location") shouldBe "/financial-documents/${v1.id}"
            v1.version shouldBe 1
            v1.previousVersion.shouldBeNull()
            v1.stage shouldBe "ESTIMATE"
            v1.inquiryId shouldBe inquiryId
            v1.lines.map { it.charge() } shouldBe previewed.lines.map { it.charge() }
            v1.total shouldBe "681.25"
            v1.subtotal shouldBe "681.25"
            v1.taxAmount shouldBe "0.00"
            v1.currency shouldBe "USD"
            v1.pricing shouldBe
                DocumentPricing(
                    revision,
                    75,
                    false,
                    120,
                    listOf(
                        PricingSelection("soft-serve-flavor", listOf("vanilla", "horchata")),
                        PricingSelection("topping", TOPPINGS),
                        PricingSelection("cone-option", listOf("waffle-cone")),
                    ),
                )
            v1.reconciliation shouldBe DocumentReconciliation("0.00", "0.00", "681.25", "USD")
            val id = v1.id
            application.database.strings(
                "SELECT inquiry_id FROM fionas.inquiry_financial_documents WHERE document_id = '$id'",
            ) shouldContainExactly listOf(inquiryId)
            application.database.strings(
                "SELECT document_version || ' ' || catalog_revision || ' ' || guest_count FROM fionas.financial_document_pricing " +
                    "WHERE document_id = '$id'",
            ) shouldContainExactly listOf("1 $revision 75")

            // D/v2: the guest count changes, and the server reprices it.
            val v2 =
                changeOrder(id, pricingBody(revision, guests = 100, expectedVersion = 1))
                    .also { it.status shouldBe Status.OK }
                    .document()
            v2.version shouldBe 2
            v2.previousVersion shouldBe 1
            v2.stage shouldBe "ESTIMATE"
            v2.total shouldBe "825.00"
            v2.pricing.guestCount shouldBe 100
            // Repricing replaces the line set: no line of v2 pretends to be a line of v1.
            (v2.lines.map { it.id } intersect v1.lines.map { it.id }.toSet()).shouldBeEmpty()

            // D/v3: the quote keeps the estimate's lines and pricing source; nothing is repriced.
            val v3 = quote(id, 2).also { it.status shouldBe Status.OK }.document()
            v3.version shouldBe 3
            v3.stage shouldBe "QUOTE"
            v3.lines shouldBe v2.lines
            v3.pricing shouldBe v2.pricing
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
            v4.pricing shouldBe v3.pricing
            v4.reconciliation shouldBe DocumentReconciliation("300.00", "300.00", "525.00", "USD")
            allocatedVersions(id) shouldContainExactly listOf("3")

            // D/v5: service time is added to the invoice; the balance follows the new total.
            val v5 =
                changeOrder(id, pricingBody(revision, guests = 100, minutes = 150, expectedVersion = 4))
                    .also { it.status shouldBe Status.OK }
                    .document()
            v5.version shouldBe 5
            v5.stage shouldBe "INVOICE"
            v5.total shouldBe "850.00"
            v5.lines.first().charge() shouldBe
                listOf("Base service", "2.5 hours · setup, staff & local travel", null, "275.00", "275.00", "0.00", "275.00", "USD")
            v5.pricing.durationMinutes shouldBe 150
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

            // The full history, each version with its own pricing source and no settlement.
            val history = get("/financial-documents/$id/history").also { it.status shouldBe Status.OK }.history()
            history.id shouldBe id
            history.inquiryId shouldBe inquiryId
            history.versions.map { it.version } shouldContainExactly listOf(1, 2, 3, 4, 5)
            history.versions.map { it.stage } shouldContainExactly listOf("ESTIMATE", "ESTIMATE", "QUOTE", "INVOICE", "INVOICE")
            history.versions.map { it.total } shouldContainExactly listOf("681.25", "825.00", "825.00", "825.00", "850.00")
            history.versions.map { it.pricing.guestCount } shouldContainExactly listOf(75, 100, 100, 100, 100)
            history.versions.map { it.pricing.durationMinutes } shouldContainExactly listOf(120, 120, 120, 120, 150)
            history.versions.map { it.pricing.catalogRevision }.toSet() shouldBe setOf(revision)
            history.versions.forEach { it.reconciliation.shouldBeNull() }
            // D/v1 is exactly as it was created.
            history.versions.first() shouldBe v1.copy(reconciliation = null)

            // The inquiry's documents.
            val listed =
                CommerceJson.asA(
                    get("/inquiries/$inquiryId/financial-documents").also { it.status shouldBe Status.OK }.bodyString(),
                    InquiryFinancialDocumentsResponse.serializer(),
                )
            listed.inquiryId shouldBe inquiryId
            listed.documents shouldContainExactly listOf(latest)
        }

        test("an inquiry may own several lineages") {
            val inquiryId = application.createInquiry()
            val first = estimate(inquiryId).document()
            val second = estimate(inquiryId, pricingBody(revision, guests = 50)).document()

            first.id shouldNotBe second.id
            val listed =
                CommerceJson.asA(
                    get("/inquiries/$inquiryId/financial-documents").bodyString(),
                    InquiryFinancialDocumentsResponse.serializer(),
                )
            // Both were created at the fixed test instant, so their order is not asserted.
            listed.documents.map { it.id } shouldContainExactlyInAnyOrder listOf(first.id, second.id)
            CommerceJson
                .asA(
                    get("/inquiries/${application.createInquiry()}/financial-documents").bodyString(),
                    InquiryFinancialDocumentsResponse.serializer(),
                ).documents
                .shouldBeEmpty()
        }

        test("the caller cannot supply lines, amounts, or totals: the server prices every persisted document") {
            val forged = ""","lines":[{"description":"Everything","unitPrice":"1.00"}],"subtotal":"1.00","total":"1.00""""
            val created = newEstimate(pricingBody(revision, extra = forged))
            created.total shouldBe "681.25"
            created.lines shouldHaveSize 5

            val changed = changeOrder(created.id, pricingBody(revision, guests = 100, expectedVersion = 1, extra = forged)).document()
            changed.total shouldBe "825.00"
        }

        test("change orders reprice in the current stage: estimate, quote, and invoice") {
            val estimate = newEstimate()
            changeOrder(estimate.id, pricingBody(revision, guests = 80, expectedVersion = 1)).document().stage shouldBe "ESTIMATE"
            quote(estimate.id, 2).document().stage shouldBe "QUOTE"
            val changedQuote =
                changeOrder(estimate.id, pricingBody(revision, guests = 80, cones = listOf("cup"), expectedVersion = 3)).document()
            changedQuote.stage shouldBe "QUOTE"
            changedQuote.version shouldBe 4
            changedQuote.lines.map { it.description } shouldContainExactly
                listOf("Base service", "Ice cream service", "Horchata", "Extra toppings (2)")
            invoice(estimate.id, 4).document().stage shouldBe "INVOICE"
            changeOrder(estimate.id, pricingBody(revision, guests = 90, cones = listOf("cup"), expectedVersion = 5)).document().let {
                it.stage shouldBe "INVOICE"
                it.version shouldBe 6
                it.pricing.guestCount shouldBe 90
            }
        }

        test("inputs that price exactly as the current version does are no financial change") {
            val estimate = newEstimate()

            changeOrder(estimate.id, pricingBody(revision, expectedVersion = 1)).let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error() shouldBe ErrorResponse("validation_failed", "The revised pricing produces no financial change")
            }
            get("/financial-documents/${estimate.id}/history").history().versions shouldHaveSize 1
        }

        test("a change order prices exactly the catalog revision it names, old or new, never a silent upgrade") {
            val estimate = newEstimate()
            val mango = application.addOffering("mango", "soft-serve-flavor", "Mango", perGuest("1.00"))

            // The old revision stays usable deliberately, and stays recorded as the source.
            val kept = changeOrder(estimate.id, pricingBody(revision, guests = 76, expectedVersion = 1)).document()
            kept.pricing.catalogRevision shouldBe revision
            kept.total shouldBe "687.00"
            // The old revision has no mango: it is rejected, not repriced from the later revision.
            changeOrder(estimate.id, pricingBody(revision, softServe = listOf("mango"), expectedVersion = 2)).let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error().message shouldContain "UNKNOWN_OFFERING"
            }
            // The newer revision is used only when named.
            val adopted = changeOrder(estimate.id, pricingBody(mango, softServe = listOf("mango"), expectedVersion = 2)).document()
            adopted.pricing.catalogRevision shouldBe mango
            adopted.lines.map { it.description } shouldContain "Mango"
            // A persisted estimate from the old revision is still priced from it.
            newEstimate(pricingBody(revision)).pricing.catalogRevision shouldBe revision
            // A revision that does not exist is not found.
            changeOrder(estimate.id, pricingBody(mango + 1, expectedVersion = 3)).let {
                it.status shouldBe Status.NOT_FOUND
                it.error() shouldBe ErrorResponse("not_found", "Offerings catalog revision r${mango + 1} was not found")
            }
        }

        test("an action on a version that is no longer the latest is a conflict, and appends nothing") {
            val estimate = newEstimate()
            changeOrder(estimate.id, pricingBody(revision, guests = 80, expectedVersion = 1)).status shouldBe Status.OK

            listOf(
                quote(estimate.id, 1),
                changeOrder(estimate.id, pricingBody(revision, guests = 90, expectedVersion = 1)),
                changeOrder(estimate.id, pricingBody(revision, guests = 90, expectedVersion = 3)),
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
                    quote(id, 1),
                    invoice(id, 1),
                    changeOrder(id, pricingBody(revision, guests = 80, expectedVersion = 1)),
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
                quote("not-a-uuid", 1),
                pay("not-a-uuid", 1, "1.00"),
            ).forEach {
                it.status shouldBe Status.BAD_REQUEST
                it.error().code shouldBe "malformed_request"
            }
            estimate(application.createInquiry(), "{}").status shouldBe Status.BAD_REQUEST
            estimate(application.createInquiry(), pricingBody(revision, guests = 0)).let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error().message shouldContain "INVALID_GUEST_COUNT"
            }
        }

        test("every financial route is staff-only, with commerce-runtime's permissions checked live") {
            val estimate = newEstimate()
            val id = estimate.id
            val inquiryId = estimate.inquiryId
            val routes =
                listOf(
                    Method.POST to "/inquiries/$inquiryId/estimates",
                    Method.GET to "/inquiries/$inquiryId/financial-documents",
                    Method.GET to "/financial-documents/$id",
                    Method.GET to "/financial-documents/$id/history",
                    Method.POST to "/financial-documents/$id/quote",
                    Method.POST to "/financial-documents/$id/invoice",
                    Method.POST to "/financial-documents/$id/change-orders",
                    Method.POST to "/financial-documents/$id/payments",
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
            quote(id, 1).status shouldBe Status.OK
        }

        test("the public estimate preview is unchanged and still records nothing") {
            val documents = application.database.count("commerce.financial_document_snapshots")
            val sources = application.database.count("fionas.financial_document_pricing")
            application
                .http(Request(Method.POST, "/estimate-preview").header("Content-Type", "application/json").body(pricingBody(revision)))
                .status shouldBe Status.OK
            application.database.count("commerce.financial_document_snapshots") shouldBe documents
            application.database.count("fionas.financial_document_pricing") shouldBe sources
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

private fun usd(amount: String) = Money(BigDecimal(amount), USD)
