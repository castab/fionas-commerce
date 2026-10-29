package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.financial.FIONAS_DEFAULT_DEPOSIT_TERMS
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.StaffRequest
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
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

@Serializable
data class CommunicationAcknowledgementResponse(
    val occurredAt: String,
    val principalKind: String,
    val principalId: String,
)

@Serializable
data class CommunicationSummaryResponse(
    val needsReply: Boolean,
    val throughRecordedOrder: Long,
    val unacknowledgedSince: String? = null,
    val latestEmailAt: String? = null,
    val lastAcknowledged: CommunicationAcknowledgementResponse? = null,
)

internal fun StaffRequest.communicationResponse(): CommunicationSummaryResponse {
    val attention = communicationAttention
    val acknowledged = communicationHistory.lastOrNull { it.activity.kind == InquiryCommunicationKind.STAFF_ACKNOWLEDGED }?.activity
    return CommunicationSummaryResponse(
        needsReply,
        communicationHistory.maxOfOrNull { it.recordedOrder } ?: 0,
        attention.unacknowledgedSince?.toString(),
        attention.latestEmailAt?.toString(),
        acknowledged?.let {
            val principal = checkNotNull(it.principalId)
            CommunicationAcknowledgementResponse(
                it.occurredAt.toString(),
                when (principal) {
                    is UserId -> "USER"
                    is ServiceId -> "SERVICE"
                },
                when (principal) {
                    is UserId -> principal.value
                    is ServiceId -> principal.value
                }.toString(),
            )
        },
    )
}

@Serializable
data class StaffRequestResponse(
    @ApiProperty(description = "Authoritative inquiry detail, including customer, event, the requested service and lifecycle.")
    val inquiry: InquiryResponse,
    @ApiProperty(
        description =
            "Current canonical INITIAL_ESTIMATE lineage only; immutable financial facts. " +
                "financial.reconciliation is always present and describes current derived settlement.",
    )
    val financial: FinancialDocumentResponse,
    @ApiProperty(description = "Latest exact published Quote/deposit pair; absent before issuance, historical context after booking.")
    val proposal: InquiryProposalResponse? = null,
    val suggestedDepositTerms: DepositTermsRequest,
    val depositRequirement: CurrentDepositRequirementResponse,
    @ApiProperty(
        description =
            "Complete payment histories ever associated with the canonical lineage, including historical document versions " +
                "and allocations to other lineages, refunds and refund allocations. After booking the deposit allocation " +
                "still references the accepted Quote version. Reconciliation is derived from the complete ledger facts. " +
                "An empty collection means no payments have been associated with this lineage.",
    )
    val payments: List<PaymentHistoryResponse>,
    @ApiProperty(
        description =
            "Service approval bound to the latest published proposal, historical after booking. Its " +
                "original snapshot may precede that Quote when retained. Use effectiveServicePlan for " +
                "fulfillment.",
    )
    val servicePlan: ServicePlanResponse? = null,
    @ApiProperty(
        description = "Latest staff approval governing fulfillment; cleared=true explicitly removes the plan. Absent if never approved.",
    )
    val effectiveServicePlan: ServicePlanResponse? = null,
    @ApiProperty(
        description =
            "Exact service approval of the accepted proposal; present only after booking when an " +
                "approval exists, immutable across amendments.",
    )
    val acceptedServicePlan: ServicePlanResponse? = null,
    @ApiProperty(
        description =
            "All independent service approvals in revision order, including clearings, with exact " +
                "snapshot references and staff provenance.",
    )
    val servicePlanHistory: List<ServicePlanResponse> = emptyList(),
    @ApiProperty(
        description =
            "All publications in durable order, each bound to its financial version, deposit " +
                "revision and optional servicePlanRevision.",
    )
    val proposalHistory: List<InquiryProposalResponse> = emptyList(),
    val communication: CommunicationSummaryResponse = CommunicationSummaryResponse(false, 0),
)

