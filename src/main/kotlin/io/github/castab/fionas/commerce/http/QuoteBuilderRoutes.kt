package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.ValidationViolationResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.financial.ComposedQuote
import io.github.castab.fionas.commerce.financial.LineProposalViolations
import io.github.castab.fionas.commerce.financial.PreviewInquiryQuote
import io.github.castab.fionas.commerce.financial.QUOTE_TOTAL_NOT_POSITIVE
import io.github.castab.fionas.commerce.financial.QuoteReviewStale
import io.github.castab.fionas.commerce.financial.ResolvedLine
import io.github.castab.fionas.commerce.financial.SERVICE_PLAN_LINE_NOT_FOUND
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with

/**
 * The body of `POST /staff/requests/{inquiryId}/quote-preview`: the complete final lines a staff
 * user proposes for the initial Quote, an optional service plan, and the deposit terms. Never a
 * pricing instruction, catalog reference or total.
 */
@Serializable
data class PreviewInquiryQuoteRequest(
    @ApiProperty(description = "Exact reviewed current Estimate version.") val expectedDocumentVersion: Int,
    @ApiProperty(
        description =
            "The complete final Quote lines, in order: existing Estimate lines by `lineItemId` (carried, or overridden " +
                "under the same id), new lines by `key`. Estimate lines left out are removed.",
    )
    val lines: List<ProposedLineRequest>,
    @ApiProperty(description = "Optional service plan approved with the Quote: what Fiona will serve, in staff's words.")
    val servicePlan: ServicePlanRequest? = null,
    @Serializable(with = StrictDepositTerms::class) val terms: DepositTermsRequest,
)

/** One line of the proposed Quote, with its derived ledger id and where it came from. */
@Serializable
data class QuotePreviewLineResponse(
    @ApiProperty(description = "The line's ledger id: kept for existing lines, derived from `key` for new ones.", format = "uuid")
    val id: String,
    @ApiProperty(description = "CARRIED (unchanged Estimate line), REPLACED (same id, new values), or NEW.")
    val origin: String,
    @ApiProperty(description = "The request-local key of a NEW line.") val key: String? = null,
    val description: String,
    val subDescription: String? = null,
    val quantity: String? = null,
    val unitPrice: String,
    val subtotal: String,
    val taxAmount: String,
    val total: String,
    val currency: String,
)

@Serializable
data class QuoteDepositPreviewResponse(
    val terms: DepositTermsRequest,
    @ApiProperty(description = "The amount the terms resolve to against this exact Quote, with commerce's shared rules (HALF_UP).")
    val requiredAmount: DepositMoneyResponse,
)

/** The service plan a preview would approve, with its notes resolved to final ledger line ids. */
@Serializable
data class ServicePlanPreviewResponse(
    val description: String,
    val guestCount: Int? = null,
    val durationMinutes: Int? = null,
    val items: List<String>,
    val lineNotes: List<ServicePlanLineNoteResponse>,
)

@Serializable
data class InquiryQuotePreviewResponse(
    @ApiProperty(format = "uuid") val inquiryId: String,
    @ApiProperty(format = "uuid") val documentId: String,
    @ApiProperty(description = "The Estimate version the proposal was resolved against.") val reviewedDocumentVersion: Int,
    @ApiProperty(description = "That Estimate's persisted total, for comparison.") val estimateTotal: String,
    @ApiProperty(
        description =
            "Whether issuance appends an intermediate Estimate before the Quote: only when the proposed lines differ from " +
                "the reviewed Estimate's.",
    )
    val financialChange: Boolean,
    @ApiProperty(description = "The version the issued Quote will have.") val quoteVersion: Int,
    @ApiProperty(description = "The proposed Quote's lines, in order.") val lines: List<QuotePreviewLineResponse>,
    val subtotal: String,
    val taxAmount: String,
    @ApiProperty(description = "The proposed Quote total, derived by commerce-domain from the lines.") val total: String,
    val currency: String,
    val deposit: QuoteDepositPreviewResponse,
    val servicePlan: ServicePlanPreviewResponse? = null,
    @ApiProperty(
        description =
            "Identity of exactly this reviewed result. Issuance with the same lines, service plan and terms must present " +
                "it; any authoritative difference answers 409 QUOTE_REVIEW_STALE.",
        pattern = "^[0-9a-f]{64}$",
    )
    val reviewToken: String,
)

private fun ResolvedLine.toResponse() =
    QuotePreviewLineResponse(
        id = line.id.toString(),
        origin = origin.name,
        key = key?.value,
        description = line.description,
        subDescription = line.subDescription,
        quantity = line.quantityText(),
        unitPrice = line.price.decimal(),
        subtotal = line.subtotal.decimal(),
        taxAmount = line.taxAmount.decimal(),
        total = line.total.decimal(),
        currency = line.currency.currencyCode,
    )

private fun LineItem.quantityText() = quantity?.stripTrailingZeros()?.toPlainString()

