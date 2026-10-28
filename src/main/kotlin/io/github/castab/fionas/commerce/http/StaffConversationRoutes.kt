package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.conversation.ConversationExpectation
import io.github.castab.fionas.commerce.conversation.ConversationSendRefused
import io.github.castab.fionas.commerce.conversation.SendStaffReply
import io.github.castab.fionas.commerce.conversation.StaffReplyBody
import io.github.castab.fionas.commerce.conversation.StaffReplyKey
import io.github.castab.fionas.commerce.inquiry.ConversationChanged
import io.github.castab.fionas.commerce.inquiry.IdempotencyKeyReused
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
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
import org.http4k.lens.Header
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import java.util.UUID

@Serializable
data class StaffReplyRequest(
    @ApiProperty(
        description =
            "Plain-text reply. Trimmed at the edges; internal newlines are preserved. The backend appends the " +
                "authenticated staff member's signature.",
        maxLength = StaffReplyBody.MAX_LENGTH,
    )
    val body: String,
    @ApiProperty(
        description =
            "The newest provider message the staff member reviewed (the timeline's conversation.latestMessageId). " +
                "Exactly one of this or expectEmptyConversation is required.",
        format = "uuid",
    )
    val expectedLatestMessageId: String? = null,
    @ApiProperty(description = "True when the staff member reviewed an inquiry with no email conversation yet.")
    val expectEmptyConversation: Boolean = false,
)

@Serializable
enum class StaffReplyOutcomeResponse { ACCEPTED, UNKNOWN }

@Serializable
data class StaffReplyResponse(
    @ApiProperty(
        description =
            "ACCEPTED: the provider durably holds the outbound intent (`sendState` pending or accepted); never " +
                "delivery. UNKNOWN: the outcome could not be confirmed; retry with the SAME Idempotency-Key, body and " +
                "original expectation (expectedLatestMessageId or expectEmptyConversation as first sent) to reconcile, " +
                "never a new key.",
    )
    val outcome: StaffReplyOutcomeResponse,
    @ApiProperty(description = "True when this key's earlier command was replayed rather than newly sent.")
    val replayed: Boolean,
    @ApiProperty(format = "uuid") val conversationId: String? = null,
    @ApiProperty(format = "uuid") val messageId: String? = null,
    @ApiProperty(description = "Provider message state: pending or accepted. Delivery appears later on the timeline.")
    val sendState: String? = null,
)

private val replyKeyHeader =
    Header.required(
        "Idempotency-Key",
        "Opaque staff reply identity, 1–128 ASCII letters, digits, underscores or hyphens. Use the SAME key and body for " +
            "every retry of one reply, including after a timeout or UNKNOWN outcome. Not a credential.",
        mapOf(
            "schema" to mapOf("minLength" to 1, "maxLength" to StaffReplyKey.MAX_LENGTH, "pattern" to StaffReplyKey.PATTERN),
        ),
    )
private val replyBody = jsonBody(StaffReplyRequest.serializer())
private val replyResponse = jsonBody(StaffReplyResponse.serializer())
private val replyError = jsonBody(ErrorResponse.serializer())

private fun refusalStatus(code: String): Status =
    when (code) {
        SendStaffReply.RECIPIENT_NOT_ALLOWED -> Status.UNPROCESSABLE_ENTITY
        SendStaffReply.CONVERSATION_MISSING -> Status.CONFLICT
        SendStaffReply.SEND_REJECTED -> Status.BAD_GATEWAY
        SendStaffReply.SENDING_DISABLED, SendStaffReply.PROVIDER_UNAVAILABLE -> Status.SERVICE_UNAVAILABLE
        else -> error("Unknown conversation send refusal")
    }

/** Fiona-local codes in the runtime ErrorResponse envelope; the runtime has no category for them. */
private val replyFailures =
    Filter { next ->
        { request ->
            try {
                next(request)
            } catch (refused: ConversationSendRefused) {
                Response(refusalStatus(refused.code))
                    .header("Cache-Control", "no-store")
                    .with(replyError of ErrorResponse(refused.code, refused.message!!))
            } catch (failure: CommerceFailure.Conflict) {
                val code =
                    when (failure.cause) {
                        is ConversationChanged -> "conversation_changed"
                        is IdempotencyKeyReused -> IDEMPOTENCY_KEY_REUSED
                        else -> throw failure
                    }
                Response(Status.CONFLICT).header("Cache-Control", "no-store").with(replyError of ErrorResponse(code, failure.message!!))
            }
        }
    }

