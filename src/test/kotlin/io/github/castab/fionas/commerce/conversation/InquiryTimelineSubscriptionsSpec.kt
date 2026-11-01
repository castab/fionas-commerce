package io.github.castab.fionas.commerce.conversation

import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InquiryTimelineSubscriptionsSpec :
    FunSpec({
        test("initial and reconnect synchronization always include the current revision, including zero") {
            val id = InquiryId(UUID.randomUUID())
            var revision = 0L
            InquiryTimelineSubscriptions { mapOf(id to revision) }.use { registry ->
                val first = checkNotNull(registry.subscribe(id))
                registry.refresh()
                first.poll() shouldBe 0L
                registry.refresh()
                first.poll() shouldBe null
                first.close()
                revision = 42
                val reconnected = checkNotNull(registry.subscribe(id))
                registry.refresh()
                reconnected.poll() shouldBe 42L
                first.poll() shouldBe null
            }
        }

        test("one batched read isolates inquiries and coalesces a stalled subscriber without blocking others") {
            val firstId = InquiryId(UUID.randomUUID())
            val secondId = InquiryId(UUID.randomUUID())
            val revisions = mutableMapOf(firstId to 1L, secondId to 9L)
            var reads = 0
            InquiryTimelineSubscriptions { ids ->
                reads++
                ids shouldBe revisions.keys
                revisions.toMap()
            }.use { registry ->
                val fast = checkNotNull(registry.subscribe(firstId))
                val stalled = checkNotNull(registry.subscribe(firstId))
                val unrelated = checkNotNull(registry.subscribe(secondId))
                registry.refresh()
                fast.poll() shouldBe 1L
                unrelated.poll() shouldBe 9L
                repeat(1000) {
                    revisions[firstId] = it + 2L
                    registry.refresh()
                    fast.poll() shouldBe it + 2L
                }
                stalled.poll() shouldBe 1001L
                stalled.poll() shouldBe null
                unrelated.poll() shouldBe null
                reads shouldBe 1001
                revisions[firstId] = 2L
                registry.refresh()
                fast.poll() shouldBe null // stale reads cannot regress an established subscription
            }
        }

        test("subscription limits are bounded and disconnect releases capacity") {
            InquiryTimelineSubscriptions { emptyMap() }.use { registry ->
                val ids = List(16) { InquiryId(UUID.randomUUID()) }
                val all =
                    ids.flatMap { id ->
                        val registered = List(16) { checkNotNull(registry.subscribe(id)) }
                        registry.subscribe(id) shouldBe null
                        registered
                    }
                registry.subscribe(ids.first()) shouldBe null
                registry.subscribe(InquiryId(UUID.randomUUID())) shouldBe null
                all.first().close()
                checkNotNull(registry.subscribe(ids.first())).close()
                registry.close()
                all.forEach { it.isClosed shouldBe true }
                registry.subscribe(ids.first()) shouldBe null
                registry.refresh()
            }
        }

        test("database failure closes affected subscribers and a new connection can synchronize after recovery") {
            val id = InquiryId(UUID.randomUUID())
            var fail = true
            InquiryTimelineSubscriptions { if (fail) error("database unavailable") else mapOf(id to 3L) }.use { registry ->
                val failed = checkNotNull(registry.subscribe(id))
                shouldThrow<IllegalStateException> { registry.refresh() }
                failed.isClosed shouldBe true
                failed.poll() shouldBe null
                fail = false
                val recovered = checkNotNull(registry.subscribe(id))
                registry.refresh()
                recovered.poll() shouldBe 3L
            }
        }

        test("a subscribe during an in-flight read catches the next revision and close never waits for the database") {
            val firstId = InquiryId(UUID.randomUUID())
            val secondId = InquiryId(UUID.randomUUID())
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            var calls = 0
            val registry =
                InquiryTimelineSubscriptions { ids ->
                    calls++
                    if (calls == 1) {
                        reading.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    ids.associateWith { calls.toLong() }
                }
            try {
                val first = checkNotNull(registry.subscribe(firstId))
                val refresh = CompletableFuture.runAsync { registry.refresh() }
                try {
                    reading.await(5, TimeUnit.SECONDS) shouldBe true
                    val second = checkNotNull(registry.subscribe(secondId))
                    registry.refresh() // concurrent scans do not race their results
                    first.close()
                    release.countDown()
                    refresh.get(5, TimeUnit.SECONDS)
                    second.poll() shouldBe null
                    registry.refresh()
                    second.poll() shouldBe 2L
                    first.poll() shouldBe null
                } finally {
                    release.countDown()
                }
            } finally {
                registry.close()
            }
        }

        test("shutdown while a read is stalled releases every subscription and ignores its late result") {
            val id = InquiryId(UUID.randomUUID())
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            val registry =
                InquiryTimelineSubscriptions {
                    reading.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    mapOf(id to 9L)
                }
            val subscription = checkNotNull(registry.subscribe(id))
            val refresh = CompletableFuture.runAsync { registry.refresh() }
            try {
                reading.await(5, TimeUnit.SECONDS) shouldBe true
                registry.close()
                subscription.isClosed shouldBe true
            } finally {
                release.countDown()
                registry.close()
            }
            refresh.get(5, TimeUnit.SECONDS)
            subscription.poll() shouldBe null
        }

        test("missing inquiry closes its subscription; idle registry does not read") {
            var reads = 0
            InquiryTimelineSubscriptions {
                reads++
                emptyMap()
            }.use { registry ->
                registry.refresh()
                reads shouldBe 0
                val missing = checkNotNull(registry.subscribe(InquiryId(UUID.randomUUID())))
                registry.refresh()
                missing.isClosed shouldBe true
                registry.refresh()
                reads shouldBe 1
            }
        }
    })
