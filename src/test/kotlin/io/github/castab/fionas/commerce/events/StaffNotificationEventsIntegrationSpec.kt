package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.outbox.PublicationResult
import io.github.castab.fionas.commerce.http.StaffNotificationStatusResponse
import io.github.castab.fionas.commerce.http.TestNotificationQueuedResponse
import io.github.castab.fionas.commerce.http.TestNotificationResponse
import io.github.castab.fionas.commerce.notification.WEBPUSH_PRODUCER
import io.github.castab.fionas.commerce.testing.BrowserSubscription
import io.github.castab.fionas.commerce.testing.FakePushSender
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestNats
import io.github.castab.fionas.commerce.testing.drainOutbox
import io.github.castab.fionas.commerce.testing.pendingOutbox
import io.github.castab.fionas.commerce.testing.pushTransport
import io.github.castab.fionas.commerce.testing.testWebPushConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.UUID

/**
 * The milestone trace: HTTP 202 → Postgres delivery state → JetStream, and the outbox recovery
 * guarantee that a broker outage or a wrong stream never re-sends a push.
 */
class StaffNotificationEventsIntegrationSpec :
    FunSpec({
        lateinit var broker: TestNats
        val push = testWebPushConfiguration()
        beforeSpec { broker = TestNats() }
        beforeTest { broker.resetStream() }
        afterSpec { broker.close() }

        fun queueTest(app: TestApplication): String {
            val device =
                app
                    .adminPost("/staff/notifications/subscriptions", BrowserSubscription().json("Phone"))
                    .also { check(it.status == Status.CREATED) { it.bodyString() } }
                    .bodyString()
                    .let {
                        Json
                            .parseToJsonElement(it)
                            .jsonObject["subscriptionId"]!!
                            .jsonPrimitive.content
                    }
            val response =
                app.http(
                    Request(Method.POST, "/staff/notifications/test")
                        .header("Cookie", app.adminCookie)
                        .header("Origin", TEST_ORIGIN)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .body("""{"subscriptionId":"$device"}"""),
                )
            response.status shouldBe Status.ACCEPTED
            return CommerceJson.asA(response.bodyString(), TestNotificationQueuedResponse.serializer()).notificationId
        }

        fun status(
            app: TestApplication,
            id: String,
        ) = CommerceJson.asA(app.adminGet("/staff/notifications/$id").bodyString(), TestNotificationResponse.serializer())

        fun eventIds(
            app: TestApplication,
            notification: String,
        ) = app.database.strings(
            "SELECT id::text FROM commerce.outbox_events WHERE producer_id = '$NOTIFICATION_EVENT_PRODUCER' " +
                "AND aggregate_id = '$notification' ORDER BY aggregate_sequence",
        )

        test("push accepted during a NATS outage stays accepted; recovery publishes the same event ids without another push") {
            TestApplication.create(webPush = push, webPushSender = FakePushSender()).use { app ->
                val id = queueTest(app)
                val sender = FakePushSender()
                app.drainOutbox(app.pushTransport(push, sender))
                status(app, id).status shouldBe StaffNotificationStatusResponse.PUSH_ACCEPTED
                val ids = eventIds(app, id)
                ids shouldHaveSize 2

                val transports = EventTransports(null, broker.notificationConfiguration())
                val transport = checkNotNull(transports.notificationTransport)
                try {
                    broker.stop()
                    try {
                        app.drainOutbox(transport, NOTIFICATION_EVENT_PRODUCER, rounds = 2).toSet() shouldBe
                            setOf(PublicationResult.UNCONFIRMED)
                        app.pendingOutbox(NOTIFICATION_EVENT_PRODUCER) shouldBe 2L
                        status(app, id).status shouldBe StaffNotificationStatusResponse.PUSH_ACCEPTED
                    } finally {
                        broker.start()
                    }
                    app.drainOutbox(transport, NOTIFICATION_EVENT_PRODUCER, rounds = 30)
                    app.pendingOutbox(NOTIFICATION_EVENT_PRODUCER) shouldBe 0L
                } finally {
                    transport.close()
                }
                sender.requests shouldHaveSize 1
                app.pendingOutbox(WEBPUSH_PRODUCER) shouldBe 0L

                broker.admin().use { observer ->
                    val management = observer.jetStreamManagement()
                    management.getStreamInfo(FIONA_EVENTS_STREAM).streamState.msgCount shouldBe 2L
                    val messages = (1L..2L).map { management.getMessage(FIONA_EVENTS_STREAM, it) }
                    messages.map { it.subject } shouldBe
                        listOf("fionas.events.notification.queued.v1", "fionas.events.notification.push_accepted.v1")
                    messages.map { it.headers!!.getFirst("Nats-Msg-Id") } shouldBe ids
                    messages.forEach { message ->
                        message.headers!!.getFirst(PRODUCER_HEADER) shouldBe NOTIFICATION_EVENT_PRODUCER
                        message.headers!!.getFirst(AGGREGATE_ID_HEADER) shouldBe id
                        message.headers!!.getFirst("Nats-Expected-Stream") shouldBe FIONA_EVENTS_STREAM
                        val payload = message.data!!.toString(Charsets.UTF_8)
                        Json
                            .parseToJsonElement(payload)
                            .jsonObject["data"]!!
                            .jsonObject["notificationId"]!!
                            .jsonPrimitive.content shouldBe id
                    }
                    validateInquiryEventContract(messages[0].data!!.toString(Charsets.UTF_8), "StaffNotificationQueuedV1")
                    validateInquiryEventContract(messages[1].data!!.toString(Charsets.UTF_8), "StaffNotificationPushAcceptedV1")
                }
            }
        }

        test("a wrong expected stream keeps events pending; the corrected destination publishes them unchanged") {
            TestApplication.create(webPush = push, webPushSender = FakePushSender()).use { app ->
                val id = queueTest(app)
                val wrongStream = broker.notificationConfiguration(stream = "NOT_FIONAS_EVENTS")
                checkNotNull(EventTransports(null, wrongStream).notificationTransport).use {
                    app.drainOutbox(it, NOTIFICATION_EVENT_PRODUCER, rounds = 2).toSet() shouldBe setOf(PublicationResult.UNCONFIRMED)
                }
                app.pendingOutbox(NOTIFICATION_EVENT_PRODUCER) shouldBe 1L
                checkNotNull(EventTransports(null, broker.notificationConfiguration()).notificationTransport).use {
                    app.drainOutbox(it, NOTIFICATION_EVENT_PRODUCER)
                }
                app.pendingOutbox(NOTIFICATION_EVENT_PRODUCER) shouldBe 0L
                broker.admin().use { observer ->
                    observer
                        .jetStreamManagement()
                        .getMessage(FIONA_EVENTS_STREAM, 1L)
                        .headers!!
                        .getFirst("Nats-Msg-Id") shouldBe eventIds(app, id).single()
                }
            }
        }

        test("matching settings share one connection; a different destination or identity gets its own") {
            val shared = EventTransports(broker.publisherConfiguration("admin"), NotificationEventsConfiguration(publishing = true))
            shared.sharesConnection shouldBe true
            val dedicated = EventTransports(broker.publisherConfiguration(), broker.notificationConfiguration())
            dedicated.sharesConnection shouldBe false
            TestApplication.create(webPush = push, webPushSender = FakePushSender()).use { app ->
                queueTest(app)
                val inquiry = checkNotNull(shared.inquiryTransport)
                val notifications = checkNotNull(shared.notificationTransport)
                // Releasing one owner keeps the shared connection for the other.
                inquiry.close()
                app.drainOutbox(notifications, NOTIFICATION_EVENT_PRODUCER) shouldBe listOf(PublicationResult.CONFIRMED)
                notifications.close()
            }
            dedicated.inquiryTransport?.close()
            dedicated.notificationTransport?.close()
        }

        test("notification publishing without any destination fails composition") {
            val failure =
                runCatching { EventTransports(null, NotificationEventsConfiguration(publishing = true)) }.exceptionOrNull()
            failure?.message shouldBe
                "FIONAS_NOTIFICATION_EVENTS_ENABLED requires FIONAS_NOTIFICATION_NATS_URL or FIONAS_OUTBOX_PUBLISHER_ENABLED=true"
            EventTransports(null, NotificationEventsConfiguration()).notificationTransport shouldBe null
        }
    })
