package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.offering.CatalogRevisionDto
import io.github.castab.commerce.runtime.offering.CategoryDto
import io.github.castab.commerce.runtime.offering.OfferingAvailabilityDto
import io.github.castab.commerce.runtime.offering.OfferingDto
import io.github.castab.commerce.runtime.offering.OfferingPriceDto
import io.github.castab.commerce.runtime.offering.OfferingResultDto
import io.github.castab.commerce.runtime.offering.OfferingSelectionStateDto
import io.github.castab.commerce.runtime.offering.OfferingsBatchDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.commerce.runtime.offering.OfferingsDto
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.UUID

/** The six toppings of the acceptance catalog; the first four are included in the ice cream service. */
val TOPPINGS = listOf("sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough")

/** A catalog price of [amount] USD per guest. */
fun perGuest(amount: String) = """{"kind":"PER_QUANTITY","amount":"$amount","currency":"USD","dimension":"guest"}"""

/**
 * Appends an offering through commerce-runtime's Offerings API, as an administrator would,
 * against the caller's observed [expectedRevision], and returns the new catalog revision.
 */
fun TestApplication.addOffering(
    expectedRevision: Int,
    key: String,
    category: String,
    displayName: String,
    price: String? = null,
    description: String? = null,
    selectionState: OfferingSelectionStateDto = OfferingSelectionStateDto.ENABLED,
    availability: OfferingAvailabilityDto = OfferingAvailabilityDto.AVAILABLE,
    badge: String? = null,
    statusNote: String? = null,
    infoNote: String? = null,
): Int =
    addOfferings(
        expectedRevision,
        listOf(
            OfferingDto(
                key,
                category,
                displayName,
                description,
                price?.let { CommerceJson.asA(it, OfferingPriceDto.serializer()) },
                selectionState,
                availability,
                badge,
                statusNote,
                infoNote,
            ),
        ),
    )

/** A complete batch uses the caller's revision and advances it once. */
fun TestApplication.addOfferings(
    expectedRevision: Int,
    offerings: List<OfferingDto>,
): Int {
    val body = CommerceJson.json.encodeToString(OfferingsBatchDto.serializer(), OfferingsBatchDto(expectedRevision, offerings))
    val response = adminPost("/offering-catalog/offerings", body)
    check(response.status == Status.CREATED) { "Adding offerings failed: ${response.status} ${response.bodyString()}" }
    return CommerceJson.asA(response.bodyString(), OfferingsDto.serializer()).revision
}

/** Changes only state facts through a one-item batch, preserving all other properties. */
fun TestApplication.setOfferingState(
    expectedRevision: Int,
    key: String,
    selectionState: OfferingSelectionStateDto? = null,
    availability: OfferingAvailabilityDto? = null,
): Int {
    val original = CommerceJson.asA(adminGet("/offering-catalog/offerings/$key").bodyString(), OfferingResultDto.serializer()).offering
    val mutation =
        OfferingsBatchDto(
            expectedRevision,
            listOf(
                original.copy(
                    selectionState = selectionState ?: original.selectionState,
                    availability = availability ?: original.availability,
                ),
            ),
        )
    val response =
        adminRequest(
            Method.PUT,
            "/offering-catalog/offerings",
            CommerceJson.json.encodeToString(OfferingsBatchDto.serializer(), mutation),
        )
    check(response.status == Status.OK) { "Updating offering state failed: ${response.status} ${response.bodyString()}" }
    return CommerceJson.asA(response.bodyString(), OfferingsDto.serializer()).revision
}

/**
 * Fiona's acceptance catalog, entered through commerce-runtime's Offerings API: soft-serve
 * flavors (Horchata at `$0.50` per guest), six toppings, and cones (Waffle cones at `$0.75`
 * per guest). Returns its revision, from which [pricingBody]'s defaults price `$681.25`.
 */
fun TestApplication.createAcceptanceCatalog(): Int {
    val created = adminPost("/offering-catalog")
    check(created.status == Status.CREATED) { "The catalog already exists" }
    var revision = CommerceJson.asA(created.bodyString(), OfferingsCatalogDto.serializer()).revision
    listOf(
        """{"key":"soft-serve-flavor","displayName":"Soft Serve","minimumSelections":1,"maximumSelections":2}""",
        """{"key":"topping","displayName":"Toppings","minimumSelections":4,"maximumSelections":6}""",
        """{"key":"cone-option","displayName":"Cones","minimumSelections":1,"maximumSelections":1}""",
    ).forEach {
        val response = adminPost("/offering-catalog/categories", """{"expectedRevision":$revision,${it.drop(1)}""")
        check(response.status == Status.CREATED) { "Adding category failed: ${response.status} ${response.bodyString()}" }
        revision = CommerceJson.asA(response.bodyString(), CategoryDto.serializer()).revision
    }

    fun item(
        key: String,
        category: String,
        name: String,
        price: String? = null,
        description: String? = null,
    ) = OfferingDto(
        key,
        category,
        name,
        description,
        price?.let { CommerceJson.asA(it, OfferingPriceDto.serializer()) },
        OfferingSelectionStateDto.ENABLED,
        OfferingAvailabilityDto.AVAILABLE,
    )
    val offerings =
        listOf(
            item("vanilla", "soft-serve-flavor", "Vanilla"),
            item("chocolate", "soft-serve-flavor", "Chocolate"),
            item("horchata", "soft-serve-flavor", "Horchata", perGuest("0.50"), "Premium soft serve"),
        ) +
            TOPPINGS.zip(listOf("Sprinkles", "Oreos", "Strawberries", "Brownies", "Gummy Bears", "Cookie Dough")).map { (key, name) ->
                item(key, "topping", name)
            } +
            listOf(
                item("cup", "cone-option", "Cups"),
                item("waffle-cone", "cone-option", "Waffle cones", perGuest("0.75")),
            )
    return addOfferings(revision, offerings)
}

