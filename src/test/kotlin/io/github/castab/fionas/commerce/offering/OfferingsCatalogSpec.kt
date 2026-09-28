package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.offering.CategoryDto
import io.github.castab.commerce.runtime.offering.CategoryOfferingsDto
import io.github.castab.commerce.runtime.offering.OfferingCategoryDto
import io.github.castab.commerce.runtime.offering.OfferingDto
import io.github.castab.commerce.runtime.offering.OfferingPriceDto
import io.github.castab.commerce.runtime.offering.OfferingResultDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.commerce.runtime.offering.OfferingsDto
import io.github.castab.fionas.commerce.testing.TestApplication
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
 * Fiona's catalog really is the runtime's append-only snapshot catalog. They build one
 * catalog history in order, on a database of their own, so each test continues the last.
 */
class OfferingsCatalogSpec :
    FunSpec({
        lateinit var application: TestApplication

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
            )
        val chocolate = OfferingDto(key = "chocolate", category = "soft-serve-flavor", displayName = "Chocolate")

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
            }

            post(
                "/offering-catalog/offerings",
                """
                {
                  "key": "vanilla",
                  "category": "soft-serve-flavor",
                  "displayName": "Vanilla",
                  "description": "Classic vanilla soft serve"
                }
                """.trimIndent(),
            ).let {
                it.status shouldBe Status.CREATED
                it.body(OfferingResultDto.serializer()) shouldBe OfferingResultDto(3, vanilla)
            }

            post(
                "/offering-catalog/offerings",
                """{"key": "chocolate", "category": "soft-serve-flavor", "displayName": "Chocolate"}""",
            ).let {
                it.status shouldBe Status.CREATED
                it.body(OfferingResultDto.serializer()) shouldBe OfferingResultDto(4, chocolate)
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
            post("/offering-catalog/categories", """{"key": "test-category", "displayName": "Test Category"}""").status shouldBe
                Status.CREATED
            val fixed =
                OfferingDto("fixed-item", "test-category", "Fixed Item", price = OfferingPriceDto("FIXED", "120.00", "USD"))
            val perQuantity =
                OfferingDto(
                    "waffle-cones",
                    "test-category",
                    "Waffle Cones",
                    price = OfferingPriceDto("PER_QUANTITY", "0.75", "USD", dimension = "guest"),
                )
            val perDuration =
                OfferingDto(
                    "service-hour",
                    "test-category",
                    "Service Hour",
                    price = OfferingPriceDto("PER_DURATION", "50.00", "USD", interval = "PT1H"),
                )
            listOf(
                """{"key": "fixed-item", "category": "test-category", "displayName": "Fixed Item",
                   "price": {"kind": "FIXED", "amount": "120.00", "currency": "USD"}}""",
                """{"key": "waffle-cones", "category": "test-category", "displayName": "Waffle Cones",
                   "price": {"kind": "PER_QUANTITY", "amount": "0.75", "currency": "USD", "dimension": "guest"}}""",
                """{"key": "service-hour", "category": "test-category", "displayName": "Service Hour",
                   "price": {"kind": "PER_DURATION", "amount": "50.00", "currency": "USD", "interval": "PT1H"}}""",
            ).forEach { post("/offering-catalog/offerings", it).status shouldBe Status.CREATED }

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

        test("the catalog is append-only: no route replaces or removes a category or an offering") {
            listOf(
                Request(Method.PUT, "/offering-catalog"),
                Request(Method.DELETE, "/offering-catalog"),
                Request(Method.PUT, "/offering-catalog/categories/soft-serve-flavor"),
                Request(Method.DELETE, "/offering-catalog/categories/soft-serve-flavor"),
                Request(Method.PUT, "/offering-catalog/offerings/vanilla"),
                Request(Method.PATCH, "/offering-catalog/offerings/vanilla"),
                Request(Method.DELETE, "/offering-catalog/offerings/vanilla"),
            ).forEach { request ->
                application.http(request).status shouldBe Status.METHOD_NOT_ALLOWED
            }
            get("/offering-catalog/offerings/vanilla").body(OfferingResultDto.serializer()).offering shouldBe vanilla
        }
    })
