package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.financial.FIONAS_DEFAULT_DEPOSIT_TERMS
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
data class StaffRequestResponse(
    @ApiProperty(description = "Authoritative inquiry detail, including customer, event, pinned requested pricing intent and lifecycle.")
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
            "The immutable approved service plan of the published Quote (proposal.documentVersion): promised service, " +
                "reviewed catalog names and why each Quote line exists. Absent before issuance, for a Quote issued without a " +
                "composition, and after a later Quote revision until a revision slice records one; never invented.",
    )
    val servicePlan: ServicePlanResponse? = null,
)

internal fun readStaffRequestRoute(
    read: (InquiryId) -> StaffRequest,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(StaffRequestResponse.serializer())
    val financial = exampleEstimate.copy(id = exampleInquiry.lifecycle.documentId, pricing = null)
    return "/staff/requests" / inquiryDetailIdPath meta {
        operationId = "readStaffRequest"
        summary = "Read a staff request"
        description = "One unlocked REPEATABLE_READ snapshot of inquiry, durable customer, event facts, requested pricing intent, " +
            "canonical lifecycle, the latest immutable INITIAL_ESTIMATE financial lineage, " +
            "proposal/deposit state and complete payment histories. " +
            "financial.reconciliation is always present and describes current derived settlement. " +
            "RELATED lineages are excluded. Requires BOTH `${FionaPermissions.InquiriesRead.value}` and " +
            "`${CommercePermissions.FinancialDocumentRead.value}` for USER sessions or SERVICE tokens. " +
            "Financial lines and totals are authoritative; requested pricing inputs remain inquiry history. " +
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
                            ),
                    )
            }
    }
}
