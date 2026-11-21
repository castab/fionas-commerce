package io.github.castab.fionas.commerce.notification

import io.github.castab.fionas.commerce.events.NatsConnectionConfiguration
import io.github.castab.fionas.commerce.events.NotificationEventsConfiguration
import io.github.castab.fionas.commerce.testing.BrowserSubscription
import io.github.castab.fionas.commerce.testing.TEST_INSTANT
import io.github.castab.fionas.commerce.testing.testWebPushConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.ZoneId
import java.util.Base64

class WebPushConfigurationSpec :
    FunSpec({
        fun keyPair(): Pair<String, String> {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val d =
                (pair.private as ECPrivateKey)
                    .s
                    .toByteArray()
                    .dropWhile { it == 0.toByte() }
                    .toByteArray()
            val private = ByteArray(32 - d.size) + d
            val public = WebPushConfiguration.uncompressed(pair.public as ECPublicKey)
            val encoder = Base64.getUrlEncoder().withoutPadding()
            return encoder.encodeToString(private) to encoder.encodeToString(public)
        }

        test("disabled by default; reminders cannot be on without push; the retired inquiry trigger is refused") {
            WebPushConfiguration.fromEnvironment(emptyMap()) shouldBe null
            WebPushConfiguration.fromEnvironment(mapOf("FIONAS_PUSH_ENABLED" to "false")) shouldBe null
            WebPushConfiguration.fromEnvironment(mapOf("FIONAS_PUSH_INQUIRY_NOTIFICATIONS_ENABLED" to "false")) shouldBe null
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(mapOf("FIONAS_PUSH_UNANSWERED_REMINDERS_ENABLED" to "true"))
            }
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(mapOf("FIONAS_PUSH_INQUIRY_NOTIFICATIONS_ENABLED" to "true"))
            }.message shouldContain "FIONAS_PUSH_UNANSWERED_REMINDERS_ENABLED"
            shouldThrow<IllegalStateException> { WebPushConfiguration.fromEnvironment(mapOf("FIONAS_PUSH_ENABLED" to "yes")) }
        }

        test("raw base64url VAPID keys load, must form a pair, and never render") {
            val (private, public) = keyPair()
            val environment =
                mapOf(
                    "FIONAS_PUSH_ENABLED" to "true",
                    "FIONAS_PUSH_VAPID_PRIVATE_KEY" to private,
                    "FIONAS_PUSH_VAPID_PUBLIC_KEY" to public,
                    "FIONAS_PUSH_VAPID_SUBJECT" to "mailto:ops@fionas.example",
                )
            val configuration = checkNotNull(WebPushConfiguration.fromEnvironment(environment))
            configuration.applicationServerKey shouldBe public
            configuration.unansweredReminders shouldBe null
            configuration.toString() shouldNotContain private
            val (_, otherPublic) = keyPair()
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(environment + ("FIONAS_PUSH_VAPID_PUBLIC_KEY" to otherPublic))
            }.message shouldContain "not a pair"
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(environment + ("FIONAS_PUSH_VAPID_PRIVATE_KEY_FILE" to "/nope"))
            }
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(environment + ("FIONAS_PUSH_VAPID_SUBJECT" to "ops@fionas.example"))
            }
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(environment + ("FIONAS_PUSH_SEND_TIMEOUT_MS" to "60000"))
            }
            val reminders = environment + ("FIONAS_PUSH_UNANSWERED_REMINDERS_ENABLED" to "true")
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(reminders + ("FIONAS_PUSH_UNANSWERED_REMINDER_PATH" to "https://evil.example/"))
            }
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(reminders + ("FIONAS_PUSH_UNANSWERED_REMINDER_PATH" to "//evil.example/"))
            }
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(reminders + ("FIONAS_PUSH_UNANSWERED_REMINDER_SCHEDULE" to ""))
            }.message shouldContain "FIONAS_PUSH_UNANSWERED_REMINDER_SCHEDULE"
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(reminders + ("FIONAS_PUSH_UNANSWERED_REMINDER_SCHEDULE" to "0 25 * * *"))
            }
            shouldThrow<IllegalArgumentException> {
                WebPushConfiguration.fromEnvironment(reminders + ("FIONAS_PUSH_UNANSWERED_REMINDER_TIME_ZONE" to "Pacific"))
            }.message shouldContain "FIONAS_PUSH_UNANSWERED_REMINDER_TIME_ZONE"
            val defaults = checkNotNull(checkNotNull(WebPushConfiguration.fromEnvironment(reminders)).unansweredReminders)
            defaults.schedule.expression shouldBe "0 10,14 * * MON-FRI"
            defaults.schedule.zone shouldBe ZoneId.of("America/Los_Angeles")
            defaults.path shouldBe "/"
            val custom =
                checkNotNull(
                    WebPushConfiguration.fromEnvironment(
                        reminders +
                            mapOf(
                                "FIONAS_PUSH_ALLOWED_HOSTS" to "*.push.example.com",
                                "FIONAS_PUSH_UNANSWERED_REMINDER_SCHEDULE" to "30 9 * * *",
                                "FIONAS_PUSH_UNANSWERED_REMINDER_TIME_ZONE" to "America/New_York",
                                "FIONAS_PUSH_UNANSWERED_REMINDER_PATH" to "/requests",
                            ),
                    ),
                )
            custom.allowedHosts.allows("eu.push.example.com") shouldBe true
            custom.allowedHosts.allows("push.example.com") shouldBe false
            val settings = checkNotNull(custom.unansweredReminders)
            settings.schedule.expression shouldBe "30 9 * * *"
            settings.schedule.zone shouldBe ZoneId.of("America/New_York")
            settings.path shouldBe "/requests"
        }

        test("endpoints must be https on an allowed push service; keys must be real subscription keys") {
            val allowed = PushHostAllowList.DEFAULT
            PushEndpoint.of("https://fcm.googleapis.com/fcm/send/abc", allowed).service shouldBe PushService.FCM
            PushEndpoint.of("https://updates.push.services.mozilla.com/wpush/v2/abc", allowed).service shouldBe PushService.MOZILLA
            PushEndpoint.of("https://wns2-par02p.notify.windows.com/w/?token=abc", allowed).service shouldBe PushService.WINDOWS
            PushEndpoint.of("https://web.push.apple.com/QGuQ", allowed).service shouldBe PushService.APPLE
            listOf(
                "http://fcm.googleapis.com/fcm/send/abc",
                "https://fcm.googleapis.com.evil.example/x",
                "https://evilfcm.googleapis.com/x",
                "https://fcm.googleapis.com:8443/x",
                "https://u:p@fcm.googleapis.com/x",
                "https://127.0.0.1/x",
                "https://notify.windows.com/x",
                "https://fcm.googleapis.com/x#fragment",
                "https://fcm.googleapis.com/" + "a".repeat(2048),
            ).forEach { shouldThrow<IllegalArgumentException> { PushEndpoint.of(it, allowed) } }
            PushEndpoint.of("https://fcm.googleapis.com/fcm/send/abc", allowed).toString() shouldBe "PushEndpoint([redacted])"
            val browser = BrowserSubscription()
            PushKeys.of(browser.p256dh, browser.auth).toString() shouldBe "PushKeys([redacted])"
            PushKeys.of(browser.p256dh + "=", browser.auth + "==").p256dh shouldBe browser.p256dh
            shouldThrow<IllegalArgumentException> { PushKeys.of(browser.auth, browser.auth) }
            shouldThrow<IllegalArgumentException> { PushKeys.of(browser.p256dh, browser.p256dh) }
        }

        test("provider answers map to delivery outcomes; Retry-After accepts seconds and HTTP dates") {
            JdkWebPushSender.classify(201, null, TEST_INSTANT) shouldBe PushResult.Accepted(201)
            JdkWebPushSender.classify(410, null, TEST_INSTANT) shouldBe PushResult.Gone(410)
            JdkWebPushSender.classify(404, null, TEST_INSTANT) shouldBe PushResult.Gone(404)
            JdkWebPushSender.classify(400, null, TEST_INSTANT) shouldBe PushResult.Rejected(400)
            JdkWebPushSender.classify(301, null, TEST_INSTANT) shouldBe PushResult.Rejected(301)
            JdkWebPushSender.classify(429, "30", TEST_INSTANT) shouldBe
                PushResult.Retryable(DeliveryReason.PROVIDER_THROTTLED, 429, Duration.ofSeconds(30))
            JdkWebPushSender.classify(503, "Sat, 26 Sep 2026 18:32:00 GMT", TEST_INSTANT.minusNanos(TEST_INSTANT.nano.toLong())) shouldBe
                PushResult.Retryable(DeliveryReason.PROVIDER_UNAVAILABLE, 503, Duration.ofSeconds(120))
            JdkWebPushSender.classify(503, "soon", TEST_INSTANT) shouldBe
                PushResult.Retryable(DeliveryReason.PROVIDER_UNAVAILABLE, 503, null)
        }

        test("the prepared request is VAPID-signed, encrypted and carries TTL, urgency and topic; disallowed hosts are never called") {
            val configuration = testWebPushConfiguration()
            val sender = JdkWebPushSender(configuration) { TEST_INSTANT }
            val browser = BrowserSubscription()
            val request =
                PushRequest(
                    PushEndpoint.of(browser.endpoint, PushHostAllowList.DEFAULT),
                    PushKeys.of(browser.p256dh, browser.auth),
                    """{"title":"t"}""",
                    Duration.ofMinutes(10),
                    true,
                    "1a5bd3fd6a0a4d2e9a7c3f8f0c2d9e41",
                )
            val http = sender.prepare(request)
            http.method() shouldBe "POST"
            http.uri().toString() shouldBe browser.endpoint
            http.headers().firstValue("Authorization").get() shouldContain "vapid t="
            http.headers().firstValue("Content-Encoding").get() shouldBe "aes128gcm"
            http.headers().firstValue("TTL").get() shouldBe "600"
            http.headers().firstValue("Urgency").get() shouldBe "high"
            http.headers().firstValue("Topic").get() shouldBe "1a5bd3fd6a0a4d2e9a7c3f8f0c2d9e41"
            http.timeout().get() shouldBe configuration.sendTimeout
            request.toString() shouldBe "PushRequest([redacted])"

            val narrowed =
                JdkWebPushSender(
                    WebPushConfiguration(
                        configuration.vapidPrivateKey,
                        configuration.vapidPublicKey,
                        configuration.subject,
                        PushHostAllowList(listOf("web.push.apple.com")),
                    ),
                )
            narrowed.send(request).shouldBeInstanceOf<PushResult.EndpointNotAllowed>()
        }

        test("NATS servers may be a list of one scheme; notification event settings default to the inquiry stream") {
            NatsConnectionConfiguration
                .fromEnvironment(mapOf("X_URL" to "nats://a.internal:4222, nats://b.internal:4222"), "X")
                .servers shouldBe listOf("nats://a.internal:4222", "nats://b.internal:4222")
            shouldThrow<IllegalArgumentException> {
                NatsConnectionConfiguration.fromEnvironment(mapOf("X_URL" to "nats://a:4222,tls://b:4222"), "X")
            }
            shouldThrow<IllegalArgumentException> {
                NatsConnectionConfiguration.fromEnvironment(mapOf("X_URL" to "nats://user:secret@a:4222"), "X")
            }.message shouldNotContain "secret"
            val defaults = NotificationEventsConfiguration.fromEnvironment(emptyMap())
            defaults.publishing shouldBe false
            defaults.stream shouldBe "FIONAS_EVENTS"
            defaults.subjects.pushFailed shouldBe "fionas.events.notification.push_failed.v1"
            val custom =
                NotificationEventsConfiguration.fromEnvironment(
                    mapOf(
                        "FIONAS_NOTIFICATION_EVENTS_ENABLED" to "true",
                        "FIONAS_NOTIFICATION_EVENTS_SUBJECT_PREFIX" to "ops.fiona",
                        "FIONAS_NOTIFICATION_EVENTS_STREAM" to "FIONAS_OPS",
                        "FIONAS_NOTIFICATION_NATS_URL" to "tls://nats.ops.example:4222",
                        "FIONAS_NOTIFICATION_NATS_TOKEN" to "s3cret",
                    ),
                )
            custom.subjects.queued shouldBe "ops.fiona.notification.queued.v1"
            custom.stream shouldBe "FIONAS_OPS"
            custom.toString() shouldNotContain "s3cret"
            shouldThrow<IllegalArgumentException> {
                NotificationEventsConfiguration.fromEnvironment(mapOf("FIONAS_NOTIFICATION_EVENTS_SUBJECT_PREFIX" to "fionas.>"))
            }
        }
    })
