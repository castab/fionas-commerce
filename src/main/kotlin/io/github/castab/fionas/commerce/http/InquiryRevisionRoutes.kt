package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.financial.ComposedServicePlan
import io.github.castab.fionas.commerce.financial.InquiryInvoiceRevisions
import io.github.castab.fionas.commerce.financial.InvoiceRevisionIntent
import io.github.castab.fionas.commerce.financial.InvoiceRevisionPreview
import io.github.castab.fionas.commerce.financial.QuoteReviewToken
import io.github.castab.fionas.commerce.financial.QuoteRevisionIntent
import io.github.castab.fionas.commerce.financial.QuoteRevisionPreview
import io.github.castab.fionas.commerce.financial.RevisedInquiryInvoice
import io.github.castab.fionas.commerce.financial.ServicePlanAction
import io.github.castab.fionas.commerce.financial.ServicePlanChange
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.BodyLens

/** How a revision treats the effective service approval; RETAIN never silently discards one. */
@Serializable
enum class ServicePlanActionRequest { RETAIN, REPLACE, CLEAR }

/** The proposed Invoice's derived balance sign: positive, exactly zero, or negative. */
@Serializable
enum class InvoiceSettlementResponse { UNDERPAID, SETTLED, OVERPAID }

@Serializable
data class QuoteRevisionPreviewRequest(
    val expectedDocumentVersion: Int,
    val expectedDepositRequirementRevision: Int,
    val lines: List<ProposedLineRequest>,
    @Serializable(with = StrictDepositTerms::class) val terms: DepositTermsRequest,
    val servicePlanAction: ServicePlanActionRequest = ServicePlanActionRequest.RETAIN,
    val servicePlan: ServicePlanRequest? = null,
)

@Serializable
data class QuoteRevisionPreviewResponse(
    val quote: InquiryQuotePreviewResponse,
    val currentProposalId: String,
    val reviewedDepositRequirementRevision: Int,
    val currentServicePlanRevision: Int? = null,
    val resultingServicePlanRevision: Int? = null,
    val servicePlanChange: Boolean,
    val depositChange: Boolean,
)

@Serializable
data class InvoiceRevisionPreviewRequest(
    val expectedDocumentVersion: Int,
    val lines: List<ProposedLineRequest>,
    val servicePlanAction: ServicePlanActionRequest = ServicePlanActionRequest.RETAIN,
    val servicePlan: ServicePlanRequest? = null,
)

@Serializable
data class InvoiceRevisionRequest(
    val expectedDocumentVersion: Int,
    val lines: List<ProposedLineRequest>,
    val reviewToken: String,
    val servicePlanAction: ServicePlanActionRequest = ServicePlanActionRequest.RETAIN,
    val servicePlan: ServicePlanRequest? = null,
)

@Serializable
data class InvoiceRevisionPreviewResponse(
    val inquiryId: String,
    val documentId: String,
    val reviewedDocumentVersion: Int,
    val resultingDocumentVersion: Int,
    val currentTotal: String,
    val total: String,
    val subtotal: String,
    val taxAmount: String,
    val currency: String,
    val lines: List<QuotePreviewLineResponse>,
    val financialChange: Boolean,
    val servicePlanChange: Boolean,
    val currentServicePlanRevision: Int? = null,
    val resultingServicePlanRevision: Int? = null,
    val servicePlan: ServicePlanPreviewResponse? = null,
    val netApplied: String,
    val balance: String,
    val settlement: InvoiceSettlementResponse,
    val reviewToken: String,
)

@Serializable
data class RevisedInquiryInvoiceResponse(
    val financial: FinancialDocumentResponse,
    val servicePlan: ServicePlanResponse? = null,
)

internal fun ComposedServicePlan.toResponse() =
    ServicePlanPreviewResponse(
        service.description,
        service.guestCount,
        service.durationMinutes,
        service.items.map { it.toResponse() },
        lineNotes.map { it.toResponse() },
    )

internal fun InvoiceRevisionPreview.toResponse() =
    InvoiceRevisionPreviewResponse(
        inquiryId.value.toString(),
        reviewed.id.toString(),
        reviewed.version.number,
        invoice.version.number,
        reviewed.total.decimal(),
        invoice.total.decimal(),
        invoice.subtotal.decimal(),
        invoice.taxAmount.decimal(),
        invoice.currency.currencyCode,
        lines.lines.map { it.toResponse() },
        financialChange,
        servicePlanChange,
        currentServicePlan?.revision,
        resultingServicePlanRevision,
        servicePlan?.toResponse(),
        reconciliation.netApplied.decimal(),
        reconciliation.balance.decimal(),
        when (reconciliation.balance.amount.signum()) {
            1 -> InvoiceSettlementResponse.UNDERPAID
            0 -> InvoiceSettlementResponse.SETTLED
            else -> InvoiceSettlementResponse.OVERPAID
        },
        reviewToken.value,
    )

