package io.github.castab.fionas.commerce.notification

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.outbox.OutboxFailureCode
import io.github.castab.commerce.runtime.outbox.PublicationResult
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.events.NOTIFICATION_EVENT_PRODUCER
import io.github.castab.fionas.commerce.events.validateInquiryEventContract
import io.github.castab.fionas.commerce.http.PushDeviceListResponse
import io.github.castab.fionas.commerce.http.PushDeviceResponse
import io.github.castab.fionas.commerce.http.PushDeviceStateResponse
import io.github.castab.fionas.commerce.http.StaffNotificationConfigResponse
import io.github.castab.fionas.commerce.http.StaffNotificationStatusResponse
import io.github.castab.fionas.commerce.http.TestNotificationQueuedResponse
import io.github.castab.fionas.commerce.http.TestNotificationResponse
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.BrowserSubscription
import io.github.castab.fionas.commerce.testing.FakePushSender
import io.github.castab.fionas.commerce.testing.MutableClock
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.drainOutbox
import io.github.castab.fionas.commerce.testing.inquiryBody
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.github.castab.fionas.commerce.testing.pendingOutbox
import io.github.castab.fionas.commerce.testing.pushTransport
import io.github.castab.fionas.commerce.testing.requestedServiceJson
import io.github.castab.fionas.commerce.testing.staffMember
import io.github.castab.fionas.commerce.testing.testWebPushConfiguration
import io.github.castab.fionas.commerce.testing.withBearer
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StaffPushNotificationsSpec :
    FunSpec({
        val configuration = testWebPushConfiguration()
        val clock = MutableClock()
        lateinit var app: TestApplication
        beforeSpec { app = TestApplication.create(clock = clock, webPush = configuration, webPushSender = FakePushSender()) }
        afterSpec { app.close() }
        // Work a previous test queued but did not dispatch never reaches the next test's provider.
        beforeTest { app.drainOutbox(app.pushTransport(configuration, FakePushSender(), clock)) }

        fun call(
            method: Method,
            path: String,
            cookie: String = app.adminCookie,
            body: String = "",
            key: String? = null,
            origin: String? = TEST_ORIGIN,
        ): Response {
            var request = Request(method, path).header("Cookie", cookie).header("Content-Type", "application/json").body(body)
            origin?.let { request = request.header("Origin", it) }
            key?.let { request = request.header("Idempotency-Key", it) }
            return app.http(request)
        }

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun Response.device() = CommerceJson.asA(bodyString(), PushDeviceResponse.serializer())

        fun enroll(
            cookie: String = app.adminCookie,
            subscription: BrowserSubscription = BrowserSubscription(),
            label: String? = null,
        ) = call(Method.POST, "/staff/notifications/subscriptions", cookie, subscription.json(label))

        fun enrolled(
            cookie: String = app.adminCookie,
            subscription: BrowserSubscription = BrowserSubscription(),
        ): String {
            val response = enroll(cookie, subscription)
            check(response.status == Status.CREATED) { response.bodyString() }
            return response.device().subscriptionId
        }

        fun sendTest(
            device: String,
            key: String = UUID.randomUUID().toString(),
            cookie: String = app.adminCookie,
        ) = call(Method.POST, "/staff/notifications/test", cookie, """{"subscriptionId":"$device"}""", key)

        fun queued(response: Response): String {
            check(response.status == Status.ACCEPTED) { "${response.status} ${response.bodyString()}" }
            return CommerceJson.asA(response.bodyString(), TestNotificationQueuedResponse.serializer()).notificationId
        }

        fun status(
            id: String,
            cookie: String = app.adminCookie,
        ): TestNotificationResponse {
            val response = call(Method.GET, "/staff/notifications/$id", cookie)
            check(response.status == Status.OK) { "${response.status} ${response.bodyString()}" }
            return CommerceJson.asA(response.bodyString(), TestNotificationResponse.serializer())
        }

        fun deliveries(notification: String) =
            app.database
                .strings("SELECT count(*)::text FROM fionas.staff_notification_deliveries WHERE notification_id = '$notification'")
                .single()
                .toInt()

        fun events(notification: String): List<String> =
            app.database.strings(
                "SELECT payload::text FROM commerce.outbox_events WHERE producer_id = '$NOTIFICATION_EVENT_PRODUCER' " +
                    "AND aggregate_id = '$notification' ORDER BY aggregate_sequence",
            )

        fun eventTypes(notification: String) =
            events(notification).map {
                Json
                    .parseToJsonElement(it)
                    .jsonObject["type"]!!
                    .jsonPrimitive.content
            }

        test("setup is readable before enrollment, and the disabled feature answers enabled=false and 503 elsewhere") {
            val config = call(Method.GET, "/staff/notifications/config")
            config.status shouldBe Status.OK
            config.header("Cache-Control") shouldBe "no-store"
            CommerceJson.asA(config.bodyString(), StaffNotificationConfigResponse.serializer()) shouldBe
                StaffNotificationConfigResponse(true, configuration.applicationServerKey)

            TestApplication.create().use { disabled ->
                val response =
                    disabled.http(
                        Request(Method.GET, "/staff/notifications/config").header("Cookie", disabled.adminCookie),
                    )
                CommerceJson.asA(response.bodyString(), StaffNotificationConfigResponse.serializer()) shouldBe
                    StaffNotificationConfigResponse(false)
                response.bodyString() shouldNotContain "vapidPublicKey"
                val enroll =
                    disabled.http(
                        Request(Method.POST, "/staff/notifications/subscriptions")
                            .header("Cookie", disabled.adminCookie)
                            .header("Origin", TEST_ORIGIN)
                            .body(BrowserSubscription().json()),
                    )
                enroll.status shouldBe Status.SERVICE_UNAVAILABLE
                enroll.error().code shouldBe "push_notifications_disabled"
            }
        }

        test("enrollment validates the endpoint, keys, principal kind and browser origin") {
            val good = BrowserSubscription()
            enroll(subscription = good.copy(endpoint = "http://fcm.googleapis.com/fcm/send/x")).status shouldBe
                Status.UNPROCESSABLE_ENTITY
            enroll(subscription = good.copy(endpoint = "https://push.attacker.example/x")).status shouldBe Status.UNPROCESSABLE_ENTITY
            enroll(subscription = good.copy(endpoint = "https://user:pw@fcm.googleapis.com/x")).status shouldBe
                Status.UNPROCESSABLE_ENTITY
            enroll(subscription = good.copy(p256dh = "AAAA")).status shouldBe Status.UNPROCESSABLE_ENTITY
            enroll(subscription = good.copy(auth = "AAAA")).status shouldBe Status.UNPROCESSABLE_ENTITY
            call(Method.POST, "/staff/notifications/subscriptions", body = good.json(), origin = null).status shouldBe Status.FORBIDDEN
            call(Method.POST, "/staff/notifications/subscriptions", body = "{").status shouldBe Status.BAD_REQUEST
            val service = app.provisionService("push-service", setOf(FionaPermissions.InquiriesRead))
            app
                .http(
                    Request(Method.POST, "/staff/notifications/subscriptions")
                        .withBearer(app.serviceToken(service))
                        .body(good.json()),
                ).status shouldBe Status.FORBIDDEN
            app.database.count("fionas.staff_push_subscriptions") shouldBe 0
        }

        test("re-enrolling the same browser keeps the device id; responses never echo endpoints or keys") {
            val subscription = BrowserSubscription()
            val first = enroll(subscription = subscription, label = "Front desk")
            first.status shouldBe Status.CREATED
            first.bodyString() shouldNotContain subscription.endpoint
            first.bodyString() shouldNotContain subscription.p256dh
            val id = first.device().subscriptionId
            val again = enroll(subscription = subscription.copy(p256dh = BrowserSubscription().p256dh), label = "  Kitchen  ")
            again.status shouldBe Status.OK
            again.device().subscriptionId shouldBe id
            again.device().label shouldBe "Kitchen"
            again.device().service shouldBe "FCM"
            app.database
                .strings("SELECT registration_version::text FROM fionas.staff_push_subscriptions WHERE id = '$id'")
                .single() shouldBe "2"
        }

        test("an endpoint enrolled for another staff member is a conflict that never discloses the owner") {
            val subscription = BrowserSubscription()
            enrolled(subscription = subscription)
            val (otherId, other) = app.staffMember()
            val response = enroll(other, subscription)
            response.status shouldBe Status.CONFLICT
            response.error().code shouldBe "push_endpoint_in_use"
            response.bodyString() shouldNotContain otherId.value.toString()
        }

        test("two staff members enrolling the same endpoint concurrently: exactly one owns it") {
            val subscription = BrowserSubscription()
            val cookies = listOf(app.staffMember().second, app.staffMember().second)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures =
                    cookies.map { cookie ->
                        pool.submit<Status> {
                            start.await()
                            enroll(cookie, subscription).status
                        }
                    }
                start.countDown()
                futures.map { it.get(30, TimeUnit.SECONDS) } shouldContainExactlyInAnyOrder listOf(Status.CREATED, Status.CONFLICT)
            } finally {
                pool.shutdownNow()
            }
            app.database
                .strings(
                    "SELECT count(*)::text FROM fionas.staff_push_subscriptions WHERE endpoint_sha256 = " +
                        "'${sha256Hex(subscription.endpoint)}' AND retired_at IS NULL",
                ).single() shouldBe "1"
        }

        test("devices and tests are private: another staff member cannot list, revoke, send to or read them") {
            val (_, mine) = app.staffMember()
            val (_, theirs) = app.staffMember()
            val device = enrolled(mine)
            val notification = queued(sendTest(device, cookie = mine))

            CommerceJson
                .asA(call(Method.GET, "/staff/notifications/subscriptions", theirs).bodyString(), PushDeviceListResponse.serializer())
                .devices shouldBe emptyList()
            call(Method.DELETE, "/staff/notifications/subscriptions/$device", theirs).status shouldBe Status.NOT_FOUND
            sendTest(device, cookie = theirs).status shouldBe Status.NOT_FOUND
            call(Method.GET, "/staff/notifications/$notification", theirs).status shouldBe Status.NOT_FOUND
            call(Method.GET, "/staff/notifications/${UUID.randomUUID()}", theirs).status shouldBe Status.NOT_FOUND
            call(Method.GET, "/staff/notifications/not-a-uuid", theirs).status shouldBe Status.BAD_REQUEST

            val list =
                CommerceJson.asA(
                    call(Method.GET, "/staff/notifications/subscriptions", mine).bodyString(),
                    PushDeviceListResponse.serializer(),
                )
            list.devices.map { it.subscriptionId } shouldBe listOf(device)
        }

        test("a test replay keeps its notification id and single delivery, even after the device is removed") {
            val (_, cookie) = app.staffMember()
            val device = enrolled(cookie)
            val other = enrolled(cookie)
            val key = "test-${UUID.randomUUID()}"
            val response = sendTest(device, key, cookie)
            response.header("Cache-Control") shouldBe "no-store"
            val id = queued(response)
            CommerceJson.asA(response.bodyString(), TestNotificationQueuedResponse.serializer()).status shouldBe
                StaffNotificationStatusResponse.QUEUED
            queued(sendTest(device, key, cookie)) shouldBe id

            val reused = sendTest(other, key, cookie)
            reused.status shouldBe Status.CONFLICT
            reused.error().code shouldBe "IDEMPOTENCY_KEY_REUSED"

            call(Method.DELETE, "/staff/notifications/subscriptions/$device", cookie).status shouldBe Status.NO_CONTENT
            call(Method.DELETE, "/staff/notifications/subscriptions/$device", cookie).status shouldBe Status.NO_CONTENT
            queued(sendTest(device, key, cookie)) shouldBe id
            deliveries(id) shouldBe 1
            eventTypes(id) shouldBe listOf("fionas.notification.queued")

            // A fresh request for the removed device queues nothing.
            sendTest(device, cookie = cookie).status shouldBe Status.NOT_FOUND
            sendTest(device, "", cookie).status shouldBe Status.BAD_REQUEST
        }

        test("removal before dispatch prevents the provider call and reports skipped") {
            val sender = FakePushSender()
            val device = enrolled()
            val id = queued(sendTest(device))
            call(Method.DELETE, "/staff/notifications/subscriptions/$device").status shouldBe Status.NO_CONTENT
            app.drainOutbox(app.pushTransport(configuration, sender, clock))
            sender.requests shouldHaveSize 0
            status(id).status shouldBe StaffNotificationStatusResponse.SKIPPED
            status(id).failureReason shouldBe "subscription_revoked"
            eventTypes(id) shouldBe listOf("fionas.notification.queued")
        }

        test("provider acceptance records push_accepted with a schema-valid outcome event and server-defined copy") {
            val sender = FakePushSender()
            val device = enrolled()
            val id = queued(sendTest(device))
            status(id).status shouldBe StaffNotificationStatusResponse.QUEUED
            app.drainOutbox(app.pushTransport(configuration, sender, clock))
            sender.requests shouldHaveSize 1
            val payload = Json.parseToJsonElement(sender.requests.single().payload).jsonObject
            payload["notificationId"]!!.jsonPrimitive.content shouldBe id
            payload["title"]!!.jsonPrimitive.content shouldBe "Fiona’s test notification"
            payload["body"]!!.jsonPrimitive.content shouldBe "Push notifications are working on this device."
            payload["kind"]!!.jsonPrimitive.content shouldBe "test"
            sender.requests.single().topic shouldBe id.replace("-", "")
            with(status(id)) {
                status shouldBe StaffNotificationStatusResponse.PUSH_ACCEPTED
                attempts shouldBe 1
                providerCode shouldBe 201
                failureReason shouldBe null
                subscriptionId shouldBe device
            }
            val published = events(id)
            published shouldHaveSize 2
            validateInquiryEventContract(published[0], "StaffNotificationQueuedV1")
            validateInquiryEventContract(published[1], "StaffNotificationPushAcceptedV1")
            published.joinToString() shouldNotContain "fcm.googleapis.com"
        }

        test("404/410 retires the registration the attempt used, but preserves a newer registration") {
            val gone = FakePushSender(PushResult.Gone(410))
            val device = enrolled()
            val id = queued(sendTest(device))
            app.drainOutbox(app.pushTransport(configuration, gone, clock))
            with(status(id)) {
                status shouldBe StaffNotificationStatusResponse.PUSH_FAILED
                failureReason shouldBe "subscription_expired"
                providerCode shouldBe 410
            }
            val list =
                CommerceJson.asA(
                    call(Method.GET, "/staff/notifications/subscriptions").bodyString(),
                    PushDeviceListResponse.serializer(),
                )
            list.devices.single { it.subscriptionId == device }.state shouldBe PushDeviceStateResponse.EXPIRED
            validateInquiryEventContract(events(id).last(), "StaffNotificationPushFailedV1")

            // The browser re-subscribes with new keys while the provider request is in flight.
            val subscription = BrowserSubscription()
            val refreshed = enrolled(subscription = subscription)
            val racing = FakePushSender(PushResult.Gone(404))
            racing.onSend = { enroll(subscription = subscription.copy(p256dh = BrowserSubscription().p256dh)).status shouldBe Status.OK }
            val second = queued(sendTest(refreshed))
            app.drainOutbox(app.pushTransport(configuration, racing, clock))
            status(second).failureReason shouldBe "subscription_expired"
            val after =
                CommerceJson.asA(
                    call(Method.GET, "/staff/notifications/subscriptions").bodyString(),
                    PushDeviceListResponse.serializer(),
                )
            after.devices.single { it.subscriptionId == refreshed }.state shouldBe PushDeviceStateResponse.ACTIVE
        }

        test("a transient provider failure retries only that delivery and reports retrying until accepted") {
            val sender = FakePushSender()
            sender.next(PushResult.Retryable(DeliveryReason.PROVIDER_UNAVAILABLE, 503, null))
            val id = queued(sendTest(enrolled()))
            val transport = app.pushTransport(configuration, sender, clock)
            app.drainOutbox(transport, rounds = 1) shouldBe listOf(PublicationResult.UNCONFIRMED)
            with(status(id)) {
                status shouldBe StaffNotificationStatusResponse.RETRYING
                failureReason shouldBe "provider_unavailable"
                providerCode shouldBe 503
            }
            app.drainOutbox(transport)
            with(status(id)) {
                status shouldBe StaffNotificationStatusResponse.PUSH_ACCEPTED
                attempts shouldBe 2
                failureReason shouldBe null
            }
            eventTypes(id) shouldBe listOf("fionas.notification.queued", "fionas.notification.push_accepted")
        }

        test("Retry-After is a minimum delay: no provider call before it, one after") {
            val sender = FakePushSender()
            sender.next(PushResult.Retryable(DeliveryReason.PROVIDER_THROTTLED, 429, Duration.ofSeconds(120)))
            val id = queued(sendTest(enrolled()))
            val transport = app.pushTransport(configuration, sender, clock)
            app.drainOutbox(transport, rounds = 4)
            sender.requests shouldHaveSize 1
            status(id).failureReason shouldBe "provider_throttled"
            clock.advance(Duration.ofSeconds(121))
            try {
                app.drainOutbox(transport)
            } finally {
                clock.advance(Duration.ofSeconds(-121))
            }
            sender.requests shouldHaveSize 2
            status(id).status shouldBe StaffNotificationStatusResponse.PUSH_ACCEPTED
        }

        test("a Retry-After beyond the delivery window fails now with the transient reason") {
            val sender = FakePushSender(PushResult.Retryable(DeliveryReason.PROVIDER_THROTTLED, 429, Duration.ofHours(2)))
            val id = queued(sendTest(enrolled()))
            app.drainOutbox(app.pushTransport(configuration, sender, clock))
            sender.requests shouldHaveSize 1
            status(id).status shouldBe StaffNotificationStatusResponse.PUSH_FAILED
            status(id).failureReason shouldBe "provider_throttled"
        }

        test("uncertain outcomes retry under the same notification id and give up as provider_outcome_unknown") {
            val sender = FakePushSender(PushResult.Unknown)
            val id = queued(sendTest(enrolled()))
            val transport = app.pushTransport(configuration, sender, clock)
            app.drainOutbox(transport, rounds = 2)
            with(status(id)) {
                status shouldBe StaffNotificationStatusResponse.RETRYING
                failureReason shouldBe "provider_outcome_unknown"
            }
            sender.requests
                .map {
                    Json
                        .parseToJsonElement(
                            it.payload,
                        ).jsonObject["notificationId"]!!
                        .jsonPrimitive.content
                }.toSet() shouldBe
                setOf(id)
            clock.advance(TEST_DELIVERY_WINDOW)
            try {
                app.drainOutbox(transport)
            } finally {
                clock.advance(TEST_DELIVERY_WINDOW.negated())
            }
            with(status(id)) {
                status shouldBe StaffNotificationStatusResponse.PUSH_FAILED
                failureReason shouldBe "provider_outcome_unknown"
                providerCode shouldBe null
            }
            val failed = Json.parseToJsonElement(events(id).last()).jsonObject
            failed["data"]!!.jsonObject["reason"]!!.jsonPrimitive.content shouldBe "provider_outcome_unknown"
            failed["data"]!!.jsonObject["attempts"]!!.jsonPrimitive.int shouldBe sender.requests.size
        }

        test("a restarted worker recovers pending work, including an attempt whose process died mid-send") {
            val id = queued(sendTest(enrolled()))

            class Crash : Error()
            val dying = FakePushSender()
            dying.onSend = { throw Crash() }
            val first = app.context.outbox.publisher(WEBPUSH_PRODUCER, "worker-a")
            val claim = first.claim(10, Duration.ofSeconds(1)).single()
            shouldThrow<Crash> { app.pushTransport(configuration, dying, clock).publish(claim.record) }
            // The process is gone: its claim is never confirmed or failed, and its attempt intent is committed.
            status(id).attempts shouldBe 1
            Thread.sleep(1_200)

            val restarted = FakePushSender()
            // Within the in-flight grace the new worker waits instead of sending a second copy.
            app.drainOutbox(app.pushTransport(configuration, restarted, clock), rounds = 1) shouldBe listOf(PublicationResult.UNCONFIRMED)
            restarted.requests shouldHaveSize 0
            clock.advance(Duration.ofMinutes(1))
            try {
                app.drainOutbox(app.pushTransport(configuration, restarted, clock))
            } finally {
                clock.advance(Duration.ofMinutes(-1))
            }
            restarted.requests shouldHaveSize 1
            with(status(id)) {
                status shouldBe StaffNotificationStatusResponse.PUSH_ACCEPTED
                attempts shouldBe 2
            }
            app.pendingOutbox(WEBPUSH_PRODUCER) shouldBe 0
            first.fail(claim, OutboxFailureCode.TIMEOUT, Duration.ofMillis(1)) shouldBe false
        }

        test("a stale attempt cannot overwrite the outcome a newer attempt recorded") {
            val id = queued(sendTest(enrolled()))
            val transport = app.pushTransport(configuration, FakePushSender(), clock)
            val slow = FakePushSender(PushResult.Gone(410))
            slow.onSend = {
                // The slow attempt outlives its grace; a newer worker delivers meanwhile.
                clock.advance(Duration.ofMinutes(1))
                transport.deliver(StaffDeliveryId(UUID.fromString(deliveryOf(app, id)))) shouldBe PublicationResult.CONFIRMED
            }
            try {
                app.pushTransport(configuration, slow, clock).deliver(StaffDeliveryId(UUID.fromString(deliveryOf(app, id)))) shouldBe
                    PublicationResult.CONFIRMED
            } finally {
                clock.advance(Duration.ofMinutes(-1))
            }
            status(id).status shouldBe StaffNotificationStatusResponse.PUSH_ACCEPTED
            eventTypes(id) shouldBe listOf("fionas.notification.queued", "fionas.notification.push_accepted")
        }
    })

