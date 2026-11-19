package io.github.castab.fionas.commerce.notification

import com.zerodeplibs.webpush.PushSubscription
import com.zerodeplibs.webpush.VAPIDKeyPairs
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer
import com.zerodeplibs.webpush.key.PrivateKeySources
import com.zerodeplibs.webpush.key.PublicKeySources
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** One encrypted Web Push request: where, with which keys, what, and for how long the service may hold it. */
class PushRequest(
    val endpoint: PushEndpoint,
    val keys: PushKeys,
    val payload: String,
    val ttl: Duration,
    val urgentDelivery: Boolean,
    /** Collapses not-yet-delivered copies of the same notification at the push service. */
    val topic: String,
) {
    override fun toString() = "PushRequest([redacted])"
}

/** What a push service said about one request. Never carries the endpoint, keys or response body. */
sealed interface PushResult {
    /** 201/202: the push service accepted the message. Acceptance is not display. */
    data class Accepted(
        val code: Int,
    ) : PushResult

    /** 404/410: the subscription no longer exists at the push service. */
    data class Gone(
        val code: Int,
    ) : PushResult

    /** A definitive refusal (other 4xx, or a redirect, which is never followed). */
    data class Rejected(
        val code: Int,
    ) : PushResult

    /** A transient failure: 429, 5xx or a connection that never reached the service. */
    data class Retryable(
        val reason: DeliveryReason,
        val code: Int?,
        val retryAfter: Duration?,
    ) : PushResult

    /** The request may have reached the service, but no answer arrived (timeout, lost response). */
    data object Unknown : PushResult

    /** The endpoint's host is no longer on the allow-list; nothing was sent. */
    data object EndpointNotAllowed : PushResult
}

/** The narrow provider seam. Implementations perform network I/O and are never called inside a transaction. */
fun interface WebPushSender {
    fun send(request: PushRequest): PushResult
}

/**
 * Sends VAPID-signed, aes128gcm-encrypted messages with the JDK client. Requests never follow
 * redirects, are bounded by [WebPushConfiguration.sendTimeout], and log nothing.
 */
class JdkWebPushSender(
    private val configuration: WebPushConfiguration,
    private val clock: () -> Instant = Instant::now,
) : WebPushSender {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(configuration.sendTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    private val vapid =
        VAPIDKeyPairs.of(
            PrivateKeySources.ofECPrivateKey(configuration.vapidPrivateKey),
            PublicKeySources.ofECPublicKey(configuration.vapidPublicKey),
        )

    override fun send(request: PushRequest): PushResult {
        // The allow-list may have narrowed since enrollment: never call a host that is no longer allowed.
        if (!configuration.allowedHosts.allows(request.endpoint.host)) return PushResult.EndpointNotAllowed
        val http = prepare(request)
        val response =
            try {
                client.send(http, HttpResponse.BodyHandlers.discarding())
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return PushResult.Unknown
            } catch (_: HttpConnectTimeoutException) {
                return PushResult.Retryable(DeliveryReason.TRANSPORT_FAILURE, null, null)
            } catch (_: HttpTimeoutException) {
                return PushResult.Unknown
            } catch (_: ConnectException) {
                return PushResult.Retryable(DeliveryReason.TRANSPORT_FAILURE, null, null)
            } catch (_: IOException) {
                return PushResult.Unknown
            }
        return classify(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null), clock())
    }

    /** The signed, encrypted request; no network I/O. */
    internal fun prepare(request: PushRequest): HttpRequest {
        val subscription =
            PushSubscription().apply {
                endpoint = request.endpoint.value
                keys =
                    PushSubscription.Keys().apply {
                        p256dh = request.keys.p256dh
                        auth = request.keys.auth
                    }
            }
        return StandardHttpClientRequestPreparer
            .getBuilder()
            .pushSubscription(subscription)
            .vapidJWTSubject(configuration.subject)
            .vapidJWTExpirationTime(clock().plus(12, ChronoUnit.HOURS))
            .pushMessage(request.payload)
            .ttl(request.ttl.seconds.coerceAtLeast(0), TimeUnit.SECONDS)
            .topic(request.topic)
            .let { if (request.urgentDelivery) it.urgencyHigh() else it.urgencyNormal() }
            .build(vapid)
            .toRequestBuilder()
            .timeout(configuration.sendTimeout)
            .build()
    }

    companion object {
        /** Maps an HTTP answer to a delivery outcome; `Retry-After` may be delta-seconds or an HTTP date. */
        fun classify(
            code: Int,
            retryAfter: String?,
            now: Instant,
        ): PushResult =
            when {
                code in 200..299 -> PushResult.Accepted(code)
                code == 404 || code == 410 -> PushResult.Gone(code)
                code == 429 -> PushResult.Retryable(DeliveryReason.PROVIDER_THROTTLED, code, retryAfter(retryAfter, now))
                code in 500..599 -> PushResult.Retryable(DeliveryReason.PROVIDER_UNAVAILABLE, code, retryAfter(retryAfter, now))
                else -> PushResult.Rejected(code)
            }

        internal fun retryAfter(
            header: String?,
            now: Instant,
        ): Duration? {
            val value = header?.trim() ?: return null
            value.toLongOrNull()?.let { return if (it >= 0) Duration.ofSeconds(it) else null }
            return try {
                Duration.between(now, DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from)).takeUnless { it.isNegative }
            } catch (_: Exception) {
                null
            }
        }
    }
}
