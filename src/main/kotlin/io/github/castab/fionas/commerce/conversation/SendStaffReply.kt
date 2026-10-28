package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.TransactionIsolation
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.ConversationChanged
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.IdempotencyKeyReused
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

private val replyLogger = KotlinLogging.logger {}

/** A refusal before or instead of a send, with a stable code. Nothing was sent. */
class ConversationSendRefused(
    val code: String,
    message: String,
) : RuntimeException(message)

/**
 * Staff reply orchestration. Commerce state commits in short transactions around, never across, the provider call:
 * the command record is durable before sending so an ambiguous outcome is reconciled by replaying the exact request
 * under the same provider key, and STAFF_EMAIL_SENT is recorded only after durable provider intent.
 */
class SendStaffReply(
    private val transactor: Transactor,
    private val inquiries: GetInquiry,
    private val communications: InquiryCommunicationRepository,
    private val replies: JdbiStaffReplyRepository,
    private val establishment: ConversationEstablishment,
    private val users: (UserId) -> User?,
    private val sender: ConversationSender?,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    data class Command(
        val inquiryId: InquiryId,
        val sentBy: UserId,
        val key: StaffReplyKey,
        val body: StaffReplyBody,
        val expectation: ConversationExpectation,
    )

    enum class Outcome { ACCEPTED, UNKNOWN }

    data class Result(
        val outcome: Outcome,
        val replayed: Boolean,
        val conversationId: UUID? = null,
        val messageId: UUID? = null,
        val providerState: String? = null,
    )

    operator fun invoke(command: Command): Result {
        val fingerprint = staffReplyFingerprint(command.inquiryId, command.sentBy, command.body, command.expectation)
        transactor.inTransaction { replies.find(it, command.key) }?.let { return resume(it, command, fingerprint) }
        val provider = sender ?: throw ConversationSendRefused(SENDING_DISABLED, "Email sending is not enabled")
        // The observed boundary is read before the provider lookup: any inbound Fiona had recorded by then was already
        // in the provider thread, so a lookup confirming the staff member's expectation proves they saw it. Inbound
        // recorded later is never cleared by this reply, however late the provider accepts it.
        val (details, established, observedThrough) =
            transactor.inTransaction(TransactionIsolation.REPEATABLE_READ) { transaction ->
                Triple(
                    inquiries.read(transaction, command.inquiryId),
                    establishment.established(transaction, command.inquiryId),
                    communications.history(transaction, command.inquiryId).maxOfOrNull { it.recordedOrder } ?: 0L,
                )
            }
        if (!provider.configuration.permits(details.customer.email)) {
            throw ConversationSendRefused(RECIPIENT_NOT_ALLOWED, "This customer's address is not on the outgoing email allow-list")
        }
        val signature = checkNotNull(users(command.sentBy)) { "Authenticated staff user is missing" }.displayName.trim()
        val conversation =
            when (val lookup = provider.lookup(command.inquiryId)) {
                TopicLookup.Unavailable, TopicLookup.Forbidden ->
                    throw ConversationSendRefused(PROVIDER_UNAVAILABLE, "The email provider could not be reached; nothing was sent")
                TopicLookup.Missing -> {
                    if (command.expectation != ConversationExpectation.Empty) changed()
                    // A thread Fiona has evidence of must never be silently replaced by a new opening message.
                    if (established) {
                        throw ConversationSendRefused(CONVERSATION_MISSING, "The established email conversation is missing")
                    }
                    null
                }
                is TopicLookup.Found -> {
                    val expected = (command.expectation as? ConversationExpectation.Latest)?.messageId
                    if (expected != lookup.latestMessageId) changed()
                    lookup.conversationId
                }
            }
        val request = request(provider, command, details.customer.email, details.customer.name.value, conversation, signature)
        val record =
            StaffReplyRecord(
                command.key,
                command.inquiryId,
                fingerprint,
                command.sentBy,
                if (conversation == null) StaffReplyMode.OPEN else StaffReplyMode.REPLY,
                request.digest,
                signature,
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                conversation,
                observedThrough,
            )
        val claimed =
            transactor.inTransaction { transaction ->
                communications.lock(transaction, command.inquiryId)
                replies.claim(transaction, record)
            }
        if (!claimed) return resume(checkNotNull(transactor.inTransaction { replies.find(it, command.key) }), command, fingerprint)
        return dispatch(provider, record, request, replayed = false)
    }

    private fun resume(
        record: StaffReplyRecord,
        command: Command,
        fingerprint: String,
    ): Result {
        if (record.inquiryId != command.inquiryId || record.fingerprint != fingerprint) {
            throw CommerceFailure.Conflict("This key already represents a different staff reply", IdempotencyKeyReused())
        }
        return when (record.outcome) {
            StaffReplyOutcome.ACCEPTED ->
                Result(Outcome.ACCEPTED, true, record.conversationId, record.providerMessageId, record.providerState)
            StaffReplyOutcome.REJECTED -> rejected()
            null -> {
                val provider = sender ?: throw ConversationSendRefused(SENDING_DISABLED, "Email sending is not enabled")
                val customer = transactor.inTransaction { inquiries.read(it, command.inquiryId) }.customer
                val request = request(provider, command, customer.email, customer.name.value, record.conversationId, record.signatureName)
                // Never send a different request under a key the provider may already hold.
                check(request.digest == record.providerRequest) { "Staff reply provider request changed; reconcile manually" }
                dispatch(provider, record, request, replayed = true)
            }
        }
    }

    private fun dispatch(
        provider: ConversationSender,
        record: StaffReplyRecord,
        request: ProviderSendRequest,
        replayed: Boolean,
    ): Result =
        when (val outcome = provider.send(request)) {
            is ProviderSendOutcome.Accepted -> {
                transactor.inTransaction { transaction ->
                    communications.lock(transaction, record.inquiryId)
                    val current = checkNotNull(replies.find(transaction, record.key))
                    if (current.outcome == null) {
                        val activity =
                            InquiryCommunication(
                                InquiryCommunicationId(newId()),
                                record.inquiryId,
                                InquiryCommunicationKind.STAFF_EMAIL_SENT,
                                clock.instant().truncatedTo(ChronoUnit.MICROS),
                                record.sentBy,
                                current.observedThrough,
                            )
                        communications.append(transaction, activity)
                        replies.accept(transaction, record.key, outcome.conversationId, outcome.messageId, outcome.state, activity.id)
                    } else {
                        check(current.outcome == StaffReplyOutcome.ACCEPTED && current.providerMessageId == outcome.messageId) {
                            "Staff reply provider result disagrees with its recorded outcome"
                        }
                    }
                }
                Result(Outcome.ACCEPTED, replayed, outcome.conversationId, outcome.messageId, outcome.state)
            }
            ProviderSendOutcome.Rejected -> {
                transactor.inTransaction { transaction ->
                    communications.lock(transaction, record.inquiryId)
                    replies.reject(transaction, record.key)
                }
                replyLogger.warn { "event=staff_reply_rejected inquiry=${record.inquiryId.value}" }
                rejected()
            }
            ProviderSendOutcome.Unknown -> {
                replyLogger.warn { "event=staff_reply_outcome_unknown inquiry=${record.inquiryId.value} action=replay_same_key" }
                Result(Outcome.UNKNOWN, replayed, record.conversationId)
            }
        }

    private fun request(
        provider: ConversationSender,
        command: Command,
        participant: Email,
        participantName: String,
        conversation: UUID?,
        signature: String,
    ) = staffReplyRequest(
        provider.configuration,
        command.key,
        command.inquiryId,
        conversation,
        participant,
        participantName,
        signedStaffReply(command.body, signature),
    )

    private fun changed(): Nothing =
        throw CommerceFailure.Conflict("The conversation has changed; review it before replying", ConversationChanged())

    private fun rejected(): Nothing = throw ConversationSendRefused(SEND_REJECTED, "The email provider did not accept this reply")

    companion object {
        const val SENDING_DISABLED = "email_sending_disabled"
        const val RECIPIENT_NOT_ALLOWED = "email_recipient_not_allowed"
        const val PROVIDER_UNAVAILABLE = "email_provider_unavailable"
        const val CONVERSATION_MISSING = "conversation_missing"
        const val SEND_REJECTED = "email_send_rejected"
    }
}
