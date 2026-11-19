package io.github.castab.fionas.commerce.notification

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.castab.commerce.runtime.outbox.OutboxDispatchSettings
import io.github.castab.commerce.runtime.outbox.OutboxDispatcher
import io.github.castab.commerce.runtime.outbox.OutboxTransport
import io.github.castab.commerce.runtime.outbox.PublicationResult
import io.github.castab.commerce.runtime.outbox.RecordedOutboxEvent
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.events.StaffNotificationEventLog
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** Who may receive pushes: test sends need an active staff user, inquiry pushes also need to read inquiries. */
class PushRecipients(
    private val activeUser: (UserId) -> Boolean,
    private val readsInquiries: (UserId) -> Boolean,
) {
    fun eligible(
        kind: StaffNotificationKind,
        user: UserId,
    ): Boolean =
        activeUser(user) &&
            when (kind) {
                StaffNotificationKind.TEST -> true
                StaffNotificationKind.NEW_INQUIRY -> readsInquiries(user)
            }

    companion object {
        /** Live runtime authorization: user status and current permission grants, resolved per check. */
        fun of(context: CommerceRuntimeContext) =
            PushRecipients(
                { context.authorization.getUser(it)?.status == PrincipalStatus.ACTIVE },
                { FionaPermissions.InquiriesRead in context.authorization.permissionResolver.permissionsFor(it) },
            )
    }
}

/**
 * The [WEBPUSH_PRODUCER] outbox transport. A fan-out record resolves a new-inquiry job's
 * eligible devices; a delivery record performs one fenced Web Push attempt. Network I/O never
 * runs inside a transaction: attempt intent commits first (A), then the provider call, then the
 * outcome and its notification event commit together (B), fenced by the attempt token so an
 * overlapping or recovered dispatcher cannot record a stale result.
 *
 * Returning CONFIRMED ends the work item; UNCONFIRMED asks the runtime to retry it later with its
 * own backoff. Retry-After is honored as a minimum: attempts before it return UNCONFIRMED without
 * calling the provider. Every delivery ends by its give-up time.
 */
