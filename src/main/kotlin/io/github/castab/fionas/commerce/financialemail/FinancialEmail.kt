package io.github.castab.fionas.commerce.financialemail

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.conversation.ConversationExpectation
import io.github.castab.fionas.commerce.conversation.StaffReplyKey
import io.github.castab.fionas.commerce.financial.InquiryProposalId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.payments.CustomerPaymentSummary
import io.github.castab.fionas.commerce.staff.StaffRequest
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@JvmInline
value class FinancialEmailId(
    val value: UUID,
)

enum class FinancialEmailKind { QUOTE, INVOICE }

/** Staff prose only. No transport headers, HTML, recipient or financial inputs. */
@JvmInline
value class FinancialEmailMessage(
    val value: String,
) {
    init {
        require(value.isNotBlank() && value == value.trim()) { "Message must not be blank" }
        require(value.length <= MAX_LENGTH) { "Message must be at most $MAX_LENGTH characters" }
        require(
            value.none { it.isISOControl() && it !in "\r\n\t" } && value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value,
        ) {
            "Message must be plain Unicode text without control characters"
        }
    }

    companion object {
        const val MAX_LENGTH = 2000

        fun ofOptional(value: String?): FinancialEmailMessage? = value?.trim()?.takeIf { it.isNotEmpty() }?.let(::FinancialEmailMessage)
    }
}

data class FinancialEmailIntent(
    val inquiryId: InquiryId,
    val kind: FinancialEmailKind,
    val documentId: UUID,
    val expectedDocumentVersion: Version,
    val expectedBusinessRevision: Int,
    val proposalId: InquiryProposalId?,
    val expectedDepositRequirementRevision: Int?,
    val message: FinancialEmailMessage?,
) {
    init {
        require(expectedBusinessRevision > 0) { "Business revision must be positive" }
        require(
            expectedDepositRequirementRevision == null || expectedDepositRequirementRevision > 0,
        ) { "Deposit revision must be positive" }
        require((kind == FinancialEmailKind.QUOTE) == (proposalId != null)) { "Only Quote emails require proposalId" }
        require((kind == FinancialEmailKind.QUOTE) == (expectedDepositRequirementRevision != null)) {
            "Only Quote emails require expectedDepositRequirementRevision"
        }
    }
}

data class SendFinancialEmail(
    val intent: FinancialEmailIntent,
    val sentBy: UserId,
    val key: StaffReplyKey,
    val reviewToken: String,
    val expectation: ConversationExpectation,
) {
    init {
        require(Regex("[a-f0-9]{64}").matches(reviewToken)) { "Invalid financial email review token" }
    }
}

/** Secret-bearing rendered bodies must never stringify into diagnostics. */
class RenderedFinancialEmail(
    val subject: String,
    val html: String,
    val text: String,
) {
    override fun toString() = "RenderedFinancialEmail([redacted])"
}

class FinancialEmailPreview(
    val intent: FinancialEmailIntent,
    val request: StaffRequest,
    val summary: CustomerPaymentSummary,
    val email: RenderedFinancialEmail,
    val reviewToken: String,
) {
    val document: FinancialDocument get() = request.financial.latest.document

    override fun toString() = "FinancialEmailPreview([redacted])"
}

enum class FinancialEmailOutcome { ACCEPTED, REJECTED, UNKNOWN }

data class FinancialEmailResult(
    val sendId: FinancialEmailId,
    val outcome: FinancialEmailOutcome,
    val replayed: Boolean,
    val conversationId: UUID?,
    val messageId: UUID?,
    val sendState: String?,
)

/** Only command identity, exact references, provenance and digests; no copied amounts or content. */
data class FinancialEmailRecord(
    val id: FinancialEmailId,
    val inquiryId: InquiryId,
    val key: StaffReplyKey,
    val fingerprint: String,
    val sentBy: UserId,
    val kind: FinancialEmailKind,
    val documentId: UUID,
    val documentVersion: Int,
    val businessRevision: Int,
    val proposalId: InquiryProposalId?,
    val recoveryDigest: String,
    val providerRequest: String,
    val signature: String,
    val opening: Boolean,
    val requestedAt: Instant,
    val observedThrough: Long,
    val conversationId: UUID?,
    val outcome: FinancialEmailOutcome = FinancialEmailOutcome.UNKNOWN,
    val messageId: UUID? = null,
    val sendState: String? = null,
    val acceptedAt: Instant? = null,
    val dispatchCount: Int = 0,
)

/** Length prefixes preserve empty/null and field boundaries; hashes never contain recoverable message content. */
internal fun financialEmailDigest(values: List<String?>): String {
    val canonical = values.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun SendFinancialEmail.fingerprint() =
    financialEmailDigest(
        listOf(
            "fionas.financial-email.command.v1",
            sentBy.value.toString(),
            intent.inquiryId.value.toString(),
            intent.kind.name,
            intent.documentId.toString(),
            intent.expectedDocumentVersion.number.toString(),
            intent.expectedBusinessRevision.toString(),
            intent.proposalId?.value?.toString(),
            intent.expectedDepositRequirementRevision?.toString(),
            intent.message?.value,
            reviewToken,
            expectation.toString(),
        ),
    )

/** Caller-safe runtime-envelope refusal. No provider diagnostics or request content. */
class FinancialEmailRefused(
    val code: String,
    message: String,
) : RuntimeException(message)

internal fun emailRefused(
    code: String,
    message: String,
): Nothing = throw FinancialEmailRefused(code, message)
