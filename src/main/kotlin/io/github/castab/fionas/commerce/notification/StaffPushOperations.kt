package io.github.castab.fionas.commerce.notification

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.commerce.runtime.outbox.Outbox
import io.github.castab.commerce.runtime.outbox.OutboxEventDraft
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.events.StaffNotificationEventLog
import io.github.castab.fionas.commerce.inquiry.IdempotencyKeyReused
import io.github.castab.fionas.commerce.inquiry.Inquiry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The runtime outbox producer whose records are Web Push work items, not messages: its dispatcher
 * runs [WebPushDeliveryTransport], so claims, leases, fencing and retry backoff stay the
 * runtime's. Records carry only Fiona identities, never endpoints or keys.
 */
const val WEBPUSH_PRODUCER = "fionas-commerce.webpush"
internal const val WEBPUSH_FANOUT = "fionas.webpush.fanout"
internal const val WEBPUSH_DELIVERY = "fionas.webpush.delivery"

/** How long a provider may keep trying: a test is interesting briefly, a new inquiry for an hour. */
val TEST_DELIVERY_WINDOW: Duration = Duration.ofMinutes(10)
val INQUIRY_DELIVERY_WINDOW: Duration = Duration.ofHours(1)

/** The active endpoint already belongs to another staff member; the owner is never disclosed. */
class PushEndpointInUse : RuntimeException()

internal class WebPushWork(
    private val outbox: Outbox,
    private val newEventId: () -> UUID = UUID::randomUUID,
) {
    fun fanOut(
        transaction: Transaction,
        notification: StaffNotificationId,
        at: Instant,
    ) = append(transaction, "staff_push_fanout", notification.value, WEBPUSH_FANOUT, "notificationId", at)

    fun delivery(
        transaction: Transaction,
        delivery: StaffDeliveryId,
        at: Instant,
    ) = append(transaction, "staff_push_delivery", delivery.value, WEBPUSH_DELIVERY, "deliveryId", at)

    private fun append(
        transaction: Transaction,
        aggregateType: String,
        id: UUID,
        type: String,
        field: String,
        at: Instant,
    ) {
        outbox.append(
            transaction,
            OutboxEventDraft(newEventId(), WEBPUSH_PRODUCER, aggregateType, id.toString(), type, 1, "webpush", at),
        ) { WebPushPayloads.workItem(field, id) }
    }
}

/** A staff member's own devices: enroll (or refresh), list and revoke. */
class StaffPushDevices(
    private val transactor: Transactor,
    private val repository: JdbiStaffPushRepository,
    private val allowedHosts: PushHostAllowList,
    private val clock: Clock,
    private val newId: () -> StaffPushSubscriptionId = { StaffPushSubscriptionId(UUID.randomUUID()) },
) {
    data class Enrollment(
        val subscription: StaffPushSubscription,
        val created: Boolean,
    )

    /**
     * Registers the browser's push subscription for [owner]. The same active endpoint for the
     * same owner keeps its id (keys, expiry and label refresh); an active endpoint owned by
     * someone else is a conflict. The partial unique index serializes concurrent enrollments.
     */
    fun enroll(
        owner: UserId,
        endpoint: String,
        p256dh: String,
        auth: String,
        expirationTime: Instant?,
        label: String?,
    ): Enrollment {
        val validated =
            validating {
                Triple(PushEndpoint.of(endpoint, allowedHosts), PushKeys.of(p256dh, auth), DeviceLabel.ofOptional(label))
            }
        val (pushEndpoint, keys, deviceLabel) = validated
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val expiry = expirationTime?.truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            val candidate =
                StaffPushSubscription(
                    newId(),
                    owner,
                    pushEndpoint,
                    pushEndpoint.sha256,
                    pushEndpoint.service,
                    keys,
                    expiry,
                    deviceLabel,
                    1,
                    now,
                    now,
                    null,
                    null,
                )
            if (repository.claimEndpoint(transaction, candidate)) return@inTransaction Enrollment(candidate, true)
            // A separate READ COMMITTED statement sees the owner that the insert waited for.
            val existing =
                repository.activeByEndpointForUpdate(transaction, pushEndpoint.sha256)
                    ?: throw CommerceFailure.Conflict("The push endpoint changed concurrently; retry", null)
            if (existing.owner != owner) {
                throw CommerceFailure.Conflict("This browser is enrolled for another staff account", PushEndpointInUse())
            }
            val version = if (existing.keys == keys) existing.registrationVersion else existing.registrationVersion + 1
            repository.refresh(transaction, existing.id, keys, expiry, deviceLabel, version, now)
            Enrollment(
                existing.copy(keys = keys, expirationTime = expiry, label = deviceLabel, registrationVersion = version, refreshedAt = now),
                false,
            )
        }
    }

    fun list(owner: UserId): List<StaffPushSubscription> = transactor.inTransaction { repository.listForUser(it, owner) }

    /** Idempotent for the owner; unknown and foreign ids are the same not-found. */
    fun revoke(
        owner: UserId,
        id: StaffPushSubscriptionId,
    ) {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        transactor.inTransaction { transaction ->
            if (repository.revoke(transaction, id, owner, now)) return@inTransaction
            val existing = repository.find(transaction, id)
            if (existing == null || existing.owner != owner) throw CommerceFailure.NotFound("Push subscription was not found")
        }
    }
}

