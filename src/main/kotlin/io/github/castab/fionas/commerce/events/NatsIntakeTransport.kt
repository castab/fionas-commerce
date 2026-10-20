package io.github.castab.fionas.commerce.events

import io.github.oshai.kotlinlogging.KotlinLogging
import io.nats.client.Connection
import io.nats.client.ConnectionListener
import io.nats.client.ErrorListener
import io.nats.client.JetStreamApiException
import io.nats.client.JetStreamOptions
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PublishOptions
import io.nats.client.PullSubscribeOptions
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.ReplayPolicy
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

private val transportLogger = KotlinLogging.logger {}

interface IntakeTransport : AutoCloseable {
    fun next(): IntakeDelivery?

    fun publishDiagnostic(
        id: String,
        bytes: ByteArray,
    )

    override fun close()
}

internal fun verifyIntakeConsumer(config: ConsumerConfiguration) {
    if (config.durable != INQUIRY_CONSUMER ||
        config.name != INQUIRY_CONSUMER ||
        config.filterSubjects != listOf(INQUIRY_INPUT_SUBJECT) ||
        !config.deliverSubject.isNullOrEmpty() ||
        config.ackPolicy != AckPolicy.Explicit ||
        config.deliverPolicy != DeliverPolicy.All ||
        config.replayPolicy != ReplayPolicy.Instant ||
        config.ackWait != Duration.ofSeconds(90) ||
        config.maxDeliver != 10L ||
        config.maxAckPending != 1L ||
        config.maxPullWaiting != 2L ||
        config.maxBatch != 1L ||
        config.maxExpires != Duration.ofSeconds(5) ||
        config.backoff.isNotEmpty() ||
        (config.inactiveThreshold?.let { !it.isZero && !it.isNegative } == true)
    ) {
        throw IntakeFailure("consumer_configuration_mismatch", FailureKind.SYSTEMIC)
    }
}

