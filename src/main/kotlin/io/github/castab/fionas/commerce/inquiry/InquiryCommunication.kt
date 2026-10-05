package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.staff.PrincipalId
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@JvmInline
value class InquiryCommunicationId(
    val value: UUID,
)

enum class InquiryCommunicationKind {
    CUSTOMER_EMAIL_RECEIVED,
    STAFF_EMAIL_SENT,
    STAFF_ACKNOWLEDGED,
    ;

    val clearsCustomerAttention: Boolean
        get() =
            when (this) {
                CUSTOMER_EMAIL_RECEIVED -> false
                STAFF_EMAIL_SENT, STAFF_ACKNOWLEDGED -> true
            }

    val contributesToQuoteActivity: Boolean
        get() =
            when (this) {
                CUSTOMER_EMAIL_RECEIVED, STAFF_EMAIL_SENT -> true
                STAFF_ACKNOWLEDGED -> false
            }
}

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

/** Database-assigned observation order, allocated only after acquiring the per-inquiry append lock. */
data class RecordedInquiryCommunication(
    val activity: InquiryCommunication,
    val recordedOrder: Long,
) {
    init {
        require(recordedOrder > 0)
    }
}

/** Clearing follows durable append order; attention age and meaningful email activity follow actual occurrence time. */
data class InquiryCommunicationAttention(
    val unacknowledgedSince: Instant?,
    val latestEmailAt: Instant?,
) {
    companion object {
        fun project(activity: Collection<RecordedInquiryCommunication>): InquiryCommunicationAttention {
            val clearedOrder =
                activity
                    .filter {
                        it.activity.kind.clearsCustomerAttention
                    }.maxOfOrNull { it.recordedOrder }
            val outstanding =
                activity.filter {
                    it.activity.kind == InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED &&
                        (clearedOrder == null || it.recordedOrder > clearedOrder)
                }
            return InquiryCommunicationAttention(
                outstanding.minOfOrNull { it.activity.occurredAt },
                activity.filter { it.activity.kind.contributesToQuoteActivity }.maxOfOrNull { it.activity.occurredAt },
            )
        }
    }
}
