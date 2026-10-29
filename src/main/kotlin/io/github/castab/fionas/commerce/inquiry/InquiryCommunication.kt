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

    val requiresActingPrincipal: Boolean
        get() =
            when (this) {
                CUSTOMER_EMAIL_RECEIVED -> false
                STAFF_EMAIL_SENT, STAFF_ACKNOWLEDGED -> true
            }
}

/**
 * Append-only activity facts. No email content, provider identity or delivery inference.
 *
 * [clearsThrough] is a clearing fact's explicit observed boundary: the greatest `recordedOrder` the staff member
 * had observed when acting. Inbound recorded after it stays outstanding even though the fact itself is recorded
 * later (a reply accepted by the provider after new inbound arrived). Null clears through the fact's own order.
 */
data class InquiryCommunication(
    val id: InquiryCommunicationId,
    val inquiryId: InquiryId,
    val kind: InquiryCommunicationKind,
    val occurredAt: Instant,
    val principalId: PrincipalId?,
    val clearsThrough: Long? = null,
) {
    init {
        require(occurredAt == occurredAt.truncatedTo(ChronoUnit.MICROS)) { "Communication timestamp must have microsecond precision" }
        require(kind.requiresActingPrincipal == (principalId != null)) {
            "Only staff activity has acting principal provenance"
        }
        require(clearsThrough == null || (kind.clearsCustomerAttention && clearsThrough >= 0)) {
            "Only clearing activity has a nonnegative observed boundary"
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
        require(activity.clearsThrough == null || activity.clearsThrough < recordedOrder)
    }

    /** The observation order this fact clears through, or null when it never clears customer attention. */
    val clearingOrder: Long?
        get() = if (activity.kind.clearsCustomerAttention) activity.clearsThrough ?: recordedOrder else null
}

/** Clearing follows durable append order; attention age and meaningful email activity follow actual occurrence time. */
data class InquiryCommunicationAttention(
    val unacknowledgedSince: Instant?,
    val latestEmailAt: Instant?,
) {
    companion object {
        fun project(activity: Collection<RecordedInquiryCommunication>): InquiryCommunicationAttention {
            val clearedOrder = activity.mapNotNull { it.clearingOrder }.maxOrNull()
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