class WebPushDeliveryTransport internal constructor(
    private val transactor: Transactor,
    private val repository: JdbiStaffPushRepository,
    private val events: StaffNotificationEventLog,
    private val sender: WebPushSender,
    private val recipients: PushRecipients,
    private val inquiryPath: String,
    private val clock: Clock,
    private val inFlightGrace: Duration,
    private val scheduler: WebPushWork,
    private val newDeliveryId: () -> StaffDeliveryId = { StaffDeliveryId(UUID.randomUUID()) },
) : OutboxTransport {
    override fun publish(event: RecordedOutboxEvent): PublicationResult =
        when (event.event.eventType) {
            WEBPUSH_FANOUT -> fanOut(StaffNotificationId(WebPushPayloads.workItemId(event.event.payload, "notificationId")))
            WEBPUSH_DELIVERY -> deliver(StaffDeliveryId(WebPushPayloads.workItemId(event.event.payload, "deliveryId")))
            else -> error("Unknown Web Push work item")
        }

    /** Creates one delivery per eligible active device, plus `notification.queued`, exactly once. */
    fun fanOut(id: StaffNotificationId): PublicationResult {
        val candidates =
            transactor.inTransaction { transaction ->
                val notification = checkNotNull(repository.findNotification(transaction, id))
                if (notification.fannedOutAt != null) null else notification to repository.active(transaction)
            } ?: return PublicationResult.CONFIRMED
        val (notification, active) = candidates
        // Directory reads stay outside the business transaction; the dispatch recheck repeats them.
        val owners =
            active
                .map { it.owner }
                .distinct()
                .filter { recipients.eligible(notification.kind, it) }
                .toSet()
        transactor.inTransaction { transaction ->
            val locked = checkNotNull(repository.findNotification(transaction, id, forUpdate = true))
            if (locked.fannedOutAt != null) return@inTransaction
            val now = now()
            var count = 0
            repository.active(transaction).filter { it.owner in owners }.forEach { subscription ->
                val delivery = newDelivery(newDeliveryId(), locked, subscription.id, now)
                if (repository.insertDelivery(transaction, delivery)) {
                    scheduler.delivery(transaction, delivery.id, now)
                    count += 1
                }
            }
            events.queued(transaction, locked.id.value, locked.kind.wire, count, now)
            repository.markFannedOut(transaction, locked.id, now)
            logger.info { "event=staff_push_fanned_out notification=${locked.id.value} deliveries=$count" }
        }
        return PublicationResult.CONFIRMED
    }

    private sealed interface Decision {
        data object Done : Decision

        data object Later : Decision

        class Send(
            val token: UUID,
            val request: PushRequest,
            val registrationVersion: Long,
            val subscription: StaffPushSubscriptionId,
        ) : Decision
    }

    /** One attempt for one device. Retries touch only this delivery's row. */
    fun deliver(id: StaffDeliveryId): PublicationResult {
        val context =
            transactor.inTransaction { transaction ->
                val delivery = checkNotNull(repository.findDelivery(transaction, id))
                if (delivery.state.terminal) {
                    null
                } else {
                    val notification = checkNotNull(repository.findNotification(transaction, delivery.notificationId))
                    val owner = checkNotNull(repository.find(transaction, delivery.subscriptionId)).owner
                    notification to owner
                }
            } ?: return PublicationResult.CONFIRMED
        val (notification, owner) = context
        val eligible = recipients.eligible(notification.kind, owner)
        val decision = transactor.inTransaction { transaction -> decide(transaction, id, notification, eligible) }
        val send =
            when (decision) {
                Decision.Done -> return PublicationResult.CONFIRMED
                Decision.Later -> return PublicationResult.UNCONFIRMED
                is Decision.Send -> decision
            }
        val result =
            try {
                sender.send(send.request)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                PushResult.Unknown
            } catch (_: RuntimeException) {
                logger.warn { "event=staff_push_send_failed delivery=${id.value} code=SENDER_EXCEPTION" }
                PushResult.Retryable(DeliveryReason.TRANSPORT_FAILURE, null, null)
            }
        return transactor.inTransaction { transaction -> record(transaction, id, notification, send, result) }
    }

    private fun decide(
        transaction: Transaction,
        id: StaffDeliveryId,
        notification: StaffNotification,
        eligible: Boolean,
    ): Decision {
        var delivery = checkNotNull(repository.findDelivery(transaction, id, forUpdate = true))
        if (delivery.state.terminal) return Decision.Done
        val now = now()
        delivery.attemptStartedAt?.let { started ->
            // Another dispatcher is still within its attempt; a stale token means it died mid-send.
            if (started + inFlightGrace > now) return Decision.Later
            delivery = delivery.copy(attemptToken = null, attemptStartedAt = null, everUncertain = true)
        }
        // Share-locked: a revocation waits for this decision, or this decision sees it.
        val subscription = checkNotNull(repository.findForShare(transaction, delivery.subscriptionId))
        if (!subscription.active) {
            val reason =
                if (subscription.retirement == SubscriptionRetirement.PROVIDER_EXPIRED) {
                    DeliveryReason.SUBSCRIPTION_EXPIRED
                } else {
                    DeliveryReason.SUBSCRIPTION_REVOKED
                }
            finish(transaction, notification, delivery, DeliveryState.SKIPPED, reason, now)
            return Decision.Done
        }
        if (!eligible) {
            finish(transaction, notification, delivery, DeliveryState.SKIPPED, DeliveryReason.RECIPIENT_NOT_ELIGIBLE, now)
            return Decision.Done
        }
        if (!now.isBefore(delivery.giveUpAt)) {
            finish(transaction, notification, delivery, DeliveryState.PUSH_FAILED, exhausted(delivery), now)
            return Decision.Done
        }
        delivery.notBefore?.let { if (now.isBefore(it)) return Decision.Later }
        val token = UUID.randomUUID()
        val attempt =
            delivery.copy(
                attempts = delivery.attempts + 1,
                attemptToken = token,
                attemptStartedAt = now,
                lastAttemptAt = now,
                notBefore = null,
            )
        repository.saveDelivery(transaction, attempt)
        val request =
            PushRequest(
                checkNotNull(subscription.endpoint),
                checkNotNull(subscription.keys),
                WebPushPayloads.browser(notification, inquiryPath),
                Duration.between(now, delivery.giveUpAt),
                notification.kind == StaffNotificationKind.NEW_INQUIRY,
                notification.id.value
                    .toString()
                    .replace("-", ""),
            )
        return Decision.Send(token, request, subscription.registrationVersion, subscription.id)
    }

    private fun record(
        transaction: Transaction,
        id: StaffDeliveryId,
        notification: StaffNotification,
        send: Decision.Send,
        result: PushResult,
    ): PublicationResult {
        val delivery = checkNotNull(repository.findDelivery(transaction, id, forUpdate = true))
        if (delivery.attemptToken != send.token) {
            // A newer attempt owns the row now (this one outlived its grace); it records the outcome.
            return if (delivery.state.terminal) PublicationResult.CONFIRMED else PublicationResult.UNCONFIRMED
        }
        val now = now()
        val current = delivery.copy(attemptToken = null, attemptStartedAt = null)
        logger.info { "event=staff_push_attempt delivery=${id.value} attempt=${delivery.attempts} result=${result.javaClass.simpleName}" }
        when (result) {
            is PushResult.Accepted -> {
                finish(transaction, notification, current.copy(providerCode = result.code), DeliveryState.PUSH_ACCEPTED, null, now)
                return PublicationResult.CONFIRMED
            }
            is PushResult.Gone -> {
                // Only the registration this attempt used; a newer registration of the device survives.
                repository.expireIfVersion(transaction, send.subscription, send.registrationVersion, now)
                finish(
                    transaction,
                    notification,
                    current.copy(providerCode = result.code),
                    DeliveryState.PUSH_FAILED,
                    DeliveryReason.SUBSCRIPTION_EXPIRED,
                    now,
                )
                return PublicationResult.CONFIRMED
            }
            is PushResult.Rejected -> {
                finish(
                    transaction,
                    notification,
                    current.copy(providerCode = result.code.takeIf { it in 100..599 }),
                    DeliveryState.PUSH_FAILED,
                    DeliveryReason.PROVIDER_REJECTED,
                    now,
                )
                return PublicationResult.CONFIRMED
            }
            PushResult.EndpointNotAllowed -> {
                finish(transaction, notification, current, DeliveryState.PUSH_FAILED, DeliveryReason.ENDPOINT_NOT_ALLOWED, now)
                return PublicationResult.CONFIRMED
            }
            is PushResult.Retryable -> {
                val waited = current.copy(providerCode = result.code, reason = result.reason)
                val notBefore = result.retryAfter?.let { now + it }
                if (!(notBefore ?: now).isBefore(delivery.giveUpAt)) {
                    finish(transaction, notification, waited, DeliveryState.PUSH_FAILED, exhausted(waited), now)
                    return PublicationResult.CONFIRMED
                }
                repository.saveDelivery(transaction, waited.copy(state = DeliveryState.RETRYING, notBefore = notBefore))
                return PublicationResult.UNCONFIRMED
            }
            PushResult.Unknown -> {
                val uncertain = current.copy(everUncertain = true, reason = DeliveryReason.PROVIDER_OUTCOME_UNKNOWN, providerCode = null)
                if (!now.isBefore(delivery.giveUpAt)) {
                    finish(transaction, notification, uncertain, DeliveryState.PUSH_FAILED, DeliveryReason.PROVIDER_OUTCOME_UNKNOWN, now)
                    return PublicationResult.CONFIRMED
                }
                repository.saveDelivery(transaction, uncertain.copy(state = DeliveryState.RETRYING))
                return PublicationResult.UNCONFIRMED
            }
        }
    }

    /** The reason a delivery that ran out of time failed with. */
    private fun exhausted(delivery: StaffDelivery): DeliveryReason =
        when {
            delivery.everUncertain -> DeliveryReason.PROVIDER_OUTCOME_UNKNOWN
            else -> delivery.reason ?: DeliveryReason.DELIVERY_EXPIRED
        }

    /** A terminal state; provider outcomes (not skips) also append their notification event, atomically. */
    private fun finish(
        transaction: Transaction,
        notification: StaffNotification,
        delivery: StaffDelivery,
        state: DeliveryState,
        reason: DeliveryReason?,
        now: Instant,
    ) {
        val final =
            delivery.copy(state = state, reason = reason, attemptToken = null, attemptStartedAt = null, notBefore = null, completedAt = now)
        repository.saveDelivery(transaction, final)
        if (state != DeliveryState.SKIPPED) {
            events.outcome(
                transaction,
                notification.id.value,
                delivery.id.value,
                notification.kind.wire,
                state == DeliveryState.PUSH_ACCEPTED,
                final.attempts,
                final.providerCode,
                reason?.code,
                now,
            )
        }
        logger.info { "event=staff_push_delivery_completed delivery=${delivery.id.value} state=${state.wire} reason=${reason?.code}" }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}

/**
 * Runs the [WEBPUSH_PRODUCER] work items through the runtime's own dispatcher (claims, leases,
 * fencing, backoff). The publish timeout exceeds the push send timeout so an attempt normally
 * records its own outcome; when it does not, the attempt token keeps the next claim honest.
 */
class StaffPushDispatcher(
    context: CommerceRuntimeContext,
    transport: WebPushDeliveryTransport,
    instanceId: String = UUID.randomUUID().toString(),
) : ManagedBackgroundService {
    private val dispatcher =
        OutboxDispatcher(
            context.outbox.publisher(WEBPUSH_PRODUCER, instanceId),
            transport,
            OutboxDispatchSettings(
                batchSize = 1,
                pollInterval = Duration.ofSeconds(1),
                lease = Duration.ofSeconds(60),
                publishTimeout = Duration.ofSeconds(15),
                retryInitial = Duration.ofSeconds(5),
                retryMaximum = Duration.ofMinutes(5),
            ),
        )

    override fun start() = dispatcher.start()

    override fun close() = dispatcher.close()
}

/** The delivery transport as the composition root builds it. */
fun webPushDeliveryTransport(
    context: CommerceRuntimeContext,
    configuration: WebPushConfiguration,
    events: StaffNotificationEventLog,
    sender: WebPushSender,
    clock: Clock,
    recipients: PushRecipients = PushRecipients.of(context),
): WebPushDeliveryTransport =
    WebPushDeliveryTransport(
        context.transactor,
        JdbiStaffPushRepository(),
        events,
        sender,
        recipients,
        configuration.inquiryPath,
        clock,
        configuration.sendTimeout + Duration.ofSeconds(10),
        WebPushWork(context.outbox),
    )
