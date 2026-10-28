package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Opaque staff reply command identity, not a credential. Its exact spelling matters; no trimming. */
@JvmInline
value class StaffReplyKey(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH && FORMAT.matches(value)) { "Invalid staff reply key" }
    }

    companion object {
        const val MAX_LENGTH = 128
        const val PATTERN = "^[A-Za-z0-9_-]{1,128}$"
        private val FORMAT = Regex(PATTERN)
    }
}

/** Plain-text reply. Trimmed at the edges only, so internal newlines and indentation are preserved. */
@JvmInline
value class StaffReplyBody(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim()) { "Reply must not be blank" }
        require(value.length <= MAX_LENGTH) { "Reply must be at most $MAX_LENGTH characters" }
        require(value.none { it.isISOControl() && it !in "\n\r\t" }) { "Reply must be plain text without control characters" }
    }

    companion object {
        const val MAX_LENGTH = 5000

        fun of(submitted: String): StaffReplyBody = StaffReplyBody(submitted.trim())
    }
}

/** What the staff member saw: an empty thread, or the newest provider message. Required on every new reply. */
sealed interface ConversationExpectation {
    data object Empty : ConversationExpectation

    data class Latest(
        val messageId: UUID,
    ) : ConversationExpectation
}

enum class StaffReplyMode { OPEN, REPLY }

enum class StaffReplyOutcome { ACCEPTED, REJECTED }

/** The durable command record. No message body; [providerRequest] is the digest of the exact provider request. */
data class StaffReplyRecord(
    val key: StaffReplyKey,
    val inquiryId: InquiryId,
    val fingerprint: String,
    val sentBy: UserId,
    val mode: StaffReplyMode,
    val providerRequest: String,
    val signatureName: String,
    val requestedAt: Instant,
    val conversationId: UUID?,
    val outcome: StaffReplyOutcome? = null,
    val providerMessageId: UUID? = null,
    val providerState: String? = null,
    val communicationId: InquiryCommunicationId? = null,
)

/** The signature is built from trusted runtime profile data, never from the request. */
internal fun signedStaffReply(
    body: StaffReplyBody,
    signatureName: String,
): String = "${body.value}\n\n— Sent from $signatureName @ Fiona's Ice Cream"

/** Semantic intent of one key: inquiry, sender, exact body and expectation, length-prefixed then SHA-256 hashed. */
internal fun staffReplyFingerprint(
    inquiry: InquiryId,
    sender: UserId,
    body: StaffReplyBody,
    expectation: ConversationExpectation,
): String {
    val expected =
        when (expectation) {
            ConversationExpectation.Empty -> "EMPTY"
            is ConversationExpectation.Latest -> "LATEST:${expectation.messageId}"
        }
    val canonical =
        listOf("fionas.staff-reply.v1", inquiry.value.toString(), sender.value.toString(), body.value, expected)
            .joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
}
