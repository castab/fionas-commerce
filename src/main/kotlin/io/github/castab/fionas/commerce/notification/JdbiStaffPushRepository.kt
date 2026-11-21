package io.github.castab.fionas.commerce.notification

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Staff push registrations, notification jobs and their per-device deliveries. Every call takes
 * the caller's transaction; uniqueness races are resolved with `ON CONFLICT DO NOTHING` followed
 * by a separate read, never by catching an exception inside an aborted transaction.
 */
class JdbiStaffPushRepository {
    // ---- subscriptions -------------------------------------------------------------------------

    /** Inserts [subscription] unless its endpoint already has an active owner; false when it does. */
    fun claimEndpoint(
        transaction: Transaction,
        subscription: StaffPushSubscription,
    ): Boolean {
        val endpoint = checkNotNull(subscription.endpoint)
        val keys = checkNotNull(subscription.keys)
        return transaction.handle
            .createUpdate(
                "INSERT INTO fionas.staff_push_subscriptions (id, user_id, endpoint, endpoint_sha256, push_service, p256dh, " +
                    "auth, expiration_time, label, registration_version, enrolled_at, refreshed_at) VALUES (:id, :user, " +
                    ":endpoint, :sha, :service, :p256dh, :auth, CAST(:expiration AS timestamptz), CAST(:label AS text), " +
                    ":version, :at, :at) " +
                    "ON CONFLICT (endpoint_sha256) WHERE retired_at IS NULL DO NOTHING",
            ).bind("id", subscription.id.value)
            .bind("user", subscription.owner.value)
            .bind("endpoint", endpoint.value)
            .bind("sha", subscription.endpointSha256)
            .bind("service", subscription.service.name)
            .bind("p256dh", keys.p256dh)
            .bind("auth", keys.auth)
            .bindByType("expiration", subscription.expirationTime, Instant::class.java)
            .bindByType("label", subscription.label?.value, String::class.java)
            .bind("version", subscription.registrationVersion)
            .bind("at", subscription.enrolledAt)
            .execute() == 1
    }

