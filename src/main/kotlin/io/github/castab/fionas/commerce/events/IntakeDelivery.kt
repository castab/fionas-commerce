package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.inquiry.IdempotencyKeyReused
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.time.Instant

const val INQUIRY_INPUT_STREAM = "FIONAS_INQUIRIES"
const val INQUIRY_INPUT_SUBJECT = "fionas.inquiries.submitted.v1"
const val INQUIRY_CONSUMER = "inquiry-to-commerce"
const val INQUIRY_FAILURE_SUBJECT = "fionas.events.inquiry.processing-failed.v2"
private val intakeLogger = KotlinLogging.logger {}

enum class FailureKind { PERMANENT, TRANSIENT, SYSTEMIC }

/** Only static codes cross the logging/diagnostic boundary. */
class IntakeFailure(
    val code: String,
    val kind: FailureKind,
) : RuntimeException(code)

internal fun classifyIntakeFailure(error: Exception): IntakeFailure {
    if (error is IntakeFailure) return error
    if (error is CommerceFailure.Conflict) {
        return if (error.cause is IdempotencyKeyReused) {
            IntakeFailure("idempotency_key_reused", FailureKind.PERMANENT)
        } else {
            IntakeFailure("commerce_concurrent_conflict", FailureKind.TRANSIENT)
        }
    }
    if (error is CommerceFailure.ValidationFailed) return IntakeFailure("commerce_input_rejected", FailureKind.PERMANENT)
    val causes = generateSequence<Throwable>(error) { it.cause }.toList()
    if (causes.any { it is SQLTransientConnectionException || it is SQLRecoverableException }) {
        return IntakeFailure("database_unavailable", FailureKind.TRANSIENT)
    }
    val states = causes.filterIsInstance<SQLException>().mapNotNull { it.sqlState }
    if (states.any {
            it.startsWith(
                "08",
            ) ||
                it in setOf("40001", "40P01", "55P03", "57014", "57P01", "57P02", "57P03", "25P04", "53300")
        }
    ) {
        return IntakeFailure("database_unavailable", FailureKind.TRANSIENT)
    }
    return IntakeFailure("unexpected_application_failure", FailureKind.SYSTEMIC)
}

fun intakeRetryDelay(attempt: Long): Duration =
    listOf(10L, 30L, 120L, 300L, 900L).let { Duration.ofSeconds(it[(attempt - 1).coerceIn(0, it.lastIndex.toLong()).toInt()]) }

/** Narrow transport seam: one delivery, no buffering, no business persistence. */
interface IntakeDelivery {
    val bytes: ByteArray
    val sequence: Long
    val attempt: Long
    val storedAt: Instant

    fun acknowledge()

    fun retry(delay: Duration)

    fun terminate()
}

internal fun failureDiagnostic(
    delivery: IntakeDelivery,
    code: String,
): Pair<String, ByteArray> {
    val identity = "fionas.inquiry.processing-failed.v2:$INQUIRY_INPUT_STREAM:${delivery.sequence}"
    val eventId =
        try {
            (submittedObject(delivery.bytes)["id"] as? JsonPrimitive)?.takeIf { it.isString && submittedUuid.matches(it.content) }?.content
        } catch (_: Exception) {
            null
        }
    val json =
        buildJsonObject {
            put("schemaVersion", 2)
            put("type", "fionas.inquiry.processing-failed")
            put("id", identity)
            eventId?.let { put("correlationId", it) }
            put("occurredAt", delivery.storedAt.toString())
            put("source", "fionas-commerce")
            put(
                "data",
                buildJsonObject {
                    put("inputStream", INQUIRY_INPUT_STREAM)
                    put("inputSequence", delivery.sequence)
                    put("errorCode", code)
                },
            )
        }
    return identity to json.toString().toByteArray(Charsets.UTF_8)
}

enum class IntakeResult { PERSISTED, FAILED, RETRY, INTERRUPTED }

/** The handler owns interpretation; transport owns confirmed disposition. Recovery uses this same pipeline. */
class IntakeProcessor(
    private val handle: (ByteArray) -> Unit,
    private val publishDiagnostic: (String, ByteArray) -> Unit,
) {
    fun process(
        delivery: IntakeDelivery,
        sourceDisposition: Boolean = true,
        cancelled: () -> Boolean = { false },
    ): IntakeResult {
        if (cancelled()) return IntakeResult.INTERRUPTED
        var failure =
            try {
                handle(delivery.bytes)
                intakeLogger.info { "event=intake_committed streamSequence=${delivery.sequence} attempt=${delivery.attempt}" }
                if (cancelled()) return IntakeResult.INTERRUPTED
                if (sourceDisposition) {
                    try {
                        delivery.acknowledge()
                    } catch (error: Exception) {
                        if (error is IntakeFailure && error.kind == FailureKind.SYSTEMIC) throw error
                        throw IntakeFailure("input_ack_unconfirmed", FailureKind.TRANSIENT)
                    }
                    intakeLogger.info { "event=intake_acknowledged streamSequence=${delivery.sequence}" }
                }
                return IntakeResult.PERSISTED
            } catch (error: Exception) {
                classifyIntakeFailure(error)
            }
        if (cancelled()) return IntakeResult.INTERRUPTED
        if (failure.kind == FailureKind.PERMANENT) {
            try {
                val (id, bytes) = failureDiagnostic(delivery, failure.code)
                publishDiagnostic(id, bytes)
                intakeLogger.info { "event=intake_diagnostic_confirmed streamSequence=${delivery.sequence} code=${failure.code}" }
                if (cancelled()) return IntakeResult.INTERRUPTED
                if (sourceDisposition) {
                    delivery.terminate()
                    intakeLogger.warn { "event=intake_input_terminated streamSequence=${delivery.sequence} code=${failure.code}" }
                }
                return IntakeResult.FAILED
            } catch (error: Exception) {
                failure = classifyIntakeFailure(error)
            }
        }
        intakeLogger.error {
            "event=intake_delivery_failed streamSequence=${delivery.sequence} attempt=${delivery.attempt} code=${failure.code} kind=${failure.kind}"
        }
        if (failure.kind == FailureKind.SYSTEMIC) throw failure
        if (delivery.attempt >= 10) throw IntakeFailure("retry_exhausted", FailureKind.SYSTEMIC)
        if (cancelled()) return IntakeResult.INTERRUPTED
        if (sourceDisposition) delivery.retry(intakeRetryDelay(delivery.attempt))
        return IntakeResult.RETRY
    }
}
