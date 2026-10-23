package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.castab.commerce.runtime.operation.InternalComponentId
import io.github.castab.commerce.runtime.operation.OperationOrigin
import io.github.castab.fionas.commerce.createInquiryOperation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Duration

private val INQUIRY_INTAKE_ORIGIN = OperationOrigin.Internal(InternalComponentId("fionas.inquiry-intake"))

private val workerLogger = KotlinLogging.logger {}

enum class IntakeState { NEW, CONNECTING, RUNNING, RETRYING, HALTED, STOPPED }

data class IntakeStatus(
    val state: IntakeState,
    val code: String? = null,
)

/** One dedicated virtual thread, one delivery, no prefetch, no shared dispatcher or unbounded queue. */
class ManagedJetStreamWorker(
    private val transport: IntakeTransport,
    handle: (ByteArray) -> Unit,
    private val grace: Duration = Duration.ofSeconds(55),
) : ManagedBackgroundService {
    private val processor = IntakeProcessor(handle, transport::publishDiagnostic)
    private val monitor = Any()

    @Volatile private var stopping = false

    @Volatile private var abort = false

    @Volatile var status = IntakeStatus(IntakeState.NEW)
        private set
    private var worker: Thread? = null

    override fun start() =
        synchronized(monitor) {
            check(!stopping) { "Intake worker is closed" }
            if (worker == null) {
                status = IntakeStatus(IntakeState.CONNECTING)
                worker =
                    Thread
                        .ofVirtual()
                        .name("fionas-inquiry-intake")
                        .unstarted(::runLoop)
                        .also(Thread::start)
            }
        }

    private fun runLoop() {
        try {
            while (!stopping) {
                try {
                    val delivery = transport.next()
                    if (stopping) break
                    status = IntakeStatus(IntakeState.RUNNING)
                    if (delivery != null) processor.process(delivery) { abort }
                } catch (error: Exception) {
                    if (stopping) break
                    val failure = classifyIntakeFailure(error)
                    if (failure.kind != FailureKind.TRANSIENT) throw failure
                    status = IntakeStatus(IntakeState.RETRYING, failure.code)
                    workerLogger.warn { "event=intake_retrying code=${failure.code}" }
                    // Bounded retry without spinning during broker/database interruption.
                    repeat(20) { if (!stopping) Thread.sleep(100) }
                }
            }
        } catch (error: Exception) {
            if (!stopping) {
                val failure = classifyIntakeFailure(error)
                status = IntakeStatus(IntakeState.HALTED, failure.code)
                workerLogger.error { "event=intake_halted code=${failure.code} action=repair_and_restart" }
            }
        } finally {
            transport.close()
            if (status.state != IntakeState.HALTED) status = IntakeStatus(IntakeState.STOPPED)
            workerLogger.info { "event=intake_stopped state=${status.state}" }
        }
    }

    override fun close() {
        val thread =
            synchronized(monitor) {
                stopping = true
                worker
            }
        // Stop new pulls first. A fetch is bounded to 2s; current business work/ACK gets its grace.
        thread?.join(grace)
        if (thread?.isAlive == true) {
            abort = true
            transport.close()
            thread.interrupt()
            thread.join(Duration.ofSeconds(15))
        }
        check(thread?.isAlive != true) { "Intake worker exceeded its configured database/network shutdown bounds" }
        transport.close()
        if (status.state != IntakeState.HALTED) status = IntakeStatus(IntakeState.STOPPED)
    }
}

internal fun inquiryIntakeHandler(
    context: CommerceRuntimeContext,
    clock: Clock,
): (ByteArray) -> Unit {
    val createInquiry = createInquiryOperation(context, clock)
    return { bytes ->
        val command = submittedCommand(bytes, INQUIRY_INTAKE_ORIGIN)
        createInquiry(command)
        Unit
    }
}

internal fun inquiryIntake(
    context: CommerceRuntimeContext,
    configuration: NatsIntakeConfiguration,
    clock: Clock,
): ManagedJetStreamWorker {
    requireIntakeDatabaseBounds(context.configuration.database)
    return ManagedJetStreamWorker(NatsIntakeTransport(configuration), inquiryIntakeHandler(context, clock))
}
