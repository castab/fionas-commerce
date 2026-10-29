package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
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
import io.github.castab.fionas.commerce.conversation.PaymentClassification
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
data class TimelinePaymentAllocationResponse(
    @ApiProperty(format = "uuid") val allocationId: String,
    @ApiProperty(description = "The destination lineage.", format = "uuid") val documentId: String,
    @ApiProperty(description = "The exact snapshot version the allocation stays attached to.") val documentVersion: Int,
    @ApiProperty(description = "Whether the destination is this inquiry's canonical lineage.") val canonical: Boolean,
    @ApiProperty(description = "ESTIMATE, QUOTE or INVOICE of that exact snapshot; canonical lineage only.")
    val documentStage: String? = null,
    @ApiProperty(description = "The amount applied, an exact decimal string.", pattern = SIGNED_DECIMAL) val amount: String,
    val currency: String,
    @ApiProperty(format = "date-time") val allocatedAt: String,
)

@Serializable
data class TimelineRefundUnwindResponse(
    @ApiProperty(format = "uuid") val refundAllocationId: String,
    @ApiProperty(format = "uuid") val paymentAllocationId: String,
    @ApiProperty(description = "The unwound allocation's lineage.", format = "uuid") val documentId: String,
    @ApiProperty(description = "The unwound allocation's exact snapshot version.") val documentVersion: Int,
    @ApiProperty(description = "The amount unwound, an exact decimal string.", pattern = SIGNED_DECIMAL) val amount: String,
    val currency: String,
    @ApiProperty(format = "date-time") val allocatedAt: String,
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
        @ApiProperty(format = "uuid") val paymentId: String,
        @ApiProperty(description = "The received amount, an exact decimal string.", pattern = SIGNED_DECIMAL) val amount: String,
        val currency: String,
        @ApiProperty(description = "How the money was received, from the runtime payment record.") val method: String,
        @ApiProperty(description = "The payment's identity in an external system, when recorded.")
        val externalReference: PaymentExternalReference? = null,
        @ApiProperty(
            description =
                "DEPOSIT: the receipt accepted against a published canonical Quote (its proposal is named). " +
                    "ADDITIONAL: any other receipt in the canonical lineage's history. Refunds never reclassify a receipt.",
        )
        val classification: PaymentClassification,
        @ApiProperty(description = "The published proposal this deposit accepted; DEPOSIT only.", format = "uuid")
        val proposalId: String? = null,
        @ApiProperty(description = "The deposit requirement revision this deposit satisfied; DEPOSIT only.")
        val depositRequirementRevision: Int? = null,
        @ApiProperty(description = "Every allocation of the payment, in runtime order, including ones later unwound.")
        val allocations: List<TimelinePaymentAllocationResponse>,
    ) : TimelineItemResponse()

    @Serializable
    @SerialName("REFUND_RECORDED")
    data class Refund(
        override val id: String,
        override val occurredAt: String,
        @ApiProperty(format = "uuid") val refundId: String,
        @ApiProperty(format = "uuid") val paymentId: String,
        @ApiProperty(description = "The refunded amount, an exact decimal string.", pattern = SIGNED_DECIMAL) val amount: String,
        val currency: String,
        @ApiProperty(description = "How the money was returned, from the runtime refund record.") val method: String,
        @ApiProperty(description = "The refund's identity at an external provider, when recorded.")
        val externalReference: RefundExternalReference? = null,
        @ApiProperty(description = "The refunded receipt's classification.")
        val paymentClassification: PaymentClassification,
        @ApiProperty(description = "The allocations this refund explicitly unwound; empty when it returned unapplied value.")
        val unwound: List<TimelineRefundUnwindResponse>,
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

private fun stageName(document: FinancialDocument) =
    when (document) {
        is FinancialDocument.Estimate -> "ESTIMATE"
        is FinancialDocument.Quote -> "QUOTE"
        is FinancialDocument.Invoice -> "INVOICE"
    }

private fun TimelineItem.Payment.toResponse(): TimelineItemResponse.Payment {
    val payment = history.payment
    return TimelineItemResponse.Payment(
        id,
        occurredAt.toString(),
        payment.id.toString(),
        payment.amount.decimal(),
        payment.currency.currencyCode,
        payment.method.name,
        payment.externalReference?.let { PaymentExternalReference(it.provider, it.reference) },
        classification,
        acceptedProposal?.id?.value?.toString(),
        acceptedProposal?.depositRequirementRevision?.number,
        history.allocations.map {
            val reference = it.financialDocumentReference
            TimelinePaymentAllocationResponse(
                it.id.toString(),
                reference.id.toString(),
                reference.version.number,
                canonicalDocuments.keys.any { known -> known.id == reference.id },
                canonicalDocuments[reference]?.let(::stageName),
                it.amount.decimal(),
                it.currency.currencyCode,
                it.allocatedAt.toString(),
            )
        },
    )
}

private fun TimelineItem.Refund.toResponse(): TimelineItemResponse.Refund {
    val allocations = payment.history.allocations.associateBy { it.id }
    return TimelineItemResponse.Refund(
        id,
        occurredAt.toString(),
        refund.id.toString(),
        refund.paymentReference.toString(),
        refund.amount.decimal(),
        refund.currency.currencyCode,
        refund.method.name,
        refund.externalReference?.let { RefundExternalReference(it.provider, it.reference) },
        payment.classification,
        unwound.map {
            val allocation = checkNotNull(allocations[it.paymentAllocationReference]) { "Refund unwinds an unknown allocation" }
            TimelineRefundUnwindResponse(
                it.id.toString(),
                it.paymentAllocationReference.toString(),
                allocation.financialDocumentReference.id.toString(),
                allocation.financialDocumentReference.version.number,
                it.amount.decimal(),
                it.currency.currencyCode,
                it.allocatedAt.toString(),
            )
        },
    )
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
                is TimelineItem.Payment -> item.toResponse()
                is TimelineItem.Refund -> item.toResponse()
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
