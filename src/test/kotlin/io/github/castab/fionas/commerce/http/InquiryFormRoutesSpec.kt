package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.offering.CategoryDto
import io.github.castab.commerce.runtime.offering.GetOfferingsCatalog
import io.github.castab.commerce.runtime.offering.OfferingAvailabilityDto
import io.github.castab.commerce.runtime.offering.OfferingSelectionStateDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokenAuthenticator
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.GetInquiryForm
import io.github.castab.fionas.commerce.inquiry.InquiryFormControl
import io.github.castab.fionas.commerce.inquiry.InquiryFormInput
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.inquiry.ZipCode
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.setOfferingState
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.contract.contract
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.math.BigDecimal

class InquiryFormRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0

        fun TestApplication.form(): InquiryFormResponse {
            val response = http(Request(Method.GET, "/inquiry-form").asFionasWeb(this))
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "private, max-age=60, must-revalidate"
            return CommerceJson.asA(response.bodyString(), InquiryFormResponse.serializer())
        }

        fun InquiryFormResponse.fields() = sections.flatMap { it.fields }

        fun InquiryFormResponse.choices() = fields().mapNotNull { it.input as? InquiryFormInputResponse.OfferingChoice }

        // Browser-like arithmetic using only the wire response and customer answers, never the policy or engine.
        fun InquiryFormResponse.browserTotal(inputs: InquiryPricingInputs): BigDecimal {
            val facts = pricingPreview
            val duration = facts.durationOptions.single { it.durationMinutes == inputs.durationMinutes }
            val guests = inputs.guestCount.toBigDecimal()
            val selected = inputs.selections.flatMap { it.offerings }.toSet()
            var total = BigDecimal(duration.baseServiceAmount) + BigDecimal(facts.perGuestAmount) * guests
            choices().flatMap { it.options }.filter { it.key in selected }.forEach { offering ->
                offering.price?.let { price ->
                    total +=
                        when (price.kind) {
                            "FIXED" -> BigDecimal(price.amount)
                            "PER_QUANTITY" -> BigDecimal(price.amount) * guests
                            "PER_DURATION" -> BigDecimal(duration.offeringContributions.single { it.offeringKey == offering.key }.amount)
                            else -> error("Unknown price kind")
                        }
                }
            }
            val adjustment = facts.toppingAdjustment
            val toppingCount =
                inputs.selections
                    .singleOrNull { it.category == adjustment.category }
                    ?.offerings
                    ?.size ?: 0
            return total + (toppingCount - adjustment.includedSelections).coerceAtLeast(0).toBigDecimal() *
                guests * BigDecimal(adjustment.additionalSelectionPerGuestAmount)
        }

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        test("public options retain enabled availability, metadata and order without filtering the authoritative catalog") {
            TestApplication.create().use { fresh ->
                var latest = fresh.createAcceptanceCatalog()
                val originalRevision = latest
                val durationPrice = """{"kind":"PER_DURATION","amount":"3.00","currency":"USD","interval":"PT1H"}"""
                latest = fresh.setOfferingState(latest, "chocolate", selectionState = OfferingSelectionStateDto.DISABLED)
                latest =
                    fresh.addOffering(
                        latest,
                        "temporary",
                        "soft-serve-flavor",
                        "Temporary special",
                        durationPrice,
                        description = "Check back later",
                        availability = OfferingAvailabilityDto.UNAVAILABLE,
                        badge = "Seasonal",
                        statusNote = "Looks available",
                        infoNote = "Made in small batches",
                    )
                latest =
                    fresh.addOffering(
                        latest,
                        "hidden",
                        "soft-serve-flavor",
                        "Hidden special",
                        durationPrice,
                        selectionState = OfferingSelectionStateDto.DISABLED,
                    )
                latest =
                    fresh.addOffering(
                        latest,
                        "secret",
                        "soft-serve-flavor",
                        "Secret special",
                        durationPrice,
                        selectionState = OfferingSelectionStateDto.DISABLED,
                        availability = OfferingAvailabilityDto.UNAVAILABLE,
                    )
                val before = fresh.adminGet("/offering-catalog").bodyString()
                val form = fresh.form()
                form.catalogRevision shouldBe latest
                val options = form.choices().single { it.category == "soft-serve-flavor" }.options
                options.map { it.key } shouldContainExactly listOf("vanilla", "horchata", "temporary")
                options.all { it.selectionState == OfferingSelectionStateDto.ENABLED } shouldBe true
                options.first().availability shouldBe OfferingAvailabilityDto.AVAILABLE
                options.last().let {
                    it.displayName shouldBe "Temporary special"
                    it.description shouldBe "Check back later"
                    it.availability shouldBe OfferingAvailabilityDto.UNAVAILABLE
                    it.badge shouldBe "Seasonal"
                    it.statusNote shouldBe "Looks available"
                    it.infoNote shouldBe "Made in small batches"
                    val price = checkNotNull(it.price)
                    price.kind shouldBe "PER_DURATION"
                    price.amount shouldBe "3.00"
                    price.currency shouldBe "USD"
                    price.interval shouldBe "PT1H"
                }
                form.pricingPreview.durationOptions.forEach {
                    it.offeringContributions.map { contribution -> contribution.offeringKey } shouldBe listOf("temporary")
                }
                fresh.adminGet("/offering-catalog").bodyString() shouldBe before
                fresh.adminGet("/offering-catalog/revisions/$latest").status shouldBe Status.NOT_FOUND
                val full = CommerceJson.asA(before, OfferingsCatalogDto.serializer())
                full.categories
                    .first()
                    .offerings
                    .map { it.key } shouldBe
                    listOf("vanilla", "chocolate", "horchata", "temporary", "hidden", "secret")
                full.categories
                    .first()
                    .offerings
                    .single { it.key == "secret" }
                    .selectionState shouldBe OfferingSelectionStateDto.DISABLED
                fresh.adminGet("/offering-catalog/revisions/$originalRevision").status shouldBe Status.NOT_FOUND
            }
        }

        test("unavailable enabled options satisfy visible minimum while disabled options cannot") {
            TestApplication.create().use { fresh ->
                var latest = fresh.createAcceptanceCatalog()
                latest = fresh.setOfferingState(latest, "vanilla", availability = OfferingAvailabilityDto.UNAVAILABLE)
                latest = fresh.setOfferingState(latest, "chocolate", availability = OfferingAvailabilityDto.UNAVAILABLE)
                latest = fresh.setOfferingState(latest, "horchata", selectionState = OfferingSelectionStateDto.DISABLED)
                val changed =
                    fresh.adminRequest(
                        Method.PUT,
                        "/offering-catalog/categories/soft-serve-flavor",
                        """{"expectedRevision":$latest,"displayName":"Flavors","minimumSelections":2,"maximumSelections":2}""",
                    )
                changed.status shouldBe Status.OK
                latest = CommerceJson.asA(changed.bodyString(), CategoryDto.serializer()).revision
                fresh
                    .form()
                    .choices()
                    .first()
                    .options
                    .map { it.availability } shouldBe
                    listOf(OfferingAvailabilityDto.UNAVAILABLE, OfferingAvailabilityDto.UNAVAILABLE)
                fresh.setOfferingState(latest, "chocolate", selectionState = OfferingSelectionStateDto.DISABLED)
                val failed = fresh.http(Request(Method.GET, "/inquiry-form").asFionasWeb(fresh))
                failed.status shouldBe Status.INTERNAL_SERVER_ERROR
                failed.header("Cache-Control") shouldBe "no-store"
                CommerceJson.asA(failed.bodyString(), ErrorResponse.serializer()).code shouldBe "internal_failure"
            }
        }

        test("a missing catalog returns the runtime not-found envelope; the read initializes nothing") {
            TestApplication.create().use { fresh ->
                val response = fresh.http(Request(Method.GET, "/inquiry-form").asFionasWeb(fresh))
                response.status shouldBe Status.NOT_FOUND
                response.header("Cache-Control") shouldBe "no-store"
                CommerceJson.asA(response.bodyString(), ErrorResponse.serializer()).code shouldBe "not_found"
                fresh.database.count("commerce.offerings_catalogs") shouldBe 0
            }
        }

        test("public form returns ordered questions, submission bindings, and separate presentation hints") {
            val form = application.form()
            form.definitionVersion shouldBe 11
            form.catalogRevision shouldBe revision
            form.sections.map { it.key to it.title } shouldContainExactly
                listOf(
                    "contact" to "Contact information",
                    "event" to "Event details",
                    "service" to "Build your ice cream service",
                    "additional" to "Additional information",
                )
            // Every inquiry configures the service; only the additional information may be omitted.
            form.sections.map { it.optional } shouldContainExactly listOf(false, false, false, true)
            form.sections.single { it.key == "service" }.description shouldBe
                "Choose your guest count, service duration, and ice cream options."
            form.fields().map { it.key } shouldContainExactly
                listOf(
                    "name",
                    "email",
                    "zipCode",
                    "eventDate",
                    "eventType",
                    "guestCount",
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
                    "/zipCode",
                    "/eventDate",
                    "/eventType",
                    "/pricingInputs/guestCount",
                    "/pricingInputs/durationMinutes",
                    "/pricingInputs/selections",
                    "/pricingInputs/selections",
                    "/pricingInputs/selections",
                    "/message",
                )
            form.fields().single { it.key == "offering:soft-serve-flavor" }.let {
                it.label shouldBe "Choose your soft serve flavors"
                it.presentation.control shouldBe InquiryFormControl.CHIPS
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
                    listOf("name", "email", "zipCode", "eventDate", "eventType", "guestCount", "durationMinutes", "message")
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
                active.choices().map { it.category } shouldContainExactly listOf("soft-serve-flavor", "cone-option")
                fresh
                    .adminRequest(Method.DELETE, "/offering-catalog/categories/soft-serve-flavor?expectedRevision=$latest")
                    .status shouldBe Status.OK
                val retired = fresh.form()
                retired.catalogRevision shouldBe latest + 1
                retired.choices().map { it.category } shouldContainExactly listOf("cone-option")
                retired.choices().flatMap { it.options } shouldBe emptyList()
                fresh
                    .adminPost(
                        "/offering-catalog/categories/soft-serve-flavor/restore",
                        """{"expectedRevision":${retired.catalogRevision},"displayName":"Restored flavors","maximumSelections":1}""",
                    ).status shouldBe Status.OK
                val restored = fresh.form()
                restored.choices().map { it.category } shouldContainExactly listOf("soft-serve-flavor", "cone-option")
                restored.fields().single { it.key == "offering:soft-serve-flavor" }.let {
                    it.label shouldBe "Choose your soft serve flavors"
                    it.presentation.control shouldBe InquiryFormControl.CHIPS
                }
            }
        }

        test("CHIPS option text maps to JSON while SELECT options retain null defaults") {
            val catalog =
                GetOfferingsCatalog(application.transactor, application.context.offeringsSnapshotRepository)(FIONA_OFFERINGS_CATALOG_ID)
            val original = GetInquiryForm({ catalog })()
            val customized =
                original.copy(
                    sections =
                        original.sections.map { section ->
                            section.copy(
                                fields =
                                    section.fields.map { field ->
                                        when (val input = field.input) {
                                            is InquiryFormInput.IntegerChoice ->
                                                field.copy(
                                                    control = InquiryFormControl.CHIPS,
                                                    input =
                                                        input.copy(
                                                            options =
                                                                input.options.map {
                                                                    it.copy(
                                                                        badge = " Popular ",
                                                                        statusNote = "Today",
                                                                        infoNote = "Duration details",
                                                                    )
                                                                },
                                                        ),
                                                )
                                            else -> field
                                        }
                                    },
                            )
                        },
                )
            val access =
                AccessControl(
                    authentication(ServiceAccessTokenAuthenticator(application.context.serviceAccessTokens)),
                    application.context.authorization,
                )
            val handler =
                contract {
                    renderer = fionaOpenApi("test")
                    routes += getInquiryFormRoute({ customized }, access)
                }
            val response = handler(Request(Method.GET, "/inquiry-form").asFionasWeb(application))
            response.status shouldBe Status.OK
            val fields = CommerceJson.asA(response.bodyString(), InquiryFormResponse.serializer()).fields()
            (fields.single { it.key == "durationMinutes" }.input as InquiryFormInputResponse.IntegerChoice).options.first().let {
                it.badge shouldBe " Popular "
                it.statusNote shouldBe "Today"
                it.infoNote shouldBe "Duration details"
            }
            fields.single { it.key == "durationMinutes" }.presentation.control shouldBe InquiryFormControl.CHIPS
            fields.single { it.key == "eventType" }.let { event ->
                event.presentation.control shouldBe InquiryFormControl.SELECT
                (event.input as InquiryFormInputResponse.StringChoice).options.forEach {
                    it.badge shouldBe null
                    it.statusNote shouldBe null
                    it.infoNote shouldBe null
                }
            }
            val defaultJson = application.http(Request(Method.GET, "/inquiry-form").asFionasWeb(application)).bodyString()
            listOf("badge", "statusNote", "infoNote").forEach { defaultJson.contains("\"$it\"") shouldBe false }
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
            fields.getValue("guestCount").description shouldBe
                "An estimate is totally okay - we can hash out the finer details during quoting."
            fields.getValue("zipCode").let {
                it.input shouldBe InquiryFormInputResponse.Text(ZipCode.LENGTH, ZipCode.LENGTH, ZipCode.PATTERN)
                it.required shouldBe true
                it.presentation.control shouldBe InquiryFormControl.TEXT
            }
            fields.containsKey("guestCountIsMinimum") shouldBe false
            fields.getValue("eventDate").let {
                it.required shouldBe true
                it.input shouldBe InquiryFormInputResponse.Date("date")
                it.presentation.control shouldBe InquiryFormControl.DATE
            }
            fields.getValue("eventType").let {
                it.required shouldBe true
                it.presentation.control shouldBe InquiryFormControl.SELECT
                val options = (it.input as InquiryFormInputResponse.StringChoice).options
                options.map { option -> option.label } shouldContainExactly
                    listOf("Birthday", "Wedding", "Corporate", "School event", "Neighborhood event", "Other")
                options.map { option -> InquiryEventType.valueOf(option.value).toDomain().name } shouldContainExactly
                    InquiryEventType.entries.map { type -> type.name }
            }
            fields.getValue("durationMinutes").presentation.control shouldBe InquiryFormControl.CHIPS
            (
                fields
                    .getValue(
                        "durationMinutes",
                    ).input as InquiryFormInputResponse.IntegerChoice
            ).options.map { it.value } shouldContainExactly
                FIONAS_PRICING_POLICY.allowedDurations.sorted().map { Math.toIntExact(it.toMinutes()) }
            val json =
                CommerceJson
                    .parse(
                        application.http(Request(Method.GET, "/inquiry-form").asFionasWeb(application)).bodyString(),
                    ).jsonObject
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
            form
                .choices()
                .single { it.category == "topping" }
                .options
                .map { it.key to it.displayName } shouldContainExactly
                TOPPINGS.zip(listOf("Sprinkles", "Oreos", "Strawberries", "Brownies", "Gummy Bears", "Cookie Dough"))
        }

        test("the acceptance estimate is calculated from form facts alone and matches the authoritative preview") {
            val form = application.form()
            val inputs =
                InquiryPricingInputs(
                    form.catalogRevision,
                    75,
                    durationMinutes = 120,
                    selections =
                        listOf(
                            PricingSelection(form.choices()[0].category, listOf("vanilla", "horchata")),
                            PricingSelection(form.choices()[1].category, TOPPINGS),
                            PricingSelection(form.choices()[2].category, listOf("waffle-cone")),
                        ),
                )
            val local = form.browserTotal(inputs)
            local.compareTo(BigDecimal("681.25")) shouldBe 0
            val response =
                application.http(
                    Request(Method.POST, "/estimate-preview")
                        .asFionasWeb(application)
                        .header("Content-Type", "application/json")
                        .body(CommerceJson.json.encodeToString(InquiryPricingInputs.serializer(), inputs)),
                )
            response.status shouldBe Status.OK
            val authoritative = CommerceJson.asA(response.bodyString(), EstimatePreviewResponse.serializer())
            authoritative.catalogRevision shouldBe form.catalogRevision
            authoritative.currency shouldBe form.pricingPreview.currency
            BigDecimal(authoritative.total).compareTo(local) shouldBe 0
            form.pricingPreview.guestQuantityDimension shouldBe "guest"
            form.browserTotal(inputs.copy(guestCountIsMinimum = true)).compareTo(local) shouldBe 0
        }

        test("fixed and duration contributions match authoritative pricing for every advertised duration and stay revision-pinned") {
            TestApplication.create().use { fresh ->
                val initial = fresh.createAcceptanceCatalog()
                val withFixed =
                    fresh.addOffering(
                        initial,
                        "premium-cup",
                        "cone-option",
                        "Premium cup",
                        """{"kind":"FIXED","amount":"10.00","currency":"USD"}""",
                    )
                val withDuration =
                    fresh.addOffering(
                        withFixed,
                        "hourly-flavor",
                        "soft-serve-flavor",
                        "Hourly flavor",
                        """{"kind":"PER_DURATION","amount":"5.00","currency":"USD","interval":"PT1H"}""",
                    )
                val form = fresh.form()
                form.catalogRevision shouldBe withDuration
                form.pricingPreview.durationOptions.forEach { duration ->
                    val inputs =
                        InquiryPricingInputs(
                            form.catalogRevision,
                            75,
                            durationMinutes = duration.durationMinutes,
                            selections =
                                listOf(
                                    PricingSelection(form.choices()[0].category, listOf("horchata", "hourly-flavor")),
                                    PricingSelection(form.choices()[1].category, TOPPINGS),
                                    PricingSelection(form.choices()[2].category, listOf("premium-cup")),
                                ),
                        )
                    val forged =
                        CommerceJson.json.encodeToString(InquiryPricingInputs.serializer(), inputs).dropLast(1) +
                            ""","total":"0.01","lines":[{"unitPrice":"0.01"}]}"""
                    val response =
                        fresh.http(
                            Request(
                                Method.POST,
                                "/estimate-preview",
                            ).asFionasWeb(fresh).header("Content-Type", "application/json").body(forged),
                        )
                    response.status shouldBe Status.OK
                    BigDecimal(CommerceJson.asA(response.bodyString(), EstimatePreviewResponse.serializer()).total)
                        .compareTo(form.browserTotal(inputs)) shouldBe 0
                }
                fresh
                    .adminPost(
                        "/offering-catalog/offerings/retire",
                        """{"expectedRevision":$withDuration,"keys":["hourly-flavor"]}""",
                    ).status shouldBe Status.OK
                val latest = fresh.form()
                latest.catalogRevision shouldBe withDuration + 1
                latest.pricingPreview.durationOptions.flatMap { it.offeringContributions } shouldBe emptyList()
                latest.choices().flatMap { it.options }.any { it.key == "hourly-flavor" } shouldBe false
                // Captured browser facts remain advisory; a new backend request must refresh its revision.
                val historical =
                    InquiryPricingInputs(
                        form.catalogRevision,
                        75,
                        durationMinutes = 90,
                        selections =
                            listOf(
                                PricingSelection(form.choices()[0].category, listOf("horchata", "hourly-flavor")),
                                PricingSelection(form.choices()[1].category, TOPPINGS),
                                PricingSelection(form.choices()[2].category, listOf("premium-cup")),
                            ),
                    )
                val response =
                    fresh.http(
                        Request(Method.POST, "/estimate-preview")
                            .asFionasWeb(fresh)
                            .header("Content-Type", "application/json")
                            .body(CommerceJson.json.encodeToString(InquiryPricingInputs.serializer(), historical)),
                    )
                response.status shouldBe Status.CONFLICT
                CommerceJson.asA(response.bodyString(), ErrorResponse.serializer()).code shouldBe CATALOG_REVISION_STALE
                response.header("Cache-Control") shouldBe "no-store"
            }
        }

        listOf(
            """{"kind":"FIXED","amount":"1.00","currency":"EUR"}""",
            """{"kind":"PER_QUANTITY","amount":"1.00","currency":"USD","dimension":"vehicle"}""",
            """{"kind":"PER_DURATION","amount":"1.00","currency":"USD","interval":"PT7M"}""",
            """{"kind":"PER_DURATION","amount":"1.00","currency":"USD","interval":"PT45M"}""",
        ).forEachIndexed { index, price ->
            test("incompatible public offering $index fails the whole form as a server configuration error") {
                TestApplication.create().use { fresh ->
                    val initial = fresh.createAcceptanceCatalog()
                    fresh.addOffering(initial, "invalid-flavor", "soft-serve-flavor", "Invalid flavor", price)
                    val response = fresh.http(Request(Method.GET, "/inquiry-form").asFionasWeb(fresh))
                    response.status shouldBe Status.INTERNAL_SERVER_ERROR
                    response.header("Cache-Control") shouldBe "no-store"
                    CommerceJson.asA(response.bodyString(), ErrorResponse.serializer()) shouldBe
                        ErrorResponse("internal_failure", INTERNAL_FAILURE)
                    fresh.database.count("fionas.inquiries") shouldBe 0
                }
            }
        }

        test("adding unrelated categories and offerings never adds questions or changes the public pricing facts") {
            TestApplication.create().use { fresh ->
                val initial = fresh.createAcceptanceCatalog()
                val before = fresh.form()
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
                form.sections shouldBe before.sections
                form.pricingPreview shouldBe before.pricingPreview
                fresh.database.count("fionas.inquiries") shouldBe 0
            }
        }

        test("previews and inquiry submissions both reject a captured stale form") {
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
                    .adminPost(
                        "/offering-catalog/offerings/retire",
                        """{"expectedRevision":${edited.catalogRevision},"keys":["horchata"]}""",
                    ).status shouldBe Status.OK
                val current = fresh.form()
                current.catalogRevision shouldBe initial + 2
                current
                    .choices()
                    .first()
                    .options
                    .map { it.key } shouldContainExactly listOf("vanilla", "chocolate")
                val inputs = pricingBody(edited.catalogRevision)
                fresh
                    .http(
                        Request(
                            Method.POST,
                            "/estimate-preview",
                        ).asFionasWeb(fresh).header("Content-Type", "application/json").body(inputs),
                    ).status shouldBe Status.CONFLICT
                fresh
                    .http(
                        Request(Method.POST, "/inquiries")
                            .withSubmissionKey()
                            .asFionasWeb(fresh)
                            .header("Content-Type", "application/json")
                            .body(
                                """{"name":"Jane","email":"jane@example.com","zipCode":"92626","eventDate":"2026-12-05","eventType":"BIRTHDAY","pricingInputs":$inputs}""",
                            ),
                    ).let { stale ->
                        stale.status shouldBe Status.CONFLICT
                        CommerceJson.asA(stale.bodyString(), ErrorResponse.serializer()).code shouldBe "CATALOG_REVISION_STALE"
                        fresh.database.count("fionas.customers") shouldBe 0
                        fresh.database.count("fionas.inquiries") shouldBe 0
                        fresh.database.count("commerce.financial_document_snapshots") shouldBe 0
                    }
                fresh
                    .http(
                        Request(Method.POST, "/estimate-preview")
                            .asFionasWeb(fresh)
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
                        .withSubmissionKey()
                        .asFionasWeb(application)
                        .header("Content-Type", "application/json")
                        .body(
                            CommerceJson.json.encodeToString(
                                CreateInquiryRequest.serializer(),
                                CreateInquiryRequest(
                                    "Jane",
                                    "form@example.com",
                                    pricingInputs = value,
                                    zipCode = "92626",
                                    eventDate = "2026-12-05",
                                    eventType = InquiryEventType.BIRTHDAY,
                                ),
                            ),
                        ),
                )
            application
                .http(
                    Request(Method.POST, "/estimate-preview")
                        .asFionasWeb(application)
                        .header("Content-Type", "application/json")
                        .body(CommerceJson.json.encodeToString(InquiryPricingInputs.serializer(), inputs)),
                ).status shouldBe Status.OK
            submit(inputs).status shouldBe Status.CREATED
            submit(inputs.copy(guestCount = 0)).status shouldBe Status.UNPROCESSABLE_ENTITY
            submit(inputs.copy(durationMinutes = 91)).status shouldBe Status.UNPROCESSABLE_ENTITY
            submit(inputs.copy(selections = emptyList())).status shouldBe Status.UNPROCESSABLE_ENTITY
            // Answering only the required contact and event sections omits the required service section.
            val unconfigured =
                application.http(
                    Request(Method.POST, "/inquiries")
                        .withSubmissionKey()
                        .asFionasWeb(application)
                        .header("Content-Type", "application/json")
                        .body(
                            """{"name":"Jane","email":"unconfigured@example.com","zipCode":"92626","eventDate":"2026-12-05","eventType":"BIRTHDAY"}""",
                        ),
                )
            unconfigured.status shouldBe Status.BAD_REQUEST
        }
    })
