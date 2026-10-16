package io.github.castab.fionas.commerce.events

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private fun contract() = Json.parseToJsonElement(File("asyncapi.json").readText()).jsonObject

/** Validate the exact schema's closed objects, required keys, types, enums, patterns and formats. */
fun validateInquiryEventContract(payload: String) {
    fun validate(
        value: JsonElement,
        schema: JsonObject,
    ) {
        val type = schema.getValue("type")
        val types =
            if (type is JsonArray) {
                type.map { it.jsonPrimitive.content }
            } else {
                listOf(
                    type.jsonPrimitive.content,
                )
            }
        if (value == JsonNull) {
            types.contains("null") shouldBe true
            return
        }
        when (types.first()) {
            "object" -> {
                val obj = value.jsonObject
                val properties = schema.getValue("properties").jsonObject
                obj.keys shouldBe
                    schema
                        .getValue("required")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .toSet()
                schema.getValue("additionalProperties") shouldBe JsonPrimitive(false)
                obj.forEach { (key, child) -> validate(child, properties.getValue(key).jsonObject) }
            }
            "integer" -> {
                value.jsonPrimitive.isString shouldBe false
                val number = checkNotNull(value.jsonPrimitive.longOrNull)
                schema["minimum"]?.jsonPrimitive?.longOrNull?.let { (number >= it) shouldBe true }
                schema["maximum"]?.jsonPrimitive?.longOrNull?.let { (number <= it) shouldBe true }
            }
            "boolean" -> {
                checkNotNull(value.jsonPrimitive.booleanOrNull)
                value.jsonPrimitive.isString shouldBe false
            }
            "array" -> {
                val items = value.jsonArray
                schema["minItems"]?.jsonPrimitive?.longOrNull?.let { (items.size >= it) shouldBe true }
                schema["maxItems"]?.jsonPrimitive?.longOrNull?.let { (items.size <= it) shouldBe true }
                items.forEach { validate(it, schema.getValue("items").jsonObject) }
            }
            "string" -> value.jsonPrimitive.isString shouldBe true
            else -> error("Unsupported event contract type")
        }
        schema["enum"]?.jsonArray?.contains(value)?.let { it shouldBe true }
        schema["pattern"]?.jsonPrimitive?.content?.let { Regex(it).matches(value.jsonPrimitive.content) shouldBe true }
        when (schema["format"]?.jsonPrimitive?.content) {
            "uuid" -> UUID.fromString(value.jsonPrimitive.content)
            "date-time" -> Instant.parse(value.jsonPrimitive.content)
            "date" -> LocalDate.parse(value.jsonPrimitive.content)
        }
    }
    validate(
        Json.parseToJsonElement(payload),
        contract()
            .getValue("components")
            .jsonObject
            .getValue("schemas")
            .jsonObject
            .getValue("InquiryPersistedV3")
            .jsonObject,
    )
}

class InquiryEventContractSpec :
    FunSpec({
        test("AsyncAPI example serializes exactly as the Kotlin event and satisfies its complete payload schema") {
            val document = contract()
            document.getValue("asyncapi").jsonPrimitive.content shouldBe "3.0.0"
            val channel =
                document
                    .getValue("channels")
                    .jsonObject
                    .getValue("inquiryPersistedV3")
                    .jsonObject
            channel.getValue("address").jsonPrimitive.content shouldBe INQUIRY_PERSISTED_SUBJECT
            channel.getValue("x-jetstream-stream").jsonPrimitive.content shouldBe FIONA_EVENTS_STREAM
            val message =
                document
                    .getValue("components")
                    .jsonObject
                    .getValue("messages")
                    .jsonObject
                    .getValue("InquiryPersistedV3")
                    .jsonObject
            val example =
                message
                    .getValue("examples")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("payload")
            val dto = Json.decodeFromJsonElement(InquiryPersistedV3.serializer(), example)
            val serialized = Json.encodeToString(InquiryPersistedV3.serializer(), dto)
            Json.parseToJsonElement(serialized) shouldBe example
            validateInquiryEventContract(serialized)
            val root = example.jsonObject
            shouldThrowAny { validateInquiryEventContract(JsonObject(root - "aggregate").toString()) }
            shouldThrowAny { validateInquiryEventContract(JsonObject(root + ("unexpected" to JsonPrimitive(true))).toString()) }
            shouldThrowAny { validateInquiryEventContract(JsonObject(root + ("schemaVersion" to JsonPrimitive(2))).toString()) }
            shouldThrowAny { validateInquiryEventContract(JsonObject(root + ("id" to JsonNull)).toString()) }
            val headers =
                message
                    .getValue("headers")
                    .jsonObject
                    .getValue("required")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet()
            headers shouldBe
                setOf(
                    "Nats-Msg-Id",
                    "Nats-Expected-Stream",
                    PRODUCER_HEADER,
                    AGGREGATE_TYPE_HEADER,
                    AGGREGATE_ID_HEADER,
                    AGGREGATE_SEQUENCE_HEADER,
                )
        }
    })
