package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryLifecycle
import io.github.castab.fionas.commerce.inquiry.InquiryMilestone
import io.github.castab.fionas.commerce.inquiry.ManageInquiryFulfillment
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
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
enum class InquiryStageResponse { REQUESTED, QUOTED, BOOKED, SERVED, CLOSED }

@Serializable
enum class InquiryActorKind { USER, SERVICE }

@Serializable
data class InquiryMilestoneResponse(
    @ApiProperty(description = "When this action occurred, in UTC with microsecond precision.", format = "date-time")
    val occurredAt: String,
    val principalKind: InquiryActorKind,
    @ApiProperty(description = "The authenticated acting principal's identity.", format = "uuid")
    val principalId: String,
)

@Serializable
data class InquiryLifecycleResponse(
    @ApiProperty(
        description = "The canonical INITIAL_ESTIMATE financial lineage; RELATED lineages do not drive lifecycle.",
        format = "uuid",
    )
    val documentId: String,
    @ApiProperty(
        description = "Projected from the canonical financial stage and Fiona served/closed facts. Never stored status or date-driven.",
    )
    val stage: InquiryStageResponse,
    val served: InquiryMilestoneResponse? = null,
    val closed: InquiryMilestoneResponse? = null,
)

internal fun InquiryLifecycle.toResponse() =
    InquiryLifecycleResponse(
        documentId.toString(),
        InquiryStageResponse.valueOf(stage.name),
        fulfillment?.served?.toResponse(),
        fulfillment?.closed?.toResponse(),
    )

private fun InquiryMilestone.toResponse(): InquiryMilestoneResponse {
    val (kind, id) =
        when (val principal: PrincipalId = principalId) {
            is UserId -> InquiryActorKind.USER to principal.value
            is ServiceId -> InquiryActorKind.SERVICE to principal.value
        }
    return InquiryMilestoneResponse(occurredAt.toString(), kind, id.toString())
}

/** Business-action routes with no client-assigned state or provenance. */
internal fun inquiryFulfillmentRoute(
    segment: String,
    operation: String,
    action: (ManageInquiryFulfillment.Command) -> InquiryLifecycle,
    access: AccessControl,
): ContractRoute {
    val path = Path.of("inquiryId", "The inquiry's identity.", mapOf("schema" to mapOf("format" to "uuid")))
    val response = jsonBody(InquiryLifecycleResponse.serializer())
    val closing = segment == "close"
    return "/inquiries" / path / segment meta {
        operationId = operation
        summary = if (closing) "Close a served inquiry" else "Mark a booked inquiry served"
        description =
            if (closing) {
                "Explicitly closes a served inquiry only when its canonical current Invoice balance is exactly zero. " +
                    "Positive and negative balances both reject closure. Records time and authenticated principal. " +
                    "Repeated closure is an illegal transition. Later ledger activity is still allowed."
            } else {
                "Explicitly records service of a booked inquiry whose canonical lineage is Invoice. " +
                    "Records time and authenticated principal without changing financial, payment or deposit facts. " +
                    "Repeated service or service after close is an illegal transition. Event date never serves an inquiry."
            }
        tags += inquiries
        principalAccess(FionaPermissions.InquiriesManage, UNTRUSTED_ORIGIN)
        returning(
            Status.OK,
            response to
                InquiryLifecycleResponse(
                    "aec8f5a3-9d32-470b-a519-55b17f0cfb27",
                    if (closing) InquiryStageResponse.CLOSED else InquiryStageResponse.SERVED,
                    InquiryMilestoneResponse("2026-12-05T18:30:00Z", InquiryActorKind.USER, "602df298-8d54-45b6-a40c-bbf80949a3b8"),
                    if (closing) {
                        InquiryMilestoneResponse(
                            "2026-12-05T19:30:00Z",
                            InquiryActorKind.USER,
                            "602df298-8d54-45b6-a40c-bbf80949a3b8",
                        )
                    } else {
                        null
                    },
                ),
            "The current lifecycle projection and recorded provenance.",
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "inquiryId is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returningError(
            ErrorCategory.CONFLICT,
            "A competing transition changed the inquiry. The same status with code `illegal_transition` means " +
                "the current lifecycle or balance does not allow this action, including repeated service/closeout.",
            "Inquiry is already closed",
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "An unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to { id: String, _: String ->
        access.requirePermission(FionaPermissions.InquiriesManage).then { request: Request ->
            val inquiryId =
                try {
                    InquiryId(UUID.fromString(id))
                } catch (failure: IllegalArgumentException) {
                    throw LensFailure(Invalid(path.meta), cause = failure)
                }
            Response(Status.OK).with(
                response of action(ManageInquiryFulfillment.Command(inquiryId, authenticatedPrincipal(request))).toResponse(),
            )
        }
    }
}