    /** The active registration of an endpoint, row-locked so a concurrent refresh or retirement waits. */
    fun activeByEndpointForUpdate(
        transaction: Transaction,
        endpointSha256: String,
    ): StaffPushSubscription? =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.staff_push_subscriptions WHERE endpoint_sha256 = :sha AND retired_at IS NULL FOR UPDATE",
            ).bind("sha", endpointSha256)
            .map { row, _ -> subscription(row) }
            .findOne()
            .orElse(null)

    fun refresh(
        transaction: Transaction,
        id: StaffPushSubscriptionId,
        keys: PushKeys,
        expirationTime: Instant?,
        label: DeviceLabel?,
        registrationVersion: Long,
        at: Instant,
    ) {
        val updated =
            transaction.handle
                .createUpdate(
                    "UPDATE fionas.staff_push_subscriptions SET p256dh = :p256dh, auth = :auth, " +
                        "expiration_time = CAST(:expiration AS timestamptz), " +
                        "label = CAST(:label AS text), registration_version = :version, refreshed_at = :at " +
                        "WHERE id = :id AND retired_at IS NULL",
                ).bind("id", id.value)
                .bind("p256dh", keys.p256dh)
                .bind("auth", keys.auth)
                .bindByType("expiration", expirationTime, Instant::class.java)
                .bindByType("label", label?.value, String::class.java)
                .bind("version", registrationVersion)
                .bind("at", at)
                .execute()
        check(updated == 1) { "Push registration refresh disagrees with its locked row" }
    }

    fun find(
        transaction: Transaction,
        id: StaffPushSubscriptionId,
    ): StaffPushSubscription? = one(transaction, "SELECT * FROM fionas.staff_push_subscriptions WHERE id = :id", id)

    /**
     * The registration share-locked for a dispatch decision or a test enqueue: a concurrent
     * revocation waits until the decision commits, and the decision then sees it, or vice versa.
     */
    fun findForShare(
        transaction: Transaction,
        id: StaffPushSubscriptionId,
    ): StaffPushSubscription? = one(transaction, "SELECT * FROM fionas.staff_push_subscriptions WHERE id = :id FOR SHARE", id)

    /** The owner's registrations that are active or were retired by their push service; revoked ones are gone. */
    fun listForUser(
        transaction: Transaction,
        owner: UserId,
    ): List<StaffPushSubscription> =
        transaction.handle
            .createQuery(
                "SELECT * FROM fionas.staff_push_subscriptions WHERE user_id = :user " +
                    "AND (retirement IS NULL OR retirement = 'PROVIDER_EXPIRED') ORDER BY enrolled_at, id",
            ).bind("user", owner.value)
            .map { row, _ -> subscription(row) }
            .list()

    /** Every active registration, for fan-out. */
    fun active(transaction: Transaction): List<StaffPushSubscription> =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_push_subscriptions WHERE retired_at IS NULL ORDER BY id")
            .map { row, _ -> subscription(row) }
            .list()

    /**
     * Revokes an owner's registration, clearing its endpoint and keys. An active one stops all later
     * dispatches; one the push service already expired is removed from the owner's list. False when
     * it was already revoked or is not the owner's.
     */
    fun revoke(
        transaction: Transaction,
        id: StaffPushSubscriptionId,
        owner: UserId,
        at: Instant,
    ): Boolean =
        transaction.handle
            .createUpdate(
                "UPDATE fionas.staff_push_subscriptions SET retired_at = coalesce(retired_at, :at), retirement = 'REVOKED', " +
                    "endpoint = NULL, p256dh = NULL, auth = NULL WHERE id = :id AND user_id = :user " +
                    "AND (retired_at IS NULL OR retirement = 'PROVIDER_EXPIRED')",
            ).bind("id", id.value)
            .bind("user", owner.value)
            .bind("at", at)
            .execute() == 1

    /**
     * Retires a registration the push service reported gone, but only the exact registration
     * version the failed attempt used: a newer registration of the same device survives.
     */
    fun expireIfVersion(
        transaction: Transaction,
        id: StaffPushSubscriptionId,
        registrationVersion: Long,
        at: Instant,
    ): Boolean =
        transaction.handle
            .createUpdate(
                "UPDATE fionas.staff_push_subscriptions SET retired_at = :at, retirement = 'PROVIDER_EXPIRED', endpoint = NULL, " +
                    "p256dh = NULL, auth = NULL WHERE id = :id AND registration_version = :version AND retired_at IS NULL",
            ).bind("id", id.value)
            .bind("version", registrationVersion)
            .bind("at", at)
            .execute() == 1

    // ---- notifications -------------------------------------------------------------------------

    /** False when the job already exists (the same caller and test key, or the same reminder slot). */
    fun insertNotification(
        transaction: Transaction,
        notification: StaffNotification,
    ): Boolean =
        transaction.handle
            .createUpdate(
                "INSERT INTO fionas.staff_notifications (id, kind, requested_by, idempotency_key, target_subscription_id, " +
                    "created_at, give_up_at, fanned_out_at, scheduled_for, inquiry_count) VALUES (:id, :kind, " +
                    "CAST(:requestedBy AS uuid), CAST(:key AS text), CAST(:target AS uuid), :created, :giveUp, " +
                    "CAST(:fannedOut AS timestamptz), CAST(:scheduledFor AS timestamptz), CAST(:count AS integer)) " +
                    "ON CONFLICT DO NOTHING",
            ).bind("id", notification.id.value)
            .bind("kind", notification.kind.name)
            .bindByType("requestedBy", notification.requestedBy?.value, UUID::class.java)
            .bindByType("key", notification.key?.value, String::class.java)
            .bindByType("target", notification.target?.value, UUID::class.java)
            .bind("created", notification.createdAt)
            .bind("giveUp", notification.giveUpAt)
            .bindByType("fannedOut", notification.fannedOutAt, Instant::class.java)
            .bindByType("scheduledFor", notification.scheduledFor, Instant::class.java)
            .bindByType("count", notification.inquiryCount, Int::class.javaObjectType)
            .execute() == 1

    fun findNotification(
        transaction: Transaction,
        id: StaffNotificationId,
        forUpdate: Boolean = false,
    ): StaffNotification? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_notifications WHERE id = :id" + if (forUpdate) " FOR UPDATE" else "")
            .bind("id", id.value)
            .map { row, _ -> notification(row) }
            .findOne()
            .orElse(null)

    /** The reminder raised for [slot], if any. */
    fun findReminder(
        transaction: Transaction,
        slot: Instant,
    ): StaffNotification? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_notifications WHERE scheduled_for = :slot")
            .bind("slot", slot)
            .map { row, _ -> notification(row) }
            .findOne()
            .orElse(null)

    fun findTest(
        transaction: Transaction,
        requestedBy: UserId,
        key: TestNotificationKey,
    ): StaffNotification? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_notifications WHERE requested_by = :user AND idempotency_key = :key")
            .bind("user", requestedBy.value)
            .bind("key", key.value)
            .map { row, _ -> notification(row) }
            .findOne()
            .orElse(null)

    fun markFannedOut(
        transaction: Transaction,
        id: StaffNotificationId,
        at: Instant,
    ) {
        val updated =
            transaction.handle
                .createUpdate("UPDATE fionas.staff_notifications SET fanned_out_at = :at WHERE id = :id AND fanned_out_at IS NULL")
                .bind("id", id.value)
                .bind("at", at)
                .execute()
        check(updated == 1) { "Notification fan-out disagrees with its locked row" }
    }

    // ---- deliveries ----------------------------------------------------------------------------

    /** False when the notification already targets that registration. */
    fun insertDelivery(
        transaction: Transaction,
        delivery: StaffDelivery,
    ): Boolean =
        transaction.handle
            .createUpdate(
                "INSERT INTO fionas.staff_notification_deliveries (id, notification_id, subscription_id, state, give_up_at, " +
                    "created_at) VALUES (:id, :notification, :subscription, 'QUEUED', :giveUp, :created) " +
                    "ON CONFLICT (notification_id, subscription_id) DO NOTHING",
            ).bind("id", delivery.id.value)
            .bind("notification", delivery.notificationId.value)
            .bind("subscription", delivery.subscriptionId.value)
            .bind("giveUp", delivery.giveUpAt)
            .bind("created", delivery.createdAt)
            .execute() == 1

    fun findDelivery(
        transaction: Transaction,
        id: StaffDeliveryId,
        forUpdate: Boolean = false,
    ): StaffDelivery? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_notification_deliveries WHERE id = :id" + if (forUpdate) " FOR UPDATE" else "")
            .bind("id", id.value)
            .map { row, _ -> delivery(row) }
            .findOne()
            .orElse(null)

    fun deliveriesOf(
        transaction: Transaction,
        notification: StaffNotificationId,
    ): List<StaffDelivery> =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_notification_deliveries WHERE notification_id = :id ORDER BY created_at, id")
            .bind("id", notification.value)
            .map { row, _ -> delivery(row) }
            .list()

    /** Writes a locked delivery's complete mutable state. */
    fun saveDelivery(
        transaction: Transaction,
        delivery: StaffDelivery,
    ) {
        val updated =
            transaction.handle
                .createUpdate(
                    "UPDATE fionas.staff_notification_deliveries SET state = :state, " +
                        "outcome_reason = CAST(:reason AS text), attempts = :attempts, ever_uncertain = :uncertain, " +
                        "provider_code = CAST(:code AS integer), attempt_token = CAST(:token AS uuid), " +
                        "attempt_started_at = CAST(:started AS timestamptz), not_before = CAST(:notBefore AS timestamptz), " +
                        "last_attempt_at = CAST(:lastAttempt AS timestamptz), " +
                        "completed_at = CAST(:completed AS timestamptz) WHERE id = :id",
                ).bind("id", delivery.id.value)
                .bind("state", delivery.state.name)
                .bindByType("reason", delivery.reason?.code, String::class.java)
                .bind("attempts", delivery.attempts)
                .bind("uncertain", delivery.everUncertain)
                .bindByType("code", delivery.providerCode, Int::class.javaObjectType)
                .bindByType("token", delivery.attemptToken, UUID::class.java)
                .bindByType("started", delivery.attemptStartedAt, Instant::class.java)
                .bindByType("notBefore", delivery.notBefore, Instant::class.java)
                .bindByType("lastAttempt", delivery.lastAttemptAt, Instant::class.java)
                .bindByType("completed", delivery.completedAt, Instant::class.java)
                .execute()
        check(updated == 1) { "Delivery update disagrees with its locked row" }
    }

    private fun one(
        transaction: Transaction,
        sql: String,
        id: StaffPushSubscriptionId,
    ): StaffPushSubscription? =
        transaction.handle
            .createQuery(sql)
            .bind("id", id.value)
            .map { row, _ -> subscription(row) }
            .findOne()
            .orElse(null)

    private fun subscription(row: ResultSet): StaffPushSubscription {
        val p256dh = row.getString("p256dh")
        return StaffPushSubscription(
            StaffPushSubscriptionId(row.getObject("id", UUID::class.java)),
            UserId(row.getObject("user_id", UUID::class.java)),
            row.getString("endpoint")?.let(PushEndpoint::restore),
            row.getString("endpoint_sha256"),
            PushService.valueOf(row.getString("push_service")),
            p256dh?.let { PushKeys.restore(it, row.getString("auth")) },
            row.instant("expiration_time"),
            row.getString("label")?.let(::DeviceLabel),
            row.getLong("registration_version"),
            checkNotNull(row.instant("enrolled_at")),
            checkNotNull(row.instant("refreshed_at")),
            row.instant("retired_at"),
            row.getString("retirement")?.let(SubscriptionRetirement::valueOf),
        )
    }

    private fun notification(row: ResultSet) =
        StaffNotification(
            StaffNotificationId(row.getObject("id", UUID::class.java)),
            StaffNotificationKind.valueOf(row.getString("kind")),
            row.getObject("requested_by", UUID::class.java)?.let(::UserId),
            row.getString("idempotency_key")?.let(::TestNotificationKey),
            row.getObject("target_subscription_id", UUID::class.java)?.let(::StaffPushSubscriptionId),
            checkNotNull(row.instant("created_at")),
            checkNotNull(row.instant("give_up_at")),
            row.instant("fanned_out_at"),
            row.instant("scheduled_for"),
            row.getObject("inquiry_count", Int::class.javaObjectType),
        )

    private fun delivery(row: ResultSet) =
        StaffDelivery(
            StaffDeliveryId(row.getObject("id", UUID::class.java)),
            StaffNotificationId(row.getObject("notification_id", UUID::class.java)),
            StaffPushSubscriptionId(row.getObject("subscription_id", UUID::class.java)),
            DeliveryState.valueOf(row.getString("state")),
            row.getString("outcome_reason")?.let(DeliveryReason::of),
            row.getInt("attempts"),
            row.getBoolean("ever_uncertain"),
            row.getObject("provider_code", Int::class.javaObjectType),
            row.getObject("attempt_token", UUID::class.java),
            row.instant("attempt_started_at"),
            row.instant("not_before"),
            checkNotNull(row.instant("give_up_at")),
            checkNotNull(row.instant("created_at")),
            row.instant("last_attempt_at"),
            row.instant("completed_at"),
        )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}
