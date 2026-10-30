package io.github.castab.fionas.commerce.conversation

import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transport-independent preparation for native SSE. Not mounted or scheduled until the runtime supports SSE.
 * A future managed dispatcher calls refresh once per second, then stream writers drain their own slots.
 * No client callbacks, network writes, threads, or open transactions are owned by this registry.
 */
class InquiryTimelineSubscriptions(
    private val read: (Set<InquiryId>) -> Map<InquiryId, Long>,
) : AutoCloseable {
    private val monitor = Any()
    private val subscriptions = mutableSetOf<Subscription>()
    private val refreshing = AtomicBoolean()
    private var closed = false

    /** The transport must authenticate, authorize and check existence before subscribing. */
    fun subscribe(inquiryId: InquiryId): Subscription? =
        synchronized(monitor) {
            if (closed ||
                subscriptions.size >= MAX_SUBSCRIPTIONS ||
                subscriptions.count { it.inquiryId == inquiryId } >= MAX_PER_INQUIRY
            ) {
                null
            } else {
                Subscription(inquiryId).also { subscriptions.add(it) }
            }
        }

    /**
     * Reads only a bounded snapshot of active inquiry IDs. A new subscriber missed by this snapshot is picked
     * up next time, so subscribe/read races cannot hide a commit. Concurrent refresh attempts are skipped.
     * Read failures close affected subscriptions; reconnect must synchronize again, never report stale success.
     */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            val active = synchronized(monitor) { subscriptions.toList() }
            if (active.isEmpty()) return
            val revisions =
                try {
                    read(active.map { it.inquiryId }.toSet())
                } catch (failure: Exception) {
                    active.forEach { it.close() }
                    throw failure
                }
            synchronized(monitor) {
                active.filter { !it.closed }.forEach { subscription ->
                    val revision = revisions[subscription.inquiryId]
                    if (revision == null || revision < 0) {
                        subscription.close()
                    } else if (subscription.observed == null || revision > checkNotNull(subscription.observed)) {
                        subscription.observed = revision
                        subscription.pending = revision
                    }
                }
            }
        } finally {
            refreshing.set(false)
        }
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            subscriptions.toList().forEach { it.close() }
        }
    }

    inner class Subscription internal constructor(
        val inquiryId: InquiryId,
    ) : AutoCloseable {
        internal var observed: Long? = null
        internal var pending: Long? = null
        internal var closed = false

        val isClosed: Boolean get() = synchronized(monitor) { closed }

        /** One coalescing slot: a stalled consumer cannot retain an unbounded event backlog. */
        fun poll(): Long? = synchronized(monitor) { pending.also { pending = null } }

        override fun close() {
            synchronized(monitor) {
                closed = true
                pending = null
                subscriptions.remove(this)
            }
        }
    }

    companion object {
        const val MAX_SUBSCRIPTIONS = 256
        const val MAX_PER_INQUIRY = 16
    }
}