private fun AccessControl.invoiceAmendment() =
    requirePermission(CommercePermissions.FinancialDocumentCreate)
        .then(requirePermission(FionaPermissions.FinancialTermsManage))
        .then(requireStaffUser)

private fun RouteMetaDsl.revisionErrors() {
    tags += Tag("Staff proposals", "Atomic publication of an exact canonical Quote and approved deposit pair.")
    principalAuthentication()
    security = staffSessionSecurity
    returningError(
        ErrorCategory.FORBIDDEN,
        "Staff USER and required financial permissions; trusted Origin for cookies.",
        STAFF_USER_REQUIRED,
    )
    returningError(ErrorCategory.MALFORMED_REQUEST, "Malformed body or identity.", "Malformed request")
    returningError(ErrorCategory.NOT_FOUND, "Missing canonical inquiry lineage.", "Not found")
    returningError(
        ErrorCategory.CONFLICT,
        "Stale version or review (QUOTE_REVIEW_STALE), or illegal_transition for ineligible lifecycle.",
        "Stale review",
    )
    returningError(
        ErrorCategory.VALIDATION_FAILED,
        "Invalid lines, service plan action, unknown line notes, or NO_MATERIAL_CHANGE.",
        "Invalid revision",
    )
    returningError(ErrorCategory.INTERNAL_FAILURE, "Corrupt state or unexpected failure; all writes roll back.", INTERNAL_FAILURE)
}

internal fun ServicePlanActionRequest.domain() = ServicePlanAction.valueOf(name)

internal fun planChange(
    action: ServicePlanActionRequest,
    plan: ServicePlanRequest?,
    lens: BodyLens<*>,
): ServicePlanChange {
    val notes = plan?.lineNotes?.identities(lens).orEmpty()
    return validating { ServicePlanChange(action.domain(), plan?.domain(notes)) }
}

internal fun previewInquiryQuoteRevisionRoute(
    preview: (QuoteRevisionIntent) -> QuoteRevisionPreview,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(QuoteRevisionPreviewRequest.serializer())
    val response = jsonBody(QuoteRevisionPreviewResponse.serializer())
    return "/staff/requests" / inquiryDetailIdPath / "quote-revision-preview" meta {
        operationId = "previewInquiryQuoteRevision"
        summary = "Preview a canonical Quote revision"
        description = "Write-free unlocked REPEATABLE_READ preview. Requires the same three permissions as Quote issuance. " +
            "RETAIN preserves the effective plan, REPLACE requires servicePlan, CLEAR forbids it. Only material changes are valid. " +
            "quote.reviewToken binds the latest proposal, deposit revision, service approval and complete proposed result. " +
            "Publish identical inputs and token through proposals/quote-revisions. No-store."
        receiving(body to QuoteRevisionPreviewRequest(2, 1, exampleProposedLines, DepositTermsRequest.Percentage("20")))
        returning(
            Status.OK,
            response to
                QuoteRevisionPreviewResponse(
                    exampleRevisionQuotePreview,
                    "34b41196-38b8-4e27-a48d-e2aaf896f570",
                    1,
                    servicePlanChange = false,
                    depositChange = true,
                ),
        )
        revisionErrors()
    } bindContract Method.POST to { id: String, _: String ->
        access.staffComposition().then { request: Request ->
            val input = body(request)
            val identities = input.lines.identities(body)
            val change = planChange(input.servicePlanAction, input.servicePlan, body)
            val intent =
                validating {
                    QuoteRevisionIntent(
                        inquiryId(id),
                        Version.of(input.expectedDocumentVersion),
                        DepositRequirementRevision.of(input.expectedDepositRequirementRevision),
                        input.lines.domain(identities),
                        change,
                        input.terms.domain(),
                    )
                }
            val result = preview(intent)
            Response(Status.OK).header("Cache-Control", "no-store").with(
                response of
                    QuoteRevisionPreviewResponse(
                        result.composition.toResponse(),
                        result.currentProposal.id.value
                            .toString(),
                        result.currentProposal.depositRequirementRevision.number,
                        result.currentServicePlan?.revision,
                        result.resultingServicePlanRevision,
                        result.servicePlanChange,
                        result.depositChange,
                    ),
            )
        }
    }
}

