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

/** Runtime 0.0.18 has no rate-limit category; retain its existing ErrorResponse envelope. */
fun RouteMetaDsl.returningLoginRateLimit() {
    returning(
        Status.TOO_MANY_REQUESTS,
        rateLimitBody to LOGIN_RATE_LIMIT_ERROR,
        "`rate_limited`: too many attempts. Retry-After gives seconds.",
    )
}

/** Per-process login buckets, using monotonic time and the connection IP, never forwarded headers. */
class LoginRateLimit(
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxIdentities: Int = 10_000,
) {
    init {
        require(maxIdentities > 0)
    }

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
            buckets.entries.removeIf { now - it.value.lastSeen >= REFILL_INTERVAL * CAPACITY }
            cleanedAt = now
        }
        val bucket =
            buckets[identity] ?: if (buckets.size < maxIdentities) {
                Bucket(CAPACITY, now, now).also { buckets[identity] = it }
            } else {
                overflow ?: Bucket(0, now, now).also { overflow = it }
            }
        bucket.lastSeen = now
        val elapsed = now - bucket.refilledAt
        val refills = elapsed / REFILL_INTERVAL
        if (refills > 0) {
            bucket.tokens = (bucket.tokens + refills.coerceAtMost(CAPACITY.toLong()).toInt()).coerceAtMost(CAPACITY)
            bucket.refilledAt = if (bucket.tokens == CAPACITY) now else bucket.refilledAt + refills * REFILL_INTERVAL
        }
        if (bucket.tokens > 0) {
            bucket.tokens--
            return null
        }
        val remaining = REFILL_INTERVAL - (now - bucket.refilledAt)
        return (remaining + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
    }

    companion object {
        private const val CAPACITY = 5
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val REFILL_INTERVAL = Duration.ofMinutes(5).toNanos()
        private val CLEANUP_INTERVAL = Duration.ofMinutes(1).toNanos()
    }
}
