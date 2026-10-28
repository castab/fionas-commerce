package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.conversation.ConversationAvailability
import io.github.castab.fionas.commerce.conversation.MilestoneKind
import io.github.castab.fionas.commerce.conversation.StaffTimeline
import io.github.castab.fionas.commerce.conversation.TimelineItem
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
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
data class TimelineConversationResponse(
    val state: ConversationAvailability,
    val subject: String? = null,
    @ApiProperty(description = "Provider conversation state (awaiting_us, awaiting_participant, concluded, terminated) when READY.")
    val providerState: String? = null,
    @ApiProperty(
        description = "The provider's newest message when READY; send it as expectedLatestMessageId when replying.",
        format = "uuid",
    )
    val latestMessageId: String? = null,
)

@Serializable
data class TimelineActorResponse(
    val principalKind: String,
    val principalId: String,
)

@Serializable
data class StaffTimelineResponse(
    val conversation: TimelineConversationResponse,
    val attention: CommunicationSummaryResponse,
    val items: List<TimelineItemResponse>,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed class TimelineItemResponse {
    abstract val id: String
    abstract val occurredAt: String

    @Serializable
    @SerialName("MESSAGE")
    data class Message(
        override val id: String,
        override val occurredAt: String,
        val messageId: String,
        val direction: String,
        val senderAddress: String,
        val senderName: String?,
        val recipient: String,
        val text: String?,
        val sendState: String,
        val deliveryState: String?,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("QUOTE_PUBLISHED")
    data class Quote(
        override val id: String,
        override val occurredAt: String,
        val proposal: InquiryProposalResponse,
        val financial: FinancialDocumentResponse,
        val depositRequirement: HistoricalDepositRequirementResponse,
        val actor: TimelineActorResponse,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("INVOICE_AMENDED")
    data class Invoice(
        override val id: String,
        override val occurredAt: String,
        val financial: FinancialDocumentResponse,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("PAYMENT_RECORDED")
    data class Payment(
        override val id: String,
        override val occurredAt: String,
        val paymentId: String,
        @ApiProperty(pattern = SIGNED_DECIMAL) val amount: String,
        val currency: String,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("REFUND_RECORDED")
    data class Refund(
        override val id: String,
        override val occurredAt: String,
        val paymentId: String,
        @ApiProperty(pattern = SIGNED_DECIMAL) val amount: String,
        val currency: String,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("COMMUNICATION_ACKNOWLEDGED")
    data class Acknowledged(
        override val id: String,
        override val occurredAt: String,
        val actor: TimelineActorResponse,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("SERVED")
    data class Served(
        override val id: String,
        override val occurredAt: String,
        val actor: TimelineActorResponse,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("CLOSED")
    data class Closed(
        override val id: String,
        override val occurredAt: String,
        val actor: TimelineActorResponse,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("DECLINED")
    data class Declined(
        override val id: String,
        override val occurredAt: String,
        val actor: TimelineActorResponse,
        val reason: String,
    ) : TimelineItemResponse()
}

private fun PrincipalId.timelineActor(): TimelineActorResponse =
    when (this) {
        is UserId -> TimelineActorResponse("USER", value.toString())
        is ServiceId -> TimelineActorResponse("SERVICE", value.toString())
    }

internal fun StaffTimeline.toResponse(): StaffTimelineResponse =
    StaffTimelineResponse(
        TimelineConversationResponse(
            conversation.availability,
            conversation.conversation?.subject,
            conversation.conversation?.state,
            conversation.conversation
                ?.messages
                ?.lastOrNull()
                ?.id
                ?.toString(),
        ),
        request.communicationResponse(),
        items.map { item ->
            val at = item.occurredAt.toString()
            when (item) {
                is TimelineItem.Message ->
                    item.message.let {
                        TimelineItemResponse.Message(
                            item.id,
                            at,
                            it.id.toString(),
                            it.direction,
                            it.senderAddress,
                            it.senderName,
                            it.recipient,
                            it.text,
                            it.state,
                            it.deliveryState,
                        )
                    }
                is TimelineItem.Quote ->
                    TimelineItemResponse.Quote(
                        item.id,
                        at,
                        item.proposal.toResponse(),
                        item.snapshot.toResponse(request.inquiry.inquiry.id, null),
                        item.deposit.historyResponse(),
                        item.proposal.issuedBy.timelineActor(),
                    )
                is TimelineItem.Invoice ->
                    TimelineItemResponse.Invoice(
                        item.id,
                        at,
                        item.snapshot.toResponse(request.inquiry.inquiry.id, null),
                    )
                is TimelineItem.Payment ->
                    if (item.refunded) {
                        TimelineItemResponse.Refund(item.id, at, item.paymentId, item.amount.decimal(), item.amount.currency.currencyCode)
                    } else {
                        TimelineItemResponse.Payment(item.id, at, item.paymentId, item.amount.decimal(), item.amount.currency.currencyCode)
                    }
                is TimelineItem.Milestone ->
                    when (item.kind) {
                        MilestoneKind.COMMUNICATION_ACKNOWLEDGED ->
                            TimelineItemResponse.Acknowledged(
                                item.id,
                                at,
                                item.actor.timelineActor(),
                            )
                        MilestoneKind.SERVED -> TimelineItemResponse.Served(item.id, at, item.actor.timelineActor())
                        MilestoneKind.CLOSED -> TimelineItemResponse.Closed(item.id, at, item.actor.timelineActor())
                        MilestoneKind.DECLINED ->
                            TimelineItemResponse.Declined(
                                item.id,
                                at,
                                item.actor.timelineActor(),
                                checkNotNull(item.reason),
                            )
                    }
            }
        },
    )

internal fun staffTimelineRoute(
    read: (InquiryId, PrincipalId) -> StaffTimeline,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(StaffTimelineResponse.serializer())
    return "/staff/requests" / inquiryDetailIdPath / "timeline" meta {
        operationId = "readStaffTimeline"
        summary = "Read conversation messages and canonical commerce history"
        description = "Requires inquiry and financial read. Email content additionally requires fionas.communications.read. " +
            "Items sort by occurrence timestamp then stable lexical id; this is presentation order, not cross-system causal order. " +
            "Commerce history uses one database snapshot, followed by a bounded nontransactional provider read. " +
            "READY means messages loaded; EMPTY means topic lookup returned 404 with no local establishment evidence; " +
            "MISSING means 404 despite ingested establishment evidence; UNAVAILABLE means disabled, failed or incomplete read; " +
            "FORBIDDEN means caller or provider denied message access. Commerce history remains present for each state. " +
            "Plain text only; null text means the provider has no plain-text body. Acceptance is never delivery. No-store."
        tags += Tag("Staff requests", "Coherent inquiry and canonical financial detail for staff request review.")
        principalAuthentication()
        returning(
            Status.OK,
            body to
                StaffTimelineResponse(
                    TimelineConversationResponse(ConversationAvailability.EMPTY),
                    CommunicationSummaryResponse(false, 0),
                    emptyList(),
                ),
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "Invalid inquiry UUID.", "Invalid inquiry UUID")
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returningError(ErrorCategory.FORBIDDEN, "Missing inquiry or financial read permission.", "Not permitted")
        returningError(ErrorCategory.INTERNAL_FAILURE, "Inconsistent commerce history or unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.GET to { id: String, _: String ->
        access
            .requirePermission(
                FionaPermissions.InquiriesRead,
            ).then(access.requirePermission(CommercePermissions.FinancialDocumentRead))
            .then { request: Request ->
                Response(Status.OK).header("Cache-Control", "no-store").with(
                    body of read(inquiryId(id), authenticatedPrincipal(request)).toResponse(),
                )
            }
    }
}
