package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.offering.CategoryDto
import io.github.castab.commerce.runtime.offering.OfferingAvailabilityDto
import io.github.castab.commerce.runtime.offering.OfferingMutationDto
import io.github.castab.commerce.runtime.offering.OfferingResultDto
import io.github.castab.commerce.runtime.offering.OfferingSelectionStateDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
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
): Int {
    val optional = listOfNotNull(description?.let { ",\"description\":\"$it\"" }, price?.let { ",\"price\":$it" }).joinToString("")
    val body =
        """{"expectedRevision":$expectedRevision,"key":"$key","category":"$category","displayName":"$displayName",""" +
            """"selectionState":"$selectionState","availability":"$availability"$optional}"""
    val response = adminPost("/offering-catalog/offerings", body)
    check(response.status == Status.CREATED) { "Adding offering $key failed: ${response.status} ${response.bodyString()}" }
    return CommerceJson.asA(response.bodyString(), OfferingResultDto.serializer()).revision
}

/** Changes only the requested state facts through the runtime HTTP contract, preserving other properties. */
fun TestApplication.setOfferingState(
    expectedRevision: Int,
    key: String,
    selectionState: OfferingSelectionStateDto? = null,
    availability: OfferingAvailabilityDto? = null,
): Int {
    val path = "/offering-catalog/offerings/$key"
    val original = CommerceJson.asA(adminGet(path).bodyString(), OfferingResultDto.serializer()).offering
    val mutation =
        OfferingMutationDto(
            expectedRevision,
            original.category,
            original.displayName,
            original.description,
            original.price,
            selectionState ?: original.selectionState,
            availability ?: original.availability,
        )
    val response = adminRequest(Method.PUT, path, CommerceJson.json.encodeToString(OfferingMutationDto.serializer(), mutation))
    check(response.status == Status.OK) { "Updating offering state failed: ${response.status} ${response.bodyString()}" }
    return CommerceJson.asA(response.bodyString(), OfferingResultDto.serializer()).revision
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
    revision = addOffering(revision, "vanilla", "soft-serve-flavor", "Vanilla")
    revision = addOffering(revision, "chocolate", "soft-serve-flavor", "Chocolate")
    revision = addOffering(revision, "horchata", "soft-serve-flavor", "Horchata", perGuest("0.50"), description = "Premium soft serve")
    TOPPINGS.zip(listOf("Sprinkles", "Oreos", "Strawberries", "Brownies", "Gummy Bears", "Cookie Dough")).forEach { (key, name) ->
        revision = addOffering(revision, key, "topping", name)
    }
    revision = addOffering(revision, "cup", "cone-option", "Cups")
    return addOffering(revision, "waffle-cone", "cone-option", "Waffle cones", perGuest("0.75"))
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

/** Records an inquiry through the public API and returns its id. */
fun TestApplication.createInquiry(email: String = "jane-${UUID.randomUUID()}@example.com"): String {
    val response =
        http(
            Request(Method.POST, "/inquiries")
                .withSubmissionKey()
                .withUiKey()
                .header("Content-Type", "application/json")
                .body(
                    """{"name":"Jane Doe","email":"$email","zipCode":"92626","eventDate":"2026-12-05","eventType":"BIRTHDAY","message":"Ice cream for a birthday."}""",
                ),
        )
    check(response.status == Status.CREATED) { "Recording an inquiry failed: ${response.status}" }
    return checkNotNull(response.header("Location")).substringAfterLast('/')
}
