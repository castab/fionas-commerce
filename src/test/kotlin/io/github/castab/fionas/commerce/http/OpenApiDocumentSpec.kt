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
 * Fiona serves no product catalog and no pricing endpoint: financial lines are already priced by
 * the authorized actor that commits them, and the document says so.
 */
class OpenApiDocumentSpec :
    FunSpec({
        val document = Json.parseToJsonElement(fionaOpenApiDocument()).jsonObject

        fun JsonElement.at(vararg path: String): JsonElement = path.fold(this) { node, key -> node.jsonObject.getValue(key) }

        fun JsonElement.text(vararg path: String) = at(*path).jsonPrimitive.content

        fun JsonElement.strings(vararg path: String) = at(*path).jsonArray.map { it.jsonPrimitive.content }

        fun schema(name: String) = document.at("components", "schemas", name).jsonObject

        test("change-order contract documents total policy and CLOSED canonical exclusion") {
            val operation = document.at("paths", "/financial-documents/{documentId}/change-orders", "post")
            operation.text("description") shouldContain "nonnegative"
            operation.text("description") shouldContain "CLOSED canonical Invoice"
            operation.at("responses").jsonObject shouldContainKey "422"
            operation.at("responses").jsonObject shouldContainKey "409"
            val quoteRevision = document.at("paths", "/staff/requests/{inquiryId}/proposals/quote-revisions", "post")
            quoteRevision.text("description") shouldContain "negative or zero totals"
            quoteRevision.text("description") shouldContain "No catalog or pricing policy is consulted"
        }

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
            response.strings("required") shouldContainExactly
                listOf("inquiry", "financial", "suggestedDepositTerms", "depositRequirement", "payments")
            response.text("properties", "inquiry", "\$ref") shouldBe "#/components/schemas/InquiryResponse"
            response.text("properties", "financial", "\$ref") shouldBe "#/components/schemas/FinancialDocumentResponse"
            response.text("properties", "financial", "description") shouldContain "financial.reconciliation is always present"
            response.text("properties", "payments", "type") shouldBe "array"
            response.text("properties", "payments", "items", "\$ref") shouldBe "#/components/schemas/PaymentHistoryResponse"
            listOf(
                "historical document versions",
                "allocations to other lineages",
                "accepted Quote version",
                "Reconciliation is derived",
                "empty collection",
            ).forEach { response.text("properties", "payments", "description") shouldContain it }
            route.text("description") shouldContain "REPEATABLE_READ"
        }

        test("proposal operations expose explicit tokens, shared terms/pricing schemas and exact publication identities") {
            schema("IssueInquiryProposalRequest").strings("required") shouldContainExactly listOf("expectedDocumentVersion", "terms")
            schema("ReviseInquiryQuoteProposalRequest").strings("required") shouldContainExactly
                listOf("expectedDocumentVersion", "expectedDepositRequirementRevision", "lines", "terms")
            schema("ReviseInquiryProposalDepositRequest").strings("required") shouldContainExactly
                listOf("expectedDocumentVersion", "expectedDepositRequirementRevision", "terms")
            schema("ReviseInquiryQuoteProposalRequest").text("properties", "lines", "items", "\$ref") shouldBe
                "#/components/schemas/ProposedLineRequest"
            schema("ReviseInquiryQuoteProposalRequest").text("properties", "servicePlan", "\$ref") shouldBe
                "#/components/schemas/ServicePlanRequest"
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
                    "issuedBy",
                    "issuanceKind",
                )
            schema("StaffRequestResponse").text("properties", "proposal", "\$ref") shouldBe "#/components/schemas/InquiryProposalResponse"
            val issuances =
                listOf(
                    // The composed churro example: Estimate v2 holds the staff lines, Quote v3 is published.
                    Triple("", 3, "INITIAL"),
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
                Triple("/staff/requests/{inquiryId}/quote-preview", "post", "previewInquiryQuote") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/staff/requests/{inquiryId}/proposals/quote-revisions", "post", "reviseInquiryQuoteProposal") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/staff/requests/{inquiryId}/proposals/deposit-revisions", "post", "reviseInquiryProposalDeposit") to
                    listOf("200", "400", "401", "403", "404", "409", "422", "500"),
                Triple("/inquiries", "post", "createInquiry") to listOf("201", "400", "401", "403", "409", "422", "500"),
                Triple("/inquiries", "get", "listInquiries") to listOf("200", "400", "401", "403", "422", "500"),
                Triple("/inquiries/{inquiryId}", "get", "getInquiry") to listOf("200", "400", "401", "403", "404", "500"),
                Triple("/inquiries/{inquiryId}/estimates", "post", "createInquiryEstimate") to
                    listOf("201", "400", "401", "403", "404", "422", "500"),
                Triple("/inquiries/{inquiryId}/financial-documents", "post", "createInquiryFinancialDocument") to
                    listOf("201", "400", "401", "403", "404", "422", "500"),
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
                "previewInquiryQuote" to "Staff proposals",
                "reviseInquiryQuoteProposal" to "Staff proposals",
                "reviseInquiryProposalDeposit" to "Staff proposals",
                "acknowledgeInquiryCommunication" to "Inquiries",
                "createInquiry" to "Inquiries",
                "listInquiries" to "Inquiries",
                "getInquiry" to "Inquiries",
                "markInquiryServed" to "Inquiries",
                "closeInquiry" to "Inquiries",
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
                "PreviewInquiryQuoteRequest",
                "InquiryQuotePreviewResponse",
                "QuotePreviewLineResponse",
                "QuoteDepositPreviewResponse",
                "ServicePlanPreviewResponse",
                "ServicePlanRequest",
                "ServicePlanResponse",
                "ServicePlanLineNoteResponse",
                "LineNoteRequest",
                "PricedLineRequest",
                "ProposedLineRequest",
                "LineAuthorshipResponse",
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
                "CreateInquiryRequest",
                "RequestedServiceRequest",
                "RequestedServiceItemRequest",
                "InquiryReceiptResponse",
                "InquiryResponse",
                "InquiryLifecycleResponse",
                "InquiryMilestoneResponse",
                "InquiryListResponse",
                "InquiryListItem",
                "CreateInquiryEstimateRequest",
                "CreateInquiryFinancialDocumentRequest",
                "ChangeOrderRequest",
                "StageTransitionRequest",
                "RecordPaymentRequest",
                "RecordStandalonePaymentRequest",
                "AllocatePaymentRequest",
                "PaymentExternalReference",
                "FinancialDocumentResponse",
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
                    "Financial documents",
                    "Deposit requirements",
                    "Payments",
                    "Authentication",
                    "Staff administration",
                    "Authorization",
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

        val staffTermsOperations =
            setOf(
                "createInquiryEstimate",
                "createInquiryFinancialDocument",
                "createChangeOrder",
                "previewInquiryQuote",
                "issueInquiryProposal",
                "reviseInquiryQuoteProposal",
                "reviseInquiryProposalDeposit",
                // Standalone deposit terms are staff-approved too; their reads stay open to either transport.
                "setFinancialDocumentDepositRequirement",
                "withdrawFinancialDocumentDepositRequirement",
            )

        test("line amounts document precise unit rates with settlement-exact subtotals and minor-unit tax") {
            listOf("PricedLineRequest", "ProposedLineRequest").forEach { name ->
                val properties = schema(name).at("properties")
                properties.text("unitPrice", "description") shouldContain "12 decimal places"
                properties.text("unitPrice", "description") shouldContain "minor units"
                properties.text("taxAmount", "description") shouldContain "minor-unit"
            }
            operation("/inquiries", "post").text("responses", "422", "description") shouldContain "unit rate with more than 12"
        }

        test("standalone deposit mutations advertise staff-session USER authority and its 403 causes; reads do not") {
            listOf("put", "delete").forEach { method ->
                val route = operation("/financial-documents/{documentId}/deposit-requirement", method)
                route.text("description") shouldContain "fionas.financial-terms.manage"
                route.text("responses", "403", "description") shouldContain "staff USER"
            }
            requirements("/financial-documents/{documentId}/deposit-requirement", "get") shouldBe eitherTransport
            requirements("/financial-documents/{documentId}/deposit-requirement/history", "get") shouldBe eitherTransport
        }

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

        test("each Fiona route documents exactly the transports its enforcement accepts, never both together") {
            operations.keys.forEach { (path, method, operationId) ->
                withClue(operationId) {
                    requirements(path, method) shouldBe
                        when (operationId) {
                            // Public: credentials obtain a session, so none is required.
                            "login" -> emptyList()
                            // Logout revokes browser sessions only and is idempotent cleanup without one:
                            // staffSession OR anonymous ({}). A service token is never advertised for it.
                            "logout" -> listOf(setOf("staffSession"), emptySet())
                            // Priced submission is the pricing authority's: only its service token is accepted.
                            "createInquiry" -> listOf(setOf("serviceAccessToken"))
                            // Staff-negotiated amounts need a verified staff USER, and only a session authenticates one.
                            in staffTermsOperations -> listOf(setOf("staffSession"))
                            else -> eitherTransport
                        }
                }
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
            // commerce-runtime 0.0.23 lets a host add no security to its capability routes. Its protected routes
            // still enforce Fiona's same AccessControl; this pins the gap so a runtime that closes it is noticed.
            (adminOperations + principalOperations).forEach { (path, method, operationId) ->
                withClue(operationId) { requirements(path, method) shouldBe emptyList() }
            }
        }

        test("describes exactly Fiona and bound runtime capability routes, excluding /health and /ready") {
            document.at("paths").jsonObject.mapValues { (_, methods) -> methods.jsonObject.keys } shouldBe
                (operations.keys + adminOperations + principalOperations + serviceAuthenticationOperations.keys)
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.toSet() }
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
            // commerce-runtime 0.0.23's standalone catalog capability reuses the administration route's
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

        test("has no unnamed Swagger UI operation group") {
            document.at("paths").jsonObject.values.forEach { methods ->
                methods.jsonObject.values.forEach { route ->
                    route.strings("tags").single().isNotBlank() shouldBe true
                }
            }
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
                    } else if (code == "IDEMPOTENCY_KEY_REUSED") {
                        (path to method) shouldBe ("/inquiries" to "post")
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

        test("describes the create request: customer and event facts, a requested service, and exact already-priced lines") {
            val body = operation("/inquiries", "post").at("requestBody")
            body.at("required") shouldBe JsonPrimitive(true)
            body.text("content", "application/json", "schema", "\$ref") shouldBe "#/components/schemas/CreateInquiryRequest"

            val request = schema("CreateInquiryRequest")
            request.text("type") shouldBe "object"
            request.strings("required") shouldContainExactly
                listOf("name", "email", "requestedService", "lines", "zipCode", "eventDate", "eventType")
            val properties = request.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly
                listOf("name", "email", "message", "requestedService", "lines", "zipCode", "eventDate", "eventType")
            properties.getValue("requestedService").text("\$ref") shouldBe "#/components/schemas/RequestedServiceRequest"
            properties.getValue("lines").text("type") shouldBe "array"
            properties.getValue("lines").text("items", "\$ref") shouldBe "#/components/schemas/PricedLineRequest"
            properties.getValue("lines").text("description") shouldContain "without repricing or any catalog check"
            val text = properties - listOf("requestedService", "lines", "eventDate", "eventType")
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
            // Amounts and quantities are exact decimal strings, never JSON numbers; no total is ever accepted.
            val line = schema("PricedLineRequest")
            line.strings("required") shouldContainExactly listOf("description", "unitPrice", "taxAmount", "currency")
            listOf("quantity", "unitPrice", "taxAmount").forEach {
                line.text("properties", it, "type") shouldBe "string"
                line.text("properties", it, "pattern") shouldBe SIGNED_DECIMAL
            }
            listOf("CreateInquiryRequest", "PricedLineRequest", "RequestedServiceRequest").forEach { name ->
                (schema(name).at("properties").jsonObject.keys intersect setOf("total", "subtotal", "catalogRevision")).isEmpty() shouldBe
                    true
            }
        }

        test("the priced submission is SERVICE-only and the requested service is descriptive, never a pricing input") {
            val create = operation("/inquiries", "post")
            listOf("SERVICE principal", "never reprices", "catalog revision", "IDEMPOTENCY_KEY_REUSED").forEach {
                (create.text("description") + create.text("responses", "409", "description")) shouldContain it
            }
            create.text("responses", "403", "description") shouldContain "not a SERVICE"
            create.text("responses", "409", "content", "application/json", "example", "code") shouldBe "IDEMPOTENCY_KEY_REUSED"
            val requested = schema("RequestedServiceRequest")
            requested.strings("required") shouldContainExactly listOf("guestCount")
            requested
                .at("properties")
                .jsonObject.keys
                .toList() shouldContainExactly
                listOf("guestCount", "guestCountIsMinimum", "durationMinutes", "items", "pricingReference")
            requested.text("properties", "items", "items", "\$ref") shouldBe "#/components/schemas/RequestedServiceItemRequest"
            requested.text("properties", "pricingReference", "description") shouldContain "never validates"
            schema("RequestedServiceItemRequest").strings("required") shouldContainExactly listOf("label")
        }

        test("documents the required bounded Idempotency-Key header and a replay that writes nothing") {
            val create = operation("/inquiries", "post")
            val key = create.at("parameters").jsonArray.single { it.text("name") == "Idempotency-Key" }
            key.text("in") shouldBe "header"
            key.at("required").jsonPrimitive.content shouldBe "true"
            key.text("schema", "type") shouldBe "string"
            key.at("schema", "minLength").jsonPrimitive.int shouldBe 1
            key.at("schema", "maxLength").jsonPrimitive.int shouldBe 128
            key.text("schema", "pattern") shouldBe "^[A-Za-z0-9_-]{1,128}$"
            key.text("description") shouldContain "SAME key"
            create.text("description") shouldContain "without writing anything"
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
                it shouldContain "exact already-priced lines"
                it shouldContain "commit together or not at all"
                it shouldContain "does not expose the Estimate"
                it shouldNotContain "plain"
            }
            operation("/inquiries", "post").text("responses", "400", "description") shouldContain "`lines`"
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

        test("describes the staff inquiry response with its identifiers, timestamp formats, and requested service") {
            operation("/inquiries/{inquiryId}", "get").text("responses", "200", "content", "application/json", "schema", "\$ref") shouldBe
                "#/components/schemas/InquiryResponse"

            val response = schema("InquiryResponse")
            response.strings("required") shouldContainExactly
                listOf(
                    "id",
                    "customerId",
                    "name",
                    "email",
                    "createdAt",
                    "requestedService",
                    "zipCode",
                    "eventDate",
                    "eventType",
                    "lifecycle",
                )
            val properties = response.at("properties").jsonObject
            properties.keys.toList() shouldContainExactly
                listOf(
                    "id",
                    "customerId",
                    "name",
                    "email",
                    "message",
                    "createdAt",
                    "requestedService",
                    "zipCode",
                    "eventDate",
                    "eventType",
                    "lifecycle",
                )
            properties.getValue("requestedService").text("\$ref") shouldBe "#/components/schemas/RequestedServiceRequest"
            properties.getValue("lifecycle").text("\$ref") shouldBe "#/components/schemas/InquiryLifecycleResponse"
            (properties - "requestedService" - "lifecycle").values.forEach { it.text("type") shouldBe "string" }
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

        test("quote builder: write-free preview of staff lines, optional reviewed issuance, and money-free service plans") {
            val preview = operation("/staff/requests/{inquiryId}/quote-preview", "post")
            preview.text("operationId") shouldBe "previewInquiryQuote"
            listOf("writes nothing", "REPEATABLE_READ", "reviewToken", "no-store", "No catalog or pricing policy").forEach {
                preview.text("description") shouldContain it
            }
            preview.text("responses", "409", "description").let {
                it shouldContain "`conflict`"
                it shouldContain "`illegal_transition`"
                it shouldNotContain "CATALOG"
            }
            preview.text("responses", "422", "description").let { description ->
                listOf("LINE_NOT_IN_REVIEWED_DOCUMENT", "QUOTE_TOTAL_NOT_POSITIVE", "SERVICE_PLAN_LINE_NOT_FOUND").forEach {
                    description shouldContain it
                }
            }
            schema("PreviewInquiryQuoteRequest").strings("required") shouldContainExactly
                listOf("expectedDocumentVersion", "lines", "terms")
            schema("PreviewInquiryQuoteRequest").text("properties", "terms", "\$ref") shouldBe "#/components/schemas/DepositTermsRequest"
            schema("PreviewInquiryQuoteRequest").text("properties", "lines", "items", "\$ref") shouldBe
                "#/components/schemas/ProposedLineRequest"
            schema("ProposedLineRequest").let {
                it.strings("required") shouldContainExactly listOf("description", "unitPrice", "taxAmount", "currency")
                it.text("properties", "lineItemId", "format") shouldBe "uuid"
                it.text("properties", "key", "pattern") shouldBe "^[A-Za-z0-9_-]{1,64}$"
                listOf("quantity", "unitPrice", "taxAmount").forEach { amount -> it.text("properties", amount, "type") shouldBe "string" }
            }
            schema("ServicePlanRequest").strings("required") shouldContainExactly listOf("description")
            schema("LineNoteRequest").strings("required") shouldContainExactly listOf("note")
            // Issuance stays backward compatible: lines, service plan and reviewToken are additive and optional.
            schema("IssueInquiryProposalRequest").at("properties").jsonObject.keys shouldContainExactlyInAnyOrder
                listOf("expectedDocumentVersion", "terms", "lines", "servicePlan", "reviewToken")
            operation("/staff/requests/{inquiryId}/proposals", "post").text("responses", "409", "description") shouldContain
                "QUOTE_REVIEW_STALE"
            listOf("IssuedInquiryProposalResponse", "StaffRequestResponse").forEach {
                schema(it).text("properties", "servicePlan", "\$ref") shouldBe "#/components/schemas/ServicePlanResponse"
                schema(it).strings("required").contains("servicePlan") shouldBe false
            }
            // The plan carries no money; amounts stay on the ledger lines it names.
            val plans = listOf("ServicePlanResponse", "ServicePlanRequest", "ServicePlanPreviewResponse", "ServicePlanLineNoteResponse")
            plans.forEach { name ->
                schema(name)
                    .at("properties")
                    .jsonObject.keys
                    .none { Regex("(?i)amount|price|total|balance").containsMatchIn(it) } shouldBe true
            }
            schema("ServicePlanResponse").text("properties", "approvedBy", "format") shouldBe "uuid"
            schema("InquiryQuotePreviewResponse").text("properties", "reviewToken", "pattern") shouldBe "^[0-9a-f]{64}$"
            schema("QuotePreviewLineResponse").strings("required").contains("origin") shouldBe true
            // Inspect executable metadata before rendering can collapse duplicate status entries.
            val access = metadataAuth.access
            listOf(
                previewInquiryQuoteRoute({ error("not called") }, access),
                issueInquiryProposalRoute({ error("not called") }, access),
            ).forEach { route ->
                route.meta.responses
                    .map { it.message.status }
                    .shouldBeUnique()
            }
        }

        test("every documented deposit terms example is the discriminated object clients send, never an array") {
            listOf(
                "/financial-documents/{documentId}/deposit-requirement" to "put",
                "/staff/requests/{inquiryId}/quote-preview" to "post",
                "/staff/requests/{inquiryId}/proposals" to "post",
                "/staff/requests/{inquiryId}/proposals/quote-revisions" to "post",
                "/staff/requests/{inquiryId}/proposals/deposit-revisions" to "post",
            ).forEach { (path, method) ->
                val terms = operation(path, method).at("requestBody", "content", "application/json", "example", "terms")
                (terms is JsonObject && "type" in terms.jsonObject) shouldBe true
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

        test("required event ZIP is constrained text") {
            listOf("CreateInquiryRequest", "InquiryResponse", "InquiryListItem").forEach { name ->
                val body = schema(name)
                body.strings("required").contains("zipCode") shouldBe true
                body.text("properties", "zipCode", "type") shouldBe "string"
                body.text("properties", "zipCode", "pattern") shouldBe ZipCode.PATTERN
            }
            schema("CreateInquiryRequest").at("properties", "zipCode", "minLength").jsonPrimitive.int shouldBe ZipCode.LENGTH
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
        }

        test("staff financial requests carry complete already-priced lines, never totals, and need a staff session") {
            mapOf(
                "CreateInquiryEstimateRequest" to listOf("lines"),
                "CreateInquiryFinancialDocumentRequest" to listOf("stage", "lines"),
                "ChangeOrderRequest" to listOf("expectedVersion", "lines"),
            ).forEach { (name, required) ->
                val request = schema(name)
                request.at("properties").jsonObject.keys shouldBe required.toSet()
                request.strings("required") shouldContainExactly required
            }
            schema("CreateInquiryEstimateRequest").text("properties", "lines", "items", "\$ref") shouldBe
                "#/components/schemas/PricedLineRequest"
            schema("ChangeOrderRequest").text("properties", "lines", "items", "\$ref") shouldBe "#/components/schemas/ProposedLineRequest"
            listOf(
                "/inquiries/{inquiryId}/estimates",
                "/inquiries/{inquiryId}/financial-documents",
                "/financial-documents/{documentId}/change-orders",
            ).forEach { path ->
                val route = operation(path, "post")
                route.text("description") shouldContain "fionas.financial-terms.manage"
                route.text("responses", "403", "description") shouldContain "staff USER"
            }
            operation("/financial-documents/{documentId}/change-orders", "post").text("responses", "422", "description").let {
                it shouldContain "NO_FINANCIAL_CHANGE"
                it shouldContain "LINE_NOT_IN_REVIEWED_DOCUMENT"
            }
            schema("StageTransitionRequest").strings("required") shouldContainExactly listOf("expectedVersion")
            schema("RecordPaymentRequest").let {
                it.strings("required") shouldContainExactly listOf("documentVersion", "amount", "method")
                it.text("properties", "amount", "type") shouldBe "string"
                it.text("properties", "receivedAt", "format") shouldBe "date-time"
                it.text("properties", "externalReference", "\$ref") shouldBe "#/components/schemas/PaymentExternalReference"
                it.text("properties", "expectedProposalId", "format") shouldBe "uuid"
                it.text("properties", "expectedProposalId", "description").contains("canonical Quote") shouldBe true
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

        test("describes a financial document as immutable ledger facts, line authorship, and derived settlement") {
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
                    "linesAuthoredBy",
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
            properties.getValue("linesAuthoredBy").text("\$ref") shouldBe "#/components/schemas/LineAuthorshipResponse"
            schema("LineAuthorshipResponse").strings("required") shouldContainExactly listOf("principalKind", "principalId", "recordedAt")
            schema("DocumentReconciliation").strings("required") shouldContainExactly
                listOf("grossAllocated", "netApplied", "balance", "currency")
            schema("FinancialDocumentLine").text("properties", "id", "format") shouldBe "uuid"
            // Settlement is derived: nothing in the document is a stored payment status.
            fionaOpenApiDocument().contains("paymentStatus") shouldBe false
            listOf("receivedAt", "allocatedAt").forEach {
                schema("RecordedPaymentResponse").text("properties", it, "format") shouldBe "date-time"
            }
        }

        test("documents exact canonical deposit acceptance and the standalone allocation prohibition") {
            val payment = operation("/financial-documents/{documentId}/payments", "post")
            listOf("expectedProposalId", "exactly equal", "Partial and excessive", "distinct subsequent Invoice payment").forEach {
                payment.text("description").contains(it) shouldBe true
            }
            payment.text("responses", "422", "description").contains("exact complete deposit") shouldBe true
            payment.text("responses", "409", "description").contains("current payable proposal") shouldBe true
            operation(
                "/payments/{paymentId}/allocations",
                "post",
            ).text("description").contains("Quotes reject standalone allocations") shouldBe
                true
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
            schemas.keys shouldBe fionaSchemas.toSet() + adminSchemas + serviceAuthenticationSchemas
        }

        test("serves no product catalog, inquiry form, estimate preview, or catalog staleness contract") {
            val paths = document.at("paths").jsonObject.keys
            paths.none { it.startsWith("/offering-catalog") || it == "/inquiry-form" || it == "/estimate-preview" } shouldBe true
            document
                .at(
                    "components",
                    "schemas",
                ).jsonObject.keys
                .none { Regex("(?i)offering|catalog|pricinginput").containsMatchIn(it) } shouldBe
                true
            val text = fionaOpenApiDocument()
            listOf("CATALOG_REVISION_STALE", "catalogRevision", "commerce.offerings.manage", "fionas.inquiry-form.read").forEach {
                text.contains(it) shouldBe false
            }
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
