package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Duration

/*
 * Fiona's persisted representation of one complete [FionasPricingInputs], the `pricing_inputs`
 * jsonb of `fionas.inquiries` (what the customer requested) and of
 * `fionas.financial_document_pricing` (what a staff-priced document version was priced from):
 *
 * ```
 * {"catalogRevision": 20,
 *  "context": {"guestCount": 75, "guestCountIsMinimum": false, "durationMinutes": 120},
 *  "selections": [{"categoryKey": "soft-serve-flavor", "offeringKeys": ["soft-vanilla", "soft-horchata"]},
 *                 {"categoryKey": "topping", "offeringKeys": []}]}
 * ```
 *
 * catalogRevision records the revision used for pricing; catalogs retain only current state.
 * It is durable historical state, owned by Fiona and separate from both the HTTP DTOs and
 * commerce-runtime's own (internal) snapshot JSON, so none constrains the others. Domain types
 * stay unaware of it: only Fiona's repositories encode and restore through these functions.
 *
 * Every property is required and none is nullable. `selections` keeps the submitted block order
 * and each block its submitted offering order; an explicitly empty block is an empty array.
 * The duration is a whole number of minutes. Inputs never carry lines, amounts, or totals.
 *
 * Decoding is strict, because a stored value is a historical fact that is never reinterpreted
 * or repaired: unknown, missing, or `null` properties, a JSON value of the wrong type (`"75"`
 * where an integer is required), and values the domain rejects all fail. Restoring goes through
 * the domain value types, so their invariants are checked again on every read, as are the
 * structural rules the former relational columns enforced: a revision of at least 1, at least
 * one guest, a positive whole-minute duration, no category block twice, and no offering twice
 * in one block (the engine's DUPLICATE_CATEGORY and DUPLICATE_OFFERING, which priced inputs
 * have already passed).
 */

@Serializable
private class PricingInputsJson(
    @Serializable(with = StrictIntSerializer::class) val catalogRevision: Int,
    val context: PricingContextJson,
    val selections: List<CategorySelectionJson>,
)

@Serializable
private class PricingContextJson(
    @Serializable(with = StrictIntSerializer::class) val guestCount: Int,
    @Serializable(with = StrictBooleanSerializer::class) val guestCountIsMinimum: Boolean,
    @Serializable(with = StrictIntSerializer::class) val durationMinutes: Int,
)

@Serializable
private class CategorySelectionJson(
    @Serializable(with = StrictStringSerializer::class) val categoryKey: String,
    val offeringKeys: List<
        @Serializable(with = StrictStringSerializer::class)
        String,
    >,
)

private val persistedJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

/** These inputs in Fiona's persisted JSON, after checking they satisfy its invariants. */
internal fun FionasPricingInputs.toPersistedJson(): String {
    val minutes = context.duration.toMinutes()
    check(Duration.ofMinutes(minutes) == context.duration) { "A pricing duration is a whole number of minutes, not ${context.duration}" }
    val persisted =
        PricingInputsJson(
            catalogRevision = catalogRevision.number,
            context =
                PricingContextJson(
                    guestCount = context.guestCount,
                    guestCountIsMinimum = context.guestCountIsMinimum,
                    durationMinutes = Math.toIntExact(minutes),
                ),
            selections =
                selections.categories.map { block ->
                    CategorySelectionJson(block.category.value, block.offerings.map(OfferingKey::value))
                },
        )
    // Write only what reading would accept, so no invalid value is ever stored.
    persisted.restore()
    return persistedJson.encodeToString(PricingInputsJson.serializer(), persisted)
}

/**
 * Restores the [FionasPricingInputs] stored as [json], or fails with an [IllegalStateException]
 * naming [owner] (the row it was read from) when the stored value is malformed, has an
 * unsupported shape, or breaks a domain invariant. Nothing is defaulted, coerced, or repaired.
 */
internal fun restorePersistedPricingInputs(
    owner: String,
    json: String,
): FionasPricingInputs =
    try {
        persistedJson.decodeFromString(PricingInputsJson.serializer(), json).restore()
    } catch (e: Exception) {
        throw IllegalStateException("Malformed persisted pricing inputs of $owner: ${e.message}", e)
    }

private fun PricingInputsJson.restore(): FionasPricingInputs {
    require(context.guestCount >= 1) { "guestCount must be at least 1, got ${context.guestCount}" }
    require(context.durationMinutes >= 1) { "durationMinutes must be positive, got ${context.durationMinutes}" }
    val categories = selections.map { OfferingCategoryKey(it.categoryKey) }
    categories.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.let { duplicates ->
        require(duplicates.isEmpty()) { "category ${duplicates.joinToString { it.value }} is selected more than once" }
    }
    return FionasPricingInputs(
        catalogRevision = OfferingsRevision.of(catalogRevision),
        selections =
            OfferingSelections(
                selections.zip(categories) { block, category ->
                    val offerings = block.offeringKeys.map(::OfferingKey)
                    require(offerings.toSet().size == offerings.size) {
                        "category ${category.value} selects an offering more than once"
                    }
                    OfferingCategorySelection(category, offerings)
                },
            ),
        context =
            FionasOfferingsContext(
                guestCount = context.guestCount,
                guestCountIsMinimum = context.guestCountIsMinimum,
                duration = Duration.ofMinutes(context.durationMinutes.toLong()),
            ),
    )
}

/**
 * An `Int` that must be a JSON number. kotlinx.serialization otherwise also accepts the quoted
 * text `"75"`, which would let a differently typed stored value pass.
 */
internal object StrictIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FionaStrictInt", PrimitiveKind.INT)

    override fun serialize(
        encoder: Encoder,
        value: Int,
    ) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        val element = strictPrimitive(decoder, "an integer")
        require(!element.isString) { "Expected an integer JSON number but found $element" }
        return requireNotNull(element.content.toIntOrNull()) { "Expected an integer but found ${element.content}" }
    }
}

/** A `Boolean` that must be a JSON `true` or `false`, never the text `"true"`. */
internal object StrictBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FionaStrictBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = strictPrimitive(decoder, "a boolean")
        require(!element.isString) { "Expected a JSON boolean but found $element" }
        return requireNotNull(element.booleanOrNull) { "Expected a JSON boolean but found $element" }
    }
}

/** A `String` that must be a JSON string, never a number, boolean, or `null`. */
internal object StrictStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FionaStrictString", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val element = strictPrimitive(decoder, "a string")
        require(element.isString) { "Expected a JSON string but found $element" }
        return element.content
    }
}

private fun strictPrimitive(
    decoder: Decoder,
    expected: String,
): JsonPrimitive {
    val element = (decoder as JsonDecoder).decodeJsonElement()
    require(element is JsonPrimitive && element !is JsonNull) { "Expected $expected but found $element" }
    return element
}
