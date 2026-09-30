package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.offering.CategoryDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.InquiryFormControl
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.pricingBody
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

class InquiryFormRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0

        fun TestApplication.form(): InquiryFormResponse {
            val response = http(Request(Method.GET, "/inquiry-form"))
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), InquiryFormResponse.serializer())
        }

        fun InquiryFormResponse.fields() = sections.flatMap { it.fields }

        fun InquiryFormResponse.choices() = fields().mapNotNull { it.input as? InquiryFormInputResponse.OfferingChoice }

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        test("a missing catalog returns the runtime not-found envelope; the read initializes nothing") {
            TestApplication.create().use { fresh ->
                val response = fresh.http(Request(Method.GET, "/inquiry-form"))
                response.status shouldBe Status.NOT_FOUND
                CommerceJson.asA(response.bodyString(), ErrorResponse.serializer()).code shouldBe "not_found"
                fresh.database.count("commerce.offerings_snapshots") shouldBe 0
            }
        }

        test("public form returns ordered questions, submission bindings, and separate presentation hints") {
            val form = application.form()
            form.definitionVersion shouldBe 1
            form.catalogRevision shouldBe revision
            form.sections.map { it.key to it.title } shouldContainExactly
                listOf(
                    "contact" to "Contact information",
                    "service" to "Build your ice cream service",
                    "additional" to "Additional information",
                )
            form.sections.map { it.optional } shouldContainExactly listOf(false, true, true)
            form.fields().map { it.key } shouldContainExactly
                listOf(
                    "name",
                    "email",
                    "guestCount",
                    "guestCountIsMinimum",
                    "durationMinutes",
                    "offering:soft-serve-flavor",
                    "offering:topping",
                    "offering:cone-option",
                    "message",
                )
            form.fields().map { it.submissionPointer } shouldContainExactly
                listOf(
                    "/name",
                    "/email",
                    "/pricingInputs/guestCount",
                    "/pricingInputs/guestCountIsMinimum",
                    "/pricingInputs/durationMinutes",
                    "/pricingInputs/selections",
                    "/pricingInputs/selections",
                    "/pricingInputs/selections",
                    "/message",
                )
            form.fields().single { it.key == "offering:soft-serve-flavor" }.let {
                it.label shouldBe "Choose your soft serve flavors"
                it.presentation.control shouldBe InquiryFormControl.CARDS
                it.required shouldBe true
            }
            application.http(Request(Method.POST, "/inquiry-form")).status shouldBe Status.METHOD_NOT_ALLOWED
        }

        test("an initialized empty catalog still exposes ordinary questions; new and retired categories follow catalog lifecycle") {
            TestApplication.create().use { fresh ->
                fresh.adminPost("/offering-catalog").status shouldBe Status.CREATED
                val empty = fresh.form()
                empty.catalogRevision shouldBe 1
                empty.choices() shouldBe emptyList()
                empty.fields().map { it.key } shouldContainExactly
                    listOf("name", "email", "guestCount", "guestCountIsMinimum", "durationMinutes", "message")
                // Insert in a different order to prove the known questions follow Fiona's definition.
                var latest = empty.catalogRevision
                listOf("cone-option", "extras", "soft-serve-flavor").forEach { category ->
                    val response =
                        fresh.adminPost(
                            "/offering-catalog/categories",
                            """{"expectedRevision":$latest,"key":"$category","displayName":"$category","maximumSelections":1}""",
                        )
                    response.status shouldBe Status.CREATED
                    latest = CommerceJson.asA(response.bodyString(), CategoryDto.serializer()).revision
                }
                val active = fresh.form()
                active.choices().map { it.category } shouldContainExactly listOf("soft-serve-flavor", "cone-option", "extras")
                active
                    .fields()
                    .single { it.key == "offering:extras" }
                    .presentation.control shouldBe InquiryFormControl.SELECT
                fresh
                    .adminRequest(Method.DELETE, "/offering-catalog/categories/soft-serve-flavor?expectedRevision=$latest")
                    .status shouldBe Status.OK
                val retired = fresh.form()
                retired.catalogRevision shouldBe latest + 1
                retired.choices().map { it.category } shouldContainExactly listOf("cone-option", "extras")
                retired.choices().flatMap { it.options } shouldBe emptyList()
            }
        }

        test("ordinary semantics mirror normalization, domain lengths, integers, and policy-owned duration choices") {
            val fields = application.form().fields().associateBy { it.key }
            fields.getValue("name").input shouldBe InquiryFormInputResponse.Text(1, CustomerName.MAX_LENGTH)
            fields.getValue("name").required shouldBe true
            fields.getValue("email").input shouldBe InquiryFormInputResponse.Email(Email.MAX_LENGTH)
            fields.getValue("email").required shouldBe true
            fields.getValue("message").input shouldBe InquiryFormInputResponse.Text(0, InquiryMessage.MAX_LENGTH)
            fields.getValue("message").required shouldBe false
            fields.getValue("message").presentation.control shouldBe InquiryFormControl.TEXTAREA
            fields.getValue("guestCount").input shouldBe InquiryFormInputResponse.Integer(1)
            fields.getValue("guestCountIsMinimum").input shouldBe InquiryFormInputResponse.BooleanValue(false)
            (
                fields
                    .getValue(
                        "durationMinutes",
                    ).input as InquiryFormInputResponse.IntegerChoice
            ).options.map { it.value } shouldContainExactly
                FIONAS_PRICING_POLICY.allowedDurations.sorted().map { Math.toIntExact(it.toMinutes()) }
            val json = CommerceJson.parse(application.http(Request(Method.GET, "/inquiry-form")).bodyString()).jsonObject
            json
                .getValue("sections")
                .jsonArray
                .first()
                .jsonObject
                .getValue("fields")
                .jsonArray
                .first()
                .jsonObject
                .getValue("input")
                .jsonObject
                .getValue("type")
                .jsonPrimitive.content shouldBe "TEXT"
        }

        test("all choices and their option descriptions and prices are projected from one catalog revision") {
            val catalog =
                CommerceJson.asA(
                    application.http(Request(Method.GET, "/offering-catalog")).bodyString(),
                    OfferingsCatalogDto.serializer(),
                )
            val form = application.form()
            form.catalogId shouldBe catalog.catalogId
            form.catalogRevision shouldBe catalog.revision
            form.choices().forEach { field ->
                val category = catalog.categories.single { it.key == field.category }
                field.minSelections shouldBe category.minimumSelections
                field.maxSelections shouldBe category.maximumSelections
                field.options shouldContainExactly category.offerings
            }
            val flavors = form.choices().first()
            flavors.options.map { it.key to it.displayName } shouldContainExactly
                listOf("vanilla" to "Vanilla", "chocolate" to "Chocolate", "horchata" to "Horchata")
            flavors.options.last().description shouldBe "Premium soft serve"
            flavors.options
                .last()
                .price
                ?.amount shouldBe "0.50"
            flavors.options.first().price shouldBe null
        }

        test("new categories, all price forms, unbounded selections and catalog descriptions need no frontend category rules") {
            TestApplication.create().use { fresh ->
                val initial = fresh.createAcceptanceCatalog()
                val response =
                    fresh.adminPost(
                        "/offering-catalog/categories",
                        """{"expectedRevision":$initial,"key":"extras","displayName":"Extras","description":"Make it special"}""",
                    )
                response.status shouldBe Status.CREATED
                var latest = CommerceJson.asA(response.bodyString(), CategoryDto.serializer()).revision
                latest =
                    fresh.addOffering(latest, "setup", "extras", "Special setup", """{"kind":"FIXED","amount":"10.00","currency":"USD"}""")
                latest =
                    fresh.addOffering(
                        latest,
                        "staff",
                        "extras",
                        "Extra staff",
                        """{"kind":"PER_DURATION","amount":"5.00","currency":"USD","interval":"PT30M"}""",
                    )
                val form = fresh.form()
                form.catalogRevision shouldBe latest
                val field = form.fields().single { it.key == "offering:extras" }
                field.description shouldBe "Make it special"
                field.label shouldBe "Choose Extras"
                field.required shouldBe false
                field.presentation.control shouldBe InquiryFormControl.CHECKBOXES
                val input = field.input as InquiryFormInputResponse.OfferingChoice
                input.minSelections shouldBe 0
                input.maxSelections shouldBe null
                input.options.map { it.price?.kind } shouldContainExactly listOf("FIXED", "PER_DURATION")
                input.options
                    .last()
                    .price
                    ?.interval shouldBe "PT30M"
                fresh.database.count("fionas.inquiries") shouldBe 0
            }
        }

        test("category edits and retirements update the next form while previously rendered answers retain their revision") {
            TestApplication.create().use { fresh ->
                val initial = fresh.createAcceptanceCatalog()
                fresh
                    .adminRequest(
                        Method.PUT,
                        "/offering-catalog/categories/soft-serve-flavor",
                        """{"expectedRevision":$initial,"displayName":"Soft serve","minimumSelections":2,"maximumSelections":2}""",
                    ).status shouldBe Status.OK
                val edited = fresh.form()
                edited.catalogRevision shouldBe initial + 1
                edited.choices().first().minSelections shouldBe 2
                edited.choices().first().maxSelections shouldBe 2
                fresh
                    .adminRequest(Method.DELETE, "/offering-catalog/offerings/horchata?expectedRevision=${edited.catalogRevision}")
                    .status shouldBe Status.OK
                val current = fresh.form()
                current.catalogRevision shouldBe initial + 2
                current
                    .choices()
                    .first()
                    .options
                    .map { it.key } shouldContainExactly listOf("vanilla", "chocolate")
                val inputs = pricingBody(edited.catalogRevision)
                fresh
                    .http(Request(Method.POST, "/estimate-preview").header("Content-Type", "application/json").body(inputs))
                    .status shouldBe Status.OK
                fresh
                    .http(
                        Request(Method.POST, "/inquiries")
                            .header("Content-Type", "application/json")
                            .body("""{"name":"Jane","email":"jane@example.com","pricingInputs":$inputs}"""),
                    ).status shouldBe Status.CREATED
                fresh
                    .http(
                        Request(Method.POST, "/estimate-preview")
                            .header("Content-Type", "application/json")
                            .body(pricingBody(current.catalogRevision)),
                    ).status shouldBe Status.UNPROCESSABLE_ENTITY
            }
        }

        test("answers selected using only form metadata preview and submit, while malicious answers still fail backend validation") {
            val form = application.form()
            val selections =
                form.choices().map { choice ->
                    PricingSelection(choice.category, choice.options.take(choice.minSelections).map { it.key })
                }
            val duration =
                (
                    form
                        .fields()
                        .single {
                            it.input is InquiryFormInputResponse.IntegerChoice
                        }.input as InquiryFormInputResponse.IntegerChoice
                ).options
                    .first()
                    .value
            val inputs = InquiryPricingInputs(form.catalogRevision, 75, durationMinutes = duration, selections = selections)

            fun submit(value: InquiryPricingInputs) =
                application.http(
                    Request(Method.POST, "/inquiries")
                        .header("Content-Type", "application/json")
                        .body(
                            CommerceJson.json.encodeToString(
                                CreateInquiryRequest.serializer(),
                                CreateInquiryRequest("Jane", "form@example.com", pricingInputs = value),
                            ),
                        ),
                )
            application
                .http(
                    Request(Method.POST, "/estimate-preview")
                        .header("Content-Type", "application/json")
                        .body(CommerceJson.json.encodeToString(InquiryPricingInputs.serializer(), inputs)),
                ).status shouldBe Status.OK
            submit(inputs).status shouldBe Status.CREATED
            submit(inputs.copy(guestCount = 0)).status shouldBe Status.UNPROCESSABLE_ENTITY
            submit(inputs.copy(durationMinutes = 91)).status shouldBe Status.UNPROCESSABLE_ENTITY
            submit(inputs.copy(selections = emptyList())).status shouldBe Status.UNPROCESSABLE_ENTITY
            val plain =
                application.http(
                    Request(Method.POST, "/inquiries")
                        .header("Content-Type", "application/json")
                        .body("""{"name":"Jane","email":"plain@example.com"}"""),
                )
            plain.status shouldBe Status.CREATED
        }
    })
