package io.github.castab.fionas.commerce.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class IntakeEventContractSpec :
    FunSpec({
        test("minimal v2 diagnostic matches AsyncAPI and historical v1 keeps its original producer identity") {
            val document = Json.parseToJsonElement(File("asyncapi.json").readText()).jsonObject
            val components = document.getValue("components").jsonObject
            val messages = components.getValue("messages").jsonObject
            val schemas = components.getValue("schemas").jsonObject
            val payload =
                Json
                    .parseToJsonElement(
                        failureDiagnostic(TestDelivery(bytes = "{".toByteArray()), "invalid_event_json").second.decodeToString(),
                    ).jsonObject
            val example =
                messages
                    .getValue("InquiryProcessingFailedV2")
                    .jsonObject
                    .getValue("examples")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("payload")
                    .jsonObject
            payload shouldBe example
            val schema = schemas.getValue("InquiryProcessingFailedV2").jsonObject
            schema
                .getValue("required")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet() shouldBe payload.keys
            schema
                .getValue("properties")
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .single()
                .jsonPrimitive.content shouldBe "fionas-commerce"
            schemas
                .getValue("InquiryProcessingFailedV1")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .single()
                .jsonPrimitive.content shouldBe "fionas-event-workers"
            document
                .getValue("channels")
                .jsonObject
                .getValue("inquiryProcessingFailedV2")
                .jsonObject
                .getValue("address")
                .jsonPrimitive.content shouldBe INQUIRY_FAILURE_SUBJECT
            val producer = "https://raw.githubusercontent.com/castab/fionas-web/main/asyncapi.yaml"
            messages
                .getValue("InquirySubmittedV1")
                .jsonObject
                .getValue("payload")
                .jsonObject
                .getValue("\$ref")
                .jsonPrimitive.content shouldBe "$producer#/components/schemas/InquirySubmittedV1"
        }
    })
