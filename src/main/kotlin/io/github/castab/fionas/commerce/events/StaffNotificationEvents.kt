package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.outbox.Outbox
import io.github.castab.commerce.runtime.outbox.OutboxEventDraft
import io.github.castab.commerce.runtime.persistence.Transaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.UUID

/**
 * The outbox producer of staff notification events. It is distinct from [FIONA_EVENT_PRODUCER]
 * so its own dispatcher (and destination) publishes it, and a notification broker outage never
 * delays inquiry events or re-sends a push.
 */
const val NOTIFICATION_EVENT_PRODUCER = "fionas-commerce.notifications"
const val NOTIFICATION_AGGREGATE_TYPE = "staff_notification"
const val NOTIFICATION_QUEUED_TYPE = "fionas.notification.queued"
const val NOTIFICATION_PUSH_ACCEPTED_TYPE = "fionas.notification.push_accepted"
const val NOTIFICATION_PUSH_FAILED_TYPE = "fionas.notification.push_failed"
const val DEFAULT_NOTIFICATION_SUBJECT_PREFIX = "fionas.events"

/** Subjects under a deployment-chosen prefix: `<prefix>.notification.<event>.v1`. */
class NotificationSubjects(
    val prefix: String = DEFAULT_NOTIFICATION_SUBJECT_PREFIX,
) {
    init {
        require(PREFIX.matches(prefix)) {
            "FIONAS_NOTIFICATION_EVENTS_SUBJECT_PREFIX must be dot-separated NATS tokens without wildcards"
        }
    }

    val queued = "$prefix.notification.queued.v1"
    val pushAccepted = "$prefix.notification.push_accepted.v1"
    val pushFailed = "$prefix.notification.push_failed.v1"

    companion object {
        private val PREFIX = Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){0,8}$")
    }
}

@Serializable
data class StaffNotificationAggregate(
    val type: String,
    val id: String,
    val sequence: Long,
)

/** Published once per notification when its deliveries are durably queued (zero for a job with no eligible device). */
@Serializable
data class StaffNotificationQueuedV1(
    val schemaVersion: Int,
    val type: String,
    val id: String,
    val occurredAt: String,
    val source: String,
    val aggregate: StaffNotificationAggregate,
    val data: StaffNotificationQueuedData,
)

@Serializable
data class StaffNotificationQueuedData(
    val notificationId: String,
    val kind: String,
    val deliveryCount: Int,
)

/** Published once per device delivery that reaches a terminal provider outcome (accepted or failed). */
@Serializable
data class StaffNotificationOutcomeV1(
    val schemaVersion: Int,
    val type: String,
    val id: String,
    val occurredAt: String,
    val source: String,
    val aggregate: StaffNotificationAggregate,
    val data: StaffNotificationOutcomeData,
)

@Serializable
data class StaffNotificationOutcomeData(
    val notificationId: String,
    val deliveryId: String,
    val kind: String,
    val attempts: Int,
    /** The push service's HTTP status of the deciding attempt; null when none was received. */
    val providerCode: Int?,
    /** Null for push_accepted; a stable delivery reason code for push_failed. */
    val reason: String?,
)

/**
 * Appends notification events to the runtime outbox in the caller's transaction, so a delivery
 * state change and its event commit or roll back together.
 */
class StaffNotificationEventLog(
    private val outbox: Outbox,
    private val subjects: NotificationSubjects,
    private val newEventId: () -> UUID = UUID::randomUUID,
) {
    fun queued(
        transaction: Transaction,
        notificationId: UUID,
        kind: String,
        deliveryCount: Int,
        at: Instant,
    ) {
        val draft = draft(notificationId, NOTIFICATION_QUEUED_TYPE, subjects.queued, at)
        outbox.append(transaction, draft) { sequence ->
            Json
                .encodeToJsonElement(
                    StaffNotificationQueuedV1.serializer(),
                    StaffNotificationQueuedV1(
                        1,
                        draft.eventType,
                        draft.id.toString(),
                        at.toString(),
                        FIONA_EVENT_PRODUCER,
                        StaffNotificationAggregate(NOTIFICATION_AGGREGATE_TYPE, notificationId.toString(), sequence),
                        StaffNotificationQueuedData(notificationId.toString(), kind, deliveryCount),
                    ),
                ).jsonObject
        }
    }

    fun outcome(
        transaction: Transaction,
        notificationId: UUID,
        deliveryId: UUID,
        kind: String,
        accepted: Boolean,
        attempts: Int,
        providerCode: Int?,
        reason: String?,
        at: Instant,
    ) {
        require(accepted == (reason == null)) { "Only a failed delivery carries a reason" }
        val draft =
            if (accepted) {
                draft(notificationId, NOTIFICATION_PUSH_ACCEPTED_TYPE, subjects.pushAccepted, at)
            } else {
                draft(notificationId, NOTIFICATION_PUSH_FAILED_TYPE, subjects.pushFailed, at)
            }
        outbox.append(transaction, draft) { sequence ->
            Json
                .encodeToJsonElement(
                    StaffNotificationOutcomeV1.serializer(),
                    StaffNotificationOutcomeV1(
                        1,
                        draft.eventType,
                        draft.id.toString(),
                        at.toString(),
                        FIONA_EVENT_PRODUCER,
                        StaffNotificationAggregate(NOTIFICATION_AGGREGATE_TYPE, notificationId.toString(), sequence),
                        StaffNotificationOutcomeData(
                            notificationId.toString(),
                            deliveryId.toString(),
                            kind,
                            attempts,
                            providerCode,
                            reason,
                        ),
                    ),
                ).jsonObject
        }
    }

    private fun draft(
        notificationId: UUID,
        type: String,
        subject: String,
        at: Instant,
    ) = OutboxEventDraft(
        id = newEventId(),
        producerId = NOTIFICATION_EVENT_PRODUCER,
        aggregateType = NOTIFICATION_AGGREGATE_TYPE,
        aggregateId = notificationId.toString(),
        eventType = type,
        schemaVersion = 1,
        destination = subject,
        occurredAt = at,
    )
}