internal fun previewInquiryInvoiceRevisionRoute(
    preview: (InvoiceRevisionIntent) -> InvoiceRevisionPreview,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(InvoiceRevisionPreviewRequest.serializer())
    val response = jsonBody(InvoiceRevisionPreviewResponse.serializer())
    return "/staff/requests" / inquiryDetailIdPath / "invoice-revision-preview" meta {
        operationId = "previewInquiryInvoiceRevision"
        summary = "Preview a canonical Invoice amendment"
        description = "Write-free unlocked REPEATABLE_READ preview for BOOKED or SERVED. Requires financial-document.create and " +
            "fionas.financial-terms.manage and a staff USER. No deposit permission or deposit changes. Shared reconciliation " +
            "derives the proposed balance. Service-only changes keep the financial version; retain/replace/clear are explicit. No-store."
        receiving(body to InvoiceRevisionPreviewRequest(3, exampleProposedLines))
        returning(Status.OK, response to exampleInvoiceRevisionPreview)
        revisionErrors()
    } bindContract Method.POST to { id: String, _: String ->
        access.invoiceAmendment().then { request: Request ->
            val input = body(request)
            val identities = input.lines.identities(body)
            val change = planChange(input.servicePlanAction, input.servicePlan, body)
            val intent =
                validating {
                    InvoiceRevisionIntent(
                        inquiryId(id),
                        Version.of(input.expectedDocumentVersion),
                        input.lines.domain(identities),
                        change,
                    )
                }
            Response(Status.OK).header("Cache-Control", "no-store").with(response of preview(intent).toResponse())
        }
    }
}

internal fun reviseInquiryInvoiceRoute(
    revise: (InquiryInvoiceRevisions.Command) -> RevisedInquiryInvoice,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(InvoiceRevisionRequest.serializer())
    val response = jsonBody(RevisedInquiryInvoiceResponse.serializer())
    return "/staff/requests" / inquiryDetailIdPath / "invoice-revisions" meta {
        operationId = "reviseInquiryInvoice"
        summary = "Approve a canonical Invoice amendment"
        description = "Recomposes the exact preview under the canonical association and payment locks. Requires staff USER, " +
            "financial-document.create and fionas.financial-terms.manage. Commits real financial changes and service approvals " +
            "atomically; retains payments, accepted proposal/deposit and served provenance. CLOSED is ineligible. " +
            "No automatic refund, booking transition or closure. Missing reviewToken is malformed; stale review is QUOTE_REVIEW_STALE. No-store."
        receiving(body to InvoiceRevisionRequest(3, exampleProposedLines, "0".repeat(64)))
        returning(
            Status.OK,
            response to RevisedInquiryInvoiceResponse(exampleQuote.copy(stage = "INVOICE", version = 4, previousVersion = 3)),
        )
        revisionErrors()
    } bindContract Method.POST to { id: String, _: String ->
        access.invoiceAmendment().then(quoteReviewStaleResponses).then { request: Request ->
            val input = body(request)
            val identities = input.lines.identities(body)
            val change = planChange(input.servicePlanAction, input.servicePlan, body)
            val command =
                validating {
                    InquiryInvoiceRevisions.Command(
                        InvoiceRevisionIntent(
                            inquiryId(id),
                            Version.of(input.expectedDocumentVersion),
                            input.lines.domain(identities),
                            change,
                        ),
                        QuoteReviewToken(input.reviewToken),
                        staffUser(request),
                    )
                }
            val result = revise(command)
            Response(Status.OK).header("Cache-Control", "no-store").with(
                response of RevisedInquiryInvoiceResponse(result.financial.toResponse(), result.servicePlan?.toResponse()),
            )
        }
    }
}

private val exampleRevisionQuotePreview =
    InquiryQuotePreviewResponse(
        exampleInquiry.id,
        exampleInquiry.lifecycle.documentId,
        2,
        "1200.00",
        true,
        3,
        emptyList(),
        "1450.00",
        "0.00",
        "1450.00",
        "USD",
        QuoteDepositPreviewResponse(DepositTermsRequest.Percentage("20"), DepositMoneyResponse("290.00", "USD")),
        reviewToken = "0".repeat(64),
    )
private val exampleInvoiceRevisionPreview =
    InvoiceRevisionPreviewResponse(
        exampleInquiry.id,
        exampleInquiry.lifecycle.documentId,
        3,
        4,
        "1200.00",
        "1450.00",
        "1450.00",
        "0.00",
        "USD",
        emptyList(),
        true,
        false,
        netApplied = "300.00",
        balance = "1150.00",
        settlement = InvoiceSettlementResponse.UNDERPAID,
        reviewToken = "0".repeat(64),
    )
