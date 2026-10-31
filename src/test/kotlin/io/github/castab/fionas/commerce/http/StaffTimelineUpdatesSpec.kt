package io.github.castab.fionas.commerce.http

import io.github.castab.fionas.commerce.conversation.InquiryTimelineSubscriptions
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.sse.Sse
import org.http4k.sse.SseMessage
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class StaffTimelineUpdatesSpec :
    FunSpec({
        fun id() = InquiryId(UUID.randomUUID())

        test("blocked writers are isolated, deadline cancelled and joined; failures release capacity") {
            val inquiry = id()
            val entered = CountDownLatch(1)
            val never = CountDownLatch(1)
            val now = AtomicLong()
            val registry = InquiryTimelineSubscriptions { ids -> ids.associateWith { 1L } }
            StaffTimelineUpdates(registry, nanoTime = now::get).use { updates ->
                updates.start()
                val blocked =
                    TestStream {
                        entered.countDown()
                        never.await()
                    }
                updates.open(blocked, inquiry) { true }
                entered.await(3, TimeUnit.SECONDS) shouldBe true
                val healthy = TestStream()
                updates.open(healthy, inquiry) { true }
                healthy.event() shouldBe true
                now.set(Duration.ofSeconds(6).toNanos())
                blocked.closed.await(3, TimeUnit.SECONDS) shouldBe true
                val failed = TestStream { error("write failed") }
                updates.open(failed, inquiry) { true }
                failed.closed.await(3, TimeUnit.SECONDS) shouldBe true
                healthy.close()
            }
        }

        test("maximum lifetime and authorization failure close even idle connections; heartbeat is not a change") {
            val inquiry = id()
            val now = AtomicLong()
            StaffTimelineUpdates(InquiryTimelineSubscriptions { it.associateWith { 0L } }, nanoTime = now::get).use { updates ->
                updates.start()
                val stream = TestStream()
                updates.open(stream, inquiry) { true }
                stream.event() shouldBe true
                now.set(Duration.ofSeconds(21).toNanos())
                stream.messages.poll(3, TimeUnit.SECONDS) shouldBe SseMessage.Ping
                now.set(Duration.ofSeconds(61).toNanos())
                stream.closed.await(3, TimeUnit.SECONDS) shouldBe true
                val denied = TestStream()
                updates.open(denied, inquiry) { false }
                denied.closed.await(3, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("concurrent admissions enforce per inquiry capacity and shutdown refuses new consumers") {
            val inquiry = id()
            val updates = StaffTimelineUpdates(InquiryTimelineSubscriptions { it.associateWith { 0L } })
            updates.start()
            val streams = List(32) { TestStream() }
            val start = CountDownLatch(1)
            val threads =
                streams.map { stream ->
                    Thread.ofVirtual().start {
                        start.await()
                        updates.open(stream, inquiry) { true }
                    }
                }
            start.countDown()
            threads.forEach {
                it.join(Duration.ofSeconds(3))
                it.isAlive shouldBe false
            }
            updates.activeCount shouldBe 16
            streams.count { it.closed.count == 0L } shouldBe 16
            updates.close()
            updates.activeCount shouldBe 0
            streams.all { it.closed.count == 0L } shouldBe true
            val late = TestStream()
            updates.open(late, inquiry) { true }
            late.closed.count shouldBe 0
            updates.close()
        }

        test("shutdown joins an in-flight scanner and never resurrects closed subscriptions") {
            val entered = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val registry =
                InquiryTimelineSubscriptions { ids ->
                    entered.countDown()
                    try {
                        CountDownLatch(1).await()
                    } finally {
                        exited.countDown()
                    }
                    ids.associateWith { 0L }
                }
            val updates = StaffTimelineUpdates(registry)
            updates.start()
            val stream = TestStream()
            updates.open(stream, id()) { true }
            entered.await(3, TimeUnit.SECONDS) shouldBe true
            updates.close()
            exited.count shouldBe 0
            stream.closed.count shouldBe 0
            updates.activeCount shouldBe 0
        }

        test("callback registration and native close failures release reservations") {
            val updates = StaffTimelineUpdates(InquiryTimelineSubscriptions { it.associateWith { 0L } })
            updates.start()
            try {
                val registrationFailure =
                    object : Sse by TestStream() {
                        override fun onClose(fn: () -> Unit): Sse = error("test callback registration failure")
                    }
                shouldThrow<IllegalStateException> { updates.open(registrationFailure, id()) { true } }
                updates.activeCount shouldBe 0
                val closed = CountDownLatch(1)
                val closeFailure =
                    object : Sse by TestStream() {
                        override fun close() {
                            closed.countDown()
                            error("test close failure")
                        }
                    }
                updates.open(closeFailure, id()) { false }
                closed.await(3, TimeUnit.SECONDS) shouldBe true
            } finally {
                updates.close()
            }
            updates.activeCount shouldBe 0
            shouldThrow<IllegalStateException> { updates.start() }
        }

        test("closing an unstarted owner is safe and idle scanning performs no database reads") {
            val reads = AtomicLong()
            val registry =
                InquiryTimelineSubscriptions {
                    reads.incrementAndGet()
                    emptyMap()
                }
            val updates = StaffTimelineUpdates(registry)
            updates.close()
            updates.activeCount shouldBe 0
            reads.get() shouldBe 0
        }
    })

internal class TestStream(
    private val write: () -> Unit = {},
) : Sse {
    override val connectRequest = Request(Method.GET, "/updates")
    val messages = LinkedBlockingQueue<SseMessage>(32)
    val closed = CountDownLatch(1)
    private var onClose: () -> Unit = {}

    override fun send(message: SseMessage): Sse {
        write()
        messages.put(message)
        return this
    }

    override fun onClose(fn: () -> Unit): Sse {
        onClose = fn
        return this
    }

    override fun close() {
        if (closed.count > 0) {
            closed.countDown()
            onClose()
        }
    }

    fun event(): Boolean {
        val deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos()
        while (System.nanoTime() < deadline) {
            if (messages.poll(1, TimeUnit.SECONDS) is SseMessage.Event) return true
        }
        return false
    }
}