internal fun sendStaffReplyRoute(
    send: (SendStaffReply.Command) -> SendStaffReply.Result,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "conversation" / "replies" meta {
        operationId = "sendStaffConversationReply"
        headers += replyKeyHeader
        preFlightExtraction = PreFlightExtraction.None
        summary = "Reply in an inquiry's email conversation"
        description =
            "Sends a verified staff user's plain-text reply to the inquiry's customer through the Resend Conversation Service, " +
            "in the conversation whose topic is (`booking`, inquiryId). The recipient is the inquiry's customer and the " +
            "sender/Reply-To identities are configuration; the body cannot choose either. The reply is signed with the " +
            "authenticated user's display name. When the topic has no conversation and Fiona has no evidence of one, the " +
            "reply opens it (expectEmptyConversation); the provider generates the Reply-To routing address. " +
            "The expectation is checked against the provider's newest message immediately before sending: a newer message " +
            "is `409 conversation_changed`. A small race remains between that check and the provider accepting the reply. " +
            "Idempotency-Key is required: the command is recorded before the provider call, and the same key with the same " +
            "body replays the exact provider request and provider idempotency key, so a retry never sends a second email. " +
            "The same key with different content is `409 IDEMPOTENCY_KEY_REUSED`. On durable provider intent Fiona records " +
            "STAFF_EMAIL_SENT with the user's provenance, which clears customer communication attention recorded earlier. " +
            "201 is a new send, 200 a replay of an accepted send, and 202 UNKNOWN an outcome that could not be confirmed. " +
            "Acceptance is never delivery. Requires `${FionaPermissions.CommunicationsSend.value}` and a staff USER. No-store."
        tags += inquiries
        principalAuthentication()
        security = staffSessionSecurity
        receiving(replyBody to StaffReplyRequest("Hi Maria, we can adjust the deposit.", "019808b9-37c4-7ed7-93c5-8960f0b690ab"))
        val accepted =
            StaffReplyResponse(
                StaffReplyOutcomeResponse.ACCEPTED,
                false,
                "019808b9-37c4-7ed7-93c5-8960f0b690aa",
                "019808b9-37c4-7ed7-93c5-8960f0b690ac",
                "accepted",
            )
        returning(Status.CREATED, replyResponse to accepted, "The provider durably accepted the new reply.")
        returning(
            Status.OK,
            replyResponse to accepted.copy(replayed = true),
            "A replay of this key's accepted reply; nothing new was sent.",
        )
        returning(
            Status.ACCEPTED,
            replyResponse to StaffReplyResponse(StaffReplyOutcomeResponse.UNKNOWN, false),
            "UNKNOWN: the provider outcome could not be confirmed and may have been sent. Never a failure: retry with the " +
                "same key, body and original expectation to reconcile, or review the timeline.",
        )
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "Idempotency-Key is missing, repeated or invalid; inquiryId or expectedLatestMessageId is not a UUID; or the body is not JSON.",
            "Malformed request",
        )
        returningError(
            ErrorCategory.FORBIDDEN,
            "The principal lacks `${FionaPermissions.CommunicationsSend.value}`, is a SERVICE, $UNTRUSTED_ORIGIN.",
            STAFF_SENDER_REQUIRED,
        )
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returning(
            Status.CONFLICT,
            replyError to ErrorResponse("conversation_changed", "The conversation has changed; review it before replying"),
            "`conversation_changed`: the newest provider message differs from the expectation (including an existing " +
                "conversation when an empty one was expected). `IDEMPOTENCY_KEY_REUSED`: the key belongs to a different reply. " +
                "`conversation_missing`: Fiona has evidence of a conversation the provider no longer returns; nothing is " +
                "opened in its place. No-store.",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "a blank or overlong body (over ${StaffReplyBody.MAX_LENGTH} characters), control characters, or not exactly one " +
                "expectation. The same status with code `email_recipient_not_allowed` means the customer's address is outside " +
                "the configured outgoing allow-list. Nothing was sent.",
            "Reply must not be blank",
        )
        returning(
            Status.BAD_GATEWAY,
            replyError to ErrorResponse(SendStaffReply.SEND_REJECTED, "The email provider did not accept this reply"),
            "`email_send_rejected`: the provider definitively did not send the reply. Replaying this key returns the same; " +
                "a corrected reply needs a new key.",
        )
        returning(
            Status.SERVICE_UNAVAILABLE,
            replyError to ErrorResponse(SendStaffReply.PROVIDER_UNAVAILABLE, "The email provider could not be reached; nothing was sent"),
            "`email_provider_unavailable`: the conversation lookup failed before anything was sent. `email_sending_disabled`: " +
                "outgoing email is not configured. Retry later with the same key.",
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "An unexpected failure; its cause is never described.", INTERNAL_FAILURE)
    } bindContract Method.POST to { id: String, _: String, _: String ->
        access
            .requirePermission(FionaPermissions.CommunicationsSend)
            .then(requireStaffSender)
            .then(replyFailures)
            .then { request: Request ->
                val inquiry = inquiryId(id)
                val key =
                    try {
                        require(request.headerValues("Idempotency-Key").size == 1)
                        StaffReplyKey(replyKeyHeader(request))
                    } catch (failure: IllegalArgumentException) {
                        throw LensFailure(Invalid(replyKeyHeader.meta), cause = failure)
                    }
                val input = replyBody(request)
                val latest =
                    input.expectedLatestMessageId?.let {
                        try {
                            UUID.fromString(it)
                        } catch (failure: IllegalArgumentException) {
                            throw LensFailure(Invalid(replyBody.metas.single().copy(name = "expectedLatestMessageId")), cause = failure)
                        }
                    }
                val command =
                    validating {
                        require((latest != null) != input.expectEmptyConversation) {
                            "Exactly one of expectedLatestMessageId or expectEmptyConversation is required"
                        }
                        SendStaffReply.Command(
                            inquiry,
                            staffUser(request),
                            key,
                            StaffReplyBody.of(input.body),
                            latest?.let(ConversationExpectation::Latest) ?: ConversationExpectation.Empty,
                        )
                    }
                val result = send(command)
                val status =
                    when {
                        result.outcome == SendStaffReply.Outcome.UNKNOWN -> Status.ACCEPTED
                        result.replayed -> Status.OK
                        else -> Status.CREATED
                    }
                Response(status).header("Cache-Control", "no-store").with(
                    replyResponse of
                        StaffReplyResponse(
                            StaffReplyOutcomeResponse.valueOf(result.outcome.name),
                            result.replayed,
                            result.conversationId?.toString(),
                            result.messageId?.toString(),
                            result.providerState,
                        ),
                )
            }
    }
