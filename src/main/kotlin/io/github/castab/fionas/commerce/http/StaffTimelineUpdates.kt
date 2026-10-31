package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.castab.fionas.commerce.conversation.InquiryTimelineSubscriptions
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.sse.Sse
import org.http4k.sse.SseEventId
import org.http4k.sse.SseMessage
import java.time.Duration

private val updatesLogger = KotlinLogging.logger {}

/** Bounded native writers, independent of the database scanner and deadline watchdog. No event queue. */
class StaffTimelineUpdates(
    private val subscriptions: InquiryTimelineSubscriptions,
    private val lifetime: Duration = Duration.ofSeconds(60),
    private val heartbeat: Duration = Duration.ofSeconds(20),
    private val scanInterval: Duration = Duration.ofSeconds(1),
    private val authorizationInterval: Duration = Duration.ofSeconds(1),
    private val writeTimeout: Duration = Duration.ofSeconds(5),
    private val nanoTime: () -> Long = System::nanoTime,
) : ManagedBackgroundService {
    private val monitor = Any()
    private val writers = mutableSetOf<Writer>()

    @Volatile private var running = false
    private var closed = false
    private var scanner: Thread? = null
    private var watchdog: Thread? = null

    override fun start() {
        synchronized(monitor) {
            check(!closed) { "Timeline updates are closed" }
            if (running) return
            running = true
            try {
                scanner =
                    Thread
                        .ofVirtual()
                        .name("fionas-timeline-scan")
                        .unstarted {
                            while (running) {
                                try {
                                    subscriptions.refresh()
                                } catch (_: Exception) {
                                    if (running) updatesLogger.warn { "event=timeline_scan_failed" }
                                }
                                try {
                                    Thread.sleep(scanInterval)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }.also(Thread::start)
                watchdog =
                    Thread
                        .ofVirtual()
                        .name("fionas-timeline-deadlines")
                        .unstarted {
                            while (running) {
                                val now = nanoTime()
                                synchronized(monitor) { writers.toList() }.forEach {
                                    if (it.subscription.isClosed ||
                                        now - it.started >= lifetime.toNanos() ||
                                        it.writingSince?.let { since -> now - since >= writeTimeout.toNanos() } == true
                                    ) {
                                        it.cancel()
                                    }
                                }
                                try {
                                    Thread.sleep(100)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                        }.also(Thread::start)
            } catch (failure: Throwable) {
                running = false
                scanner?.interrupt()
                watchdog?.interrupt()
                throw failure
            }
        }
    }

    /** Called only after the native response commits. Admission and writer creation are one critical section. */
    fun open(
        stream: Sse,
        inquiry: InquiryId,
        authorized: () -> Boolean,
    ) {
        val admitted =
            synchronized(monitor) {
                if (!running ||
                    closed ||
                    writers.size >= InquiryTimelineSubscriptions.MAX_SUBSCRIPTIONS ||
                    writers.count { it.subscription.inquiryId == inquiry } >= InquiryTimelineSubscriptions.MAX_PER_INQUIRY
                ) {
                    null
                } else {
                    subscriptions.subscribe(inquiry)?.let { subscription ->
                        Writer(stream, subscription, authorized).also { writer ->
                            writers.add(writer)
                            try {
                                stream.onClose { writer.cancel() }
                                writer.thread.start()
                            } catch (failure: Throwable) {
                                subscription.close()
                                writers.remove(writer)
                                throw failure
                            }
                        }
                    }
                }
            }
        if (admitted == null) {
            updatesLogger.warn { "event=timeline_subscription_refused" }
            stream.close()
        }
    }

    internal val activeCount: Int get() = synchronized(monitor) { writers.size }

    private inner class Writer(
        private val stream: Sse,
        val subscription: InquiryTimelineSubscriptions.Subscription,
        private val authorized: () -> Boolean,
    ) {
        val started = nanoTime()

        @Volatile var writingSince: Long? = null
        val thread: Thread = Thread.ofVirtual().name("fionas-timeline-writer").unstarted(::run)

        fun cancel() {
            subscription.close()
            thread.interrupt() // Never acquire the native send/close lock from a callback or the scanner.
        }

        private fun send(message: SseMessage) {
            writingSince = nanoTime()
            try {
                stream.send(message)
            } finally {
                writingSince = null
            }
        }

        private fun run() {
            try {
                if (!authorized()) return
                send(SseMessage.Retry(Duration.ofSeconds(3)))
                var lastAuthorization = nanoTime()
                var lastHeartbeat = lastAuthorization
                while (running && !subscription.isClosed && nanoTime() - started < lifetime.toNanos()) {
                    val now = nanoTime()
                    val revision = subscription.poll()
                    if (revision != null || now - lastAuthorization >= authorizationInterval.toNanos()) {
                        if (!authorized()) break
                        lastAuthorization = nanoTime()
                    }
                    if (subscription.isClosed || nanoTime() - started >= lifetime.toNanos()) break
                    if (revision != null) {
                        send(
                            SseMessage.Event(
                                "timeline.changed",
                                """{"inquiryId":"${subscription.inquiryId.value}","revision":"$revision"}""",
                                SseEventId(revision.toString()),
                            ),
                        )
                    }
                    if (now - lastHeartbeat >= heartbeat.toNanos()) {
                        send(SseMessage.Ping)
                        lastHeartbeat = now
                    }
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) {
                // Cancellation is expected on disconnect, deadline and shutdown.
            } catch (_: Exception) {
                updatesLogger.debug { "event=timeline_writer_stopped" }
            } finally {
                subscription.close()
                try {
                    stream.close()
                } catch (_: Exception) {
                    updatesLogger.warn { "event=timeline_close_failed" }
                } finally {
                    synchronized(monitor) { writers.remove(this) }
                }
            }
        }
    }

    override fun close() {
        val threads =
            synchronized(monitor) {
                if (closed) return
                closed = true
                running = false
                subscriptions.close()
                writers.forEach { it.cancel() }
                (listOfNotNull(scanner, watchdog) + writers.map { it.thread }).also { it.forEach(Thread::interrupt) }
            }
        // Configured database bounds cap a transaction at 50s and driver/pool waits at 5s.
        val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
        threads.forEach { thread ->
            val remaining = deadline - System.nanoTime()
            if (remaining > 0 && thread !== Thread.currentThread()) thread.join(Duration.ofNanos(remaining))
        }
        check(threads.none { it.isAlive }) { "Timeline update workers did not stop within the shutdown bound" }
    }
}