internal fun ComposedQuote.toResponse() =
    InquiryQuotePreviewResponse(
        inquiryId = inquiryId.value.toString(),
        documentId = reviewed.id.toString(),
        reviewedDocumentVersion = reviewed.version.number,
        estimateTotal = reviewed.total.decimal(),
        financialChange = financialChange,
        quoteVersion = quote.version.number,
        lines = lines.lines.map { it.toResponse() },
        subtotal = quote.subtotal.decimal(),
        taxAmount = quote.taxAmount.decimal(),
        total = quote.total.decimal(),
        currency = quote.currency.currencyCode,
        deposit =
            QuoteDepositPreviewResponse(
                terms.toResponse(),
                DepositMoneyResponse(requiredDeposit.amount.toPlainString(), requiredDeposit.currency.currencyCode),
            ),
        servicePlan =
            servicePlan?.let { plan ->
                ServicePlanPreviewResponse(
                    plan.service.description,
                    plan.service.guestCount,
                    plan.service.durationMinutes,
                    plan.service.items,
                    plan.lineNotes.map { it.toResponse() },
                )
            },
        reviewToken = reviewToken.value,
    )

internal const val QUOTE_REVIEW_STALE = "QUOTE_REVIEW_STALE"

private val reviewConflictBody = jsonBody(ErrorResponse.serializer())

/** Only a changed reviewed result has this local code; every other conflict keeps runtime handling. */
internal val quoteReviewStaleResponses =
    Filter { next ->
        { request ->
            try {
                next(request)
            } catch (failure: CommerceFailure.Conflict) {
                if (failure.cause !is QuoteReviewStale) throw failure
                Response(Status.CONFLICT)
                    .with(reviewConflictBody of ErrorResponse(QUOTE_REVIEW_STALE, failure.message!!))
                    .header("Cache-Control", "no-store")
            }
        }
    }

/** The composition rejection codes, documented on the composing routes. */
internal val compositionViolationExamples =
    listOf(LineProposalViolations.LINE_NOT_IN_REVIEWED_DOCUMENT, QUOTE_TOTAL_NOT_POSITIVE).map(::ValidationViolationResponse)

internal const val COMPOSITION_REJECTED =
    "invalid lines (no lines or more than 100, a blank description, a malformed decimal, more fraction digits than the " +
        "currency allows, an inexact subtotal), invalid service plan values, deposit terms the Quote cannot satisfy, or a " +
        "rejection with stable `violations` codes: `LINE_NOT_IN_REVIEWED_DOCUMENT`, `CURRENCY_MISMATCH`, " +
        "`NEGATIVE_DOCUMENT_TOTAL`, `$QUOTE_TOTAL_NOT_POSITIVE`, `$SERVICE_PLAN_LINE_NOT_FOUND`."

/** The `403` causes of staff Quote composition beyond the commerce permissions. */
internal const val STAFF_COMPOSITION_FORBIDDEN =
    "Requires a staff USER holding commerce.financial-document.create, commerce.deposit-requirement.manage and " +
        "fionas.financial-terms.manage; a SERVICE token never commits or previews staff-negotiated terms. Unsafe cookie " +
        "requests require a trusted Origin."

/** Everything staff Quote composition requires: both commerce permissions, Fiona's terms authority, and a staff USER. */
internal fun AccessControl.staffComposition() =
    requirePermission(CommercePermissions.FinancialDocumentCreate)
        .then(requirePermission(CommercePermissions.DepositRequirementManage))
        .then(requirePermission(FionaPermissions.FinancialTermsManage))
        .then(requireStaffUser)

private val previewBody = jsonBody(PreviewInquiryQuoteRequest.serializer())
private val previewResponseBody = jsonBody(InquiryQuotePreviewResponse.serializer())

/** The churro example: soft serve replaced by a bespoke service and a separate courtesy discount. */
internal val exampleProposedLines =
    listOf(
        ProposedLineRequest(null, "churros", "Churro catering service", "Prepared on site", "1", "450.00", "0.00", "USD"),
        ProposedLineRequest(null, "courtesy", "Courtesy discount", null, null, "-50.00", "0.00", "USD"),
    )

internal val exampleServicePlanRequest =
    ServicePlanRequest(
        description = "Churro catering for an evening reception",
        guestCount = 100,
        durationMinutes = 120,
        items = listOf("Churros with chocolate sauce", "Cinnamon sugar"),
        lineNotes = listOf(LineNoteRequest(null, "courtesy", "Returning customer courtesy")),
    )

