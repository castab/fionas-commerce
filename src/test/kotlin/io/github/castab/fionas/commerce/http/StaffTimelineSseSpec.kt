package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.conversation.InquiryTimelineRevisionRepository
import io.github.castab.fionas.commerce.conversation.ReadInquiryTimelineRevisions
import io.github.castab.fionas.commerce.fionaApplication
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class StaffTimelineSseSpec :
    FunSpec({
        lateinit var app: TestApplication
        lateinit var client: HttpClient
        beforeTest {
            app = TestApplication.create(timelineUpdatesEnabled = true)
            app.runtime.start()
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        }
        afterTest {
            client.shutdownNow()
            app.close()
        }

        fun request(
            path: String,
            cookie: String? = app.adminCookie,
        ) = HttpRequest
            .newBuilder(URI("http://localhost:${app.runtime.port()}$path"))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "text/event-stream")
            .apply { cookie?.let { header("Cookie", it) } }
            .GET()
            .build()

        fun open(id: String) = SocketEvents(client.send(request("/staff/requests/$id/updates"), HttpResponse.BodyHandlers.ofInputStream()))

        test("two native streams synchronize, flush committed changes, isolate inquiries and reconnect while HTTP stays healthy") {
            val id = app.createInquiry()
            val other = app.createInquiry()
            open(id).use { first ->
                open(id).use { second ->
                    open(other).use { isolated ->
                        first.response.statusCode() shouldBe 200
                        first.response
                            .headers()
                            .firstValue("Content-Type")
                            .orElse("") shouldContain "text/event-stream"
                        first.response
                            .headers()
                            .firstValue("Cache-Control")
                            .orElse("") shouldBe "no-store, no-transform"
                        first.response
                            .headers()
                            .firstValue("X-Accel-Buffering")
                            .orElse("") shouldBe "no"
                        first.changed(id, 0)
                        second.changed(id, 0)
                        isolated.changed(other, 0)
                        app.adminPost("/inquiries/$id/communications/acknowledge", """{"throughRecordedOrder":0}""")
                        first.changed(id, 1)
                        second.changed(id, 1)
                        repeat(3) {
                            listOf("/health", "/ready", "/inquiries/$id").forEach { path ->
                                client.send(request(path), HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 200
                            }
                        }
                        app
                            .adminPost("/inquiries/$id/decline", """{"expectedDocumentVersion":1,"reason":"Unavailable"}""")
                            .status.code shouldBe 200
                        first.changed(id, 2)
                        second.changed(id, 2)
                        isolated.frames.poll(200, TimeUnit.MILLISECONDS) shouldBe null
                    }
                }
            }
            // Last-Event-ID is not replay: every connection synchronizes current authority.
            val reconnect = request("/staff/requests/$id/updates")
            SocketEvents(
                client.send(
                    HttpRequest.newBuilder(reconnect, { _, _ -> true }).header("Last-Event-ID", "2").build(),
                    HttpResponse.BodyHandlers.ofInputStream(),
                ),
            ).use { it.changed(id, 2) }
        }

        test("another runtime instance observes database commits without local publication or sticky sessions") {
            val id = app.createInquiry()
            val secondApplication =
                fionaApplication(
                    bootstrap = null,
                    outboxPublisher = null,
                    intake = null,
                    conversations = null,
                    conversationReads = null,
                    conversationSends = null,
                    timelineUpdatesEnabled = true,
                )
            commerceRuntime(app.context.configuration, secondApplication).use { second ->
                second.start()
                val remote =
                    HttpRequest
                        .newBuilder(URI("http://localhost:${second.port()}/staff/requests/$id/updates"))
                        .header("Accept", "text/event-stream")
                        .header("Cookie", app.adminCookie)
                        .build()
                SocketEvents(client.send(remote, HttpResponse.BodyHandlers.ofInputStream())).use { stream ->
                    stream.changed(id, 0)
                    app
                        .adminPost("/inquiries/$id/communications/acknowledge", """{"throughRecordedOrder":0}""")
                        .status.code shouldBe 204
                    stream.changed(id, 1)
                }
            }
        }

        test("handshake preserves JSON errors and authorizes before identifiers and existence") {
            val id = app.createInquiry()

            fun rejected(
                target: String,
                cookie: String?,
                status: Int,
                code: String,
            ) {
                val response = client.send(request("/staff/requests/$target/updates", cookie), HttpResponse.BodyHandlers.ofString())
                response.statusCode() shouldBe status
                response.body() shouldContain code
                response.headers().firstValue("Content-Type").orElse("") shouldContain "application/json"
            }
            rejected("bad", null, 401, "unauthenticated")
            rejected(id, "__Host-fionas_session=invalid", 401, "unauthenticated")
            rejected("bad", app.adminCookie, 400, "malformed_request")
            rejected(UUID.randomUUID().toString(), app.adminCookie, 404, "not_found")
            val grants = app.authorization.permissionResolver.permissionsFor(app.adminId)
            listOf(
                FionaPermissions.InquiriesRead,
                CommercePermissions.FinancialDocumentRead,
                FionaPermissions.CommunicationsRead,
            ).forEach { missing ->
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants - missing)
                rejected(id, app.adminCookie, 403, "forbidden")
            }
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
            val service = app.provisionService("sse-service", grants)
            val serviceRequest =
                HttpRequest
                    .newBuilder(request("/staff/requests/$id/updates", null), { _, _ -> true })
                    .header("Authorization", "Bearer ${app.serviceToken(service)}")
                    .build()
            client.send(serviceRequest, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 403
            open(id).use { it.changed(id, 0) }
        }

        test("pre-stream unexpected failures are sanitized JSON and non-SSE GET never fakes a stream") {
            val id = app.createInquiry()
            app.adminGet("/staff/requests/$id/updates").status.code shouldBe 400
            app.database.execute("ALTER TABLE fionas.inquiry_change_revisions RENAME TO hidden_revisions")
            try {
                val response = client.send(request("/staff/requests/$id/updates"), HttpResponse.BodyHandlers.ofString())
                response.statusCode() shouldBe 500
                response.body() shouldContain "internal_failure"
                response.body().contains("hidden_revisions") shouldBe false
                response.body().contains("SELECT") shouldBe false
            } finally {
                app.database.execute("ALTER TABLE fionas.hidden_revisions RENAME TO inquiry_change_revisions")
            }
            open(id).use { it.changed(id, 0) }
        }

        test("disabled deployments return explicit JSON 503 without activating a consumer") {
            TestApplication.create().use { disabled ->
                val id = disabled.createInquiry()
                disabled.runtime.start()
                val request =
                    HttpRequest
                        .newBuilder(URI("http://localhost:${disabled.runtime.port()}/staff/requests/$id/updates"))
                        .header("Accept", "text/event-stream")
                        .header("Cookie", disabled.adminCookie)
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                response.statusCode() shouldBe 503
                response.body() shouldContain "timeline_updates_unavailable"
            }
        }

        test("original session revocation and permission removal terminate live native streams") {
            val id = app.createInquiry()
            open(id).use { stream ->
                stream.changed(id, 0)
                val grants = app.authorization.permissionResolver.permissionsFor(app.adminId)
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants - FionaPermissions.CommunicationsRead)
                stream.ended()
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
            }
            open(id).use { stream ->
                stream.changed(id, 0)
                app.sessions.revokeAll(app.adminId)
                stream.ended()
            }
        }

        test("session expiration is resolved by the original authority during a stream and at the next handshake") {
            val id = app.createInquiry()
            open(id).use { stream ->
                stream.changed(id, 0)
                // Test-only time travel of the runtime authority; production never accesses this schema.
                app.database.execute(
                    "UPDATE commerce.principal_sessions SET created_at = now() - interval '2 hours', " +
                        "expires_at = now() - interval '1 hour'",
                )
                stream.ended()
            }
            client.send(request("/staff/requests/$id/updates"), HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 401
        }

        test("failed database scan closes streams and a new connection synchronizes after recovery") {
            val id = app.createInquiry()
            open(id).use { stream ->
                stream.changed(id, 0)
                app.database.execute("ALTER TABLE fionas.inquiry_change_revisions RENAME TO hidden_revisions")
                try {
                    stream.ended()
                } finally {
                    app.database.execute("ALTER TABLE fionas.hidden_revisions RENAME TO inquiry_change_revisions")
                }
            }
            val inquiry = InquiryId(UUID.fromString(id))
            app.transactor.inTransaction { InquiryTimelineRevisionRepository().advance(it, listOf(inquiry)) }
            ReadInquiryTimelineRevisions(app.transactor, InquiryTimelineRevisionRepository())(setOf(inquiry))[inquiry] shouldBe 1
            open(id).use { it.changed(id, 1) }
        }

        test("shutdown cancels native streams before stopping Jetty and the pool") {
            val id = app.createInquiry()
            open(id).use { stream ->
                stream.changed(id, 0)
                val start = System.nanoTime()
                app.runtime.close()
                (System.nanoTime() - start < Duration.ofSeconds(10).toNanos()) shouldBe true
                stream.ended()
            }
        }
    })

