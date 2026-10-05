package io.github.castab.fionas.commerce.staff

import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationAttention
import io.github.castab.fionas.commerce.inquiry.InquiryOperationalState
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Queue-specific reasons retain their own onset; an aggregated card uses the earliest applicable onset. */
data class StaffAttention(
    val onsets: Map<StaffAttentionReason, Instant>,
) {
    init {
        require(onsets.isNotEmpty())
    }

    val attentionSince: Instant get() = onsets.values.min()
    val reasons: Set<StaffAttentionReason> get() = onsets.keys
}

/** Fiona's synchronous, pure interpretation of authoritative source facts. */
data class DashboardAttentionPolicy(
    val quoteStaleAfter: Duration = Duration.ofDays(3),
) {
    init {
        require(!quoteStaleAfter.isNegative && !quoteStaleAfter.isZero)
    }

    fun needsReply(communication: InquiryCommunicationAttention): StaffAttention? =
        communication.unacknowledgedSince?.let { StaffAttention(mapOf(StaffAttentionReason.CUSTOMER_COMMUNICATION_UNACKNOWLEDGED to it)) }

    fun needsQuote(
        state: InquiryOperationalState,
        createdAt: Instant,
    ): StaffAttention? = if (state.needsQuote) StaffAttention(mapOf(StaffAttentionReason.NEEDS_QUOTE to createdAt)) else null

    fun needsResolution(
        state: InquiryOperationalState,
        eventDate: EventDate,
        communication: InquiryCommunicationAttention,
        asOf: Instant,
        zone: ZoneId,
    ): StaffAttention? {
        val reasons = linkedMapOf<StaffAttentionReason, Instant>()
        if (state.lifecycle.stage == InquiryStage.QUOTED) {
            val activity = maxOf(state.financial.latestVersion.createdAt, communication.latestEmailAt ?: Instant.MIN)
            val staleAt = activity.plus(quoteStaleAfter)
            if (asOf >= staleAt) reasons[StaffAttentionReason.QUOTE_STALE] = staleAt
        }
        if (state.lifecycle.stage == InquiryStage.BOOKED && eventDate.value < asOf.atZone(zone).toLocalDate()) {
            reasons[StaffAttentionReason.EVENT_DATE_PASSED_UNSERVED] =
                eventDate.value
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
        }
        if (state.lifecycle.stage == InquiryStage.SERVED) {
            val servedAt = checkNotNull(state.lifecycle.fulfillment).served.occurredAt
            if (state.financial.reconciliation.balance.amount
                    .signum() > 0
            ) {
                reasons[StaffAttentionReason.SERVED_WITH_BALANCE_DUE] = servedAt
            }
            if (state.needsClosing) reasons[StaffAttentionReason.READY_TO_CLOSE] = servedAt
        }
        return reasons.takeIf { it.isNotEmpty() }?.let(::StaffAttention)
    }
}
