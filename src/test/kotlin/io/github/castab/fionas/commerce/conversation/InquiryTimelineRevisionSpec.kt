package io.github.castab.fionas.commerce.conversation

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.events.RecordConversationEvent
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository
import io.github.castab.fionas.commerce.inquiry.RecordedInquiryCommunication
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Status
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InquiryTimelineRevisionSpec :
    FunSpec({
        lateinit var app: TestApplication
        lateinit var read: ReadInquiryTimelineRevisions
        val communications = JdbiInquiryCommunicationRepository()
        beforeTest {
            app = TestApplication.create()
            read = ReadInquiryTimelineRevisions(app.transactor, InquiryTimelineRevisionRepository())
        }
        afterTest { app.close() }

        fun inquiry() = InquiryId(UUID.fromString(app.createInquiry()))

        fun revision(id: InquiryId) = read(setOf(id)).getValue(id)

        fun inbound(id: InquiryId) =
            InquiryCommunication(
                InquiryCommunicationId(UUID.randomUUID()),
                id,
                InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED,
                STORED_INSTANT,
                null,
            )

        fun event(
            id: InquiryId,
            sequence: Long = 1,
            conversation: UUID = UUID.randomUUID(),
            delivery: Boolean = false,
        ): ByteArray {
            val type = if (delivery) "conversation.message.delivery.updated" else "conversation.message.received"
            val actor = if (delivery) "system" else "participant"
            val cause = if (delivery) "resend_delivery_webhook" else "inbound_email"
            val state = if (delivery) "\"deliveryState\":\"delivered\"," else ""
            return """{$state"schemaVersion":1,"id":"${UUID.randomUUID()}","type":"$type",
                "conversationId":"$conversation","sequence":$sequence,
                "topic":{"type":"booking","externalId":"${id.value}"},"messageId":"${UUID.randomUUID()}",
                "occurredAt":"2026-10-01T12:00:00Z","actor":"$actor","cause":"$cause"}""".toByteArray()
        }

        fun ingest(bytes: ByteArray) = RecordConversationEvent(app.transactor, communications, JdbiInquiryConversationRepository())(bytes)

        test("initial baseline, inquiry isolation, inbound and delivery changes, duplicate and out-of-order events") {
            val id = inquiry()
            val unrelated = inquiry()
            val missing = InquiryId(UUID.randomUUID())
            read(setOf(id, unrelated, missing)) shouldBe mapOf(id to 0L, unrelated to 0L)
            val conversation = UUID.randomUUID()
            val inbound = event(id, 10, conversation)
            ingest(inbound)
            revision(id) shouldBe 2L // one source identity and one communication; opaque, not a message count
            ingest(inbound)
            revision(id) shouldBe 2L
            val delivery = event(id, 3, conversation, delivery = true)
            ingest(delivery) // provider order has no bearing on the local invalidation order
            revision(id) shouldBe 3L
            ingest(delivery)
            revision(id) shouldBe 3L
            revision(unrelated) shouldBe 0L
            app.transactor.inTransaction { communications.history(it, id).size } shouldBe 1
        }

        test("acknowledgement advances once and a stale acknowledgement rolls back without a revision") {
            val id = inquiry()
            app
                .adminPost("/inquiries/${id.value}/communications/acknowledge", """{"throughRecordedOrder":0}""")
                .status shouldBe Status.NO_CONTENT
            revision(id) shouldBe 1L
            ingest(event(id))
            revision(id) shouldBe 3L
            app
                .adminPost("/inquiries/${id.value}/communications/acknowledge", """{"throughRecordedOrder":0}""")
                .status shouldBe Status.CONFLICT
            revision(id) shouldBe 3L
        }

        test("failure after source claim and communication insertion rolls back both revision increments") {
            val id = inquiry()
            val bytes = event(id)
            val failing =
                object : InquiryCommunicationRepository by communications {
                    override fun append(
                        transaction: Transaction,
                        activity: InquiryCommunication,
                    ): RecordedInquiryCommunication {
                        communications.append(transaction, activity)
                        error("fail after both revision writes")
                    }
                }
            shouldThrow<IllegalStateException> {
                RecordConversationEvent(app.transactor, failing, JdbiInquiryConversationRepository())(bytes)
            }
            revision(id) shouldBe 0L
            app.database.count("fionas.inquiry_change_revisions") shouldBe 0
            ingest(bytes)
            revision(id) shouldBe 2L
            shouldThrow<IllegalStateException> {
                app.transactor.inTransaction {
                    communications.append(it, inbound(id))
                    error("rollback an existing counter")
                }
            }
            revision(id) shouldBe 2L
        }

        test("uncommitted changes stay invisible and independent registries discover the committed revision") {
            val id = inquiry()
            val appended = CountDownLatch(1)
            val release = CountDownLatch(1)
            InquiryTimelineSubscriptions(read::invoke).use { first ->
                InquiryTimelineSubscriptions(read::invoke).use { second ->
                    val one = checkNotNull(first.subscribe(id))
                    val two = checkNotNull(second.subscribe(id))
                    first.refresh()
                    second.refresh()
                    one.poll() shouldBe 0L
                    two.poll() shouldBe 0L
                    val writer =
                        CompletableFuture.runAsync {
                            app.transactor.inTransaction {
                                communications.append(it, inbound(id))
                                appended.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                        }
                    try {
                        appended.await(5, TimeUnit.SECONDS) shouldBe true
                        first.refresh()
                        second.refresh()
                        one.poll() shouldBe null
                        two.poll() shouldBe null
                    } finally {
                        release.countDown()
                    }
                    writer.get(10, TimeUnit.SECONDS)
                    first.refresh()
                    second.refresh()
                    one.poll() shouldBe 1L
                    two.poll() shouldBe 1L
                }
            }
        }

        test("concurrent same-inquiry writers cannot lose an increment") {
            val id = inquiry()
            val writes =
                List(12) {
                    CompletableFuture.runAsync { app.transactor.inTransaction { communications.append(it, inbound(id)) } }
                }
            writes.forEach { it.get(15, TimeUnit.SECONDS) }
            revision(id) shouldBe 12L
        }
    })
