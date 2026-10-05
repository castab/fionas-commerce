package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.staff.PrincipalId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@JvmInline
value class InquiryCommunicationId(
    val value: UUID,
)

enum class InquiryCommunicationKind { CUSTOMER_EMAIL_RECEIVED, STAFF_EMAIL_SENT, STAFF_ACKNOWLEDGED }

/** Append-only activity facts. No email content, provider identity or delivery inference. */
data class InquiryCommunication(
    val id: InquiryCommunicationId,
    val inquiryId: InquiryId,
    val kind: InquiryCommunicationKind,
    val occurredAt: Instant,
    val principalId: PrincipalId?,
) {
    init {
        require(occurredAt == occurredAt.truncatedTo(ChronoUnit.MICROS)) { "Communication timestamp must have microsecond precision" }
        require((kind == InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED) == (principalId == null)) {
            "Only staff activity has acting principal provenance"
        }
    }
}

/** Pure timestamp interpretation; equal-time inbound is cleared, because only strictly later inbound counts. */
data class InquiryCommunicationAttention(
    val unacknowledgedSince: Instant?,
    val latestEmailAt: Instant?,
) {
    companion object {
        fun project(activity: Collection<InquiryCommunication>): InquiryCommunicationAttention {
            val clearedAt = activity.filter { it.kind != InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED }.maxOfOrNull { it.occurredAt }
            val outstanding =
                activity.filter {
                    it.kind == InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED && (clearedAt == null || it.occurredAt > clearedAt)
                }
            return InquiryCommunicationAttention(
                outstanding.minOfOrNull { it.occurredAt },
                activity.filter { it.kind != InquiryCommunicationKind.STAFF_ACKNOWLEDGED }.maxOfOrNull { it.occurredAt },
            )
        }
    }
}
