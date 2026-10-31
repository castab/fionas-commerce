package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.ManagedBackgroundService
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.fionas.commerce.conversation.InquiryTimelineSubscriptions
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.routing.sse.bind
import org.http4k.sse.Sse
import org.http4k.sse.SseMessage
import org.http4k.sse.SseResponse
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Production writer ownership against the real pinned Jetty emitter, with test-only payload amplification. */
class StaffTimelineBackpressureSpec :
    FunSpec({
        test("partial runtime startup failure cancels the already started writer owner") {
            TestDatabase.create().use { database ->
                val owner = StaffTimelineUpdates(InquiryTimelineSubscriptions { it.associateWith { 0L } })
                val stream = TestStream()
                val failing =
                    object : ManagedBackgroundService {
                        override fun start() {
                            owner.open(stream, InquiryId(UUID.randomUUID())) { true }
                            error("test partial startup failure")
                        }

                        override fun close() = Unit
                    }
                commerceRuntime(
                    CommerceRuntimeConfiguration(
                        server = CommerceRuntimeConfiguration.Server(0),
                        database = database.configuration,
                        migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
                    ),
                    ApplicationContributions(
                        backgroundServices = listOf({ owner }, { failing }),
                        sseRoutes = { listOf("/updates" bind { SseResponse { } }) },
                    ),
                ).use { runtime ->
                    shouldThrow<IllegalStateException> { runtime.start() }
                    stream.closed.count shouldBe 0
                    owner.activeCount shouldBe 0
                }
            }
        }

        test("a socket blocked inside Jetty cannot stall another writer or HTTP and is cancelled before Jetty stops") {
            TestDatabase.create().use { database ->
                val id = InquiryId(UUID.randomUUID())
                val owner = StaffTimelineUpdates(InquiryTimelineSubscriptions { it.associateWith { 0L } })
                val writer = AtomicReference<Thread>()
                val entered = CountDownLatch(1)
                commerceRuntime(
                    CommerceRuntimeConfiguration(
                        server = CommerceRuntimeConfiguration.Server(0),
                        database = database.configuration,
                        migrations = CommerceRuntimeConfiguration.Migrations(CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE),
                    ),
                    ApplicationContributions(
                        backgroundServices = listOf({ owner }),
                        sseRoutes = {
                            listOf(
                                "/updates" bind { request ->
                                    SseResponse { stream ->
                                        val transport =
                                            if (request.uri.query == "stalled") {
                                                object : Sse by stream {
                                                    override fun send(message: SseMessage): Sse {
                                                        writer.set(Thread.currentThread())
                                                        entered.countDown()
                                                        // Test-only amplification fills the socket.
                                                        return stream.send(SseMessage.Data("x".repeat(32 * 1024 * 1024)))
                                                    }
                                                }
                                            } else {
                                                stream
                                            }
                                        owner.open(transport, id) { true }
                                    }
                                },
                            )
                        },
                    ),
                ).use { runtime ->
                    runtime.start()
                    Socket().use { stalled ->
                        stalled.receiveBufferSize = 1024
                        stalled.connect(InetSocketAddress("localhost", runtime.port()), 5000)
                        stalled.getOutputStream().write(
                            "GET /updates?stalled HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n".toByteArray(),
                        )
                        stalled.getOutputStream().flush()
                        entered.await(3, TimeUnit.SECONDS) shouldBe true
                        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()

                        fun blocked() =
                            writer.get().let { thread ->
                                thread.state == Thread.State.WAITING &&
                                    thread.stackTrace.any { it.className.startsWith("org.eclipse.jetty") }
                            }
                        while (!blocked() && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000)
                        blocked() shouldBe true
                        val client = HttpClient.newHttpClient()
                        try {
                            fun request(path: String) =
                                HttpRequest
                                    .newBuilder(URI("http://localhost:${runtime.port()}$path"))
                                    .timeout(Duration.ofSeconds(3))
                                    .header("Accept", "text/event-stream")
                                    .build()
                            SocketEvents(client.send(request("/updates"), HttpResponse.BodyHandlers.ofInputStream())).use { healthy ->
                                healthy.changed(id.value.toString(), 0)
                                listOf("/health", "/ready").forEach {
                                    client.send(request(it), HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 200
                                }
                                val start = System.nanoTime()
                                runtime.close()
                                (System.nanoTime() - start < Duration.ofSeconds(10).toNanos()) shouldBe true
                                writer.get().isAlive shouldBe false
                                owner.activeCount shouldBe 0
                                healthy.ended()
                            }
                        } finally {
                            client.shutdownNow()
                        }
                    }
                }
            }
        }
    })