private val examplePreview: InquiryQuotePreviewResponse =
    InquiryQuotePreviewResponse(
        inquiryId = exampleInquiry.id,
        documentId = exampleInquiry.lifecycle.documentId,
        reviewedDocumentVersion = 1,
        estimateTotal = "681.25",
        financialChange = true,
        quoteVersion = 3,
        lines =
            listOf(
                QuotePreviewLineResponse(
                    "8f1d2c3b-4a5e-3f60-9a7b-1c2d3e4f5a6b",
                    "NEW",
                    "churros",
                    "Churro catering service",
                    "Prepared on site",
                    "1",
                    "450.00",
                    "450.00",
                    "0.00",
                    "450.00",
                    "USD",
                ),
                QuotePreviewLineResponse(
                    "2a3b4c5d-6e7f-3081-92a3-b4c5d6e7f809",
                    "NEW",
                    "courtesy",
                    "Courtesy discount",
                    null,
                    null,
                    "-50.00",
                    "-50.00",
                    "0.00",
                    "-50.00",
                    "USD",
                ),
            ),
        subtotal = "400.00",
        taxAmount = "0.00",
        total = "400.00",
        currency = "USD",
        deposit = QuoteDepositPreviewResponse(DepositTermsRequest.Percentage("20"), DepositMoneyResponse("80.00", "USD")),
        servicePlan =
            ServicePlanPreviewResponse(
                "Churro catering for an evening reception",
                100,
                120,
                listOf("Churros with chocolate sauce", "Cinnamon sugar"),
                listOf(ServicePlanLineNoteResponse("2a3b4c5d-6e7f-3081-92a3-b4c5d6e7f809", "Returning customer courtesy")),
            ),
        reviewToken = "9f2c4a1b8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b",
    )

internal val exampleServicePlan =
    ServicePlanResponse(
        documentId = exampleInquiry.lifecycle.documentId,
        documentVersion = 3,
        reviewedDocumentVersion = 1,
        description = "Churro catering for an evening reception",
        guestCount = 100,
        durationMinutes = 120,
        items = listOf("Churros with chocolate sauce", "Cinnamon sugar"),
        lineNotes = listOf(ServicePlanLineNoteResponse("2a3b4c5d-6e7f-3081-92a3-b4c5d6e7f809", "Returning customer courtesy")),
        approvedAt = "2026-10-05T17:00:00Z",
        approvedBy = "580a28a1-7417-480a-9089-8f5f3c25c1cd",
    )

/**
 * `POST /staff/requests/{inquiryId}/quote-preview`: the write-free preview of an initial Quote
 * from staff-committed final lines. Same authority as issuance, which publishes exactly this result.
 */
internal fun previewInquiryQuoteRoute(
    preview: (PreviewInquiryQuote.Command) -> ComposedQuote,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "quote-preview" meta {
        operationId = "previewInquiryQuote"
        summary = "Preview a staff-composed initial Quote"
        description =
            "A query that writes nothing, even on failure. Resolves the complete final lines a staff user proposes against " +
            "the canonical Estimate at exactly `expectedDocumentVersion` (REQUESTED, no proposal) in one unlocked " +
            "REPEATABLE_READ snapshot: existing lines by `lineItemId` are carried or overridden in place under the same " +
            "id, Estimate lines left out are removed, and new lines by `key` (a bespoke service, a charge, a signed " +
            "discount or credit) are added with ids derived from the document, version and key. No catalog or pricing " +
            "policy is consulted: the staff user is the pricing authority. Returns the resolved lines, the totals " +
            "commerce-domain derives, the deposit the terms resolve to against that exact Quote, the service plan with " +
            "notes resolved to line ids, and a reviewToken binding every reviewed fact. Publish it with " +
            "`issueInquiryProposal` and the same lines, service plan, terms and reviewToken. $STAFF_COMPOSITION_FORBIDDEN " +
            "Cache-Control: no-store."
        tags += Tag("Staff proposals", "Atomic publication of an exact canonical Quote and approved deposit pair.")
        receiving(
            previewBody to
                PreviewInquiryQuoteRequest(1, exampleProposedLines, exampleServicePlanRequest, DepositTermsRequest.Percentage("20")),
        )
        returning(Status.OK, previewResponseBody to examplePreview)
        principalAuthentication()
        security = staffSessionSecurity
        returningError(ErrorCategory.FORBIDDEN, STAFF_COMPOSITION_FORBIDDEN, STAFF_USER_REQUIRED)
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "Unreadable inquiry or line UUID, a line or note naming both or neither of `lineItemId` and `key`, or request shape.",
            "Malformed request",
        )
        returningError(ErrorCategory.NOT_FOUND, "Inquiry or canonical lineage does not exist.", "Not found")
        returningError(
            ErrorCategory.CONFLICT,
            "`conflict`: expectedDocumentVersion is no longer the latest Estimate version; reload and review. " +
                "`illegal_transition`: the canonical lineage is no longer an unissued Estimate.",
            "Stale expected version",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            COMPOSITION_REJECTED,
            "The proposed lines are invalid",
            compositionViolationExamples,
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "Corrupt canonical state or an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to { id: String, _: String ->
        access.staffComposition().then { request: Request ->
            val inquiry = inquiryId(id)
            val body = previewBody(request)
            val lineIdentities = body.lines.identities(previewBody)
            val noteIdentities =
                body.servicePlan
                    ?.lineNotes
                    ?.identities(previewBody)
                    .orEmpty()
            val command =
                validating {
                    PreviewInquiryQuote.Command(
                        inquiry,
                        Version.of(body.expectedDocumentVersion),
                        body.lines.domain(lineIdentities),
                        body.servicePlan?.domain(noteIdentities),
                        body.terms.domain(),
                    )
                }
            Response(Status.OK).header("Cache-Control", "no-store").with(previewResponseBody of preview(command).toResponse())
        }
    }
