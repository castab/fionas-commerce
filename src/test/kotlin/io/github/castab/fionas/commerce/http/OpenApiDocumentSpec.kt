package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.fionaVersion
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.openapi.fionaOpenApiDocument
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

        // Only schema-bearing keywords are traversed. Examples, defaults, constants, enums,
        // and extensions are application data even when they contain a field named format.
        fun checkSchemaFormats(value: JsonElement) {
            if (value !is JsonObject) return // Boolean schemas carry no format keyword.
            value["format"]?.let { (it is JsonPrimitive && it.isString) shouldBe true }
            listOf("properties", "patternProperties", "dependentSchemas", "\$defs", "definitions").forEach { keyword ->
                (value[keyword] as? JsonObject)?.values?.forEach(::checkSchemaFormats)
            }
            listOf(
                "additionalProperties",
                "items",
                "contains",
                "not",
                "if",
                "then",
                "else",
                "propertyNames",
                "unevaluatedProperties",
                "unevaluatedItems",
                "contentSchema",
                "additionalItems",
            ).forEach { keyword -> value[keyword]?.let(::checkSchemaFormats) }
            listOf("prefixItems", "allOf", "anyOf", "oneOf").forEach { keyword ->
                (value[keyword] as? JsonArray)?.forEach(::checkSchemaFormats)
            }
        }

        fun operation(
            path: String,
            method: String,
        ) = document.at("paths", path, method).jsonObject

        // Every operation and its expected statuses, as the implementation answers them.
        val operations =
            mapOf(
                Triple("/inquiries", "post", "createInquiry") to listOf("201", "400", "404", "409", "422", "500"),
                Triple("/inquiries", "get", "listInquiries") to listOf("200", "400", "401", "403", "422", "500"),
                Triple("/inquiries/{inquiryId}", "get", "getInquiry") to listOf("200", "400", "401", "403", "404", "500"),
                Triple("/estimate-preview", "post", "previewEstimate") to listOf("200", "400", "404", "422", "500"),
                Triple("/inquiries/{inquiryId}/estimates", "post", "createInquiryEstimate") to
                    listOf("201", "400", "401", "403", "404", "422", "500"),
                Triple("/inquiries/{inquiryId}/financial-documents", "post", "createInquiryFinancialDocument") to
                    listOf("201", "400", "401", "403", "404", "422", "500"),
                Triple("/inquiries/{inquiryId}/financial-documents", "get", "listInquiryFinancialDocuments") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/financial-documents/{documentId}", "get", "getFinancialDocument") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/financial-documents/{documentId}/history", "get", "getFinancialDocumentHistory") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/financial-documents/{documentId}/quote", "post", "issueQuote") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/financial-documents/{documentId}/invoice", "post", "issueInvoice") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/financial-documents/{documentId}/change-orders", "post", "createChangeOrder") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/financial-documents/{documentId}/payments", "post", "recordPayment") to
                    listOf("201", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/financial-documents/{documentId}/payments", "get", "listFinancialDocumentPayments") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/payments", "post", "recordStandalonePayment") to listOf("201", "400", "401", "403", "409", "422", "500"),
                Triple("/payments/unapplied", "get", "listUnappliedPayments") to listOf("200", "401", "403", "500"),
                Triple("/payments/{paymentId}/allocations", "post", "allocatePayment") to
                    listOf("201", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/payments/{paymentId}/refunds", "post", "recordRefund") to
                    listOf("201", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/auth/login", "post", "login") to listOf("204", "400", "401", "403", "500"),
                Triple("/auth/logout", "post", "logout") to listOf("204", "403", "500"),
                Triple("/auth/me", "get", "getCurrentUser") to listOf("200", "401", "403", "500"),
                Triple("/admin/users/{userId}/credentials/password", "put", "setStaffPassword") to
                    listOf("204", "400", "401", "403", "404", "422", "500"),
            )

        // Each Fiona operation's tag.
        val tags =
            mapOf(
                "createInquiry" to "Inquiries",
                "listInquiries" to "Inquiries",
                "getInquiry" to "Inquiries",
                "previewEstimate" to "Estimates",
                "createInquiryEstimate" to "Financial documents",
                "createInquiryFinancialDocument" to "Financial documents",
                "listInquiryFinancialDocuments" to "Financial documents",
                "getFinancialDocument" to "Financial documents",
                "getFinancialDocumentHistory" to "Financial documents",
                "issueQuote" to "Financial documents",
                "issueInvoice" to "Financial documents",
                "createChangeOrder" to "Financial documents",
                "recordPayment" to "Payments",
                "listFinancialDocumentPayments" to "Payments",
                "listUnappliedPayments" to "Payments",
                "recordStandalonePayment" to "Payments",
                "allocatePayment" to "Payments",
                "recordRefund" to "Payments",
                "login" to "Authentication",
                "logout" to "Authentication",
                "getCurrentUser" to "Authentication",
                "setStaffPassword" to "Staff administration",
            )

        // commerce-runtime's Offerings operations, where Fiona binds them and as Fiona's prefix names them.
        val offeringOperations =
            listOf(
                Triple("/offering-catalog", "get", "fionasOfferingsGetCatalog"),
                Triple("/offering-catalog", "post", "fionasOfferingsCreateCatalog"),
                Triple("/offering-catalog/revisions/{revision}", "get", "fionasOfferingsGetCatalogRevision"),
                Triple("/offering-catalog/categories", "get", "fionasOfferingsListCategories"),
                Triple("/offering-catalog/categories", "post", "fionasOfferingsAddCategory"),
                Triple("/offering-catalog/categories/{categoryKey}", "get", "fionasOfferingsGetCategory"),
                Triple("/offering-catalog/categories/{categoryKey}", "put", "fionasOfferingsUpdateCategory"),
                Triple("/offering-catalog/categories/{categoryKey}", "delete", "fionasOfferingsRetireCategory"),
                Triple("/offering-catalog/categories/{categoryKey}/restore", "post", "fionasOfferingsRestoreCategory"),
                Triple("/offering-catalog/categories/{categoryKey}/offerings", "get", "fionasOfferingsListCategoryOfferings"),
                Triple("/offering-catalog/offerings", "get", "fionasOfferingsListOfferings"),
                Triple("/offering-catalog/offerings", "post", "fionasOfferingsAddOffering"),
                Triple("/offering-catalog/offerings/{offeringKey}", "get", "fionasOfferingsGetOffering"),
                Triple("/offering-catalog/offerings/{offeringKey}", "put", "fionasOfferingsUpdateOffering"),
                Triple("/offering-catalog/offerings/{offeringKey}", "delete", "fionasOfferingsRetireOffering"),
                Triple("/offering-catalog/offerings/{offeringKey}/restore", "post", "fionasOfferingsRestoreOffering"),
                Triple("/offering-catalog/retired/offerings", "get", "fionasOfferingsListRetiredOfferings"),
                Triple("/offering-catalog/retired/categories", "get", "fionasOfferingsListRetiredCategories"),
            )

        val adminOperations =
            listOf(
                Triple("/admin/access/users", "get", "authorizationListUsers"),
                Triple("/admin/access/users", "post", "authorizationCreateUser"),
                Triple("/admin/access/users/{userId}", "get", "authorizationGetUser"),
                Triple("/admin/access/users/{userId}", "patch", "authorizationUpdateUser"),
                Triple("/admin/access/users/{userId}/status", "put", "authorizationSetUserStatus"),
                Triple("/admin/access/users/{userId}/roles", "get", "authorizationUserRoles"),
                Triple("/admin/access/users/{userId}/roles/{roleKey}", "put", "authorizationAssignUserRole"),
                Triple("/admin/access/users/{userId}/roles/{roleKey}", "delete", "authorizationUnassignUserRole"),
                Triple("/admin/access/services", "get", "authorizationListServices"),
                Triple("/admin/access/services", "post", "authorizationCreateService"),
                Triple("/admin/access/services/{serviceId}", "get", "authorizationGetService"),
                Triple("/admin/access/services/{serviceId}", "patch", "authorizationRenameService"),
                Triple("/admin/access/services/{serviceId}/status", "put", "authorizationSetServiceStatus"),
                Triple("/admin/access/services/{serviceId}/roles", "get", "authorizationServiceRoles"),
                Triple("/admin/access/services/{serviceId}/roles/{roleKey}", "put", "authorizationAssignServiceRole"),
                Triple("/admin/access/services/{serviceId}/roles/{roleKey}", "delete", "authorizationUnassignServiceRole"),
                Triple("/admin/access/roles", "get", "authorizationListRoles"),
                Triple("/admin/access/roles", "post", "authorizationCreateRole"),
                Triple("/admin/access/roles/{roleKey}", "get", "authorizationGetRole"),
                Triple("/admin/access/roles/{roleKey}", "patch", "authorizationUpdateRole"),
                Triple("/admin/access/roles/{roleKey}", "delete", "authorizationDeleteRole"),
                Triple("/admin/access/roles/{roleKey}/permissions", "put", "authorizationReplaceRolePermissions"),
                Triple("/admin/access/permissions", "get", "authorizationListPermissions"),
            )

        // The schemas Fiona itself describes; every other one is commerce-runtime's.
        val fionaSchemas =
            listOf(
                "CreateInquiryRequest",
                "InquiryPricingInputs",
                "InquiryReceiptResponse",
                "InquiryResponse",
                "InquiryRequestedPricing",
                "InquiryListResponse",
                "InquiryListItem",
                "EstimatePreviewRequest",
                "EstimatePreviewSelection",
                "EstimatePreviewResponse",
                "EstimatePreviewLine",
                "CreateInquiryEstimateRequest",
                "CreateInquiryFinancialDocumentRequest",
                "ChangeOrderRequest",
                "PricingSelection",
                "StageTransitionRequest",
                "RecordPaymentRequest",
                "RecordStandalonePaymentRequest",
                "AllocatePaymentRequest",
                "PaymentExternalReference",
                "FinancialDocumentResponse",
                "DocumentPricing",
                "FinancialDocumentLine",
                "DocumentReconciliation",
                "FinancialDocumentHistoryResponse",
                "InquiryFinancialDocumentsResponse",
                "RecordedPaymentResponse",
                "PaymentRecordResponse",
                "PaymentAllocationResponse",
                "RecordRefundRequest",
                "RefundExternalReference",
                "RefundAllocationRequest",
                "RecordedRefundResponse",
                "RefundAllocationResponse",
                "PaymentReconciliationResponse",
                "FinancialDocumentPaymentsResponse",
                "PaymentHistoryResponse",
                "PaymentAllocationRecordResponse",
                "RefundRecordResponse",
                "RefundAllocationRecordResponse",
                "ErrorResponse",
                "ValidationErrorResponse",
                "ValidationViolationResponse",
                "UnappliedPaymentsResponse",
                "LoginRequest",
                "CurrentUserResponse",
                "SetStaffPasswordRequest",
            )

        val adminSchemas =
            setOf(
                "UsersDto",
                "UserDto",
                "UserWriteDto",
                "StatusDto",
                "AssignmentsDto",
                "ServicesDto",
                "ServiceDto",
                "ServiceWriteDto",
                "RolesDto",
                "RoleDto",
                "RoleWriteDto",
                "RoleProfileDto",
                "PermissionKeysDto",
                "PermissionsDto",
                "PermissionDto",
            )

        test("is an OpenAPI 3.1 document of Fiona's Commerce API at the application's version") {
            document.text("openapi") shouldBe "3.1.0"
            document.text("info", "title") shouldBe "Fiona's Commerce API"
            document.text("info", "version") shouldBe fionaVersion()
            document.at("tags").jsonArray.map { it.text("name") } shouldContainExactlyInAnyOrder
                listOf(
                    "Inquiries",
                    "Estimates",
                    "Financial documents",
                    "Payments",
                    "Authentication",
                    "Staff administration",
                    "Offerings catalog",
                )
        }

        test("names no host, so every deployment serves the same document") {
            document
                .at("servers")
                .jsonArray
                .map { it.text("url") }
                .forEach { it shouldStartWith "/" }
            fionaOpenApiDocument().contains("://localhost") shouldBe false
        }

        test("describes exactly Fiona and bound runtime capability routes, excluding /health and /ready") {
            document.at("paths").jsonObject.mapValues { (_, methods) -> methods.jsonObject.keys } shouldBe
                (operations.keys + offeringOperations + adminOperations)
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.toSet() }
        }

        test("serves commerce-runtime's Offerings operations under Fiona's base path and operationId prefix") {
            offeringOperations.forEach { (path, method, operationId) ->
                operation(path, method).text("operationId") shouldBe operationId
                operation(path, method).strings("tags") shouldContainExactly listOf("Offerings catalog")
            }
            document
                .at("paths")
                .jsonObject
                .filterKeys { it.startsWith("/offering-catalog") }
                .values
                .flatMap { methods -> methods.jsonObject.values.map { it.text("operationId") } }
                .forEach { it shouldStartWith "fionasOfferings" }
        }

        test("mounts commerce-runtime's authorization administration contract unchanged") {
            adminOperations.forEach { (path, method, operationId) ->
                operation(path, method).text("operationId") shouldBe operationId
                operation(path, method).strings("tags") shouldContainExactly listOf("Staff administration")
            }
        }

        test("Offerings add, update, and restore bodies retain required integer revisions and path-owned mutation keys") {
            offeringOperations
                .filter { (path, method) -> path != "/offering-catalog" && method in listOf("post", "put") }
                .forEach { (path, method) ->
                    val request = operation(path, method).at("requestBody", "content", "application/json", "schema")
                    val body = schema(request.text("\$ref").substringAfterLast('/'))
                    body.strings("required").contains("expectedRevision") shouldBe true
                    body.text("properties", "expectedRevision", "type") shouldBe "integer"
                    if (method == "put" || path.endsWith("/restore")) {
                        body.at("properties").jsonObject.containsKey("key") shouldBe false
                    }
                }
        }

        test("Offerings retirement retains the required integer expectedRevision query parameter") {
            offeringOperations.filter { it.second == "delete" }.forEach { (path, method) ->
                val revision = operation(path, method).at("parameters").jsonArray.single { it.text("name") == "expectedRevision" }
                revision.text("in") shouldBe "query"
                revision.at("required") shouldBe JsonPrimitive(true)
                revision.text("schema", "type") shouldBe "integer"
            }
        }

        test("Offerings management retains runtime mutation and retired-discovery error responses") {
            offeringOperations
                .filter { (path, method) -> path != "/offering-catalog" && method in listOf("post", "put", "delete") }
                .forEach { (path, method) ->
                    operation(path, method).at("responses").jsonObject.keys shouldContainExactlyInAnyOrder
                        listOf(
                            if (method == "post" &&
                                !path.endsWith("/restore")
                            ) {
                                "201"
                            } else {
                                "200"
                            },
                            "400",
                            "401",
                            "403",
                            "404",
                            "409",
                            "422",
                        )
                }
            listOf("offerings", "categories").forEach { kind ->
                operation("/offering-catalog/retired/$kind", "get").at("responses").jsonObject.keys shouldContainExactlyInAnyOrder
                    listOf("200", "401", "403", "404")
            }
        }

        test("has no unnamed Swagger UI operation group") {
            document.at("paths").jsonObject.values.forEach { methods ->
                methods.jsonObject.values.forEach { route ->
                    route.strings("tags").single().isNotBlank() shouldBe true
                }
            }
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
                    val code = content.text("example", "code")
                    content.text("schema", "\$ref") shouldBe
                        "#/components/schemas/" + if (code == "validation_failed") "ValidationErrorResponse" else "ErrorResponse"
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

        test("validation violations are optional runtime codes and no schema has a null format") {
            val validation = schema("ValidationErrorResponse")
            validation.strings("required") shouldContainExactly listOf("code", "message")
            validation.at("properties").jsonObject.keys shouldBe setOf("code", "message", "violations")
            validation.text("properties", "violations", "items", "\$ref") shouldBe "#/components/schemas/ValidationViolationResponse"
            schema("ValidationViolationResponse").text("properties", "code", "type") shouldBe "string"
            schema("ValidationViolationResponse").strings("required") shouldContainExactly listOf("code")

            document
                .at("components", "schemas")
                .jsonObject.values
                .forEach(::checkSchemaFormats)
        }

        test("schema format checks preserve arbitrary payload format fields and still reject invalid schema keywords") {
            val payload = Json.parseToJsonElement("""{"format":null,"properties":{"field":{"format":null}}}""")
            val fixture =
                Json
                    .parseToJsonElement(
                        """{
                    "type":"object",
                    "properties":{"timestamp":{"type":"string","format":"date-time"}},
                    "additionalProperties":false,
                    "example":$payload,
                    "examples":[$payload],
                    "default":$payload,
                    "const":$payload,
                    "enum":[$payload],
                    "x-payload":$payload
                }""",
                    ).jsonObject
            checkSchemaFormats(fixture)
            listOf("example", "default", "const", "x-payload").forEach { fixture.getValue(it) shouldBe payload }
            listOf("examples", "enum").forEach { fixture.getValue(it).jsonArray.single() shouldBe payload }

            val invalidFormat = JsonObject(mapOf("format" to JsonNull))
            shouldThrow<AssertionError> { checkSchemaFormats(invalidFormat) }
            shouldThrow<AssertionError> { checkSchemaFormats(JsonObject(fixture + ("format" to JsonNull))) }
            // The same invalid keyword must be found in real nested schemas, not just the root.
            listOf("properties", "patternProperties", "dependentSchemas", "\$defs", "definitions").forEach { keyword ->
                shouldThrow<AssertionError> {
                    checkSchemaFormats(JsonObject(mapOf(keyword to JsonObject(mapOf("field" to invalidFormat)))))
                }
            }
            listOf("items", "additionalProperties", "contains", "not", "if", "then", "else", "propertyNames").forEach { keyword ->
                shouldThrow<AssertionError> { checkSchemaFormats(JsonObject(mapOf(keyword to invalidFormat))) }
            }
            listOf("prefixItems", "allOf", "anyOf", "oneOf").forEach { keyword ->
                shouldThrow<AssertionError> { checkSchemaFormats(JsonObject(mapOf(keyword to JsonArray(listOf(invalidFormat))))) }
            }
        }

        test("unapplied discovery reuses whole histories and me documents effective permissions") {
            val queue = operation("/payments/unapplied", "get")
            queue.text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/UnappliedPaymentsResponse"
            queue.text("description").contains("commerce.payment.record") shouldBe true
            schema("UnappliedPaymentsResponse").text("properties", "payments", "items", "\$ref") shouldBe
                "#/components/schemas/PaymentHistoryResponse"
            schema("CurrentUserResponse").strings("required").contains("permissions") shouldBe true
            schema("CurrentUserResponse").text("properties", "permissions", "items", "type") shouldBe "string"
        }

        test("describes the create request: required name and email, optional message and pricing inputs, and their limits") {
            val body = operation("/inquiries", "post").at("requestBody")
            body.at("required") shouldBe JsonPrimitive(true)
            body.text("content", "application/json", "schema", "\$ref") shouldBe "#/components/schemas/CreateInquiryRequest"

            val request = schema("CreateInquiryRequest")
            request.text("type") shouldBe "object"
            request.strings("required") shouldContainExactly listOf("name", "email")
            val properties = request.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly listOf("name", "email", "message", "pricingInputs")
            properties.getValue("pricingInputs").text("\$ref") shouldBe "#/components/schemas/InquiryPricingInputs"
            val text = properties - "pricingInputs"
            text.values.forEach { it.text("type") shouldBe "string" }
            text.mapValues { (_, property) -> property.at("maxLength").jsonPrimitive.int } shouldBe
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

        test("describes the public create response as a receipt of the new inquiry, naming no customer") {
            operation("/inquiries", "post").text("responses", "201", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/InquiryReceiptResponse"

            val receipt = schema("InquiryReceiptResponse")
            receipt.strings("required") shouldContainExactly listOf("id", "createdAt")
            receipt
                .at("properties")
                .jsonObject.keys
                .toList() shouldContainExactly listOf("id", "createdAt")
            receipt.text("properties", "id", "format") shouldBe "uuid"
            receipt.text("properties", "createdAt", "format") shouldBe "date-time"
        }

        test("describes the staff inquiry response with its identifiers, timestamp formats, and requested pricing inputs") {
            operation("/inquiries/{inquiryId}", "get").text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/InquiryResponse"

            val response = schema("InquiryResponse")
            response.strings("required") shouldContainExactly listOf("id", "customerId", "name", "email", "createdAt")
            val properties = response.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly
                listOf("id", "customerId", "name", "email", "message", "createdAt", "pricingInputs")
            properties.getValue("pricingInputs").text("\$ref") shouldBe "#/components/schemas/InquiryRequestedPricing"
            (properties - "pricingInputs").values.forEach { it.text("type") shouldBe "string" }
            properties.filterValues { "format" in it.jsonObject }.mapValues { (_, property) -> property.text("format") } shouldBe
                mapOf("id" to "uuid", "customerId" to "uuid", "createdAt" to "date-time")
        }

        test("describes an inquiry's pricing inputs as the estimate's commercial inputs: never amounts, and pinned to a revision") {
            val commercialInputs = listOf("catalogRevision", "guestCount", "guestCountIsMinimum", "durationMinutes", "selections")
            listOf("InquiryPricingInputs", "InquiryRequestedPricing").forEach { name ->
                val inputs = schema(name)
                inputs
                    .at("properties")
                    .jsonObject.keys
                    .toList() shouldContainExactly commercialInputs
                inputs.text("properties", "selections", "items", "\$ref") shouldBe "#/components/schemas/PricingSelection"
                listOf("catalogRevision", "guestCount", "durationMinutes").forEach {
                    inputs.text("properties", it, "type") shouldBe "integer"
                }
                inputs.text("properties", "guestCountIsMinimum", "type") shouldBe "boolean"
            }
            // Submitted as an estimate request takes them; read back as staff submit them to an estimate.
            schema("InquiryPricingInputs").strings("required") shouldContainExactly
                schema("CreateInquiryEstimateRequest").strings("required")
            schema("InquiryPricingInputs").at("properties").jsonObject.keys shouldBe
                schema("CreateInquiryEstimateRequest").at("properties").jsonObject.keys
            schema("InquiryRequestedPricing").strings("required") shouldContainExactly commercialInputs
        }

        test("describes the staff inquiry list: an optional bounded limit, an opaque cursor, and the next page's cursor") {
            val list = operation("/inquiries", "get")
            list.text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/InquiryListResponse"
            list.at("parameters").jsonArray.associateBy { it.text("name") }.let { parameters ->
                parameters.keys shouldBe setOf("limit", "cursor")
                parameters.values.forEach {
                    it.text("in") shouldBe "query"
                    it.at("required") shouldBe JsonPrimitive(false)
                }
                parameters.getValue("limit").at("schema").jsonObject shouldBe
                    Json.parseToJsonElement("""{"type":"integer","minimum":1,"maximum":100,"default":25}""")
                parameters.getValue("cursor").text("schema", "type") shouldBe "string"
            }
            list.text("responses", "400", "description").contains("`cursor`") shouldBe true
            list.text("responses", "422", "description").contains("`limit`") shouldBe true

            schema("InquiryListResponse").let {
                it.strings("required") shouldContainExactly listOf("inquiries")
                it
                    .at("properties")
                    .jsonObject.keys
                    .toList() shouldContainExactly listOf("inquiries", "nextCursor")
                it.text("properties", "inquiries", "items", "\$ref") shouldBe "#/components/schemas/InquiryListItem"
                it.text("properties", "nextCursor", "type") shouldBe "string"
            }
            schema("InquiryListItem").let {
                it.strings("required") shouldContainExactly listOf("id", "customerId", "name", "email", "createdAt")
                it
                    .at("properties")
                    .jsonObject.keys
                    .toList() shouldContainExactly
                    listOf("id", "customerId", "name", "email", "message", "createdAt")
            }
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

        test("persisted financial documents take commercial inputs only: no request carries lines, amounts, or totals") {
            listOf("CreateInquiryEstimateRequest", "CreateInquiryFinancialDocumentRequest", "ChangeOrderRequest").forEach { name ->
                val request = schema(name)
                request.at("properties").jsonObject.keys shouldBe
                    (
                        when (name) {
                            "ChangeOrderRequest" -> setOf("expectedVersion")
                            "CreateInquiryFinancialDocumentRequest" -> setOf("stage")
                            else -> emptySet()
                        }
                    ) +
                    setOf("catalogRevision", "guestCount", "guestCountIsMinimum", "durationMinutes", "selections")
                request.text("properties", "selections", "items", "\$ref") shouldBe "#/components/schemas/PricingSelection"
            }
            schema("CreateInquiryEstimateRequest").strings("required") shouldContainExactly
                listOf("catalogRevision", "guestCount", "durationMinutes", "selections")
            schema("CreateInquiryFinancialDocumentRequest").strings("required") shouldContainExactly
                listOf("stage", "catalogRevision", "guestCount", "durationMinutes", "selections")
            schema("ChangeOrderRequest").strings("required") shouldContainExactly
                listOf("expectedVersion", "catalogRevision", "guestCount", "durationMinutes", "selections")
            schema("StageTransitionRequest").strings("required") shouldContainExactly listOf("expectedVersion")
            schema("RecordPaymentRequest").let {
                it.strings("required") shouldContainExactly listOf("documentVersion", "amount", "method")
                it.text("properties", "amount", "type") shouldBe "string"
                it.text("properties", "receivedAt", "format") shouldBe "date-time"
                it.text("properties", "externalReference", "\$ref") shouldBe "#/components/schemas/PaymentExternalReference"
            }
            schema("PaymentExternalReference").strings("required") shouldContainExactly listOf("provider", "reference")
            schema("RecordStandalonePaymentRequest").let {
                it.strings("required") shouldContainExactly listOf("amount", "currency", "method")
                it.text("properties", "amount", "type") shouldBe "string"
                it.text("properties", "receivedAt", "format") shouldBe "date-time"
            }
            schema("AllocatePaymentRequest").let {
                it.strings("required") shouldContainExactly listOf("documentId", "documentVersion", "amount")
                it.text("properties", "documentId", "format") shouldBe "uuid"
                it.text("properties", "amount", "type") shouldBe "string"
                it.at("properties").jsonObject.containsKey("currency") shouldBe false
            }
        }

        test("documents first-snapshot creation and separate payment facts with derived allocation settlement") {
            operation("/inquiries/{inquiryId}/financial-documents", "post")
                .text("responses", "201", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/FinancialDocumentResponse"
            operation("/payments", "post").text("responses", "201", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/PaymentRecordResponse"
            operation("/payments/{paymentId}/allocations", "post")
                .text("responses", "201", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/PaymentAllocationResponse"
            schema("PaymentRecordResponse").let {
                it.strings("required") shouldContainExactly listOf("paymentId", "method", "amount", "currency", "receivedAt")
                it
                    .at("properties")
                    .jsonObject.keys
                    .contains("allocationId") shouldBe false
                it.text("properties", "amount", "type") shouldBe "string"
            }
            schema("PaymentAllocationResponse").let {
                it.strings("required") shouldContainExactly
                    listOf(
                        "allocationId",
                        "paymentId",
                        "documentId",
                        "documentVersion",
                        "amount",
                        "currency",
                        "allocatedAt",
                        "reconciliation",
                    )
                it.text("properties", "reconciliation", "\$ref") shouldBe "#/components/schemas/DocumentReconciliation"
                it.text("properties", "amount", "type") shouldBe "string"
            }
        }

        test("documents the explicit refund request and its complete payment reconciliation") {
            val route = operation("/payments/{paymentId}/refunds", "post")
            route.text("requestBody", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/RecordRefundRequest"
            route.text("responses", "201", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/RecordedRefundResponse"
            schema("RecordRefundRequest").let {
                it.strings("required") shouldContainExactly listOf("amount", "currency", "method")
                it.text("properties", "amount", "type") shouldBe "string"
                it.text("properties", "refundedAt", "format") shouldBe "date-time"
                it.text("properties", "externalReference", "\$ref") shouldBe "#/components/schemas/RefundExternalReference"
            }
            schema("RefundAllocationRequest").let {
                it.strings("required") shouldContainExactly listOf("paymentAllocationId", "amount")
                it.text("properties", "paymentAllocationId", "format") shouldBe "uuid"
                it.text("properties", "amount", "type") shouldBe "string"
            }
            schema("RecordedRefundResponse").let {
                it.strings("required") shouldContainExactly
                    listOf("refundId", "paymentId", "amount", "currency", "method", "refundedAt", "allocations", "reconciliation")
                it.text("properties", "reconciliation", "\$ref") shouldBe "#/components/schemas/PaymentReconciliationResponse"
            }
            schema("PaymentReconciliationResponse").strings("required") shouldContainExactly
                listOf(
                    "paymentAmount",
                    "totalRefunded",
                    "netReceived",
                    "grossAllocated",
                    "allocationReversals",
                    "refundAllocations",
                    "netAllocated",
                    "unallocated",
                    "currency",
                )
            route.text("responses", "422", "description").contains("`invariant_violated`") shouldBe true
        }

        test("documents a document's payment histories as complete ledger facts, linked by id, with derived reconciliation") {
            val route = operation("/financial-documents/{documentId}/payments", "get")
            route.text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/FinancialDocumentPaymentsResponse"
            listOf(
                "`commerce.financial-document.read`",
                "ever been allocated to any version of the lineage",
                "Discovery is historical",
                "its allocations to other lineages are included",
                "An empty list means the document exists and has no payments",
            ).forEach { route.text("description").contains(it) shouldBe true }
            schema("FinancialDocumentPaymentsResponse").let {
                it.strings("required") shouldContainExactly listOf("documentId", "payments")
                it.text("properties", "documentId", "format") shouldBe "uuid"
                it.text("properties", "payments", "items", "\$ref") shouldBe "#/components/schemas/PaymentHistoryResponse"
            }
            schema("PaymentHistoryResponse").let {
                it.strings("required") shouldContainExactly
                    listOf("payment", "allocations", "refunds", "refundAllocations", "reconciliation")
                it.text("properties", "payment", "\$ref") shouldBe "#/components/schemas/PaymentRecordResponse"
                it.text("properties", "allocations", "items", "\$ref") shouldBe "#/components/schemas/PaymentAllocationRecordResponse"
                it.text("properties", "refunds", "items", "\$ref") shouldBe "#/components/schemas/RefundRecordResponse"
                it.text("properties", "refundAllocations", "items", "\$ref") shouldBe
                    "#/components/schemas/RefundAllocationRecordResponse"
                it.text("properties", "reconciliation", "\$ref") shouldBe "#/components/schemas/PaymentReconciliationResponse"
            }
            // Facts, not mutation receipts: an allocation carries no historical document settlement.
            schema("PaymentAllocationRecordResponse").let {
                it.strings("required") shouldContainExactly
                    listOf("allocationId", "paymentId", "documentId", "documentVersion", "amount", "currency", "allocatedAt")
                it.at("properties").jsonObject.keys shouldBe it.strings("required").toSet()
                listOf("allocationId", "paymentId", "documentId").forEach { id -> it.text("properties", id, "format") shouldBe "uuid" }
                it.text("properties", "allocatedAt", "format") shouldBe "date-time"
            }
            schema("RefundRecordResponse").let {
                it.strings("required") shouldContainExactly listOf("refundId", "paymentId", "amount", "currency", "method", "refundedAt")
                it.at("properties").jsonObject.keys shouldBe it.strings("required").toSet() + "externalReference"
                it.text("properties", "externalReference", "\$ref") shouldBe "#/components/schemas/RefundExternalReference"
                it.text("properties", "refundedAt", "format") shouldBe "date-time"
            }
            schema("RefundAllocationRecordResponse").let {
                it.strings("required") shouldContainExactly
                    listOf("refundAllocationId", "refundId", "paymentAllocationId", "amount", "currency", "allocatedAt")
                it.at("properties").jsonObject.keys shouldBe it.strings("required").toSet()
                listOf("refundAllocationId", "refundId", "paymentAllocationId").forEach { id ->
                    it.text("properties", id, "format") shouldBe "uuid"
                }
            }
            // The example links every refund allocation to a refund and an allocation it carries.
            val example =
                route
                    .at("responses", "200", "content", "application/json", "example")
                    .at("payments")
                    .jsonArray
                    .single()
            val allocationIds = example.at("allocations").jsonArray.map { it.text("allocationId") }
            val refundIds = example.at("refunds").jsonArray.map { it.text("refundId") }
            allocationIds.size shouldBe 2
            example.at("refundAllocations").jsonArray.single().let {
                refundIds.contains(it.text("refundId")) shouldBe true
                allocationIds.contains(it.text("paymentAllocationId")) shouldBe true
            }
            example.text("reconciliation", "grossAllocated") shouldBe "350.00"
        }

        test("describes a financial document as immutable ledger facts, pricing source, and derived settlement") {
            val document = schema("FinancialDocumentResponse")
            document.strings("required") shouldContainExactly
                listOf("id", "version", "createdAt", "stage", "inquiryId", "pricing", "lines", "subtotal", "taxAmount", "total", "currency")
            val properties = document.at("properties").jsonObject
            properties.keys shouldBe
                setOf(
                    "id",
                    "version",
                    "createdAt",
                    "previousVersion",
                    "stage",
                    "inquiryId",
                    "pricing",
                    "lines",
                    "subtotal",
                    "taxAmount",
                    "total",
                    "currency",
                    "reconciliation",
                )
            listOf("id", "inquiryId").forEach { properties.getValue(it).text("format") shouldBe "uuid" }
            properties.getValue("createdAt").text("format") shouldBe "date-time"
            listOf("version", "previousVersion").forEach { properties.getValue(it).text("format") shouldBe "int32" }
            listOf("subtotal", "taxAmount", "total").forEach { properties.getValue(it).text("type") shouldBe "string" }
            properties.getValue("reconciliation").text("\$ref") shouldBe "#/components/schemas/DocumentReconciliation"
            schema("DocumentReconciliation").strings("required") shouldContainExactly
                listOf("grossAllocated", "netApplied", "balance", "currency")
            schema("FinancialDocumentLine").text("properties", "id", "format") shouldBe "uuid"
            // Settlement is derived: nothing in the document is a stored payment status.
            fionaOpenApiDocument().contains("paymentStatus") shouldBe false
            listOf("receivedAt", "allocatedAt").forEach {
                schema("RecordedPaymentResponse").text("properties", it, "format") shouldBe "date-time"
            }
        }

        test("documents both payment conflicts: a stale document version, and an external reference already recorded") {
            val conflict = operation("/financial-documents/{documentId}/payments", "post").text("responses", "409", "description")
            conflict shouldStartWith "`conflict`"
            listOf("`documentVersion` is no longer the document's latest version", "already recorded for another payment").forEach {
                conflict.contains(it) shouldBe true
            }
        }

        test("describes every financial-document path identifier as a required UUID path parameter") {
            operations.keys
                .filter { (path) ->
                    path.startsWith("/financial-documents") ||
                        path.startsWith("/inquiries/{inquiryId}/") ||
                        path.startsWith("/payments/{paymentId}/")
                }.forEach { (path, method) ->
                    val parameter = operation(path, method).at("parameters").jsonArray.single()
                    parameter.text("in") shouldBe "path"
                    parameter.at("required") shouldBe JsonPrimitive(true)
                    parameter.text("schema", "format") shouldBe "uuid"
                }
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
            schemas.keys shouldBe fionaSchemas.toSet() + adminSchemas + reached
        }

        test("gives each body an example that satisfies its schema's required properties") {
            operations.keys.forEach { (path, method) ->
                val operation = operation(path, method)
                val contents =
                    listOfNotNull(operation["requestBody"]?.at("content", "application/json")) +
                        operation
                            .at("responses")
                            .jsonObject.values
                            .mapNotNull { it.jsonObject["content"]?.jsonObject?.get("application/json") }
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
