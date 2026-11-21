package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.outbox.OutboxFailureCode
import io.github.castab.commerce.runtime.outbox.OutboxTransport
import io.github.castab.commerce.runtime.outbox.PublicationResult
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.events.NotificationSubjects
import io.github.castab.fionas.commerce.events.StaffNotificationEventLog
import io.github.castab.fionas.commerce.http.STAFF_SESSION_COOKIE
import io.github.castab.fionas.commerce.notification.PushRecipients
import io.github.castab.fionas.commerce.notification.PushRequest
import io.github.castab.fionas.commerce.notification.PushResult
import io.github.castab.fionas.commerce.notification.UnansweredReminderSettings
import io.github.castab.fionas.commerce.notification.WEBPUSH_PRODUCER
import io.github.castab.fionas.commerce.notification.WebPushConfiguration
import io.github.castab.fionas.commerce.notification.WebPushDeliveryTransport
import io.github.castab.fionas.commerce.notification.WebPushSender
import io.github.castab.fionas.commerce.notification.webPushDeliveryTransport
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.Collections
import java.util.UUID

/** A clock specs move forward explicitly. */
class MutableClock(
    var instant: Instant = TEST_INSTANT,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant += duration
    }
}

private fun p256() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

/** A freshly generated VAPID identity; never a deployment key. */
fun testWebPushConfiguration(unansweredReminders: UnansweredReminderSettings? = null): WebPushConfiguration {
    val pair = p256()
    return WebPushConfiguration(
        pair.private as ECPrivateKey,
        pair.public as ECPublicKey,
        "mailto:ops@fionas.test",
        unansweredReminders = unansweredReminders,
    )
}

/** What a browser's `PushSubscription.toJSON()` holds: a real P-256 key and a 16-byte auth secret. */
data class BrowserSubscription(
    val endpoint: String = "https://fcm.googleapis.com/fcm/send/${UUID.randomUUID()}",
    val p256dh: String = Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressedForTest(p256().public as ECPublicKey)),
    val auth: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16) { (it * 7).toByte() }),
) {
    fun json(
        label: String? = null,
        expirationTime: Long? = null,
    ): String =
        """{"subscription":{"endpoint":"$endpoint","expirationTime":${expirationTime ?: "null"},""" +
            """"keys":{"p256dh":"$p256dh","auth":"$auth"}}""" + (label?.let { ""","label":"$it"""" } ?: "") + "}"
}

private fun uncompressedForTest(key: ECPublicKey): ByteArray {
    fun coordinate(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }
    return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
}

/** A scripted push service: answers queued results in order, then [fallback]; records every request. */
class FakePushSender(
    private val fallback: PushResult = PushResult.Accepted(201),
) : WebPushSender {
    val requests: MutableList<PushRequest> = Collections.synchronizedList(mutableListOf())
    private val script = ArrayDeque<PushResult>()

    /** Runs before answering, with the request; lets a spec change state while the provider call is "in flight". */
    @Volatile var onSend: (PushRequest) -> Unit = {}

    fun next(vararg results: PushResult) = synchronized(script) { results.forEach(script::addLast) }

    override fun send(request: PushRequest): PushResult {
        requests += request
        onSend(request)
        return synchronized(script) { script.removeFirstOrNull() } ?: fallback
    }
}

/** The delivery transport as composition builds it, over the test runtime, with replaceable collaborators. */
fun TestApplication.pushTransport(
    configuration: WebPushConfiguration,
    sender: WebPushSender,
    clock: Clock = testClock,
    recipients: PushRecipients = PushRecipients.of(context),
): WebPushDeliveryTransport =
    webPushDeliveryTransport(
        context,
        configuration,
        StaffNotificationEventLog(context.outbox, NotificationSubjects()),
        sender,
        clock,
        recipients,
    )

/**
 * Runs pending work of [producer] through [transport] with the runtime's own claim/confirm/fail
 * protocol, exactly as its dispatcher does, until nothing is claimable or [rounds] pass.
 * Failures are retried immediately (the runtime's backoff is not under test here).
 */
fun TestApplication.drainOutbox(
    transport: OutboxTransport,
    producer: String = WEBPUSH_PRODUCER,
    rounds: Int = 20,
): List<PublicationResult> {
    val publisher = context.outbox.publisher(producer, "test-drain-${UUID.randomUUID()}")
    val results = mutableListOf<PublicationResult>()
    repeat(rounds) {
        val claims = publisher.claim(10, Duration.ofSeconds(60))
        if (claims.isEmpty()) return results
        claims.forEach { claim ->
            val result =
                try {
                    transport.publish(claim.record)
                } catch (e: Exception) {
                    publisher.fail(claim, OutboxFailureCode.TRANSPORT_EXCEPTION, Duration.ofMillis(1))
                    throw e
                }
            results += result
            if (result == PublicationResult.CONFIRMED) {
                publisher.confirm(claim)
            } else {
                publisher.fail(claim, OutboxFailureCode.UNCONFIRMED, Duration.ofMillis(1))
            }
        }
        Thread.sleep(5)
    }
    return results
}

/** Unpublished outbox records of [producer]. */
fun TestApplication.pendingOutbox(producer: String): Long =
    database
        .strings(
            "SELECT count(*)::text FROM commerce.outbox_events WHERE producer_id = '$producer' AND published_at IS NULL",
        ).single()
        .toLong()

/** A new ACTIVE staff USER holding [permissions], and its session cookie. */
fun TestApplication.staffMember(permissions: Set<PermissionKey> = emptySet()): Pair<UserId, String> {
    val id = UserId(UUID.randomUUID())
    authorization.createUser(User(id, "staff-${id.value}", null, null, "Staff", PrincipalStatus.ACTIVE, emptySet()))
    if (permissions.isNotEmpty()) {
        val role = RoleKey("test.push-${id.value}")
        authorization.createRole(RoleDefinition(role, "Staff", null, permissions))
        authorization.assignRole(id, role)
    }
    return id to "$STAFF_SESSION_COOKIE=${sessions.create(id).token.value}"
}
