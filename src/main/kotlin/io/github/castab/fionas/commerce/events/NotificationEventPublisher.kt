package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.castab.commerce.runtime.outbox.OutboxDispatchSettings
import io.github.castab.commerce.runtime.outbox.OutboxDispatcher
import io.github.castab.commerce.runtime.outbox.OutboxTransport
import java.time.Duration
import java.util.UUID

/**
 * Where staff notification events go. [subjects] always applies (events are appended durably
 * whenever push is enabled); [publishing] composes the dispatcher. A null [connection] reuses
 * the inquiry outbox's `FIONAS_NATS_*` destination and, when the settings match, its connection.
 */
class NotificationEventsConfiguration internal constructor(
    val subjects: NotificationSubjects = NotificationSubjects(),
    val publishing: Boolean = false,
    val stream: String = FIONA_EVENTS_STREAM,
    internal val connection: NatsConnectionConfiguration? = null,
) {
    init {
        require(STREAM.matches(stream)) { "FIONAS_NOTIFICATION_EVENTS_STREAM must be a JetStream stream name" }
    }

    override fun toString() = "NotificationEventsConfiguration([redacted])"

    companion object {
        private val STREAM = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): NotificationEventsConfiguration {
            val publishing =
                when (environment["FIONAS_NOTIFICATION_EVENTS_ENABLED"]) {
                    null, "false" -> false
                    "true" -> true
                    else -> error("FIONAS_NOTIFICATION_EVENTS_ENABLED must be true or false")
                }
            val dedicated =
                if (environment.keys.any { it.startsWith("FIONAS_NOTIFICATION_NATS_") }) {
                    NatsConnectionConfiguration.fromEnvironment(environment, "FIONAS_NOTIFICATION_NATS")
                } else {
                    null
                }
            return NotificationEventsConfiguration(
                NotificationSubjects(environment["FIONAS_NOTIFICATION_EVENTS_SUBJECT_PREFIX"] ?: DEFAULT_NOTIFICATION_SUBJECT_PREFIX),
                publishing,
                environment["FIONAS_NOTIFICATION_EVENTS_STREAM"] ?: FIONA_EVENTS_STREAM,
                dedicated,
            )
        }

        /** A dedicated destination (tests and operators composing explicitly). */
        fun dedicated(
            environment: Map<String, String>,
            prefix: String = "FIONAS_NOTIFICATION_NATS",
            subjects: NotificationSubjects = NotificationSubjects(),
            stream: String = FIONA_EVENTS_STREAM,
        ) = NotificationEventsConfiguration(subjects, true, stream, NatsConnectionConfiguration.fromEnvironment(environment, prefix))
    }
}

/**
 * Publishes the [NOTIFICATION_EVENT_PRODUCER] outbox records. Its own dispatcher and producer
 * keep it independent of inquiry events and of Web Push delivery: a NATS outage or a stream
 * mismatch leaves events pending (and logged) without ever re-sending a push.
 */
class NotificationEventPublisher internal constructor(
    context: CommerceRuntimeContext,
    private val transport: OutboxTransport,
    instanceId: String = UUID.randomUUID().toString(),
) : ManagedBackgroundService {
    private val dispatcher =
        OutboxDispatcher(
            context.outbox.publisher(NOTIFICATION_EVENT_PRODUCER, instanceId),
            transport,
            OutboxDispatchSettings(
                batchSize = 1,
                pollInterval = Duration.ofSeconds(1),
                lease = Duration.ofSeconds(60),
                publishTimeout = Duration.ofSeconds(5),
                retryInitial = Duration.ofSeconds(1),
                retryMaximum = Duration.ofMinutes(5),
            ),
        )

    override fun start() = dispatcher.start()

    override fun close() {
        try {
            dispatcher.close()
        } finally {
            (transport as? AutoCloseable)?.close()
        }
    }
}

/**
 * Builds the inquiry and notification transports, sharing one connection when both publish to
 * the same servers with the same credentials, and giving the notification events a dedicated
 * connection otherwise.
 */
internal class EventTransports(
    inquiry: NatsOutboxConfiguration?,
    notifications: NotificationEventsConfiguration,
) {
    private val notificationConnection: NatsConnectionConfiguration? =
        if (notifications.publishing) {
            requireNotNull(notifications.connection ?: inquiry?.connection) {
                "FIONAS_NOTIFICATION_EVENTS_ENABLED requires FIONAS_NOTIFICATION_NATS_URL or FIONAS_OUTBOX_PUBLISHER_ENABLED=true"
            }
        } else {
            null
        }
    private val shared =
        inquiry != null && notificationConnection != null && notificationConnection.sameDestination(inquiry.connection)

    private val inquiryShared: SharedNatsConnection? =
        inquiry?.let { SharedNatsConnection(it.connection, "fionas-commerce-outbox", if (shared) 2 else 1) }

    val inquiryTransport: NatsOutboxTransport? = inquiryShared?.let { NatsOutboxTransport(it) }

    val notificationTransport: NatsOutboxTransport? =
        notificationConnection?.let { connection ->
            NatsOutboxTransport(
                if (shared) checkNotNull(inquiryShared) else SharedNatsConnection(connection, "fionas-commerce-notifications"),
                notifications.stream,
                "notifications",
            )
        }

    val sharesConnection: Boolean get() = shared
}