/** Real HTTP response reader with a bounded test mailbox and deadlines, never arbitrary sleeps. */
internal class SocketEvents(
    val response: HttpResponse<java.io.InputStream>,
) : AutoCloseable {
    val frames = LinkedBlockingQueue<String>(32)
    private val reader =
        Thread.ofVirtual().start {
            try {
                response.body().bufferedReader().use { input ->
                    val frame = mutableListOf<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) {
                            if (frame.any { it.startsWith("event:") }) frames.put(frame.joinToString("\n"))
                            frame.clear()
                        } else {
                            frame.add(line)
                        }
                    }
                }
            } finally {
                frames.offer("EOF")
            }
        }

    fun changed(
        id: String,
        revision: Long,
    ) {
        val frame = checkNotNull(frames.poll(8, TimeUnit.SECONDS)) { "No SSE event before deadline" }
        frame shouldContain "event:timeline.changed"
        frame shouldContain "id:$revision"
        frame shouldContain "data:{\"inquiryId\":\"$id\",\"revision\":\"$revision\"}"
    }

    fun ended() {
        frames.poll(8, TimeUnit.SECONDS) shouldBe "EOF"
    }

    override fun close() {
        response.body().close()
        reader.interrupt()
        reader.join(Duration.ofSeconds(2))
    }
}