/** Only INFO, NEXT and disposition against the existing durable. No consumer/stream mutation. */
class NatsIntakeTransport(
    private val configuration: NatsIntakeConfiguration,
) : IntakeTransport {
    @Volatile private var connection: Connection? = null

    @Volatile private var closed = false

    @Volatile private var brokerFailure: IntakeFailure? = null
    private var consumer: JetStreamSubscription? = null
    private var nextStatusLog = 0L

    @Volatile private var rebind = false
    private val jsOptions = JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(5)).build()

    private val options =
        Options
            .builder()
            .server(configuration.connection.url)
            .connectionName("fionas-commerce-intake")
            .connectionTimeout(Duration.ofSeconds(1))
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(2))
            .reconnectBufferSize(0)
            .socketWriteTimeout(1_000)
            .connectionListener { _, event ->
                if (event == ConnectionListener.Events.DISCONNECTED) rebind = true
                transportLogger.info { "event=intake_nats_connection state=$event" }
            }.errorListener(
                object : ErrorListener {
                    override fun errorOccurred(
                        connection: Connection,
                        error: String,
                    ) {
                        // Inspect only for classification; never print broker error text or its subject.
                        brokerFailure =
                            IntakeFailure(
                                if (error.contains("permission", true) ||
                                    error.contains("authorization", true)
                                ) {
                                    "nats_permission_denied"
                                } else {
                                    "nats_server_error"
                                },
                                FailureKind.SYSTEMIC,
                            )
                    }

                    override fun exceptionOccurred(
                        connection: Connection,
                        exception: Exception,
                    ) {
                        transportLogger.warn { "event=intake_nats_error code=CLIENT_ERROR" }
                    }
                },
            ).also(configuration.connection::authenticate)
            .build()

    private fun connected(): Connection {
        checkFailure()
        if (closed) throw InterruptedException()
        connection?.takeUnless { it.status == Connection.Status.CLOSED }?.let { return it }
        val candidate =
            try {
                Nats.connect(options)
            } catch (error: IOException) {
                throw networkFailure(error)
            }
        synchronized(this) {
            if (closed) {
                candidate.close()
                throw InterruptedException()
            }
            connection = candidate
            consumer = null
        }
        return candidate
    }

    internal fun checkFailure() {
        brokerFailure?.let { throw it }
    }

    override fun next(): IntakeDelivery? =
        network {
            val active = connected()
            if (active.status != Connection.Status.CONNECTED) throw IntakeFailure("nats_unavailable", FailureKind.TRANSIENT)
            if (rebind) {
                consumer?.unsubscribe()
                consumer = null
                rebind = false
            }
            val current =
                consumer
                    ?: active
                        .jetStream(
                            jsOptions,
                        ).subscribe(INQUIRY_INPUT_SUBJECT, PullSubscribeOptions.bind(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER))
                        .also {
                            consumer =
                                it
                        }
            val info = current.consumerInfo
            verifyIntakeConsumer(info.consumerConfiguration)
            if (System.nanoTime() >= nextStatusLog) {
                transportLogger.info {
                    "event=intake_consumer_status stream=$INQUIRY_INPUT_STREAM consumer=$INQUIRY_CONSUMER " +
                        "pending=${info.numPending} ackPending=${info.numAckPending} redelivered=${info.redelivered}"
                }
                nextStatusLog = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            }
            checkFailure()
            current.fetch(1, Duration.ofSeconds(2)).singleOrNull()?.let { message ->
                checkFailure()
                val metadata = message.metaData()
                if (message.subject != INQUIRY_INPUT_SUBJECT ||
                    metadata.stream != INQUIRY_INPUT_STREAM ||
                    metadata.consumer != INQUIRY_CONSUMER
                ) {
                    throw IntakeFailure("unexpected_delivery", FailureKind.SYSTEMIC)
                }
                NatsDelivery(message, this)
            }
        }

    override fun publishDiagnostic(
        id: String,
        bytes: ByteArray,
    ) = network {
        val ack =
            connected().jetStream(jsOptions).publish(
                INQUIRY_FAILURE_SUBJECT,
                bytes,
                PublishOptions
                    .builder()
                    .expectedStream(FIONA_EVENTS_STREAM)
                    .messageId(id)
                    .build(),
            )
        checkFailure()
        if (ack.stream != FIONA_EVENTS_STREAM || ack.seqno <= 0) throw IntakeFailure("invalid_diagnostic_puback", FailureKind.TRANSIENT)
    }

    internal fun <T> network(action: () -> T): T =
        try {
            action()
        } catch (error: JetStreamApiException) {
            throw IntakeFailure(
                if (error.errorCode in 400..499) "nats_resource_or_permission_failure" else "nats_unavailable",
                if (error.errorCode in 400..499) FailureKind.SYSTEMIC else FailureKind.TRANSIENT,
            )
        } catch (error: IllegalStateException) {
            if (rebind || connection?.status != Connection.Status.CONNECTED) {
                throw IntakeFailure("nats_unavailable", FailureKind.TRANSIENT)
            }
            throw error
        } catch (_: TimeoutException) {
            throw IntakeFailure("nats_unavailable", FailureKind.TRANSIENT)
        } catch (error: IOException) {
            throw networkFailure(error)
        }

    private fun networkFailure(error: IOException): IntakeFailure {
        checkFailure()
        val systemic =
            error is SSLHandshakeException ||
                error.message.orEmpty().let {
                    it.contains("authorization", true) || it.contains("authentication", true) || it.contains("permission", true)
                }
        return IntakeFailure(
            if (systemic) "nats_authentication_or_permission_failure" else "nats_unavailable",
            if (systemic) FailureKind.SYSTEMIC else FailureKind.TRANSIENT,
        )
    }

    override fun close() {
        val active =
            synchronized(this) {
                closed = true
                connection.also { connection = null }
            }
        active?.close()
    }
}

private class NatsDelivery(
    private val message: Message,
    private val transport: NatsIntakeTransport,
) : IntakeDelivery {
    override val bytes: ByteArray get() = message.data
    override val sequence: Long = message.metaData().streamSequence()
    override val attempt: Long = message.metaData().deliveredCount()
    override val storedAt: Instant = message.metaData().timestamp().toInstant()

    override fun acknowledge() =
        transport.network {
            transport.checkFailure()
            message.ackSync(Duration.ofSeconds(5))
            transport.checkFailure()
        }

    override fun retry(delay: Duration) =
        transport.network {
            transport.checkFailure()
            message.nakWithDelay(delay)
        }

    override fun terminate() =
        transport.network {
            transport.checkFailure()
            message.term()
            message.connection.flush(Duration.ofSeconds(5))
            transport.checkFailure()
        }
}
