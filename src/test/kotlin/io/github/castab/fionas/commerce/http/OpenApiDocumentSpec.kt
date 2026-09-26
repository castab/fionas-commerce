package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.fionaVersion
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.openapi.fionaOpenApiDocument
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the Fiona API's OpenAPI document says, rendered from the contract as the build
 * generates it (no database). [OpenApiRoutesSpec] proves the running application serves the
 * same document. An API change that changes these expectations is an API contract change.
 */
class OpenApiDocumentSpec :
    FunSpec({
        val document = Json.parseToJsonElement(fionaOpenApiDocument()).jsonObject

        fun JsonElement.at(vararg path: String): JsonElement = path.fold(this) { node, key -> node.jsonObject.getValue(key) }

        fun JsonElement.text(vararg path: String) = at(*path).jsonPrimitive.content

        fun JsonElement.strings(vararg path: String) = at(*path).jsonArray.map { it.jsonPrimitive.content }

        fun schema(name: String) = document.at("components", "schemas", name).jsonObject

        fun operation(
            path: String,
            method: String,
        ) = document.at("paths", path, method).jsonObject

        // Every operation and its expected statuses, as the implementation answers them.
        val operations =
            mapOf(
                Triple("/inquiries", "post", "createInquiry") to listOf("201", "400", "409", "422", "500"),
                Triple("/inquiries/{inquiryId}", "get", "getInquiry") to listOf("200", "400", "404", "500"),
            )

        test("is an OpenAPI 3.1 document of Fiona's Commerce API at the application's version") {
            document.text("openapi") shouldBe "3.1.0"
            document.text("info", "title") shouldBe "Fiona's Commerce API"
            document.text("info", "version") shouldBe fionaVersion()
            document.at("tags").jsonArray.map { it.text("name") } shouldContainExactly listOf("Inquiries")
        }

        test("names no host, so every deployment serves the same document") {
            document
                .at("servers")
                .jsonArray
                .map { it.text("url") }
                .forEach { it shouldStartWith "/" }
            fionaOpenApiDocument().contains("://localhost") shouldBe false
        }

        test("describes exactly Fiona's API operations, not the documentation or the runtime's routes") {
            document.at("paths").jsonObject.mapValues { (_, methods) -> methods.jsonObject.keys } shouldBe
                operations.keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        }

        test("gives every operation its stable operationId and tag") {
            operations.keys.forEach { (path, method, operationId) ->
                operation(path, method).text("operationId") shouldBe operationId
                operation(path, method).strings("tags") shouldContainExactly listOf("Inquiries")
            }
        }

        test("documents every status each operation answers") {
            operations.forEach { (key, statuses) ->
                operation(key.first, key.second).at("responses").jsonObject.keys shouldContainExactlyInAnyOrder statuses
            }
        }

        test("documents every error with commerce-runtime's one reusable error body and its code") {
            operations.keys.forEach { (path, method) ->
                operation(path, method).at("responses").jsonObject.filterKeys { it.toInt() >= 400 }.forEach { (status, response) ->
                    val content = response.at("content", "application/json")
                    content.text("schema", "\$ref") shouldBe "#/components/schemas/ErrorResponse"
                    val code = content.text("example", "code")
                    ErrorCategory.entries
                        .single { it.code == code }
                        .status.code
                        .toString() shouldBe status
                    response.text("description") shouldStartWith "`$code`"
                }
            }
            schema("ErrorResponse").let {
                it.at("properties").jsonObject.mapValues { (_, property) -> property.text("type") } shouldBe
                    mapOf("code" to "string", "message" to "string")
                it.strings("required") shouldContainExactly listOf("code", "message")
            }
        }

        test("describes the create request: required name and email, an optional message, and their limits") {
            val body = operation("/inquiries", "post").at("requestBody")
            body.at("required") shouldBe JsonPrimitive(true)
            body.text("content", "application/json", "schema", "\$ref") shouldBe "#/components/schemas/CreateInquiryRequest"

            val request = schema("CreateInquiryRequest")
            request.text("type") shouldBe "object"
            request.strings("required") shouldContainExactly listOf("name", "email")
            val properties = request.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly listOf("name", "email", "message")
            properties.values.forEach { it.text("type") shouldBe "string" }
            properties.mapValues { (_, property) -> property.at("maxLength").jsonPrimitive.int } shouldBe
                mapOf(
                    "name" to CustomerName.MAX_LENGTH,
                    "email" to Email.MAX_LENGTH,
                    "message" to InquiryMessage.MAX_LENGTH,
                )
            // The server accepts any text and validates it itself, so no format is claimed for the email.
            properties
                .getValue("email")
                .jsonObject.keys
                .contains("format") shouldBe false
        }

        test("describes the inquiry response with its identifiers and timestamp formats") {
            listOf(
                operation("/inquiries", "post").at("responses", "201"),
                operation("/inquiries/{inquiryId}", "get").at("responses", "200"),
            ).forEach { it.text("content", "application/json", "schema", "\$ref") shouldBe "#/components/schemas/InquiryResponse" }

            val response = schema("InquiryResponse")
            response.strings("required") shouldContainExactly listOf("id", "customerId", "name", "email", "createdAt")
            val properties = response.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly listOf("id", "customerId", "name", "email", "message", "createdAt")
            properties.values.forEach { it.text("type") shouldBe "string" }
            properties.filterValues { "format" in it.jsonObject }.mapValues { (_, property) -> property.text("format") } shouldBe
                mapOf("id" to "uuid", "customerId" to "uuid", "createdAt" to "date-time")
        }

        test("describes the inquiry id as a required UUID path parameter") {
            val parameter = operation("/inquiries/{inquiryId}", "get").at("parameters").jsonArray.single()
            parameter.text("name") shouldBe "inquiryId"
            parameter.text("in") shouldBe "path"
            parameter.at("required") shouldBe JsonPrimitive(true)
            parameter.text("schema", "type") shouldBe "string"
            parameter.text("schema", "format") shouldBe "uuid"
        }

        test("gives every schema property a type and resolves every reference") {
            val schemas = document.at("components", "schemas").jsonObject
            schemas.keys shouldContainExactlyInAnyOrder listOf("CreateInquiryRequest", "InquiryResponse", "ErrorResponse")
            schemas.values.forEach { schema ->
                schema
                    .at("properties")
                    .jsonObject.values
                    .forEach { it.jsonObject shouldContainKey "type" }
            }
            references(document).forEach { schemas shouldContainKey it.removePrefix("#/components/schemas/") }
        }

        test("gives each body an example that satisfies its schema's required properties") {
            operations.keys.forEach { (path, method) ->
                val operation = operation(path, method)
                val contents =
                    listOfNotNull(operation["requestBody"]?.at("content", "application/json")) +
                        operation
                            .at("responses")
                            .jsonObject.values
                            .map { it.at("content", "application/json") }
                contents.forEach { content ->
                    val required = schema(content.text("schema", "\$ref").substringAfterLast('/')).strings("required")
                    content
                        .at("example")
                        .jsonObject.keys
                        .containsAll(required) shouldBe true
                }
            }
        }
    })

private fun references(node: JsonElement): List<String> =
    when (node) {
        is JsonObject -> node.flatMap { (key, value) -> if (key == "\$ref") listOf(value.jsonPrimitive.content) else references(value) }
        is JsonArray -> node.flatMap(::references)
        else -> emptyList()
    }
