package io.github.castab.fionas.commerce.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        when (schema.getValue("type").jsonPrimitive.content) {
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
            "integer" -> value.jsonPrimitive.intOrNull shouldBe 2
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
            .getValue("InquiryPersistedV2")
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
                    .getValue("inquiryPersistedV2")
                    .jsonObject
            channel.getValue("address").jsonPrimitive.content shouldBe INQUIRY_PERSISTED_SUBJECT
            channel.getValue("x-jetstream-stream").jsonPrimitive.content shouldBe FIONA_EVENTS_STREAM
            val message =
                document
                    .getValue("components")
                    .jsonObject
                    .getValue("messages")
                    .jsonObject
                    .getValue("InquiryPersistedV2")
                    .jsonObject
            val example =
                message
                    .getValue("examples")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("payload")
            val dto = Json.decodeFromJsonElement(InquiryPersistedV2.serializer(), example)
            val serialized = Json.encodeToString(InquiryPersistedV2.serializer(), dto)
            serialized shouldBe
                """{"schemaVersion":2,"type":"fionas.inquiry.persisted","id":"7d4e9d50-4958-46d2-bcab-e3e921cceab7","correlationId":"Case_Sensitive-key-001","occurredAt":"2026-10-15T18:00:00.123456Z","source":"fionas-commerce","data":{"inquiryId":"0a6e8577-e811-487f-aa5d-3d30b74b25bd","customer":{"name":"Jane Doe","email":"jane@example.com"},"eventDate":"2026-12-05"}}"""
            Json.parseToJsonElement(serialized) shouldBe example
            validateInquiryEventContract(serialized)
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
