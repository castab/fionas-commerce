package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.offering.CatalogRevisionDto
import io.github.castab.commerce.runtime.offering.CategoryDto
import io.github.castab.commerce.runtime.offering.CategoryOfferingsDto
import io.github.castab.commerce.runtime.offering.OfferingAvailabilityDto
import io.github.castab.commerce.runtime.offering.OfferingCategoryDto
import io.github.castab.commerce.runtime.offering.OfferingDto
import io.github.castab.commerce.runtime.offering.OfferingPriceDto
import io.github.castab.commerce.runtime.offering.OfferingResultDto
import io.github.castab.commerce.runtime.offering.OfferingSelectionStateDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.commerce.runtime.offering.OfferingsDto
import io.github.castab.commerce.runtime.offering.RetiredCategoriesDto
import io.github.castab.commerce.runtime.offering.RetiredOfferingsDto
import io.github.castab.fionas.commerce.http.EstimatePreviewResponse
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.withUiKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

/**
 * Fiona's Offerings catalog through the complete fionas-commerce HTTP handler, as `main()`
 * composes it: commerce-runtime's Offerings capability bound to [FIONA_OFFERINGS_CATALOG_ID]
 * at `/offering-catalog`, over real PostgreSQL.
 *
 * commerce-runtime's own suite covers the capability exhaustively; these specs prove that
 * Fiona composes the runtime's managed catalog with immutable, append-only history.
 * Read tests build one history in order; lifecycle smoke tests each use a fresh catalog.
 */
class OfferingsCatalogSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun get(path: String) = application.http(Request(Method.GET, path))

        fun post(
            path: String,
            body: String = "",
        ) = application.adminPost(path, body)

        fun <T : Any> Response.body(serializer: KSerializer<T>): T = CommerceJson.asA(bodyString(), serializer)

        fun Response.catalog() = body(OfferingsCatalogDto.serializer())

        fun OfferingsCatalogDto.offeringKeys() = categories.map { category -> category.key to category.offerings.map { it.key } }

        val softServe =
            OfferingCategoryDto(
                key = "soft-serve-flavor",
                displayName = "Soft Serve",
                description = "Choose your soft serve flavors",
                minimumSelections = 2,
                maximumSelections = 2,
            )
        val vanilla =
            OfferingDto(
                key = "vanilla",
                category = "soft-serve-flavor",
                displayName = "Vanilla",
                description = "Classic vanilla soft serve",
                selectionState = OfferingSelectionStateDto.ENABLED,
                availability = OfferingAvailabilityDto.AVAILABLE,
            )
        val chocolate =
            OfferingDto(
                key = "chocolate",
                category = "soft-serve-flavor",
                displayName = "Chocolate",
                selectionState = OfferingSelectionStateDto.ENABLED,
                availability = OfferingAvailabilityDto.AVAILABLE,
            )

        test("on a fresh database Fiona's catalog does not exist: startup never creates it") {
            get("/offering-catalog").let {
                it.status shouldBe Status.NOT_FOUND
                it.body(ErrorResponse.serializer()).code shouldBe "not_found"
            }
            application.database.count("commerce.offerings_snapshots") shouldBe 0
        }

        test("initializing the catalog creates revision 1, once") {
            post("/offering-catalog").let {
                it.status shouldBe Status.CREATED
                val catalog = it.catalog()
                revision = catalog.revision
                catalog.catalogId shouldBe FIONA_OFFERINGS_CATALOG_ID.value.toString()
                catalog.revision shouldBe 1
                catalog.previousRevision.shouldBeNull()
                catalog.categories.shouldBeEmpty()
            }

            post("/offering-catalog").let {
                it.status shouldBe Status.CONFLICT
                it.body(ErrorResponse.serializer()).code shouldBe "conflict"
            }
        }

        test("adding a category, then two offerings, appends revisions 2, 3, and 4") {
            post(
                "/offering-catalog/categories",
                """
                {
                  "expectedRevision": $revision,
                  "key": "soft-serve-flavor",
                  "displayName": "Soft Serve",
                  "description": "Choose your soft serve flavors",
                  "minimumSelections": 2,
                  "maximumSelections": 2
                }
                """.trimIndent(),
            ).let {
                it.status shouldBe Status.CREATED
                it.body(CategoryDto.serializer()) shouldBe CategoryDto(2, softServe)
                revision = it.body(CategoryDto.serializer()).revision
            }

            post(
                "/offering-catalog/offerings",
                """
                {
                  "expectedRevision": $revision,
                  "key": "vanilla",
                  "selectionState":"ENABLED","availability":"AVAILABLE","category": "soft-serve-flavor",
                  "displayName": "Vanilla",
                  "description": "Classic vanilla soft serve"
                }
                """.trimIndent(),
            ).let {
                it.status shouldBe Status.CREATED
                it.body(OfferingResultDto.serializer()) shouldBe OfferingResultDto(3, vanilla)
                revision = it.body(OfferingResultDto.serializer()).revision
            }

            post(
                "/offering-catalog/offerings",
                """{"expectedRevision":$revision,"key":"chocolate","selectionState":"ENABLED","availability":"AVAILABLE","category":"soft-serve-flavor","displayName":"Chocolate"}""",
            ).let {
                it.status shouldBe Status.CREATED
                it.body(OfferingResultDto.serializer()) shouldBe OfferingResultDto(4, chocolate)
                revision = it.body(OfferingResultDto.serializer()).revision
            }
        }

        test("reads answer from the latest revision, in snapshot order, with unpriced offerings unpriced") {
            get("/offering-catalog/categories/soft-serve-flavor").let {
                it.status shouldBe Status.OK
                it.body(CategoryDto.serializer()) shouldBe CategoryDto(4, softServe)
            }
            get("/offering-catalog/categories/soft-serve-flavor/offerings").let {
                it.status shouldBe Status.OK
                it.body(CategoryOfferingsDto.serializer()) shouldBe CategoryOfferingsDto(4, softServe, listOf(vanilla, chocolate))
            }
            get("/offering-catalog/offerings/vanilla").let {
                it.status shouldBe Status.OK
                it.body(OfferingResultDto.serializer()) shouldBe OfferingResultDto(4, vanilla)
                it.bodyString().contains("\"price\"") shouldBe false
            }
            get("/offering-catalog/offerings").let {
                it.status shouldBe Status.OK
                it.body(OfferingsDto.serializer()) shouldBe OfferingsDto(4, listOf(vanilla, chocolate))
            }

            // The whole latest catalog, grouped for a UI, in one request.
            get("/offering-catalog").let {
                it.status shouldBe Status.OK
                val catalog = it.catalog()
                catalog.revision shouldBe 4
                catalog.previousRevision shouldBe 3
                catalog.categories.map { category -> category.displayName } shouldContainExactly listOf("Soft Serve")
                catalog.categories
                    .single()
                    .offerings
                    .map { offering -> offering.displayName } shouldContainExactly listOf("Vanilla", "Chocolate")
            }
        }

        test("every earlier revision stays exactly as it was recorded") {
            (1..4).associateWith { get("/offering-catalog/revisions/$it").catalog() }.let { revisions ->
                revisions.mapValues { (_, catalog) -> catalog.revision } shouldBe mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4)
                revisions.getValue(1).offeringKeys().shouldBeEmpty()
                revisions.getValue(2).offeringKeys() shouldContainExactly listOf("soft-serve-flavor" to emptyList())
                revisions.getValue(3).offeringKeys() shouldContainExactly listOf("soft-serve-flavor" to listOf("vanilla"))
                revisions.getValue(4).offeringKeys() shouldContainExactly
                    listOf("soft-serve-flavor" to listOf("vanilla", "chocolate"))
                revisions.getValue(4) shouldBe get("/offering-catalog").catalog()
            }
            get("/offering-catalog/revisions/5").status shouldBe Status.NOT_FOUND
        }

        test("every price form survives the round trip exactly") {
            post(
                "/offering-catalog/categories",
                """{"expectedRevision":$revision,"key":"test-category","displayName":"Test Category"}""",
            ).let {
                it.status shouldBe Status.CREATED
                revision = it.body(CategoryDto.serializer()).revision
            }
            val fixed =
                OfferingDto(
                    "fixed-item",
                    "test-category",
                    "Fixed Item",
                    price = OfferingPriceDto("FIXED", "120.00", "USD"),
                    selectionState = OfferingSelectionStateDto.ENABLED,
                    availability = OfferingAvailabilityDto.AVAILABLE,
                )
            val perQuantity =
                OfferingDto(
                    "waffle-cones",
                    "test-category",
                    "Waffle Cones",
                    price = OfferingPriceDto("PER_QUANTITY", "0.75", "USD", dimension = "guest"),
                    selectionState = OfferingSelectionStateDto.ENABLED,
                    availability = OfferingAvailabilityDto.AVAILABLE,
                )
            val perDuration =
                OfferingDto(
                    "service-hour",
                    "test-category",
                    "Service Hour",
                    price = OfferingPriceDto("PER_DURATION", "50.00", "USD", interval = "PT1H"),
                    selectionState = OfferingSelectionStateDto.ENABLED,
                    availability = OfferingAvailabilityDto.AVAILABLE,
                )
            listOf(
                """{"key": "fixed-item", "selectionState":"ENABLED","availability":"AVAILABLE","category": "test-category", "displayName": "Fixed Item",
                   "price": {"kind": "FIXED", "amount": "120.00", "currency": "USD"}}""",
                """{"key": "waffle-cones", "selectionState":"ENABLED","availability":"AVAILABLE","category": "test-category", "displayName": "Waffle Cones",
                   "price": {"kind": "PER_QUANTITY", "amount": "0.75", "currency": "USD", "dimension": "guest"}}""",
                """{"key": "service-hour", "selectionState":"ENABLED","availability":"AVAILABLE","category": "test-category", "displayName": "Service Hour",
                   "price": {"kind": "PER_DURATION", "amount": "50.00", "currency": "USD", "interval": "PT1H"}}""",
            ).forEach { body ->
                post("/offering-catalog/offerings", """{"expectedRevision":$revision,${body.drop(1)}""").let {
                    it.status shouldBe Status.CREATED
                    revision = it.body(OfferingResultDto.serializer()).revision
                }
            }

            listOf(fixed, perQuantity, perDuration).forEach { offering ->
                get("/offering-catalog/offerings/${offering.key}").body(OfferingResultDto.serializer()).offering shouldBe offering
            }
            get("/offering-catalog/categories/test-category/offerings").body(CategoryOfferingsDto.serializer()).offerings shouldBe
                listOf(fixed, perQuantity, perDuration)
            get("/offering-catalog").catalog().offeringKeys() shouldContainExactly
                listOf(
                    "soft-serve-flavor" to listOf("vanilla", "chocolate"),
                    "test-category" to listOf("fixed-item", "waffle-cones", "service-hour"),
                )
        }

        fun offeringMutation(
            expectedRevision: Int,
            displayName: String = "Horchata Soft Serve",
            amount: String = "0.75",
        ) =
            """{"expectedRevision":$expectedRevision,"selectionState":"ENABLED","availability":"AVAILABLE","category":"soft-serve-flavor","displayName":"$displayName",
            "description":"Premium horchata soft serve",
            "price":{"kind":"PER_QUANTITY","amount":"$amount","currency":"USD","dimension":"guest"}}"""

        test("Horchata can be updated, retired, discovered, and restored without changing history or historical pricing") {
            TestApplication.create().use { app ->
                val originalRevision = app.createAcceptanceCatalog()

                fun read(path: String) = app.http(Request(Method.GET, path))

                val original = read("/offering-catalog/revisions/$originalRevision").catalog()
                val updated = app.adminRequest(Method.PUT, "/offering-catalog/offerings/horchata", offeringMutation(originalRevision))
                updated.status shouldBe Status.OK
                val edited = updated.body(OfferingResultDto.serializer())
                edited.revision shouldBe originalRevision + 1
                edited.offering shouldBe
                    OfferingDto(
                        "horchata",
                        "soft-serve-flavor",
                        "Horchata Soft Serve",
                        "Premium horchata soft serve",
                        OfferingPriceDto("PER_QUANTITY", "0.75", "USD", dimension = "guest"),
                        selectionState = OfferingSelectionStateDto.ENABLED,
                        availability = OfferingAvailabilityDto.AVAILABLE,
                    )
                read("/offering-catalog")
                    .catalog()
                    .categories
                    .flatMap { it.offerings }
                    .single { it.key == "horchata" } shouldBe
                    edited.offering
                val editedHistory = read("/offering-catalog/revisions/${edited.revision}").catalog()

                fun historicalPreview() {
                    val preview =
                        app.http(
                            Request(
                                Method.POST,
                                "/estimate-preview",
                            ).withUiKey().header("Content-Type", "application/json").body(pricingBody(originalRevision)),
                        )
                    preview.status shouldBe Status.OK
                    preview.body(EstimatePreviewResponse.serializer()).catalogRevision shouldBe originalRevision
                    preview.body(EstimatePreviewResponse.serializer()).total shouldBe "681.25"
                    read("/offering-catalog/revisions/$originalRevision").catalog() shouldBe original
                }
                historicalPreview()

                val retirement = app.adminRequest(Method.DELETE, "/offering-catalog/offerings/horchata?expectedRevision=${edited.revision}")
                retirement.status shouldBe Status.OK
                val retiredRevision = retirement.body(CatalogRevisionDto.serializer()).revision
                retiredRevision shouldBe edited.revision + 1
                read("/offering-catalog")
                    .catalog()
                    .categories
                    .flatMap { it.offerings }
                    .none { it.key == "horchata" } shouldBe true
                read("/offering-catalog/revisions/${edited.revision}").catalog() shouldBe editedHistory
                historicalPreview()

                app
                    .adminPost(
                        "/offering-catalog/offerings",
                        """{"expectedRevision":$retiredRevision,"key":"horchata","selectionState":"ENABLED","availability":"AVAILABLE","category":"soft-serve-flavor","displayName":"Unrelated item"}""",
                    ).let {
                        it.status shouldBe Status.CONFLICT
                        it.body(ErrorResponse.serializer()).code shouldBe "conflict"
                    }
                app.adminGet("/offering-catalog/retired/offerings").let {
                    it.status shouldBe Status.OK
                    val discovery = it.body(RetiredOfferingsDto.serializer())
                    discovery.revision shouldBe retiredRevision
                    discovery.offerings.single().lastSeenRevision shouldBe edited.revision
                    discovery.offerings.single().offering shouldBe edited.offering
                }

                val restored =
                    app.adminPost(
                        "/offering-catalog/offerings/horchata/restore",
                        offeringMutation(retiredRevision, "Restored Horchata", "1.00"),
                    )
                restored.status shouldBe Status.OK
                val restoredItem = restored.body(OfferingResultDto.serializer())
                restoredItem.revision shouldBe retiredRevision + 1
                restoredItem.offering shouldBe
                    edited.offering.copy(
                        displayName = "Restored Horchata",
                        price = OfferingPriceDto("PER_QUANTITY", "1.00", "USD", dimension = "guest"),
                    )
                read("/offering-catalog/offerings/horchata").body(OfferingResultDto.serializer()) shouldBe restoredItem
                app
                    .adminGet("/offering-catalog/retired/offerings")
                    .body(RetiredOfferingsDto.serializer())
                    .offerings
                    .shouldBeEmpty()
                read("/offering-catalog/revisions/${edited.revision}").catalog() shouldBe editedHistory
                historicalPreview()
            }
        }

        test("missing and stale observed revisions cannot overwrite a newer offering or append a snapshot") {
            TestApplication.create().use { app ->
                val observed = app.createAcceptanceCatalog()
                val path = "/offering-catalog/offerings/horchata"
                app
                    .adminRequest(
                        Method.PUT,
                        path,
                        """{"selectionState":"ENABLED","availability":"AVAILABLE","category":"soft-serve-flavor","displayName":"Without revision"}""",
                    ).let {
                        it.status shouldBe Status.BAD_REQUEST
                        it.body(ErrorResponse.serializer()).code shouldBe "malformed_request"
                    }
                val newer = app.adminRequest(Method.PUT, path, offeringMutation(observed, "Newer Horchata", "1.00"))
                newer.status shouldBe Status.OK
                val current = newer.body(OfferingResultDto.serializer())
                current.revision shouldBe observed + 1
                app.adminRequest(Method.PUT, path, offeringMutation(observed, "Stale Horchata", "0.25")).let {
                    it.status shouldBe Status.CONFLICT
                    it.body(ErrorResponse.serializer()).code shouldBe "conflict"
                }
                app.http(Request(Method.GET, path)).body(OfferingResultDto.serializer()) shouldBe current
                app.http(Request(Method.GET, "/offering-catalog")).catalog().revision shouldBe current.revision
                app.http(Request(Method.GET, "/offering-catalog/revisions/${current.revision + 1}")).status shouldBe Status.NOT_FOUND
            }
        }

        test("an empty category can be added, updated, retired, discovered, and restored with the same key") {
            TestApplication.create().use { app ->
                val created = app.adminPost("/offering-catalog")
                created.status shouldBe Status.CREATED
                val initial = created.catalog().revision
                val added =
                    app.adminPost(
                        "/offering-catalog/categories",
                        """{"expectedRevision":$initial,"key":"seasonal","displayName":"Seasonal"}""",
                    )
                added.status shouldBe Status.CREATED
                val category = added.body(CategoryDto.serializer())
                category.revision shouldBe initial + 1
                val path = "/offering-catalog/categories/seasonal"
                val updated =
                    app.adminRequest(
                        Method.PUT,
                        path,
                        """{"expectedRevision":${category.revision},"displayName":"Seasonal Choices","description":"Optional specials","maximumSelections":2}""",
                    )
                updated.status shouldBe Status.OK
                val edited = updated.body(CategoryDto.serializer())
                edited.revision shouldBe category.revision + 1
                edited.category shouldBe OfferingCategoryDto("seasonal", "Seasonal Choices", "Optional specials", 0, 2)
                app.http(Request(Method.GET, path)).body(CategoryDto.serializer()) shouldBe edited
                val retired = app.adminRequest(Method.DELETE, "$path?expectedRevision=${edited.revision}")
                retired.status shouldBe Status.OK
                val retiredRevision = retired.body(CatalogRevisionDto.serializer()).revision
                retiredRevision shouldBe edited.revision + 1
                app.http(Request(Method.GET, path)).status shouldBe Status.NOT_FOUND
                app.adminGet("/offering-catalog/retired/categories").let {
                    it.status shouldBe Status.OK
                    val discovery = it.body(RetiredCategoriesDto.serializer())
                    discovery.revision shouldBe retiredRevision
                    discovery.categories.single().lastSeenRevision shouldBe edited.revision
                    discovery.categories.single().category shouldBe edited.category
                }
                val restored =
                    app.adminPost(
                        "$path/restore",
                        """{"expectedRevision":$retiredRevision,"displayName":"Restored Seasonal","maximumSelections":3}""",
                    )
                restored.status shouldBe Status.OK
                val restoredCategory = restored.body(CategoryDto.serializer())
                restoredCategory.revision shouldBe retiredRevision + 1
                restoredCategory.category shouldBe OfferingCategoryDto("seasonal", "Restored Seasonal", maximumSelections = 3)
                app.http(Request(Method.GET, path)).body(CategoryDto.serializer()) shouldBe restoredCategory
                app
                    .adminGet("/offering-catalog/retired/categories")
                    .body(RetiredCategoriesDto.serializer())
                    .categories
                    .shouldBeEmpty()
            }
        }
    })