internal fun readStaffRequestRoute(
    read: (InquiryId) -> StaffRequest,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(StaffRequestResponse.serializer())
    val financial =
        exampleEstimate.copy(
            id = exampleInquiry.lifecycle.documentId,
            linesAuthoredBy =
                LineAuthorshipResponse(
                    "SERVICE",
                    "d2a7c9e4-61f0-4b8e-9c3a-5f1e2d4b6a80",
                    "2026-09-26T21:19:39.321012Z",
                    "PRINCIPAL",
                    null,
                ),
        )
    return "/staff/requests" / inquiryDetailIdPath meta {
        operationId = "readStaffRequest"
        summary = "Read a staff request"
        description = "One unlocked REPEATABLE_READ snapshot of inquiry, durable customer, event facts, the requested service, " +
            "canonical lifecycle, the latest immutable INITIAL_ESTIMATE financial lineage, " +
            "proposal/deposit state and complete payment histories. " +
            "financial.reconciliation is always present and describes current derived settlement. " +
            "RELATED lineages are excluded. Requires BOTH `${FionaPermissions.InquiriesRead.value}` and " +
            "`${CommercePermissions.FinancialDocumentRead.value}` for USER sessions or SERVICE tokens. " +
            "Financial lines and totals are authoritative; the requested service remains descriptive inquiry history. " +
            "Use inquiryId and financial.version with the atomic staff proposal issuance operation and explicit deposit terms; " +
            "proposal identifies the exact published Quote/deposit pair; " +
            "financial.id, financial.version, proposal.id and depositRequirement.requiredAmount " +
            "supply the canonical deposit payment inputs " +
            "for POST /financial-documents/{documentId}/payments. Payments retain the accepted Quote allocation after booking " +
            "and include subsequent Invoice receipts, with the same complete histories as the document payment-history endpoint. " +
            "missing or mismatched canonical proposal state fails internally. " +
            "stale versions conflict and require reload. This read changes no state. Successful responses use Cache-Control: no-store."
        tags += Tag("Staff requests", "Coherent inquiry and canonical financial detail for staff request review.")
        principalAuthentication()
        returning(
            Status.OK,
            body to
                StaffRequestResponse(
                    exampleInquiry,
                    financial,
                    suggestedDepositTerms = FIONAS_DEFAULT_DEPOSIT_TERMS.toResponse(),
                    depositRequirement = CurrentDepositRequirementResponse.None(financial.id),
                    payments = emptyList(),
                ),
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "`inquiryId` is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(ErrorCategory.NOT_FOUND, "No inquiry has this id.", "Inquiry ${exampleInquiry.id} was not found")
        returningError(
            ErrorCategory.FORBIDDEN,
            "The principal lacks either fionas.inquiries.read or commerce.financial-document.read.",
            "The authenticated principal is not permitted to perform this request",
        )
        returningError(
            ErrorCategory.INTERNAL_FAILURE,
            "Missing/corrupt canonical or customer data, or an unexpected failure; no partial request is returned.",
            INTERNAL_FAILURE,
        )
    } bindContract Method.GET to { id: String ->
        access
            .requirePermission(FionaPermissions.InquiriesRead)
            .then(access.requirePermission(CommercePermissions.FinancialDocumentRead))
            .then { _: Request ->
                val request = read(inquiryId(id))
                Response(Status.OK)
                    .header("Cache-Control", "no-store")
                    .with(
                        body of
                            StaffRequestResponse(
                                request.inquiry.toResponse(),
                                request.financial.toResponse(),
                                request.proposal?.toResponse(),
                                request.suggestedDepositTerms.toResponse(),
                                request.deposit.currentDepositResponse(),
                                request.payments.map { it.toResponse() },
                                request.servicePlan?.toResponse(),
                                request.effectiveServicePlan?.toResponse(),
                                request.servicePlan
                                    ?.takeIf {
                                        request.financial.latest.document is FinancialDocument.Invoice
                                    }?.toResponse(),
                                request.servicePlanHistory.map { it.toResponse() },
                                request.proposalHistory.map { it.toResponse() },
                                request.communicationResponse(),
                            ),
                    )
            }
    }
}
