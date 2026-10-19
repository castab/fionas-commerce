package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.outbox.OutboxTransport
import io.github.castab.commerce.runtime.outbox.PublicationResult
import io.github.castab.commerce.runtime.outbox.RecordedOutboxEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.nats.client.Connection
import io.nats.client.ErrorListener
import io.nats.client.JetStreamOptions
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.PublishOptions
import io.nats.client.impl.Headers
import java.time.Duration

private val logger = KotlinLogging.logger {}

const val FIONA_EVENTS_STREAM = "FIONAS_EVENTS"
const val AGGREGATE_TYPE_HEADER = "Fionas-Aggregate-Type"
const val AGGREGATE_ID_HEADER = "Fionas-Aggregate-Id"
const val AGGREGATE_SEQUENCE_HEADER = "Fionas-Aggregate-Sequence"
const val PRODUCER_HEADER = "Fionas-Producer-Id"

/** Lazy, bounded network I/O. No stream administration, local queue, or database bookkeeping. */
class NatsOutboxTransport(
    configuration: NatsOutboxConfiguration,
) : OutboxTransport,
    AutoCloseable {
    private val options =
        Options
            .builder()
            .server(configuration.url)
            .connectionName("fionas-commerce-outbox")
            .connectionTimeout(Duration.ofSeconds(1))
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(1))
            .reconnectBufferSize(0)
            .socketWriteTimeout(1_000)
            .connectionListener { _, event -> logger.info { "event=nats_outbox_connection state=$event" } }
            // Client defaults can log server errors or exception text containing connection secrets.
            .errorListener(
                object : ErrorListener {
                    override fun errorOccurred(
                        connection: Connection,
                        error: String,
                    ) {
                        logger.warn { "event=nats_outbox_error code=SERVER_ERROR" }
                    }

                    override fun exceptionOccurred(
                        connection: Connection,
                        exception: Exception,
                    ) {
                        logger.warn { "event=nats_outbox_error code=CLIENT_ERROR" }
                    }
                },
            ).also(configuration::authenticate)
            .build()

    @Volatile private var connection: Connection? = null

    @Volatile private var closed = false

    override fun publish(event: RecordedOutboxEvent): PublicationResult {
        check(!closed) { "NATS outbox transport is closed" }
        try {
            val active = connected()
            val envelope = event.event
            val headers =
                Headers()
                    .add(PRODUCER_HEADER, envelope.producerId)
                    .add(AGGREGATE_TYPE_HEADER, envelope.aggregateType)
                    .add(AGGREGATE_ID_HEADER, envelope.aggregateId)
                    .add(AGGREGATE_SEQUENCE_HEADER, event.aggregateSequence.toString())
            val ack =
                active.jetStream(JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(3)).build()).publish(
                    envelope.destination,
                    headers,
                    event.payloadJson.toByteArray(Charsets.UTF_8),
                    PublishOptions
                        .builder()
                        .expectedStream(FIONA_EVENTS_STREAM)
                        .messageId(envelope.id.toString())
                        .build(),
                )
            return if (ack.stream == FIONA_EVENTS_STREAM && ack.seqno > 0) {
                PublicationResult.CONFIRMED
            } else {
                PublicationResult.UNCONFIRMED
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (_: Exception) {
            // Safe closed result; runtime records failure and retries unchanged content.
            return PublicationResult.UNCONFIRMED
        }
    }

    /** Never hold the lifecycle monitor during network I/O, including DNS/initial connect. */
    private fun connected(): Connection {
        connection?.takeUnless { it.status == Connection.Status.CLOSED }?.let { return it }
        val candidate = Nats.connect(options)
        val selected =
            synchronized(this) {
                if (closed) {
                    null
                } else {
                    connection?.takeUnless { it.status == Connection.Status.CLOSED }
                        ?: candidate.also { connection = it }
                }
            }
        if (selected !== candidate) candidate.close()
        return checkNotNull(selected) { "NATS outbox transport is closed" }
    }

    override fun close() {
        val active =
            synchronized(this) {
                if (closed) return
                closed = true
                connection.also { connection = null }
            }
        active?.close()
    }
}
