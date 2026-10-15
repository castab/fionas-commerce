package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.castab.commerce.runtime.outbox.OutboxDispatchSettings
import io.github.castab.commerce.runtime.outbox.OutboxDispatcher
import java.time.Duration
import java.util.UUID

/** Owns the adapter; the runtime stops this service before closing its shared database pool. */
class InquiryOutboxPublisher(
    context: CommerceRuntimeContext,
    configuration: NatsOutboxConfiguration,
    instanceId: String = UUID.randomUUID().toString(),
) : ManagedBackgroundService {
    private val transport = NatsOutboxTransport(configuration)
    private val dispatcher =
        OutboxDispatcher(
            context.outbox.publisher(FIONA_EVENT_PRODUCER, instanceId),
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
            transport.close()
        }
    }
}