private fun deliveryOf(
    app: TestApplication,
    notification: String,
) = app.database.strings("SELECT id::text FROM fionas.staff_notification_deliveries WHERE notification_id = '$notification'").single()

class StaffInquiryNotificationsSpec :
    FunSpec({
        val configuration = testWebPushConfiguration(inquiryNotifications = true)
        lateinit var app: TestApplication
        beforeSpec { app = TestApplication.create(webPush = configuration, webPushSender = FakePushSender()) }
        afterSpec { app.close() }
        beforeTest { app.drainOutbox(app.pushTransport(configuration, FakePushSender())) }

        fun jobs() =
            app.database
                .strings("SELECT count(*)::text FROM fionas.staff_notifications WHERE kind = 'NEW_INQUIRY'")
                .single()
                .toInt()

        fun enroll(
            cookie: String,
            subscription: BrowserSubscription = BrowserSubscription(),
        ): String {
            val response =
                app.http(
                    Request(Method.POST, "/staff/notifications/subscriptions")
                        .header("Cookie", cookie)
                        .header("Origin", TEST_ORIGIN)
                        .body(subscription.json()),
                )
            check(response.status == Status.CREATED) { response.bodyString() }
            return CommerceJson.asA(response.bodyString(), PushDeviceResponse.serializer()).subscriptionId
        }

        fun deliveryStates(inquiry: String): Map<String, String> =
            app.database
                .strings(
                    "SELECT d.subscription_id::text || '=' || d.state || ':' || coalesce(d.outcome_reason, '') " +
                        "FROM fionas.staff_notification_deliveries d JOIN fionas.staff_notifications n ON n.id = d.notification_id " +
                        "WHERE n.inquiry_id = '$inquiry'",
                ).associate { it.substringBefore('=') to it.substringAfter('=') }

        fun queuedCount(inquiry: String): Int {
            val notification =
                app.database.strings("SELECT id::text FROM fionas.staff_notifications WHERE inquiry_id = '$inquiry'").single()
            val payload =
                app.database
                    .strings(
                        "SELECT payload::text FROM commerce.outbox_events WHERE producer_id = '$NOTIFICATION_EVENT_PRODUCER' " +
                            "AND aggregate_id = '$notification' AND event_type = 'fionas.notification.queued'",
                    ).single()
            validateInquiryEventContract(payload, "StaffNotificationQueuedV1")
            return Json
                .parseToJsonElement(payload)
                .jsonObject["data"]!!
                .jsonObject["deliveryCount"]!!
                .jsonPrimitive.int
        }

        test("a replayed submission creates one job; eligible devices get minimal copy, others nothing") {
            val (_, reader) = app.staffMember(setOf(FionaPermissions.InquiriesRead))
            val (_, outsider) = app.staffMember()
            val readerDevice = enroll(reader)
            val readerSecond = enroll(reader)
            val outsiderDevice = enroll(outsider)
            val key = UUID.randomUUID().toString()
            val email = "push-${UUID.randomUUID()}@example.com"

            fun submit() =
                app.http(
                    Request(Method.POST, "/inquiries")
                        .withSubmissionKey(key)
                        .asFionasWeb(app)
                        .header("Content-Type", "application/json")
                        .body(inquiryBody(email, acceptanceLines(), requestedServiceJson())),
                )
            val before = jobs()
            val first = submit()
            first.status shouldBe Status.CREATED
            submit().status shouldBe Status.CREATED
            jobs() shouldBe before + 1
            val inquiry = checkNotNull(first.header("Location")).substringAfterLast('/')

            val sender = FakePushSender()
            app.drainOutbox(app.pushTransport(configuration, sender))
            queuedCount(inquiry) shouldBe 2
            deliveryStates(inquiry).keys shouldBe setOf(readerDevice, readerSecond)
            deliveryStates(inquiry).values.toSet() shouldBe setOf("PUSH_ACCEPTED:")
            outsiderDevice shouldNotContainIn deliveryStates(inquiry).keys
            sender.requests shouldHaveSize 2
            sender.requests.forEach { request ->
                request.urgentDelivery shouldBe true
                val payload = request.payload
                payload shouldNotContain email
                payload shouldNotContain "Jane"
                val json = Json.parseToJsonElement(payload).jsonObject
                json["title"]!!.jsonPrimitive.content shouldBe "New catering inquiry"
                json["kind"]!!.jsonPrimitive.content shouldBe "new_inquiry"
                json["url"]!!.jsonPrimitive.content shouldBe "/requests/$inquiry"
            }
        }

        test("one device's retry leaves the other's completed delivery alone") {
            val (_, reader) = app.staffMember(setOf(FionaPermissions.InquiriesRead))
            val flaky = BrowserSubscription()
            val flakyDevice = enroll(reader, flaky)
            val steadyDevice = enroll(reader)
            val inquiry = app.createInquiry()
            val sender =
                object : WebPushSender {
                    var flakyCalls = 0

                    override fun send(request: PushRequest): PushResult =
                        if (request.endpoint.value == flaky.endpoint && flakyCalls++ == 0) {
                            PushResult.Retryable(DeliveryReason.TRANSPORT_FAILURE, null, null)
                        } else {
                            PushResult.Accepted(201)
                        }
                }
            val transport = app.pushTransport(configuration, sender)
            // Round one fans out; round two attempts both devices once.
            app.drainOutbox(transport, rounds = 2)
            val steadyCompleted =
                app.database.strings(
                    "SELECT coalesce(completed_at::text, '') FROM fionas.staff_notification_deliveries WHERE subscription_id = '$steadyDevice'",
                )
            deliveryStates(inquiry)[steadyDevice] shouldBe "PUSH_ACCEPTED:"
            deliveryStates(inquiry)[flakyDevice] shouldBe "RETRYING:transport_failure"
            app.drainOutbox(transport)
            deliveryStates(inquiry)[flakyDevice] shouldBe "PUSH_ACCEPTED:"
            app.database.strings(
                "SELECT coalesce(completed_at::text, '') FROM fionas.staff_notification_deliveries WHERE subscription_id = '$steadyDevice'",
            ) shouldBe steadyCompleted
        }

        fun retireAll() =
            app.database.execute(
                "UPDATE fionas.staff_push_subscriptions SET retired_at = now(), retirement = 'REVOKED', endpoint = NULL, " +
                    "p256dh = NULL, auth = NULL WHERE retired_at IS NULL",
            )

        test("eligibility is rechecked before dispatch, and a job without eligible devices completes without retry") {
            retireAll()
            val (readerId, reader) = app.staffMember(setOf(FionaPermissions.InquiriesRead))
            val device = enroll(reader)
            val inquiry = app.createInquiry()
            val sender = FakePushSender()
            val transport = app.pushTransport(configuration, sender)
            // Fan out only: the delivery waits while the reader loses the permission.
            val publisher = app.context.outbox.publisher(WEBPUSH_PRODUCER, "fanout-only")
            val fanOut = publisher.claim(10, Duration.ofSeconds(60)).single { it.record.event.eventType == WEBPUSH_FANOUT }
            transport.publish(fanOut.record) shouldBe PublicationResult.CONFIRMED
            publisher.confirm(fanOut)
            app.authorization.replaceRolePermissions(RoleKey("test.push-${readerId.value}"), emptySet())
            app.drainOutbox(transport)
            sender.requests shouldHaveSize 0
            deliveryStates(inquiry)[device] shouldBe "SKIPPED:recipient_not_eligible"

            // Nobody eligible is enrolled now: the next job fans out to zero deliveries and is done.
            retireAll()
            val empty = app.createInquiry()
            app.drainOutbox(transport) shouldBe listOf(PublicationResult.CONFIRMED)
            queuedCount(empty) shouldBe 0
            deliveryStates(empty) shouldBe emptyMap()
            app.pendingOutbox(WEBPUSH_PRODUCER) shouldBe 0
        }

        test("the job commits or rolls back with its inquiry, and the trigger off records nothing") {
            val before = jobs()
            val failing =
                app.createInquiryOperation(notifyStaff = { transaction, inquiry ->
                    StaffInquiryNotifications(JdbiStaffPushRepository(), app.context.outbox).enqueue(transaction, inquiry)
                    error("fails after the job was recorded")
                })
            shouldThrow<IllegalStateException> { failing(app.inquiryCommand()) }
            jobs() shouldBe before

            TestApplication.create(webPush = testWebPushConfiguration(inquiryNotifications = false)).use { off ->
                off.createInquiry()
                off.database.count("fionas.staff_notifications") shouldBe 0
                off.pendingOutbox(WEBPUSH_PRODUCER) shouldBe 0
            }
            TestApplication.create().use { disabled ->
                disabled.createInquiry()
                disabled.database.count("fionas.staff_notifications") shouldBe 0
            }
        }
    })

private infix fun String.shouldNotContainIn(keys: Set<String>) = (this in keys) shouldBe false
