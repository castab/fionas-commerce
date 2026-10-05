package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.RecordInquiryCommunication
import io.github.castab.fionas.commerce.staff.FionaPermissions
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import java.util.UUID

internal fun acknowledgeInquiryCommunicationRoute(
    acknowledge: (RecordInquiryCommunication.Acknowledge) -> Unit,
    access: AccessControl,
): ContractRoute {
    val path = Path.of("inquiryId", "The inquiry's identity.", mapOf("schema" to mapOf("format" to "uuid")))
    return "/inquiries" / path / "communications" / "acknowledge" meta {
        operationId = "acknowledgeInquiryCommunication"
        summary = "Acknowledge customer communication"
        description = "Records an explicit acknowledgement at the server Clock time with authenticated USER or SERVICE provenance. " +
            "Clears customer email durably recorded before this action; " +
            "later ingestion remains outstanding even with an older occurredAt. " +
            "Repeated acknowledgements succeed and append a fresh fact. " +
            "Does not reset quote inactivity or change financial/lifecycle state. No message content or provider integration."
        tags += inquiries
        principalAccess(FionaPermissions.CommunicationsAcknowledge, UNTRUSTED_ORIGIN)
        returning(Status.NO_CONTENT to "Acknowledgement recorded; also succeeds when no inbound email is outstanding.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "inquiryId is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returningError(ErrorCategory.INTERNAL_FAILURE, "An unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access.requirePermission(FionaPermissions.CommunicationsAcknowledge).then { request: Request ->
            val inquiry =
                try {
                    InquiryId(UUID.fromString(id))
                } catch (failure: IllegalArgumentException) {
                    throw LensFailure(Invalid(path.meta), cause = failure)
                }
            acknowledge(RecordInquiryCommunication.Acknowledge(inquiry, authenticatedPrincipal(request)))
            Response(Status.NO_CONTENT).header("Cache-Control", "no-store")
        }
    }
}
