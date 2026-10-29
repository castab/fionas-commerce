package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.inquiry.ConversationChanged
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.RecordInquiryCommunication
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
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
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import java.util.UUID

@Serializable
data class AcknowledgeCommunicationRequest(
    @ApiProperty(description = "Observed inquiry communication watermark; 0 only for an empty history.")
    val throughRecordedOrder: Long,
)

private val acknowledgementBody = jsonBody(AcknowledgeCommunicationRequest.serializer())
private val conversationConflictBody = jsonBody(ErrorResponse.serializer())
internal val conversationChangedResponses =
    Filter { next ->
        { request ->
            try {
                next(request)
            } catch (failure: CommerceFailure.Conflict) {
                if (failure.cause !is ConversationChanged) throw failure
                Response(Status.CONFLICT)
                    .header("Cache-Control", "no-store")
                    .with(conversationConflictBody of ErrorResponse("conversation_changed", failure.message!!))
            }
        }
    }

internal fun acknowledgeInquiryCommunicationRoute(
    acknowledge: (RecordInquiryCommunication.Acknowledge) -> Unit,
    access: AccessControl,
): ContractRoute {
    val path = Path.of("inquiryId", "The inquiry's identity.", mapOf("schema" to mapOf("format" to "uuid")))
    return "/inquiries" / path / "communications" / "acknowledge" meta {
        operationId = "acknowledgeInquiryCommunication"
        summary = "Acknowledge customer communication"
        description = "Records an explicit acknowledgement at the server Clock time with authenticated USER or SERVICE provenance. " +
            "Requires the observed throughRecordedOrder; newly recorded inbound conflicts with conversation_changed. " +
            "later ingestion remains outstanding even with an older occurredAt. " +
            "Repeated acknowledgements succeed and append a fresh fact. " +
            "Does not reset quote inactivity or change financial/lifecycle state. No message content or provider integration."
        receiving(acknowledgementBody to AcknowledgeCommunicationRequest(0))
        returning(
            Status.CONFLICT,
            conversationConflictBody to
                ErrorResponse("conversation_changed", "Customer communication has changed; reload before acknowledging"),
            "`conversation_changed`: customer email was recorded after the observed watermark; reload and review. " +
                "Nothing is written. No-store.",
        )
        returningError(ErrorCategory.VALIDATION_FAILED, "Invalid or foreign watermark.", "Invalid communication watermark")
        tags += inquiries
        principalAccess(FionaPermissions.CommunicationsAcknowledge, UNTRUSTED_ORIGIN)
        returning(Status.NO_CONTENT to "Acknowledgement recorded; also succeeds when no inbound email is outstanding.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "inquiryId is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returningError(ErrorCategory.INTERNAL_FAILURE, "An unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access.requirePermission(FionaPermissions.CommunicationsAcknowledge).then(conversationChangedResponses).then { request: Request ->
            val inquiry =
                try {
                    InquiryId(UUID.fromString(id))
                } catch (failure: IllegalArgumentException) {
                    throw LensFailure(Invalid(path.meta), cause = failure)
                }
            val input = acknowledgementBody(request)
            validating {
                acknowledge(
                    RecordInquiryCommunication.Acknowledge(inquiry, authenticatedPrincipal(request), input.throughRecordedOrder),
                )
            }
            Response(Status.NO_CONTENT).header("Cache-Control", "no-store")
        }
    }
}
