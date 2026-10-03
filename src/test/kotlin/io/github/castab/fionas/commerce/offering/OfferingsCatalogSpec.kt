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
import io.github.castab.commerce.runtime.offering.OfferingsBatchDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.commerce.runtime.offering.OfferingsDto
import io.github.castab.commerce.runtime.offering.RetiredCategoriesDto
import io.github.castab.commerce.runtime.offering.RetiredOfferingsDto
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.pricingBody
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

/** Fiona's mounted runtime capability, current-only catalog, and atomic batches over PostgreSQL. */
class OfferingsCatalogSpec :
    FunSpec({
        fun <T : Any> Response.body(serializer: KSerializer<T>): T = CommerceJson.asA(bodyString(), serializer)

        fun Response.catalog() = body(OfferingsCatalogDto.serializer())

        fun TestApplication.catalog() = http(Request(Method.GET, "/offering-catalog")).catalog()

        fun TestApplication.batch(
            method: Method,
            path: String,
            revision: Int,
            offerings: List<OfferingDto>,
        ) = adminRequest(
            method,
            path,
            CommerceJson.json.encodeToString(OfferingsBatchDto.serializer(), OfferingsBatchDto(revision, offerings)),
        )

        fun item(
            key: String,
            price: OfferingPriceDto? = null,
        ) = OfferingDto(
            key,
            "soft-serve-flavor",
            key,
            price = price,
            selectionState = OfferingSelectionStateDto.ENABLED,
            availability = OfferingAvailabilityDto.AVAILABLE,
        )

        test("startup never initializes a catalog; initialization creates revision 1 exactly once") {
            TestApplication.create().use { app ->
                app.http(Request(Method.GET, "/offering-catalog")).status shouldBe Status.NOT_FOUND
                app.database.count("commerce.offerings_catalogs") shouldBe 0
                val created = app.adminPost("/offering-catalog")
                created.status shouldBe Status.CREATED
                created.catalog().let {
                    it.catalogId shouldBe FIONA_OFFERINGS_CATALOG_ID.value.toString()
                    it.revision shouldBe 1
                    it.previousRevision.shouldBeNull()
                    it.categories.shouldBeEmpty()
                }
                app.adminPost("/offering-catalog").status shouldBe Status.CONFLICT
            }
        }

        test("one add batch creates one revision with ordered offerings and current-only reads") {
            TestApplication.create().use { app ->
                val revision = app.createAcceptanceCatalog()
                revision shouldBe 5
                app.database.count("commerce.offerings_catalogs") shouldBe 1
                val latest = app.catalog()
                latest.categories
                    .first()
                    .offerings
                    .map { it.key } shouldContainExactly listOf("vanilla", "chocolate", "horchata")
                val read = app.http(Request(Method.GET, "/offering-catalog/offerings/vanilla"))
                read.status shouldBe Status.OK
                read.body(OfferingResultDto.serializer()).revision shouldBe revision
                read.bodyString().contains("\"price\"") shouldBe false
                app
                    .http(Request(Method.GET, "/offering-catalog/categories/soft-serve-flavor/offerings"))
                    .body(CategoryOfferingsDto.serializer())
                    .offerings shouldBe latest.categories.first().offerings
                app
                    .http(Request(Method.GET, "/offering-catalog/offerings"))
                    .body(OfferingsDto.serializer())
                    .offerings shouldBe latest.categories.flatMap { it.offerings }
                app.http(Request(Method.GET, "/offering-catalog/revisions/$revision")).status shouldBe Status.NOT_FOUND
                app.adminRequest(Method.PUT, "/offering-catalog/offerings/vanilla", "{}").status shouldBe Status.METHOD_NOT_ALLOWED
                app.adminRequest(Method.DELETE, "/offering-catalog/offerings/vanilla").status shouldBe Status.METHOD_NOT_ALLOWED
                app.adminPost("/offering-catalog/offerings/vanilla/restore", "{}").status shouldBe Status.NOT_FOUND
            }
        }

        test("all price forms and option text round trip in a single batch") {
            TestApplication.create().use { app ->
                val revision = app.createAcceptanceCatalog()
                val values =
                    listOf(
                        item("fixed", OfferingPriceDto("FIXED", "120.00", "USD")),
                        item("quantity", OfferingPriceDto("PER_QUANTITY", "0.75", "USD", dimension = "guest")),
                        item("duration", OfferingPriceDto("PER_DURATION", "50.00", "USD", interval = "PT1H")),
                    ).map { it.copy(badge = "Special", statusNote = "Ask staff", infoNote = "Contains nuts") }
                val added = app.batch(Method.POST, "/offering-catalog/offerings", revision, values)
                added.status shouldBe Status.CREATED
                added.body(OfferingsDto.serializer()) shouldBe OfferingsDto(revision + 1, values)
                values.forEach { value ->
                    app
                        .http(Request(Method.GET, "/offering-catalog/offerings/${value.key}"))
                        .body(OfferingResultDto.serializer())
                        .offering shouldBe value
                }
            }
        }

        test("batch update, retire, discover, and restore retain keys and last representations") {
            TestApplication.create().use { app ->
                val original = app.createAcceptanceCatalog()
                val values =
                    listOf(item("vanilla"), item("horchata")).map {
                        it.copy(displayName = "Updated ${it.key}", badge = "Special", statusNote = "Here now", infoNote = "A lasting fact")
                    }
                val updated = app.batch(Method.PUT, "/offering-catalog/offerings", original, values)
                updated.status shouldBe Status.OK
                val edited = updated.body(OfferingsDto.serializer())
                edited shouldBe OfferingsDto(original + 1, values)
                app
                    .catalog()
                    .categories
                    .first()
                    .offerings
                    .map { it.key } shouldBe listOf("vanilla", "chocolate", "horchata")
                val retired =
                    app.adminPost(
                        "/offering-catalog/offerings/retire",
                        """{"expectedRevision":${edited.revision},"keys":["vanilla","horchata"]}""",
                    )
                retired.status shouldBe Status.OK
                val retiredRevision = retired.body(CatalogRevisionDto.serializer()).revision
                retiredRevision shouldBe edited.revision + 1
                app
                    .catalog()
                    .categories
                    .first()
                    .offerings
                    .map { it.key } shouldBe listOf("chocolate")
                val discovered = app.adminGet("/offering-catalog/retired/offerings").body(RetiredOfferingsDto.serializer())
                discovered.offerings.map { it.offering.key } shouldBe listOf("horchata", "vanilla")
                discovered.offerings.forEach { it.lastSeenRevision shouldBe edited.revision }
                discovered.offerings.map { it.offering } shouldBe values.reversed()
                app.batch(Method.POST, "/offering-catalog/offerings", retiredRevision, values).status shouldBe Status.CONFLICT
                val restoredValues = values.map { it.copy(badge = null, statusNote = null, infoNote = null) }
                val restored = app.batch(Method.POST, "/offering-catalog/offerings/restore", retiredRevision, restoredValues)
                restored.status shouldBe Status.OK
                restored.body(OfferingsDto.serializer()) shouldBe OfferingsDto(retiredRevision + 1, restoredValues)
                app
                    .catalog()
                    .categories
                    .first()
                    .offerings
                    .map { it.key } shouldBe listOf("chocolate", "vanilla", "horchata")
                app
                    .adminGet("/offering-catalog/retired/offerings")
                    .body(RetiredOfferingsDto.serializer())
                    .offerings
                    .shouldBeEmpty()
                // Complete replacements clear omitted optional notes on both update and restore.
                val cleared = app.batch(Method.PUT, "/offering-catalog/offerings", retiredRevision + 1, listOf(item("vanilla")))
                cleared.status shouldBe Status.OK
                cleared.bodyString().contains("\"badge\"") shouldBe false
                val preview =
                    app.http(
                        Request(Method.POST, "/estimate-preview")
                            .asFionasWeb(app)
                            .header("Content-Type", "application/json")
                            .body(pricingBody(original)),
                    )
                preview.status shouldBe Status.CONFLICT
                preview.body(ErrorResponse.serializer()).code shouldBe "CATALOG_REVISION_STALE"
                preview.header("Cache-Control") shouldBe "no-store"
                app.database.count("commerce.offerings_catalogs") shouldBe 1
            }
        }

        test("empty, duplicate, invalid, missing-precondition, and stale batches save nothing") {
            TestApplication.create().use { app ->
                val revision = app.createAcceptanceCatalog()
                val before = app.catalog()
                listOf(
                    emptyList<OfferingDto>() to Status.UNPROCESSABLE_ENTITY,
                    listOf(item("new"), item("new")) to Status.UNPROCESSABLE_ENTITY,
                    listOf(item("new"), item("bad").copy(category = "missing")) to Status.NOT_FOUND,
                ).forEach { (values, status) ->
                    app.batch(Method.POST, "/offering-catalog/offerings", revision, values).status shouldBe status
                    app.catalog() shouldBe before
                }
                app
                    .batch(Method.POST, "/offering-catalog/offerings", revision - 1, listOf(item("new")))
                    .status shouldBe Status.CONFLICT
                app.adminPost("/offering-catalog/offerings", """{"offerings":[]}""").status shouldBe Status.BAD_REQUEST
                app
                    .batch(Method.PUT, "/offering-catalog/offerings", revision, listOf(item("vanilla"), item("missing")))
                    .status shouldBe Status.NOT_FOUND
                app
                    .adminPost(
                        "/offering-catalog/offerings/retire",
                        """{"expectedRevision":$revision,"keys":["vanilla","missing"]}""",
                    ).status shouldBe Status.NOT_FOUND
                app
                    .batch(Method.POST, "/offering-catalog/offerings/restore", revision, listOf(item("vanilla")))
                    .status shouldBe Status.CONFLICT
                app.catalog() shouldBe before
                app.database.count("commerce.offerings_catalogs") shouldBe 1
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
