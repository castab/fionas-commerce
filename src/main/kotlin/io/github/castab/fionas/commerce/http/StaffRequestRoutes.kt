package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.CommercePermissions
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
    @ApiProperty(description = "Current canonical INITIAL_ESTIMATE lineage only; immutable financial facts and current reconciliation.")
    val financial: FinancialDocumentResponse,
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
            "canonical lifecycle and the latest immutable INITIAL_ESTIMATE financial lineage with current reconciliation. " +
            "RELATED lineages are excluded. Requires BOTH `${FionaPermissions.InquiriesRead.value}` and " +
            "`${CommercePermissions.FinancialDocumentRead.value}` for USER sessions or SERVICE tokens. " +
            "Financial lines and totals are authoritative; requested pricing inputs remain inquiry history. " +
            "Use financial.id and financial.version as documentId and expectedVersion for the existing Quote transition; " +
            "stale versions conflict and require reload. This read changes no state. Successful responses use Cache-Control: no-store."
        tags += Tag("Staff requests", "Coherent inquiry and canonical financial detail for staff request review.")
        principalAuthentication()
        returning(Status.OK, body to StaffRequestResponse(exampleInquiry, financial))
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
                    .with(body of StaffRequestResponse(request.inquiry.toResponse(), request.financial.toResponse()))
            }
    }
}
