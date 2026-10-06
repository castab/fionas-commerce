package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.fionaVersion
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.inquiry.ZipCode
import io.github.castab.fionas.commerce.openapi.fionaOpenApiDocument
import io.github.castab.fionas.commerce.testing.metadataAuth
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

        test("staff request composes existing detail schemas and documents both permissions") {
            val route = operation("/staff/requests/{inquiryId}", "get")
            route.text("operationId") shouldBe "readStaffRequest"
            val id = route.at("parameters").jsonArray.single { it.text("name") == "inquiryId" }
            id.text("in") shouldBe "path"
            id.at("required").jsonPrimitive.content shouldBe "true"
            id.text("schema", "format") shouldBe "uuid"
            route.text("description") shouldContain "fionas.inquiries.read"
            route.text("description") shouldContain "commerce.financial-document.read"
            route.text("description") shouldContain "BOTH"
            route.text("description") shouldContain "INITIAL_ESTIMATE"
            route.text("description") shouldContain "financial.reconciliation is always present"
            route.text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/StaffRequestResponse"
            val response = schema("StaffRequestResponse")
            response.strings("required") shouldContainExactly listOf("inquiry", "financial", "suggestedDepositTerms", "depositRequirement")
            response.text("properties", "inquiry", "\$ref") shouldBe "#/components/schemas/InquiryResponse"
            response.text("properties", "financial", "\$ref") shouldBe "#/components/schemas/FinancialDocumentResponse"
            response.text("properties", "financial", "description") shouldContain "financial.reconciliation is always present"
        }

        test("proposal operations expose explicit tokens, shared terms/pricing schemas and exact publication identities") {
            schema("IssueInquiryProposalRequest").strings("required") shouldContainExactly listOf("expectedDocumentVersion", "terms")
            schema("ReviseInquiryQuoteProposalRequest").strings("required") shouldContainExactly
                listOf("expectedDocumentVersion", "expectedDepositRequirementRevision", "pricingInputs", "terms")
            schema("ReviseInquiryProposalDepositRequest").strings("required") shouldContainExactly
                listOf("expectedDocumentVersion", "expectedDepositRequirementRevision", "terms")
            schema("ReviseInquiryQuoteProposalRequest").text("properties", "pricingInputs", "\$ref") shouldBe
                "#/components/schemas/CreateInquiryEstimateRequest"
            val requests =
                listOf("IssueInquiryProposalRequest", "ReviseInquiryQuoteProposalRequest", "ReviseInquiryProposalDepositRequest")
            requests.forEach { name ->
                schema(name).text("properties", "terms", "\$ref") shouldBe "#/components/schemas/DepositTermsRequest"
                schema(name).at("properties").jsonObject.containsKey("documentId") shouldBe false
                schema(name).text("properties", "expectedDocumentVersion", "format") shouldBe "int32"
            }
            schema("IssuedInquiryProposalResponse").strings("required") shouldContainExactly
                listOf("proposal", "financial", "depositRequirement")
            schema("InquiryProposalResponse").strings("required") shouldContainExactly
                listOf(
                    "id",
                    "inquiryId",
                    "documentId",
                    "documentVersion",
                    "depositRequirementRevision",
                    "issuedAt",
                    "principalKind",
                    "principalId",
                    "issuanceKind",
                )
            schema("StaffRequestResponse").text("properties", "proposal", "\$ref") shouldBe "#/components/schemas/InquiryProposalResponse"
            val issuances =
                listOf(
                    Triple("", 2, "INITIAL"),
                    Triple("/quote-revisions", 3, "QUOTE_REVISED"),
                    Triple("/deposit-revisions", 2, "DEPOSIT_REVISED"),
                )
            issuances.forEach { (suffix, version, kind) ->
                val example =
                    operation("/staff/requests/{inquiryId}/proposals$suffix", "post")
                        .at("responses", "200", "content", "application/json", "example")
                val revision = if (kind == "INITIAL") 1 else 2
                example.text("proposal", "issuanceKind") shouldBe kind
                example.at("proposal", "documentVersion").jsonPrimitive.int shouldBe version
                example.at("financial", "version").jsonPrimitive.int shouldBe version
                example.at("proposal", "depositRequirementRevision").jsonPrimitive.int shouldBe revision
                example.at("depositRequirement", "revision").jsonPrimitive.int shouldBe revision
                example.at("depositRequirement", "approvalDocumentVersion").jsonPrimitive.int shouldBe version
            }
            operation("/staff/requests/{inquiryId}", "get").text("description") shouldNotContain "existing Quote transition"
            operation("/financial-documents/{documentId}/quote", "post").text("description") shouldContain
                "/staff/requests/{inquiryId}/proposals"
        }

        test("staff dashboard schemas describe attention reasons, enrichment and both live permissions") {
            val route = operation("/staff/dashboard", "get")
            route.text("operationId") shouldBe "readStaffDashboard"
            route.text("description") shouldContain "Requires BOTH `fionas.inquiries.read` and `commerce.financial-document.read`"
            route.text("description") shouldContain "Cache-Control: no-store"
            route.text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/StaffDashboardResponse"
            schema("StaffDashboardResponse").strings("required") shouldContainExactly listOf("asOf", "summary", "workQueue")
            schema("StaffDashboardResponse").text("properties", "asOf", "format") shouldBe "date-time"
            schema("StaffDashboardSummaryResponse").strings("required") shouldContainExactly
                listOf("new", "quoted", "booked", "needsClosing")
            schema("StaffDashboardWorkQueueResponse").strings("required") shouldContainExactly
                listOf("needsReply", "needsQuote", "needsResolution")
            val queue = schema("StaffWorkQueueResponse")
            queue.strings("required") shouldContainExactly listOf("items")
            queue.at("properties").jsonObject.keys shouldBe setOf("items")
            val item = schema("StaffDashboardItemResponse")
            item.strings("required") shouldContainExactly
                listOf(
                    "inquiryId",
                    "customerId",
                    "customerName",
                    "eventDate",
                    "eventType",
                    "stage",
                    "documentId",
                    "version",
                    "financialStage",
                    "total",
                    "totalQualifier",
                    "balance",
                    "currency",
                    "inquiryCreatedAt",
                    "latestDocumentVersionAt",
                    "attentionSince",
                    "reasons",
                )
            item.strings("properties", "totalQualifier", "enum") shouldContainExactly listOf("EXACT", "FROM")
            item.text("properties", "totalQualifier", "description") shouldContain "minimum guest count"
            item.text("properties", "totalQualifier", "description") shouldContain "Quote and Invoice totals are EXACT"
            listOf("total", "balance", "currency").forEach { item.text("properties", it, "type") shouldBe "string" }
            listOf("inquiryId", "customerId", "documentId").forEach { item.text("properties", it, "format") shouldBe "uuid" }
            item.text("properties", "eventDate", "format") shouldBe "date"
            listOf("inquiryCreatedAt", "latestDocumentVersionAt", "servedAt", "attentionSince").forEach {
                item.text("properties", it, "format") shouldBe "date-time"
            }
            item.strings("properties", "stage", "enum") shouldContainExactly listOf("REQUESTED", "QUOTED", "BOOKED", "SERVED", "CLOSED")
            item.strings("properties", "reasons", "items", "enum") shouldContainExactly
                listOf(
                    "CUSTOMER_COMMUNICATION_UNACKNOWLEDGED",
                    "NEEDS_QUOTE",
                    "QUOTE_STALE",
                    "EVENT_DATE_PASSED_UNSERVED",
                    "SERVED_WITH_BALANCE_DUE",
                    "READY_TO_CLOSE",
                )
        }

        test("login documents its rate limit") {
            operation("/auth/login", "post").text("responses", "429", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/ErrorResponse"
            operation("/auth/login", "post").text("responses", "429", "content", "application/json", "example", "code") shouldBe
                "rate_limited"
        }

        // Every operation and its expected statuses, as the implementation answers them.
        val operations =
            mapOf(
                Triple("/inquiries/{inquiryId}/communications/acknowledge", "post", "acknowledgeInquiryCommunication") to
                    listOf("204", "400", "401", "403", "404", "500"),
                Triple("/staff/dashboard", "get", "readStaffDashboard") to listOf("200", "401", "403", "500"),
                Triple("/staff/requests/{inquiryId}", "get", "readStaffRequest") to listOf("200", "400", "401", "403", "404", "500"),
                Triple("/staff/requests/{inquiryId}/proposals", "post", "issueInquiryProposal") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/staff/requests/{inquiryId}/proposals/quote-revisions", "post", "reviseInquiryQuoteProposal") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/staff/requests/{inquiryId}/proposals/deposit-revisions", "post", "reviseInquiryProposalDeposit") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/inquiry-form", "get", "getInquiryForm") to listOf("200", "401", "403", "404", "500"),
                Triple("/inquiries", "post", "createInquiry") to listOf("201", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/inquiries", "get", "listInquiries") to listOf("200", "400", "401", "403", "422", "500"),
                Triple("/inquiries/{inquiryId}", "get", "getInquiry") to listOf("200", "400", "401", "403", "404", "500"),
                Triple("/estimate-preview", "post", "previewEstimate") to listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/inquiries/{inquiryId}/estimates", "post", "createInquiryEstimate") to
                    listOf("201", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/inquiries/{inquiryId}/financial-documents", "post", "createInquiryFinancialDocument") to
                    listOf("201", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/inquiries/{inquiryId}/served", "post", "markInquiryServed") to
                    listOf("200", "400", "401", "403", "404", "409", "500"),
                Triple("/inquiries/{inquiryId}/close", "post", "closeInquiry") to
                    listOf("200", "400", "401", "403", "404", "409", "500"),
                Triple("/inquiries/{inquiryId}/financial-documents", "get", "listInquiryFinancialDocuments") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/financial-documents/{documentId}", "get", "getFinancialDocument") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/financial-documents/{documentId}/history", "get", "getFinancialDocumentHistory") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/financial-documents/{documentId}/deposit-requirement", "get", "getFinancialDocumentDepositRequirement") to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple(
                    "/financial-documents/{documentId}/deposit-requirement/history",
                    "get",
                    "getFinancialDocumentDepositRequirementHistory",
                ) to
                    listOf("200", "400", "401", "403", "404", "500"),
                Triple("/financial-documents/{documentId}/deposit-requirement", "put", "setFinancialDocumentDepositRequirement") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/financial-documents/{documentId}/deposit-requirement", "delete", "withdrawFinancialDocumentDepositRequirement") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/financial-documents/query", "post", "queryFinancialDocumentLineages") to
                    listOf("200", "400", "401", "403", "404", "422", "500"),
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
                Triple("/auth/login", "post", "login") to listOf("204", "400", "401", "403", "429", "500"),
                Triple("/auth/logout", "post", "logout") to listOf("204", "403", "500"),
                Triple("/auth/me", "get", "getCurrentUser") to listOf("200", "401", "403", "500"),
                Triple("/admin/users/{userId}/credentials/password", "put", "setStaffPassword") to
                    listOf("204", "400", "401", "403", "404", "422", "500"),
            )

        // Each Fiona operation's tag.
        val tags =
            mapOf(
                "readStaffDashboard" to "Staff dashboard",
                "readStaffRequest" to "Staff requests",
                "issueInquiryProposal" to "Staff proposals",
                "reviseInquiryQuoteProposal" to "Staff proposals",
                "reviseInquiryProposalDeposit" to "Staff proposals",
                "acknowledgeInquiryCommunication" to "Inquiries",
                "getInquiryForm" to "Inquiries",
                "createInquiry" to "Inquiries",
                "listInquiries" to "Inquiries",
                "getInquiry" to "Inquiries",
                "markInquiryServed" to "Inquiries",
                "closeInquiry" to "Inquiries",
                "previewEstimate" to "Estimates",
                "createInquiryEstimate" to "Financial documents",
                "createInquiryFinancialDocument" to "Financial documents",
                "listInquiryFinancialDocuments" to "Financial documents",
                "getFinancialDocument" to "Financial documents",
                "getFinancialDocumentHistory" to "Financial documents",
                "getFinancialDocumentDepositRequirement" to "Deposit requirements",
                "getFinancialDocumentDepositRequirementHistory" to "Deposit requirements",
                "setFinancialDocumentDepositRequirement" to "Deposit requirements",
                "withdrawFinancialDocumentDepositRequirement" to "Deposit requirements",
                "queryFinancialDocumentLineages" to "Deposit requirements",
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
                Triple("/offering-catalog/categories", "get", "fionasOfferingsListCategories"),
                Triple("/offering-catalog/categories", "post", "fionasOfferingsAddCategory"),
                Triple("/offering-catalog/categories/{categoryKey}", "get", "fionasOfferingsGetCategory"),
                Triple("/offering-catalog/categories/{categoryKey}", "put", "fionasOfferingsUpdateCategory"),
                Triple("/offering-catalog/categories/{categoryKey}", "delete", "fionasOfferingsRetireCategory"),
                Triple("/offering-catalog/categories/{categoryKey}/restore", "post", "fionasOfferingsRestoreCategory"),
                Triple("/offering-catalog/categories/{categoryKey}/offerings", "get", "fionasOfferingsListCategoryOfferings"),
                Triple("/offering-catalog/offerings", "get", "fionasOfferingsListOfferings"),
                Triple("/offering-catalog/offerings", "post", "fionasOfferingsAddOfferings"),
                Triple("/offering-catalog/offerings/{offeringKey}", "get", "fionasOfferingsGetOffering"),
                Triple("/offering-catalog/offerings", "put", "fionasOfferingsUpdateOfferings"),
                Triple("/offering-catalog/offerings/retire", "post", "fionasOfferingsRetireOfferings"),
                Triple("/offering-catalog/offerings/restore", "post", "fionasOfferingsRestoreOfferings"),
                Triple("/offering-catalog/retired/offerings", "get", "fionasOfferingsListRetiredOfferings"),
                Triple("/offering-catalog/retired/categories", "get", "fionasOfferingsListRetiredCategories"),
            )

        // commerce-runtime's service token endpoint, mounted by Fiona.
        val serviceAuthenticationOperations =
            mapOf(Triple("/auth/service/token", "post", "serviceAuthenticationIssueToken") to listOf("200", "400", "401"))

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
                // Commerce 0.0.20 administers service credentials inside the same capability.
                Triple("/admin/access/services/{serviceId}/credentials", "get", "authorizationServiceCredentials"),
                Triple("/admin/access/services/{serviceId}/credentials", "post", "authorizationCreateServiceCredential"),
                Triple("/admin/access/services/{serviceId}/credentials/{credentialId}", "delete", "authorizationRevokeServiceCredential"),
                Triple("/admin/access/roles", "get", "authorizationListRoles"),
                Triple("/admin/access/roles", "post", "authorizationCreateRole"),
                Triple("/admin/access/roles/{roleKey}", "get", "authorizationGetRole"),
                Triple("/admin/access/roles/{roleKey}", "patch", "authorizationUpdateRole"),
                Triple("/admin/access/roles/{roleKey}", "delete", "authorizationDeleteRole"),
                Triple("/admin/access/roles/{roleKey}/permissions", "put", "authorizationReplaceRolePermissions"),
                Triple("/admin/access/permissions", "get", "authorizationListPermissions"),
            )

        // commerce-runtime's current-principal capability, where Fiona binds it.
        val principalOperations = listOf(Triple("/authorization/me", "get", "authorizationCurrentPrincipal"))

        // The schemas Fiona itself describes; every other one is commerce-runtime's.
        val fionaSchemas =
            listOf(
                "StaffDashboardResponse",
                "StaffRequestResponse",
                "InquiryProposalResponse",
                "IssuedInquiryProposalResponse",
                "IssueInquiryProposalRequest",
                "ReviseInquiryQuoteProposalRequest",
                "ReviseInquiryProposalDepositRequest",
                "StaffDashboardSummaryResponse",
                "StaffDashboardWorkQueueResponse",
                "StaffWorkQueueResponse",
                "StaffDashboardItemResponse",
                "CurrentDepositRequirementResponse",
                "CurrentDepositRequirementResponse_NONE",
                "CurrentDepositRequirementResponse_ACTIVE",
                "CurrentDepositRequirementResponse_WITHDRAWN",
                "DepositTermsRequest",
                "DepositTermsRequest_FIXED",
                "DepositTermsRequest_PERCENTAGE",
                "DepositMoneyResponse",
                "DepositRequirementHistoryResponse",
                "HistoricalDepositRequirementResponse",
                "HistoricalDepositRequirementResponse_ACTIVE",
                "HistoricalDepositRequirementResponse_WITHDRAWN",
                "SetDepositRequirementRequest",
                "WithdrawDepositRequirementRequest",
                "QueryFinancialLineagesRequest",
                "FinancialLineagesResponse",
                "FinancialLineageResponse",
                "FinancialLineageReconciliationResponse",
                "FinancialLineageActivityResponse",
                "InquiryFormResponse",
                "InquiryFormSectionResponse",
                "InquiryFormFieldResponse",
                "InquiryFormPresentation",
                "InquiryFormIntegerOption",
                "InquiryFormStringOption",
                "InquiryPricingPreviewResponse",
                "InquiryDurationPricingResponse",
                "InquiryDurationOfferingContributionResponse",
                "InquiryToppingAdjustmentResponse",
                "InquiryFormInputResponse",
                "InquiryFormInputResponse_TEXT",
                "InquiryFormInputResponse_EMAIL",
                "InquiryFormInputResponse_INTEGER",
                "InquiryFormInputResponse_BOOLEAN",
                "InquiryFormInputResponse_INTEGER_CHOICE",
                "InquiryFormInputResponse_DATE",
                "InquiryFormInputResponse_STRING_CHOICE",
                "InquiryFormInputResponse_OFFERING_CHOICE",
                "CreateInquiryRequest",
                "InquiryPricingInputs",
                "InquiryReceiptResponse",
                "InquiryResponse",
                "InquiryLifecycleResponse",
                "InquiryMilestoneResponse",
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
                "ServiceCredentialsDto",
                "ServiceCredentialDto",
                "IssuedServiceCredentialDto",
                "ServiceCredentialWriteDto",
                "RolesDto",
                "RoleDto",
                "RoleWriteDto",
                "RoleProfileDto",
                "PermissionKeysDto",
                "PermissionsDto",
                "PermissionDto",
                "CurrentPrincipalDto",
                "PrincipalSummaryDto",
            )

        // The runtime's service token endpoint bodies, described from their serial descriptors.
        val serviceAuthenticationSchemas = setOf("ServiceAccessTokenRequestDto", "ServiceAccessTokenDto")

        test("is an OpenAPI 3.1 document of Fiona's Commerce API at the application's version") {
            document.text("openapi") shouldBe "3.1.0"
            document.text("info", "title") shouldBe "Fiona's Commerce API"
            document.text("info", "version") shouldBe fionaVersion()
            document.at("tags").jsonArray.map { it.text("name") } shouldContainExactlyInAnyOrder
                listOf(
                    "Staff dashboard",
                    "Staff requests",
                    "Staff proposals",
                    "Inquiries",
                    "Estimates",
                    "Financial documents",
                    "Deposit requirements",
                    "Payments",
                    "Authentication",
                    "Staff administration",
                    "Authorization",
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

        /** An operation's security requirements: each object is one alternative (OR), its keys required together (AND). */
        fun requirements(
            path: String,
            method: String,
        ): List<Set<String>> = (operation(path, method)["security"] as? JsonArray).orEmpty().map { it.jsonObject.keys }

        val eitherTransport = listOf(setOf("staffSession"), setOf("serviceAccessToken"))

        test("declares the two authentication transports: the staff session cookie and the service bearer token") {
            document.at("components", "securitySchemes").jsonObject.keys shouldBe setOf("staffSession", "serviceAccessToken")
            val session = document.at("components", "securitySchemes", "staffSession")
            session.text("type") shouldBe "apiKey"
            session.text("in") shouldBe "cookie"
            session.text("name") shouldBe "__Host-fionas_session"
            val bearer = document.at("components", "securitySchemes", "serviceAccessToken")
            bearer.text("type") shouldBe "http"
            bearer.text("scheme") shouldBe "bearer"
            fionaOpenApiDocument() shouldNotContain "fionasUiApiKey"
        }

        test("every Fiona route behind the shared AccessControl accepts a staff session OR a service token, never both together") {
            operations.keys.forEach { (path, method, operationId) ->
                withClue(operationId) {
                    requirements(path, method) shouldBe
                        when (operationId) {
                            // Public: credentials obtain a session, so none is required.
                            "login" -> emptyList()
                            // Logout revokes browser sessions only and is idempotent cleanup without one:
                            // staffSession OR anonymous ({}). A service token is never advertised for it.
                            "logout" -> listOf(setOf("staffSession"), emptySet())
                            else -> eitherTransport
                        }
                }
            }
            // The customer routes are not service-only: a staff user holding the permission is accepted too.
            listOf("/inquiry-form" to "get", "/estimate-preview" to "post", "/inquiries" to "post").forEach { (path, method) ->
                requirements(path, method) shouldBe eitherTransport
            }
            // Representative staff routes: permissions decide, whichever principal kind holds them.
            listOf("/inquiries" to "get", "/payments" to "post", "/admin/users/{userId}/credentials/password" to "put")
                .forEach { (path, method) -> requirements(path, method) shouldBe eitherTransport }
            // /auth/me authenticates either transport, then rejects a SERVICE with its documented 403.
            requirements("/auth/me", "get") shouldBe eitherTransport
            operation("/auth/me", "get").text("responses", "403", "description") shouldContain "SERVICE"
        }

        test("logout is staffSession OR anonymous, never a service bearer token") {
            val logout = requirements("/auth/logout", "post")
            logout shouldBe listOf(setOf("staffSession"), emptySet())
            logout.flatten() shouldNotContain "serviceAccessToken"
            operation("/auth/logout", "post").text("responses", "403", "description") shouldContain "SERVICE"
        }

        test("public authentication endpoints require neither scheme") {
            requirements("/auth/login", "post") shouldBe emptyList()
            // A credential obtains a token, so the token endpoint cannot require one.
            requirements("/auth/service/token", "post") shouldBe emptyList()
            operation("/auth/service/token", "post").strings("tags") shouldContainExactly listOf("Authentication")
        }

        test("runtime capability routes carry no host security metadata yet, an upstream gap rather than a Fiona choice") {
            // commerce-runtime 0.0.22 lets a host neither add security to its capability routes nor marks its public
            // Offerings reads NoSecurity, so a contract-wide default would mislabel those reads. Its protected routes
            // still enforce Fiona's same AccessControl; this pins the gap so a runtime that closes it is noticed.
            (offeringOperations + adminOperations + principalOperations).forEach { (path, method, operationId) ->
                withClue(operationId) { requirements(path, method) shouldBe emptyList() }
            }
        }

        test("describes exactly Fiona and bound runtime capability routes, excluding /health and /ready") {
            document.at("paths").jsonObject.mapValues { (_, methods) -> methods.jsonObject.keys } shouldBe
                (operations.keys + offeringOperations + adminOperations + principalOperations + serviceAuthenticationOperations.keys)
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

        test("mounts commerce-runtime's service token endpoint unchanged at /auth/service/token") {
            serviceAuthenticationOperations.forEach { (route, statuses) ->
                val (path, method, operationId) = route
                operation(path, method).text("operationId") shouldBe operationId
                operation(path, method).at("responses").jsonObject.keys shouldContainExactlyInAnyOrder statuses
            }
            schema("ServiceAccessTokenRequestDto").strings("required") shouldContainExactlyInAnyOrder listOf("serviceId", "secret")
            val token = schema("ServiceAccessTokenDto")
            token.strings("required") shouldContainExactlyInAnyOrder listOf("accessToken", "tokenType", "expiresAt", "expiresIn")
            token.text("properties", "expiresIn", "type") shouldBe "integer"
            token.text("properties", "expiresIn", "format") shouldBe "int64"
        }

        test("mounts commerce-runtime's authorization administration contract unchanged") {
            adminOperations.forEach { (path, method, operationId) ->
                operation(path, method).text("operationId") shouldBe operationId
                operation(path, method).strings("tags") shouldContainExactly listOf("Staff administration")
            }
        }

        test("mounts commerce-runtime's current-principal route under the Authorization tag") {
            principalOperations.forEach { (path, method, operationId) ->
                val route = operation(path, method)
                route.text("operationId") shouldBe operationId
                route.strings("tags") shouldContainExactly listOf("Authorization")
                route.at("responses").jsonObject.keys shouldContainExactlyInAnyOrder listOf("200", "401")
                route.text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                    "#/components/schemas/CurrentPrincipalDto"
                (route["security"] as? JsonArray).isNullOrEmpty() shouldBe true
            }
            // Fiona's staff profile remains its own Authentication route.
            operation("/auth/me", "get").text("operationId") shouldBe "getCurrentUser"
        }

        test("mounts one permission catalog route, so every operationId is unique") {
            // commerce-runtime 0.0.22's standalone catalog capability reuses the administration route's
            // fixed authorizationListPermissions operationId, so only the administration route is mounted.
            document
                .at("paths")
                .jsonObject.keys
                .filter { it.endsWith("/permissions") && !it.contains('{') } shouldBe
                listOf("/admin/access/permissions")
            val operationIds =
                document
                    .at("paths")
                    .jsonObject.values
                    .flatMap { methods -> methods.jsonObject.values.map { it.text("operationId") } }
            operationIds.size shouldBe operationIds.toSet().size
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

        test("offering batches expose runtime item keys, states, result arrays, and retirement keys") {
            listOf(
                "post" to "/offering-catalog/offerings",
                "put" to "/offering-catalog/offerings",
                "post" to "/offering-catalog/offerings/restore",
            ).forEach { (method, path) ->
                val operation = operation(path, method)
                val request = operation.at("requestBody", "content", "application/json", "schema")
                val body = schema(request.text("\$ref").substringAfterLast('/'))
                body.strings("required") shouldContainExactlyInAnyOrder listOf("expectedRevision", "offerings")
                body.text("properties", "offerings", "type") shouldBe "array"
                body.text("properties", "offerings", "items", "\$ref") shouldBe "#/components/schemas/OfferingDto"
                val result =
                    operation.at(
                        "responses",
                        if (path.endsWith("/restore") || method == "put") "200" else "201",
                        "content",
                        "application/json",
                        "schema",
                    )
                val resultBody = schema(result.text("\$ref").substringAfterLast('/'))
                resultBody.strings("required") shouldContainExactlyInAnyOrder listOf("revision", "offerings")
            }
            val request =
                operation("/offering-catalog/offerings/retire", "post")
                    .at("requestBody", "content", "application/json", "schema")
            schema(request.text("\$ref").substringAfterLast('/')).let {
                it.strings("required") shouldContainExactlyInAnyOrder listOf("expectedRevision", "keys")
                it.text("properties", "keys", "type") shouldBe "array"
                it.text("properties", "keys", "items", "type") shouldBe "string"
            }
            document.at("paths").jsonObject.containsKey("/offering-catalog/revisions/{revision}") shouldBe false
            document.at("paths").jsonObject.containsKey("/offering-catalog/offerings/{offeringKey}/restore") shouldBe false
            document.at("paths", "/offering-catalog/offerings/{offeringKey}").jsonObject.keys shouldBe setOf("get")
            schema("OfferingDto").strings("required") shouldContainExactlyInAnyOrder
                listOf("key", "category", "displayName", "selectionState", "availability")
            listOf("InquiryFormIntegerOption", "InquiryFormStringOption", "OfferingDto").forEach { name ->
                val option = schema(name)
                listOf("badge", "statusNote", "infoNote").forEach { property ->
                    option.strings("required").contains(property) shouldBe false
                    if (name == "OfferingDto") {
                        option.strings("properties", property, "type") shouldContainExactly listOf("string", "null")
                    } else {
                        option.text("properties", property, "type") shouldBe "string"
                    }
                }
            }
        }

        test("Category retirement retains the required integer expectedRevision query parameter") {
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
                                !path.endsWith("/restore") &&
                                !path.endsWith("/retire")
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
                    if (code == "rate_limited") {
                        (path to method) shouldBe ("/auth/login" to "post")
                        status shouldBe "429"
                    } else if (code == "CATALOG_REVISION_STALE") {
                        method shouldBe "post"
                        listOf(
                            "/inquiries",
                            "/estimate-preview",
                            "/inquiries/{inquiryId}/estimates",
                            "/inquiries/{inquiryId}/financial-documents",
                            "/financial-documents/{documentId}/change-orders",
                        ).contains(path) shouldBe true
                        status shouldBe "409"
                    } else {
                        ErrorCategory.entries
                            .single { it.code == code }
                            .status.code
                            .toString() shouldBe status
                    }
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

        test("describes the create request: required name, email and pricing inputs, an optional message, and their limits") {
            val body = operation("/inquiries", "post").at("requestBody")
            body.at("required") shouldBe JsonPrimitive(true)
            body.text("content", "application/json", "schema", "\$ref") shouldBe "#/components/schemas/CreateInquiryRequest"

            val request = schema("CreateInquiryRequest")
            request.text("type") shouldBe "object"
            request.strings("required") shouldContainExactly listOf("name", "email", "pricingInputs", "zipCode", "eventDate", "eventType")
            val properties = request.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly
                listOf("name", "email", "message", "pricingInputs", "zipCode", "eventDate", "eventType")
            properties.getValue("pricingInputs").text("\$ref") shouldBe "#/components/schemas/InquiryPricingInputs"
            properties.getValue("pricingInputs").text("description") shouldContain "Required"
            properties.getValue("pricingInputs").text("description") shouldNotContain "absent"
            val text = properties - listOf("pricingInputs", "eventDate", "eventType")
            text.values.forEach { it.text("type") shouldBe "string" }
            text.mapValues { (_, property) -> property.at("maxLength").jsonPrimitive.int } shouldBe
                mapOf(
                    "name" to CustomerName.MAX_LENGTH,
                    "email" to Email.MAX_LENGTH,
                    "message" to InquiryMessage.MAX_LENGTH,
                    "zipCode" to ZipCode.LENGTH,
                )
            // The server accepts any text and validates it itself, so no format is claimed for the email.
            properties
                .getValue("email")
                .jsonObject.keys
                .contains("format") shouldBe false
        }

        test("documents public selection eligibility and the semantic stale conflict using the runtime envelope") {
            val create = operation("/inquiries", "post")
            create.text("description") shouldContain "current catalog revision observed"
            create.text("description") shouldContain "public categories with enabled, available offerings"
            create.text("responses", "409", "description") shouldContain "CATALOG_REVISION_STALE"
            create.text("responses", "409", "description") shouldContain "IDEMPOTENCY_KEY_REUSED"
            create.text("responses", "409", "description") shouldContain "never automatically resubmit"
            create.text("responses", "409", "content", "application/json", "schema", "\$ref") shouldBe "#/components/schemas/ErrorResponse"
            create.text("responses", "409", "content", "application/json", "example", "code") shouldBe "CATALOG_REVISION_STALE"
            schema("InquiryPricingInputs").text("properties", "catalogRevision", "description") shouldContain "latest revision observed"
            operation("/inquiry-form", "get").text("description") shouldContain "private, max-age=60, must-revalidate"
        }

        test("documents the required bounded Idempotency-Key header and replay before catalog validation") {
            val create = operation("/inquiries", "post")
            val key = create.at("parameters").jsonArray.single { it.text("name") == "Idempotency-Key" }
            key.text("in") shouldBe "header"
            key.at("required").jsonPrimitive.content shouldBe "true"
            key.text("schema", "type") shouldBe "string"
            key.at("schema", "minLength").jsonPrimitive.int shouldBe 1
            key.at("schema", "maxLength").jsonPrimitive.int shouldBe 128
            key.text("schema", "pattern") shouldBe "^[A-Za-z0-9_-]{1,128}$"
            key.text("description") shouldContain "SAME key"
            create.text("description") shouldContain "without catalog access or pricing"
            create.text("description") shouldContain "Failed attempts do not consume keys"
            // Neither staff routes nor other public operations acquire this header.
            operations.keys.filter { (path, method) -> path != "/inquiries" || method != "post" }.forEach { (path, method) ->
                operation(path, method)
                    .jsonObject["parameters"]
                    ?.jsonArray
                    .orEmpty()
                    .none { it.text("name") == "Idempotency-Key" } shouldBe true
            }
        }

        test("describes the public create response as a receipt of the new inquiry, naming no customer") {
            operation("/inquiries", "post").text("description") shouldContain "canonical initial Estimate"
            operation("/inquiries", "post").text("description").let {
                it shouldContain "`pricingInputs` is required"
                it shouldContain "exactly one canonical initial Estimate"
                it shouldContain "commit together or not at all"
                it shouldContain "does not expose the initial Estimate"
                it shouldNotContain "plain"
            }
            operation("/inquiries", "post").text("responses", "400", "description") shouldContain "`pricingInputs`"
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
            response.strings("required") shouldContainExactly
                listOf("id", "customerId", "name", "email", "createdAt", "pricingInputs", "zipCode", "eventDate", "eventType", "lifecycle")
            val properties = response.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly
                listOf(
                    "id",
                    "customerId",
                    "name",
                    "email",
                    "message",
                    "createdAt",
                    "pricingInputs",
                    "zipCode",
                    "eventDate",
                    "eventType",
                    "lifecycle",
                )
            properties.getValue("pricingInputs").text("\$ref") shouldBe "#/components/schemas/InquiryRequestedPricing"
            properties.getValue("lifecycle").text("\$ref") shouldBe "#/components/schemas/InquiryLifecycleResponse"
            (properties - "pricingInputs" - "lifecycle").values.forEach { it.text("type") shouldBe "string" }
            properties.filterValues { "format" in it.jsonObject }.mapValues { (_, property) -> property.text("format") } shouldBe
                mapOf("id" to "uuid", "customerId" to "uuid", "createdAt" to "date-time", "eventDate" to "date")
        }

        test("lifecycle actions expose projected stages and authenticated provenance without an arbitrary state input") {
            val lifecycle = schema("InquiryLifecycleResponse")
            lifecycle.strings("required") shouldContainExactly listOf("documentId", "stage")
            lifecycle.strings("properties", "stage", "enum") shouldContainExactly
                listOf("REQUESTED", "QUOTED", "BOOKED", "SERVED", "CLOSED")
            lifecycle.text("properties", "served", "\$ref") shouldBe "#/components/schemas/InquiryMilestoneResponse"
            lifecycle.text("properties", "closed", "\$ref") shouldBe "#/components/schemas/InquiryMilestoneResponse"
            val milestone = schema("InquiryMilestoneResponse")
            milestone.strings("required") shouldContainExactly listOf("occurredAt", "principalKind", "principalId")
            milestone.text("properties", "occurredAt", "format") shouldBe "date-time"
            milestone.text("properties", "principalId", "format") shouldBe "uuid"
            milestone.strings("properties", "principalKind", "enum") shouldContainExactly listOf("USER", "SERVICE")
            listOf("served", "close").forEach { action ->
                val route = operation("/inquiries/{inquiryId}/$action", "post")
                route.jsonObject.containsKey("requestBody") shouldBe false
                route.text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                    "#/components/schemas/InquiryLifecycleResponse"
                route.text("responses", "409", "description") shouldContain "illegal_transition"
                route.text("description") shouldContain "principal"
            }
            operation("/financial-documents/{documentId}/invoice", "post").text("description") shouldContain "RELATED"
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
                it.strings("required") shouldContainExactly
                    listOf("id", "customerId", "name", "email", "createdAt", "zipCode", "eventDate", "eventType")
                it
                    .at("properties")
                    .jsonObject.keys
                    .toList() shouldContainExactly
                    listOf("id", "customerId", "name", "email", "message", "createdAt", "zipCode", "eventDate", "eventType")
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
            fionaSchemas.filterNot { "oneOf" in schema(it) }.forEach { name ->
                schema(name)
                    .at("properties")
                    .jsonObject.values
                    .forEach { (it.jsonObject.keys intersect setOf("type", "\$ref")).size shouldBe 1 }
            }
        }

        test("deposit error descriptions enumerate only reachable codes with one response per status") {
            val path = "/financial-documents/{documentId}/deposit-requirement"
            val put = operation(path, "put")
            put.text("responses", "409", "description") shouldContain "`conflict`"
            put.text("responses", "409", "description") shouldNotContain "illegal_transition"
            put.text("responses", "422", "description").let {
                it shouldContain "`validation_failed`"
                it shouldContain "`invariant_violated`"
            }
            val delete = operation(path, "delete")
            delete.text("responses", "409", "description").let {
                it shouldContain "`conflict`"
                it shouldContain "`illegal_transition`"
            }
            delete.text("responses", "422", "description").let {
                it shouldContain "`validation_failed`"
                it shouldContain "expectedRequirementRevision"
                it shouldNotContain "invariant_violated"
            }
            operation("/financial-documents/query", "post").let {
                it.at("responses").jsonObject.containsKey("409") shouldBe false
                it.text("responses", "422", "description") shouldContain "`validation_failed`"
                it.text("responses", "422", "description") shouldNotContain "invariant_violated"
            }
            listOf(path, "$path/history").forEach {
                operation(it, "get").at("responses").jsonObject.keys shouldContainExactlyInAnyOrder
                    listOf("200", "400", "401", "403", "404", "500")
            }
            // Inspect executable metadata before rendering can collapse duplicate status entries.
            val access = metadataAuth.access
            listOf(
                getDepositRequirementRoute({ error("not called") }, access),
                getDepositRequirementHistoryRoute({ error("not called") }, access),
                setDepositRequirementRoute({ error("not called") }, access),
                withdrawDepositRequirementRoute({ error("not called") }, access),
                queryFinancialLineagesRoute({ error("not called") }, access),
            ).forEach { route ->
                route.meta.responses
                    .map { it.message.status }
                    .shouldBeUnique()
            }
        }

        test("deposit terms and current/history states describe distinct discriminated shapes") {
            mapOf(
                "DepositTermsRequest" to ("type" to listOf("FIXED", "PERCENTAGE")),
                "CurrentDepositRequirementResponse" to ("state" to listOf("ACTIVE", "NONE", "WITHDRAWN")),
                "HistoricalDepositRequirementResponse" to ("state" to listOf("ACTIVE", "WITHDRAWN")),
            ).forEach { (name, union) ->
                val (discriminator, tags) = union
                schema(name).text("discriminator", "propertyName") shouldBe discriminator
                schema(name).at("discriminator", "mapping").jsonObject.keys shouldContainExactlyInAnyOrder tags
                schema(name).at("oneOf").jsonArray.size shouldBe tags.size
                tags.forEach { tag ->
                    val variant = schema("${name}_$tag")
                    variant.text("properties", discriminator, "const") shouldBe tag
                    variant.strings("required").contains(discriminator) shouldBe true
                }
            }
            schema("DepositTermsRequest_FIXED").strings("required") shouldContainExactlyInAnyOrder listOf("type", "amount", "currency")
            schema("DepositTermsRequest_PERCENTAGE").strings("required") shouldContainExactlyInAnyOrder listOf("type", "percentage")
            schema("DepositTermsRequest_FIXED").text("properties", "amount", "type") shouldBe "string"
            schema("DepositTermsRequest_FIXED").at("additionalProperties") shouldBe JsonPrimitive(false)
            schema("DepositTermsRequest_PERCENTAGE").at("additionalProperties") shouldBe JsonPrimitive(false)
            schema("DepositTermsRequest_PERCENTAGE").text("properties", "percentage", "type") shouldBe "string"
            schema("CurrentDepositRequirementResponse_NONE").at("properties").jsonObject.keys shouldContainExactlyInAnyOrder
                listOf("documentId", "state")
            schema("CurrentDepositRequirementResponse_WITHDRAWN").at("properties").jsonObject.keys shouldContainExactlyInAnyOrder
                listOf("documentId", "revision", "previousRevision", "createdAt", "state")
            schema("HistoricalDepositRequirementResponse_ACTIVE")
                .at("properties")
                .jsonObject.keys
                .contains("satisfied") shouldBe false
            schema("CurrentDepositRequirementResponse_ACTIVE").strings("required").contains("satisfied") shouldBe true
            schema(
                "SetDepositRequirementRequest",
            ).strings("properties", "expectedRequirementRevision", "type") shouldContainExactlyInAnyOrder
                listOf("integer", "null")
            schema("SetDepositRequirementRequest").text("properties", "expectedRequirementRevision", "description") shouldContain
                "expects no requirement history"
            schema("WithdrawDepositRequirementRequest").at("properties").jsonObject.keys shouldBe setOf("expectedRequirementRevision")
            listOf("get", "put", "delete").forEach { method ->
                operation(
                    "/financial-documents/{documentId}/deposit-requirement",
                    method,
                ).text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                    "#/components/schemas/CurrentDepositRequirementResponse"
                operation("/financial-documents/{documentId}/deposit-requirement", method).text("description") shouldContain
                    if (method == "get") "commerce.financial-document.read" else "commerce.deposit-requirement.manage"
            }
            operation("/financial-documents/query", "post").text("description") shouldContain "preserved request order"
            listOf("put", "delete").forEach { method ->
                val description = operation("/financial-documents/{documentId}/deposit-requirement", method).text("description")
                description shouldContain "Canonical lineages with proposal history"
                description shouldContain "Invoice/BOOKED"
                description shouldContain "accepted deposit history is immutable"
                description shouldContain "RELATED lineages retain"
                description shouldNotContain "canonical Invoices remain eligible"
                description shouldNotContain "canonical Invoices retain withdrawal"
            }
        }

        test("inquiry form inputs have an explicit type union with distinct required discriminator values") {
            operation("/inquiry-form", "get").text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/InquiryFormResponse"
            schema("InquiryFormResponse").strings("required") shouldContainExactly
                listOf("definitionVersion", "catalogId", "catalogRevision", "sections", "pricingPreview")
            schema("InquiryFormFieldResponse").text("properties", "input", "\$ref") shouldBe
                "#/components/schemas/InquiryFormInputResponse"
            val union = schema("InquiryFormInputResponse")
            union.text("discriminator", "propertyName") shouldBe "type"
            val tags = listOf("TEXT", "EMAIL", "INTEGER", "BOOLEAN", "INTEGER_CHOICE", "OFFERING_CHOICE", "DATE", "STRING_CHOICE")
            union.at("oneOf").jsonArray.map { it.text("\$ref") } shouldContainExactlyInAnyOrder
                tags.map { "#/components/schemas/InquiryFormInputResponse_$it" }
            tags.forEach { tag ->
                val name = "InquiryFormInputResponse_$tag"
                union.text("discriminator", "mapping", tag) shouldBe "#/components/schemas/$name"
                schema(name).text("properties", "type", "const") shouldBe tag
                schema(name).strings("required").contains("type") shouldBe true
            }
            schema("InquiryFormInputResponse_INTEGER_CHOICE").text("properties", "options", "items", "\$ref") shouldBe
                "#/components/schemas/InquiryFormIntegerOption"
            schema("InquiryFormIntegerOption").text("properties", "value", "type") shouldBe "integer"
            schema("InquiryFormInputResponse_OFFERING_CHOICE").let {
                it.strings("required") shouldContainExactly listOf("category", "minSelections", "options", "type")
                it.text("properties", "options", "items", "\$ref") shouldBe "#/components/schemas/OfferingDto"
            }
            schema("InquiryFormPresentation").strings("properties", "control", "enum") shouldContainExactly
                listOf("TEXT", "TEXTAREA", "NUMBER", "CHECKBOX", "SELECT", "CARDS", "CHECKBOXES", "DATE", "CHIPS")
        }

        test("inquiry form v11 reuses required runtime state enums and documents public visibility and structural rejections") {
            val form = operation("/inquiry-form", "get")
            form.at("responses", "200", "content", "application/json", "example", "definitionVersion").jsonPrimitive.int shouldBe 11
            form
                .at("responses", "200", "content", "application/json", "example", "sections")
                .jsonArray
                .single { it.text("key") == "service" }
                .at("optional") shouldBe JsonPrimitive(false)
            val description = form.text("description")
            description shouldContain "hand-scooped flavors"
            val hand =
                form
                    .at("responses", "200", "content", "application/json", "example", "sections")
                    .jsonArray
                    .single { it.text("key") == "service" }
                    .at("fields")
                    .jsonArray
                    .single { it.text("key") == "offering:hand-scooped-flavor" }
            hand.text("submissionPointer") shouldBe "/pricingInputs/selections"
            hand.text("presentation", "control") shouldBe "CHIPS"
            hand.at("required") shouldBe JsonPrimitive(true)
            hand.at("input", "minSelections") shouldBe JsonPrimitive(4)
            hand.at("input", "maxSelections") shouldBe JsonPrimitive(4)
            val returning = hand.at("input", "options").jsonArray.single { it.text("key") == "hand-scooped-new-york-cheesecake" }
            returning.text("availability") shouldBe "UNAVAILABLE"
            returning.text("badge") shouldBe "Returning soon"
            returning.text("statusNote") shouldBe "Back on the menu this fall!"
            description shouldContain "Service configuration is required"
            description shouldNotContain "optional"
            description shouldContain "selectionState=ENABLED"
            description shouldContain "availability=UNAVAILABLE"
            description shouldContain "unselectable"
            description shouldContain "disabled"
            description shouldContain "retired"
            val offering = schema("OfferingDto")
            offering.strings("required").containsAll(listOf("selectionState", "availability")) shouldBe true
            offering.strings("properties", "selectionState", "enum") shouldContainExactly listOf("ENABLED", "DISABLED")
            offering.strings("properties", "availability", "enum") shouldContainExactly listOf("AVAILABLE", "UNAVAILABLE")
            schema("OfferingSelectionStateDto").strings("enum") shouldContainExactly listOf("ENABLED", "DISABLED")
            schema("OfferingAvailabilityDto").strings("enum") shouldContainExactly listOf("AVAILABLE", "UNAVAILABLE")
            listOf("/inquiries", "/estimate-preview").forEach { path ->
                val validation = operation(path, "post").text("responses", "422", "description")
                validation shouldContain "OFFERING_DISABLED"
                validation shouldContain "OFFERING_UNAVAILABLE"
            }
        }

        test("required event ZIP is constrained text and form text patterns are explicitly documented") {
            listOf("CreateInquiryRequest", "InquiryResponse", "InquiryListItem").forEach { name ->
                val body = schema(name)
                body.strings("required").contains("zipCode") shouldBe true
                body.text("properties", "zipCode", "type") shouldBe "string"
                body.text("properties", "zipCode", "pattern") shouldBe ZipCode.PATTERN
            }
            schema("CreateInquiryRequest").at("properties", "zipCode", "minLength").jsonPrimitive.int shouldBe ZipCode.LENGTH
            val text = schema("InquiryFormInputResponse_TEXT")
            text.text("properties", "pattern", "type") shouldBe "string"
            text.strings("required").contains("pattern") shouldBe false
        }

        test("required event fields expose date format and exact enum values in submission and staff schemas") {
            val values = listOf("BIRTHDAY", "WEDDING", "CORPORATE", "SCHOOL_EVENT", "NEIGHBORHOOD_EVENT", "OTHER")
            listOf("CreateInquiryRequest", "InquiryResponse", "InquiryListItem").forEach { name ->
                val body = schema(name)
                body.strings("required").containsAll(listOf("eventDate", "eventType")) shouldBe true
                body.text("properties", "eventDate", "type") shouldBe "string"
                body.text("properties", "eventDate", "format") shouldBe "date"
                body.strings("properties", "eventType", "enum") shouldContainExactly values
            }
            schema("InquiryFormInputResponse_STRING_CHOICE").text("properties", "options", "items", "\$ref") shouldBe
                "#/components/schemas/InquiryFormStringOption"
            schema("InquiryFormStringOption").text("properties", "value", "type") shouldBe "string"
            schema("InquiryFormInputResponse_DATE").strings("required") shouldContainExactly listOf("format", "type")
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

        test("inquiry pricing preview is concrete arithmetic metadata with decimal strings and no client monetary authority") {
            schema("InquiryFormResponse").text("properties", "pricingPreview", "\$ref") shouldBe
                "#/components/schemas/InquiryPricingPreviewResponse"
            schema("InquiryPricingPreviewResponse").let {
                it.strings("required") shouldContainExactly
                    listOf("currency", "guestQuantityDimension", "durationOptions", "perGuestAmount", "toppingAdjustment")
                it.text("properties", "durationOptions", "items", "\$ref") shouldBe "#/components/schemas/InquiryDurationPricingResponse"
                it.text("properties", "perGuestAmount", "type") shouldBe "string"
                it.text("properties", "toppingAdjustment", "\$ref") shouldBe "#/components/schemas/InquiryToppingAdjustmentResponse"
            }
            schema("InquiryDurationPricingResponse").let {
                it.strings("required") shouldContainExactly listOf("durationMinutes", "baseServiceAmount", "offeringContributions")
                it.text("properties", "durationMinutes", "type") shouldBe "integer"
                it.text("properties", "baseServiceAmount", "type") shouldBe "string"
                it.text("properties", "offeringContributions", "items", "\$ref") shouldBe
                    "#/components/schemas/InquiryDurationOfferingContributionResponse"
            }
            schema("InquiryDurationOfferingContributionResponse").let {
                it.strings("required") shouldContainExactly listOf("offeringKey", "amount")
                it.text("properties", "amount", "type") shouldBe "string"
            }
            schema("InquiryToppingAdjustmentResponse").let {
                it.strings("required") shouldContainExactly listOf("category", "includedSelections", "additionalSelectionPerGuestAmount")
                it.text("properties", "includedSelections", "type") shouldBe "integer"
                it.text("properties", "additionalSelectionPerGuestAmount", "type") shouldBe "string"
            }
            listOf(
                "CreateInquiryRequest",
                "InquiryPricingInputs",
                "EstimatePreviewRequest",
                "CreateInquiryEstimateRequest",
            ).forEach { name ->
                val properties = schema(name).at("properties").jsonObject.keys
                (properties intersect setOf("total", "amount", "lines", "pricingPreview")).isEmpty() shouldBe true
            }
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
                listOf("id", "version", "createdAt", "stage", "inquiryId", "lines", "subtotal", "taxAmount", "total", "currency")
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
                    path.startsWith("/financial-documents/{documentId}") ||
                        path.startsWith("/inquiries/{inquiryId}/") ||
                        path.startsWith("/payments/{paymentId}/")
                }.forEach { (path, method) ->
                    val parameter = operation(path, method).at("parameters").jsonArray.single()
                    parameter.text("in") shouldBe "path"
                    parameter.at("required") shouldBe JsonPrimitive(true)
                    parameter.text("schema", "format") shouldBe "uuid"
                }
        }

        test("resolves every reference, and holds only Fiona's and runtime capability schemas") {
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
            // Runtime 0.0.20 emits these enum definitions as well as inline enums on offering properties.
            val offeringStateSchemas = setOf("OfferingSelectionStateDto", "OfferingAvailabilityDto")
            schemas.keys shouldBe fionaSchemas.toSet() + adminSchemas + serviceAuthenticationSchemas + reached + offeringStateSchemas
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
                    var model = schema(content.text("schema", "\$ref").substringAfterLast('/'))
                    if ("oneOf" in model) {
                        val discriminator = model.text("discriminator", "propertyName")
                        val tag = content.text("example", discriminator)
                        model = schema(model.text("discriminator", "mapping", tag).substringAfterLast('/'))
                    }
                    val required = model.strings("required")
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
