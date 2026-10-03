package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.ValidationErrorResponse
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.perGuest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

/**
 * `POST /estimate-preview` through the complete fionas-commerce HTTP handler, over real
 * PostgreSQL: a catalog administered through commerce-runtime's Offerings API, persisted as
 * runtime snapshots, and priced by Fiona's engine. Nothing here writes a catalog table
 * directly.
 */
class EstimatePreviewRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication

        // The revision whose choices the acceptance estimate is made from.
        var acceptanceRevision = 0
        var catalogRevision = 0

        fun post(
            path: String,
            body: String,
        ) = if (path.startsWith("/offering-catalog")) {
            application.adminPost(path, body)
        } else {
            application.http(Request(Method.POST, path).asFionasWeb(application).header("Content-Type", "application/json").body(body))
        }

        fun Response.preview() = CommerceJson.asA(bodyString(), EstimatePreviewResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        val toppings = listOf("sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough")

        fun request(
            revision: Int = catalogRevision,
            guests: Int = 75,
            minimum: Boolean? = null,
            minutes: Int = 120,
            softServe: List<String> = listOf("vanilla", "horchata"),
            chosenToppings: List<String> = toppings,
            cones: List<String>? = listOf("waffle-cone"),
        ): String {
            fun block(
                category: String,
                offerings: List<String>,
            ) = """{"category":"$category","offerings":[${offerings.joinToString(",") { "\"$it\"" }}]}"""
            val blocks =
                listOfNotNull(
                    block("soft-serve-flavor", softServe),
                    block("topping", chosenToppings),
                    cones?.let { block("cone-option", it) },
                )
            return """{"catalogRevision":$revision,"guestCount":$guests,${minimum?.let { "\"guestCountIsMinimum\":$it," } ?: ""}""" +
                """"durationMinutes":$minutes,"selections":[${blocks.joinToString(",")}]}"""
        }

        fun preview(body: String = request()) = post("/estimate-preview", body)

        beforeSpec {
            application = TestApplication.create()
            acceptanceRevision = application.createAcceptanceCatalog()
            catalogRevision = acceptanceRevision
        }
        afterSpec { application.close() }

        test("prices the \$681.25 estimate from the catalog revision it names") {
            val response = preview()

            response.status shouldBe Status.OK
            response.preview() shouldBe
                EstimatePreviewResponse(
                    catalogRevision = acceptanceRevision,
                    guestCountIsMinimum = false,
                    lines =
                        listOf(
                            EstimatePreviewLine(
                                "Base service",
                                "2 hours · setup, staff & local travel",
                                null,
                                "250.00",
                                "250.00",
                                "0.00",
                                "250.00",
                                "USD",
                            ),
                            EstimatePreviewLine("Ice cream service", "75 guests", "75", "4.00", "300.00", "0.00", "300.00", "USD"),
                            EstimatePreviewLine("Horchata", "Premium soft serve", "75", "0.50", "37.50", "0.00", "37.50", "USD"),
                            EstimatePreviewLine("Waffle cones", null, "75", "0.75", "56.25", "0.00", "56.25", "USD"),
                            EstimatePreviewLine(
                                "Extra toppings (2)",
                                "4 toppings included; each extra is charged per guest",
                                "150",
                                "0.25",
                                "37.50",
                                "0.00",
                                "37.50",
                                "USD",
                            ),
                        ),
                    subtotal = "681.25",
                    taxAmount = "0.00",
                    total = "681.25",
                    currency = "USD",
                )
            // Money is always an exact decimal string, never a JSON number.
            response.bodyString() shouldContain "\"total\":\"681.25\""
        }

        test("records nothing, and the same request prices the same") {
            val customers = application.database.count("fionas.customers")
            val inquiries = application.database.count("fionas.inquiries")
            val documents = application.database.count("commerce.financial_document_snapshots")
            val pricingSources = application.database.count("fionas.financial_document_pricing")
            val catalog = application.http(Request(Method.GET, "/offering-catalog")).bodyString()

            val first = preview().preview()
            val second = preview().preview()

            second shouldBe first
            application.database.count("fionas.customers") shouldBe customers
            application.database.count("fionas.inquiries") shouldBe inquiries
            // A preview is not a financial document: the ledger and Fiona's pricing sources are unchanged.
            application.database.count("commerce.financial_document_snapshots") shouldBe documents
            application.database.count("fionas.financial_document_pricing") shouldBe pricingSources
            application.http(Request(Method.GET, "/offering-catalog")).bodyString() shouldBe catalog
        }

        test("a minimum guest count is priced as stated, and the preview says it is a starting price") {
            val response =
                preview(
                    request(
                        guests = 100,
                        minimum = true,
                        softServe = listOf("vanilla"),
                        chosenToppings = toppings.take(4),
                        cones = listOf("cup"),
                    ),
                ).preview()

            response.guestCountIsMinimum shouldBe true
            response.lines.map { it.description to it.subtotal } shouldContainExactly
                listOf("Base service" to "250.00", "Ice cream service" to "400.00")
            response.lines[1].subDescription shouldBe "100+ guests"
            response.total shouldBe "650.00"
        }

        test("a stale selection conflicts and a refreshed selection prices current") {
            // An administrator adds a premium flavor after the page was rendered from acceptanceRevision.
            val later = application.addOffering(catalogRevision, "mango", "soft-serve-flavor", "Mango", perGuest("1.00"))
            catalogRevision = later
            later shouldBe acceptanceRevision + 1

            listOf(request(revision = acceptanceRevision), request(revision = acceptanceRevision, softServe = listOf("mango"))).forEach {
                preview(it).let { response ->
                    response.status shouldBe Status.CONFLICT
                    response.error().code shouldBe CATALOG_REVISION_STALE
                    response.error().message shouldBe
                        "The offerings catalog changed; reload it and review the selections before pricing again"
                    response.header("Cache-Control") shouldBe "no-store"
                }
            }
            // The new flavor works as soon as the catalog has it, with no code or deployment.
            preview(request(revision = later, softServe = listOf("mango"), chosenToppings = toppings.take(4))).preview().let {
                it.catalogRevision shouldBe later
                it.lines.map { line -> line.description to line.subtotal } shouldContainExactly
                    listOf(
                        "Base service" to "250.00",
                        "Ice cream service" to "300.00",
                        "Mango" to "75.00",
                        "Waffle cones" to "56.25",
                    )
                it.total shouldBe "681.25"
            }
        }

        test("a catalog revision that does not exist is not found") {
            val latest =
                CommerceJson.asA(
                    application.http(Request(Method.GET, "/offering-catalog")).bodyString(),
                    OfferingsCatalogDto.serializer(),
                )

            preview(request(revision = latest.revision + 1)).let {
                it.status shouldBe Status.NOT_FOUND
                it.error() shouldBe ErrorResponse("not_found", "Offerings catalog revision r${latest.revision + 1} was not found")
            }
        }

        test("a selection that does not fit the catalog revision, or Fiona's pricing, fails validation naming its codes") {
            mapOf(
                request(chosenToppings = toppings.take(3)) to "TOO_FEW_SELECTIONS",
                request(cones = null) to "TOO_FEW_SELECTIONS",
                request(softServe = listOf("vanilla", "vanilla")) to "DUPLICATE_OFFERING",
                request(softServe = listOf("vanilla", "horchata", "chocolate")) to "TOO_MANY_SELECTIONS",
                request(cones = listOf("sprinkles")) to "OFFERING_IN_WRONG_CATEGORY",
                request(guests = 0) to "INVALID_GUEST_COUNT",
                request(minutes = 100) to "UNSUPPORTED_DURATION",
            ).forEach { (body, code) ->
                preview(body).let {
                    it.status shouldBe Status.UNPROCESSABLE_ENTITY
                    it.error().code shouldBe "validation_failed"
                    it.error().message shouldStartWith "The selection cannot be estimated: "
                    it.error().message shouldContain code
                    CommerceJson.asA(it.bodyString(), ValidationErrorResponse.serializer()).violations!!.map { violation ->
                        violation.code
                    } shouldContainExactly listOf(code)
                }
            }
            // Values the domain rejects before any evaluation.
            listOf(request(revision = 0), request(softServe = listOf("not a key"))).forEach {
                preview(it).let { response ->
                    response.status shouldBe Status.UNPROCESSABLE_ENTITY
                    response.error().code shouldBe "validation_failed"
                    CommerceJson.asA(response.bodyString(), ValidationErrorResponse.serializer()).violations shouldBe null
                }
            }
        }

        test("unsupported catalog currency preserves structured policy code and the useful explanation") {
            val later =
                application.addOffering(
                    catalogRevision,
                    "euro",
                    "soft-serve-flavor",
                    "Euro",
                    """{"kind":"FIXED","amount":"1.00","currency":"EUR"}""",
                )
            catalogRevision = later
            val response = preview(request(revision = later, softServe = listOf("euro")))
            response.status shouldBe Status.UNPROCESSABLE_ENTITY
            val error = CommerceJson.asA(response.bodyString(), ValidationErrorResponse.serializer())
            error.violations!!.map { it.code } shouldContainExactly listOf("UNSUPPORTED_CURRENCY")
            error.message shouldContain "EUR"
            error.message shouldContain "USD"
        }

        test("a body it cannot read is a malformed request, with commerce-runtime's error body") {
            listOf(
                "",
                "{not json",
                """{"catalogRevision":1,"guestCount":75,"durationMinutes":120}""",
                """{"catalogRevision":"one","guestCount":75,"durationMinutes":120,"selections":[]}""",
                """{"catalogRevision":1,"guestCount":75,"durationMinutes":120,"selections":[{"category":"topping","offerings":"oreos"}]}""",
            ).forEach { body ->
                preview(body).let {
                    it.status shouldBe Status.BAD_REQUEST
                    it.error() shouldBe ErrorResponse("malformed_request", "Malformed request: body 'body'")
                }
            }
        }

        test("only POST is allowed") {
            listOf(Method.GET, Method.PUT, Method.DELETE, Method.OPTIONS).forEach { method ->
                application.http(Request(method, "/estimate-preview")).status shouldBe Status.METHOD_NOT_ALLOWED
            }
        }
    })