/**
 * A JSON body of commercial pricing inputs, as `/estimate-preview`, persisted estimates, and
 * change orders take them. With [expectedVersion], a change-order body.
 */
fun pricingBody(
    revision: Int,
    guests: Int = 75,
    minutes: Int = 120,
    softServe: List<String> = listOf("vanilla", "horchata"),
    toppings: List<String> = TOPPINGS,
    cones: List<String> = listOf("waffle-cone"),
    expectedVersion: Int? = null,
    extra: String = "",
): String {
    fun block(
        category: String,
        offerings: List<String>,
    ) = """{"category":"$category","offerings":[${offerings.joinToString(",") { "\"$it\"" }}]}"""
    val blocks = listOf(block("soft-serve-flavor", softServe), block("topping", toppings), block("cone-option", cones))
    return "{" + (expectedVersion?.let { "\"expectedVersion\":$it," } ?: "") +
        """"catalogRevision":$revision,"guestCount":$guests,"durationMinutes":$minutes,"selections":[${blocks.joinToString(",")}]""" +
        extra + "}"
}

/**
 * Records an inquiry through the public API and returns its id. Like a real client, it configures
 * the service against the catalog's current revision ([pricing] of that revision), so it is
 * priced and has its initial Estimate, as every accepted inquiry does.
 */
fun TestApplication.createInquiry(
    email: String = "jane-${UUID.randomUUID()}@example.com",
    pricing: (currentRevision: Int) -> String = { pricingBody(it) },
): String {
    val catalog = http(Request(Method.GET, "/offering-catalog"))
    check(catalog.status == Status.OK) { "An inquiry needs Fiona's catalog: ${catalog.status}" }
    val current = CommerceJson.asA(catalog.bodyString(), OfferingsCatalogDto.serializer()).revision
    val response =
        http(
            Request(Method.POST, "/inquiries")
                .withSubmissionKey()
                .asFionasWeb(this)
                .header("Content-Type", "application/json")
                .body(
                    """{"name":"Jane Doe","email":"$email","zipCode":"92626","eventDate":"2026-12-05","eventType":"BIRTHDAY",""" +
                        """"message":"Ice cream for a birthday.","pricingInputs":${pricing(current)}}""",
                ),
        )
    check(response.status == Status.CREATED) { "Recording an inquiry failed: ${response.status} ${response.bodyString()}" }
    return checkNotNull(response.header("Location")).substringAfterLast('/')
}

/** The id of [inquiryId]'s canonical initial Estimate, which every accepted inquiry has exactly one of. */
fun TestApplication.initialEstimateOf(inquiryId: String): String =
    database
        .strings(
            "SELECT document_id FROM fionas.inquiry_financial_documents " +
                "WHERE inquiry_id = '$inquiryId' AND purpose = 'INITIAL_ESTIMATE'",
        ).single()

/** Replaces one offering's complete definition through the runtime API, as an administrator edits it. */
fun TestApplication.updateOffering(
    expectedRevision: Int,
    key: String,
    change: (OfferingDto) -> OfferingDto,
): Int {
    val original = CommerceJson.asA(adminGet("/offering-catalog/offerings/$key").bodyString(), OfferingResultDto.serializer()).offering
    val mutation = OfferingsBatchDto(expectedRevision, listOf(change(original)))
    val response =
        adminRequest(Method.PUT, "/offering-catalog/offerings", CommerceJson.json.encodeToString(OfferingsBatchDto.serializer(), mutation))
    check(response.status == Status.OK) { "Updating an offering failed: ${response.status} ${response.bodyString()}" }
    return CommerceJson.asA(response.bodyString(), OfferingsDto.serializer()).revision
}

/** Retires offerings through the runtime API; their keys stay reserved but leave the current catalog. */
fun TestApplication.retireOfferings(
    expectedRevision: Int,
    vararg keys: String,
): Int {
    val response =
        adminPost(
            "/offering-catalog/offerings/retire",
            """{"expectedRevision":$expectedRevision,"keys":[${keys.joinToString(",") { "\"$it\"" }}]}""",
        )
    check(response.status == Status.OK) { "Retiring offerings failed: ${response.status} ${response.bodyString()}" }
    return CommerceJson.asA(response.bodyString(), CatalogRevisionDto.serializer()).revision
}

/** The current revision of Fiona's catalog, as any client observes it. */
fun TestApplication.currentCatalogRevision(): Int =
    CommerceJson.asA(http(Request(Method.GET, "/offering-catalog")).bodyString(), OfferingsCatalogDto.serializer()).revision