/** What the caller can observe about one of their test notifications. */
data class TestNotificationView(
    val notification: StaffNotification,
    val delivery: StaffDelivery,
)

/** Server-authored test sends to one of the caller's own devices, and their tracked outcome. */
class StaffTestNotifications(
    private val transactor: Transactor,
    private val repository: JdbiStaffPushRepository,
    outbox: Outbox,
    private val events: StaffNotificationEventLog,
    private val clock: Clock,
    private val newEventId: () -> UUID = UUID::randomUUID,
    private val newNotificationId: () -> StaffNotificationId = { StaffNotificationId(UUID.randomUUID()) },
    private val newDeliveryId: () -> StaffDeliveryId = { StaffDeliveryId(UUID.randomUUID()) },
) {
    private val work = WebPushWork(outbox, newEventId)

    /**
     * Queues one delivery to [target]. The same caller and key return the original notification
     * (even if the device was revoked since); the key with another target conflicts. Only an
     * active device of the caller can receive a new test.
     */
    fun send(
        owner: UserId,
        key: TestNotificationKey,
        target: StaffPushSubscriptionId,
    ): TestNotificationView {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        return transactor.inTransaction { transaction ->
            replay(transaction, owner, key, target)?.let { return@inTransaction it }
            val subscription = repository.findForShare(transaction, target)
            if (subscription == null || subscription.owner != owner || !subscription.active) {
                throw CommerceFailure.NotFound("Push subscription was not found")
            }
            val notification =
                StaffNotification(
                    newNotificationId(),
                    StaffNotificationKind.TEST,
                    owner,
                    key,
                    target,
                    null,
                    now,
                    now + TEST_DELIVERY_WINDOW,
                    now,
                )
            if (!repository.insertNotification(transaction, notification)) {
                // A concurrent request with the same key committed first.
                return@inTransaction checkNotNull(replay(transaction, owner, key, target))
            }
            val delivery = newDelivery(newDeliveryId(), notification, target, now)
            check(repository.insertDelivery(transaction, delivery))
            work.delivery(transaction, delivery.id, now)
            events.queued(transaction, notification.id.value, StaffNotificationKind.TEST.wire, 1, now)
            TestNotificationView(notification, delivery)
        }
    }

    fun read(
        owner: UserId,
        id: StaffNotificationId,
    ): TestNotificationView =
        transactor.inTransaction { transaction ->
            val notification = repository.findNotification(transaction, id)
            if (notification == null || notification.kind != StaffNotificationKind.TEST || notification.requestedBy != owner) {
                throw CommerceFailure.NotFound("Notification was not found")
            }
            TestNotificationView(notification, repository.deliveriesOf(transaction, id).single())
        }

    private fun replay(
        transaction: Transaction,
        owner: UserId,
        key: TestNotificationKey,
        target: StaffPushSubscriptionId,
    ): TestNotificationView? {
        val existing = repository.findTest(transaction, owner, key) ?: return null
        if (existing.target != target) {
            throw CommerceFailure.Conflict("This key already represents a test to a different device", IdempotencyKeyReused())
        }
        return TestNotificationView(existing, repository.deliveriesOf(transaction, existing.id).single())
    }
}

/**
 * The automatic new-inquiry trigger. Inside the inquiry's own transaction it records one job per
 * inquiry (the inquiry id is the deduplication key) and its fan-out work item; staff
 * eligibility and devices are resolved later, in the background, outside that transaction.
 */
class StaffInquiryNotifications(
    private val repository: JdbiStaffPushRepository,
    outbox: Outbox,
    private val newNotificationId: () -> StaffNotificationId = { StaffNotificationId(UUID.randomUUID()) },
) {
    private val work = WebPushWork(outbox)

    fun enqueue(
        transaction: Transaction,
        inquiry: Inquiry,
    ) {
        val at = inquiry.createdAt.truncatedTo(ChronoUnit.MICROS)
        val notification =
            StaffNotification(
                newNotificationId(),
                StaffNotificationKind.NEW_INQUIRY,
                null,
                null,
                null,
                inquiry.id,
                at,
                at + INQUIRY_DELIVERY_WINDOW,
                null,
            )
        if (repository.insertNotification(transaction, notification)) work.fanOut(transaction, notification.id, at)
    }
}

internal fun newDelivery(
    id: StaffDeliveryId,
    notification: StaffNotification,
    subscription: StaffPushSubscriptionId,
    now: Instant,
) = StaffDelivery(
    id,
    notification.id,
    subscription,
    DeliveryState.QUEUED,
    null,
    0,
    false,
    null,
    null,
    null,
    null,
    notification.giveUpAt,
    now,
    null,
    null,
)
