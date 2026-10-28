package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository

const val CONVERSATION_STREAM = "RESEND_CONVERSATIONS"
const val CONVERSATION_SUBJECT = "conversation.events.v1"
const val CONVERSATION_CONSUMER = "fionas-commerce-conversations"

/** Independent opt-in scope; credentials are parsed by the existing secret-safe NATS parser. */
class ConversationIntakeConfiguration private constructor(
    internal val transport: NatsIntakeConfiguration,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ConversationIntakeConfiguration? {
            val scoped =
                environment
                    .filterKeys { it.startsWith("FIONAS_CONVERSATION_INTAKE_") }
                    .mapKeys { (key, _) -> key.replaceFirst("FIONAS_CONVERSATION_INTAKE_", "FIONAS_INTAKE_") }
            return NatsIntakeConfiguration.fromEnvironment(scoped)?.let(::ConversationIntakeConfiguration)
        }
    }
}

/** Unknown contracts halt with the source unacknowledged. No inquiry-specific failure event is published. */
internal fun processConversationDelivery(
    delivery: IntakeDelivery,
    handle: (ByteArray) -> Unit,
    cancelled: () -> Boolean,
) {
    if (cancelled()) return
    try {
        handle(delivery.bytes)
        if (!cancelled()) {
            try {
                delivery.acknowledge()
            } catch (error: Exception) {
                if (error is IntakeFailure && error.kind == FailureKind.SYSTEMIC) throw error
                throw IntakeFailure("conversation_ack_unconfirmed", FailureKind.TRANSIENT)
            }
        }
    } catch (error: Exception) {
        val failure = classifyIntakeFailure(error)
        if (failure.kind != FailureKind.TRANSIENT) throw failure
        if (delivery.attempt >= 10) throw IntakeFailure("conversation_retry_exhausted", FailureKind.SYSTEMIC)
        if (!cancelled()) delivery.retry(intakeRetryDelay(delivery.attempt))
    }
}

internal fun conversationIntake(
    context: CommerceRuntimeContext,
    config: ConversationIntakeConfiguration,
): ManagedJetStreamWorker {
    requireIntakeDatabaseBounds(context.configuration.database)
    val handle = RecordConversationEvent(context.transactor, JdbiInquiryCommunicationRepository(), JdbiInquiryConversationRepository())
    return ManagedJetStreamWorker(
        NatsIntakeTransport(config.transport, CONVERSATION_STREAM, CONVERSATION_SUBJECT, CONVERSATION_CONSUMER),
        handle::invoke,
        name = "fionas-conversation-intake",
        processDelivery = { delivery, cancelled -> processConversationDelivery(delivery, handle::invoke, cancelled) },
    )
}
