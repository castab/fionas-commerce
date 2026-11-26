package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import org.http4k.contract.RouteMetaDsl
import org.http4k.core.Filter
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import java.time.Duration

private val rateLimitBody = jsonBody(ErrorResponse.serializer())
internal val LOGIN_RATE_LIMIT_ERROR = ErrorResponse("rate_limited", "Too many requests")

/** Runtime 0.0.20 has no rate-limit category; retain its existing ErrorResponse envelope. */
fun RouteMetaDsl.returningLoginRateLimit() {
    returning(
        Status.TOO_MANY_REQUESTS,
        rateLimitBody to LOGIN_RATE_LIMIT_ERROR,
        "`rate_limited`: too many attempts. Retry-After gives seconds.",
    )
}

/**
 * Per-process credential-attempt buckets, using monotonic time and the connection IP, never
 * forwarded headers. Each instance is one independent limit: a burst of [capacity] attempts,
 * refilling one every [refillInterval]. Password login keeps the default (five, then one every
 * five minutes); passkey ceremonies, whose options are fetched on every sign-in page view and
 * which cannot be guessed, use their own more generous instance.
 */
class LoginRateLimit(
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxIdentities: Int = 10_000,
    val capacity: Int = DEFAULT_CAPACITY,
    val refillInterval: Duration = DEFAULT_REFILL_INTERVAL,
) {
    init {
        require(maxIdentities > 0)
        require(capacity > 0)
        require(!refillInterval.isNegative && !refillInterval.isZero)
    }

    private val refillNanos = refillInterval.toNanos()

    /** The route description of this limit, for OpenAPI. */
    val described: String
        get() =
            "Limited per connection IP to a burst of $capacity attempts, " +
                "refilling one attempt every ${refillInterval.toSeconds()} seconds. " +
                "All attempts count, including successful and malformed requests. 429 includes Retry-After in seconds."

    private class Bucket(
        var tokens: Int,
        var refilledAt: Long,
        var lastSeen: Long,
    )

    private val buckets = mutableMapOf<String, Bucket>()

    // At the identity bound, new IPs share a depleted bucket instead of evicting depleted IPs.
    private var overflow: Bucket? = null
    private var cleanedAt = nanoTime()

    val filter: Filter =
        Filter { next ->
            { request ->
                val retryAfter = retryAfter(request.source?.address?.takeIf(String::isNotBlank) ?: "unknown")
                if (retryAfter == null) {
                    next(request)
                } else {
                    Response(Status.TOO_MANY_REQUESTS)
                        .with(rateLimitBody of LOGIN_RATE_LIMIT_ERROR)
                        .header("Retry-After", retryAfter.toString())
                        .header("Cache-Control", "no-store")
                }
            }
        }

    /** Atomically spends one attempt, or returns seconds until the next token (rounded up). */
    @Synchronized
    private fun retryAfter(identity: String): Long? {
        val now = nanoTime()
        if (now - cleanedAt >= CLEANUP_INTERVAL) {
            buckets.entries.removeIf { now - it.value.lastSeen >= refillNanos * capacity }
            cleanedAt = now
        }
        val bucket =
            buckets[identity] ?: if (buckets.size < maxIdentities) {
                Bucket(capacity, now, now).also { buckets[identity] = it }
            } else {
                overflow ?: Bucket(0, now, now).also { overflow = it }
            }
        bucket.lastSeen = now
        val elapsed = now - bucket.refilledAt
        val refills = elapsed / refillNanos
        if (refills > 0) {
            bucket.tokens = (bucket.tokens + refills.coerceAtMost(capacity.toLong()).toInt()).coerceAtMost(capacity)
            bucket.refilledAt = if (bucket.tokens == capacity) now else bucket.refilledAt + refills * refillNanos
        }
        if (bucket.tokens > 0) {
            bucket.tokens--
            return null
        }
        val remaining = refillNanos - (now - bucket.refilledAt)
        return (remaining + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
    }

    companion object {
        const val DEFAULT_CAPACITY = 5
        val DEFAULT_REFILL_INTERVAL: Duration = Duration.ofMinutes(5)
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val CLEANUP_INTERVAL = Duration.ofMinutes(1).toNanos()

        /** Passkey ceremonies: a burst of 30, refilling one every ten seconds. */
        fun forPasskeys(nanoTime: () -> Long = System::nanoTime) =
            LoginRateLimit(nanoTime, capacity = 30, refillInterval = Duration.ofSeconds(10))
    }
}
