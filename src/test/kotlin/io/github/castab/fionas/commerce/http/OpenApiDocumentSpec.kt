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
 *
 * The Offerings catalog's routes and schemas are commerce-runtime's, and its suite covers
 * them; these specs prove only that Fiona composed them: where they are served, their
 * operationIds, and that the runtime's price union survives Fiona's renderer.
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
                Triple("/estimate-preview", "post", "previewEstimate") to listOf("200", "400", "404", "422", "500"),
            )

        // Each Fiona operation's tag.
        val tags = mapOf("createInquiry" to "Inquiries", "getInquiry" to "Inquiries", "previewEstimate" to "Estimates")

        // commerce-runtime's Offerings operations, where Fiona binds them and as Fiona's prefix names them.
        val offeringOperations =
            listOf(
                Triple("/offering-catalog", "get", "fionasOfferingsGetCatalog"),
                Triple("/offering-catalog", "post", "fionasOfferingsCreateCatalog"),
                Triple("/offering-catalog/revisions/{revision}", "get", "fionasOfferingsGetCatalogRevision"),
                Triple("/offering-catalog/categories", "get", "fionasOfferingsListCategories"),
                Triple("/offering-catalog/categories", "post", "fionasOfferingsAddCategory"),
                Triple("/offering-catalog/categories/{categoryKey}", "get", "fionasOfferingsGetCategory"),
                Triple("/offering-catalog/categories/{categoryKey}/offerings", "get", "fionasOfferingsListCategoryOfferings"),
                Triple("/offering-catalog/offerings", "get", "fionasOfferingsListOfferings"),
                Triple("/offering-catalog/offerings", "post", "fionasOfferingsAddOffering"),
                Triple("/offering-catalog/offerings/{offeringKey}", "get", "fionasOfferingsGetOffering"),
            )

        // The schemas Fiona itself describes; every other one is commerce-runtime's.
        val fionaSchemas =
            listOf(
                "CreateInquiryRequest",
                "InquiryResponse",
                "EstimatePreviewRequest",
                "EstimatePreviewSelection",
                "EstimatePreviewResponse",
                "EstimatePreviewLine",
                "ErrorResponse",
            )

        test("is an OpenAPI 3.1 document of Fiona's Commerce API at the application's version") {
            document.text("openapi") shouldBe "3.1.0"
            document.text("info", "title") shouldBe "Fiona's Commerce API"
            document.text("info", "version") shouldBe fionaVersion()
            document.at("tags").jsonArray.map { it.text("name") } shouldContainExactlyInAnyOrder listOf("Inquiries", "Estimates")
        }

        test("names no host, so every deployment serves the same document") {
            document
                .at("servers")
                .jsonArray
                .map { it.text("url") }
                .forEach { it shouldStartWith "/" }
            fionaOpenApiDocument().contains("://localhost") shouldBe false
        }

        test("describes exactly Fiona's operations and its Offerings catalog's, not the documentation or /health and /ready") {
            document.at("paths").jsonObject.mapValues { (_, methods) -> methods.jsonObject.keys } shouldBe
                (operations.keys + offeringOperations)
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.toSet() }
        }

        test("serves commerce-runtime's Offerings operations under Fiona's base path and operationId prefix") {
            offeringOperations.forEach { (path, method, operationId) ->
                operation(path, method).text("operationId") shouldBe operationId
            }
            document
                .at("paths")
                .jsonObject
                .filterKeys { it.startsWith("/offering-catalog") }
                .values
                .flatMap { methods -> methods.jsonObject.values.map { it.text("operationId") } }
                .forEach { it shouldStartWith "fionasOfferings" }
        }

        test("keeps commerce-runtime's strict OfferingPrice union through Fiona's renderer") {
            val price = schema("OfferingPriceDto")
            price.at("oneOf").jsonArray.map { it.text("\$ref") } shouldContainExactly
                listOf("FixedOfferingPrice", "PerQuantityOfferingPrice", "PerDurationOfferingPrice").map { "#/components/schemas/$it" }
            price.text("discriminator", "propertyName") shouldBe "kind"
            price.at("discriminator", "mapping").jsonObject.keys shouldContainExactlyInAnyOrder
                listOf("FIXED", "PER_QUANTITY", "PER_DURATION")
            schema("OfferingDto").text("properties", "price", "\$ref") shouldBe "#/components/schemas/OfferingPriceDto"
        }

        test("gives every operation its stable operationId and tag") {
            operations.keys.forEach { (path, method, operationId) ->
                operation(path, method).text("operationId") shouldBe operationId
                operation(path, method).strings("tags") shouldContainExactly listOf(tags.getValue(operationId))
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

        test("gives every property of Fiona's schemas a type or a reference") {
            fionaSchemas.forEach { name ->
                schema(name)
                    .at("properties")
                    .jsonObject.values
                    .forEach { (it.jsonObject.keys intersect setOf("type", "\$ref")).size shouldBe 1 }
            }
        }

        test("describes the estimate preview request from its serial descriptors: integers, a boolean, and nested lists") {
            val body = operation("/estimate-preview", "post").at("requestBody")
            body.text("content", "application/json", "schema", "\$ref") shouldBe "#/components/schemas/EstimatePreviewRequest"

            val request = schema("EstimatePreviewRequest")
            request.strings("required") shouldContainExactly listOf("catalogRevision", "guestCount", "durationMinutes", "selections")
            val properties = request.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly
                listOf("catalogRevision", "guestCount", "guestCountIsMinimum", "durationMinutes", "selections")
            listOf("catalogRevision", "guestCount", "durationMinutes").forEach {
                properties.getValue(it).text("type") shouldBe "integer"
                properties.getValue(it).text("format") shouldBe "int32"
            }
            properties.getValue("guestCountIsMinimum").text("type") shouldBe "boolean"
            properties.getValue("selections").text("type") shouldBe "array"
            properties.getValue("selections").text("items", "\$ref") shouldBe "#/components/schemas/EstimatePreviewSelection"

            val selection = schema("EstimatePreviewSelection")
            selection.strings("required") shouldContainExactly listOf("category", "offerings")
            selection.text("properties", "offerings", "type") shouldBe "array"
            selection.text("properties", "offerings", "items", "type") shouldBe "string"
        }

        test("describes the estimate preview response: every amount an exact decimal string, quantity optional") {
            operation("/estimate-preview", "post").text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/EstimatePreviewResponse"

            val response = schema("EstimatePreviewResponse")
            response.strings("required") shouldContainExactly
                listOf("catalogRevision", "guestCountIsMinimum", "lines", "subtotal", "taxAmount", "total", "currency")
            response.text("properties", "lines", "items", "\$ref") shouldBe "#/components/schemas/EstimatePreviewLine"
            listOf("subtotal", "taxAmount", "total").forEach { response.text("properties", it, "type") shouldBe "string" }

            val line = schema("EstimatePreviewLine")
            line.strings("required") shouldContainExactly listOf("description", "unitPrice", "subtotal", "taxAmount", "total", "currency")
            line
                .at("properties")
                .jsonObject.values
                .forEach { it.text("type") shouldBe "string" }
        }

        test("resolves every reference, and holds no schema but Fiona's and those its Offerings routes use") {
            val schemas = document.at("components", "schemas").jsonObject
            references(document).forEach { schemas shouldContainKey it.removePrefix("#/components/schemas/") }

            // Every schema the Offerings routes reach, directly or through other schemas.
            val offeringPaths = document.at("paths").jsonObject.filterKeys { it.startsWith("/offering-catalog") }
            val reached = mutableSetOf<String>()
            var frontier = references(JsonObject(offeringPaths)).map { it.substringAfterLast('/') }.toSet()
            while (frontier.isNotEmpty()) {
                reached += frontier
                frontier = frontier.flatMap { references(schemas.getValue(it)) }.map { it.substringAfterLast('/') }.toSet() - reached
            }
            schemas.keys shouldBe fionaSchemas.toSet() + reached
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
