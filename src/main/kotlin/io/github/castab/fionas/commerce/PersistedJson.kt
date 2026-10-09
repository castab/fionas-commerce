package io.github.castab.fionas.commerce

import kotlinx.serialization.KSerializer
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

/*
 * Strict decoding shared by Fiona's persisted jsonb representations (an inquiry's requested
 * service and an approved service plan). A stored value is a historical fact that is never
 * reinterpreted or repaired: unknown, missing, or `null` properties where none is allowed and
 * values of the wrong JSON type fail. These are durable data contracts, separate from the HTTP
 * DTOs; only the owning repositories use them.
 */

/** The JSON configuration of every persisted Fiona representation: nothing is defaulted, coerced or ignored. */
internal val persistedJson =
    Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
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
