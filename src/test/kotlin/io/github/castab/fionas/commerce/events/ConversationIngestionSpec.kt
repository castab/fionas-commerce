package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.fionas.commerce.http.StaffRequestResponse
import io.github.castab.fionas.commerce.inquiry.InquiryCommunication
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationId
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationKind
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

class ConversationIngestionSpec :
    FunSpec({
        lateinit var app: TestApplication
        val communications = JdbiInquiryCommunicationRepository()
        val sources = JdbiInquiryConversationRepository()
        beforeTest { app = TestApplication.create() }
        afterTest { app.close() }

        fun payload(
            inquiry: String,
            event: UUID = UUID.randomUUID(),
            message: UUID = UUID.randomUUID(),
            sequence: Long = 1,
            conversation: UUID = UUID.randomUUID(),
            at: String = "2026-10-01T12:00:00Z",
        ): ByteArray =
            """{"schemaVersion":1,"id":"$event","type":"conversation.message.received","conversationId":"$conversation",

        "sequence":$sequence,"topic":{"type":"booking","externalId":"$inquiry"},"messageId":"$message",
        "occurredAt":"$at","actor":"participant","cause":"inbound_email"}""".toByteArray()

        fun detail(id: String) = CommerceJson.asA(app.adminGet("/staff/requests/$id").bodyString(), StaffRequestResponse.serializer())

        fun ingest(bytes: ByteArray) = RecordConversationEvent(app.transactor, communications, sources)(bytes)

        test("event replay and duplicate semantic messages converge after a new handler is constructed") {
            val id = app.createInquiry()
            val message = UUID.randomUUID()
            val conversation = UUID.randomUUID()
            val event = UUID.randomUUID()
            val bytes = payload(id, event, message, conversation = conversation)
            ingest(bytes)
            ingest(bytes)
            ingest(payload(id, message = message, sequence = 2, conversation = conversation))
            app.transactor.inTransaction { communications.history(it, InquiryId(UUID.fromString(id))).size } shouldBe 1
            detail(id).communication.needsReply shouldBe true
            shouldThrow<IllegalStateException> {
                ingest(
                    payload(id, event, message, conversation = conversation, at = "2026-10-02T12:00:00Z"),
                )
            }
            app.transactor.inTransaction { communications.history(it, InquiryId(UUID.fromString(id))).size } shouldBe 1
        }
        test("stale acknowledgement never clears a later ingested backdated email; repeated observed acknowledgement is safe") {
            val id = app.createInquiry()
            ingest(payload(id))
            val first = detail(id).communication.throughRecordedOrder
            ingest(payload(id, at = "2026-09-01T12:00:00Z"))
            val route = "/inquiries/$id/communications/acknowledge"
            app.adminPost(route, """{"throughRecordedOrder":$first}""").status shouldBe Status.CONFLICT
            detail(id).communication.needsReply shouldBe true
            val latest = detail(id).communication.throughRecordedOrder
            repeat(2) { app.adminPost(route, """{"throughRecordedOrder":$latest}""").status shouldBe Status.NO_CONTENT }
            detail(id).communication.needsReply shouldBe false
            ingest(payload(id, at = "2026-08-01T12:00:00Z"))
            detail(id).communication.needsReply shouldBe true
            detail(id).communication.unacknowledgedSince shouldBe "2026-08-01T12:00:00Z"
            app.adminPost(route, "{}").status shouldBe Status.BAD_REQUEST
            app.adminPost(route, """{"throughRecordedOrder":-1}""").status shouldBe Status.UNPROCESSABLE_ENTITY
        }
        test("acknowledgement waits for a concurrent inbound append and refuses the email it did not observe") {
            val id = app.createInquiry()
            ingest(payload(id))
            val observed = detail(id).communication.throughRecordedOrder
            val appended = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer =
                CompletableFuture.runAsync {
                    app.transactor.inTransaction { transaction ->
                        communications.append(
                            transaction,
                            InquiryCommunication(
                                InquiryCommunicationId(UUID.randomUUID()),
                                InquiryId(UUID.fromString(id)),
                                InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED,
                                Instant.parse("2026-10-01T13:00:00Z"),
                                null,
                            ),
                        )
                        appended.countDown()
                        release.await()
                    }
                }
            appended.await()
            val route = "/inquiries/$id/communications/acknowledge"
            val acknowledgement = CompletableFuture.supplyAsync { app.adminPost(route, """{"throughRecordedOrder":$observed}""") }
            Thread.sleep(300)
            acknowledgement.isDone shouldBe false
            release.countDown()
            writer.join()
            acknowledgement.join().status shouldBe Status.CONFLICT
            detail(id).communication.needsReply shouldBe true
        }
        test("unknown inquiries and unrelated topics have no effects; unsupported contracts are not interpreted") {
            ingest(payload(UUID.randomUUID().toString()))
            val id = app.createInquiry()
            ingest(String(payload(id)).replace("\"booking\"", "\"other\"").toByteArray())
            detail(id).communication.throughRecordedOrder shouldBe 0L
            shouldThrow<IntakeFailure> { ingest(String(payload(id)).replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray()) }
            shouldThrow<IntakeFailure> { ingest(byteArrayOf(0xc3.toByte(), 0x28)) }
            shouldThrow<IntakeFailure> { ingest(String(payload(id)).replace("\"participant\"", "\"service\"").toByteArray()) }
            detail(id).communication.throughRecordedOrder shouldBe 0L
        }
        test("valid booking topics whose provider ids are not Fiona inquiry ids are unrelated, never malformed") {
            val id = app.createInquiry()

            fun topic(externalId: String) = String(payload(id)).replace("\"externalId\":\"$id\"", "\"externalId\":\"$externalId\"")
            // Generic provider topic ids: opaque text up to 255 characters, including near-miss UUID spellings.
            listOf("order-1234", "BK_42", "booking:$id", "{$id}", id.uppercase(), id.replace("-", ""), "x".repeat(255)).forEach {
                ingest(topic(it).toByteArray())
            }
            detail(id).communication.throughRecordedOrder shouldBe 0L
            app.transactor.inTransaction { sources.established(it, InquiryId(UUID.fromString(id))) } shouldBe false
            // Only invalid provider contracts halt.
            shouldThrow<IntakeFailure> { ingest(topic("").toByteArray()) }
            shouldThrow<IntakeFailure> { ingest(topic("x".repeat(256)).toByteArray()) }
            shouldThrow<IntakeFailure> { ingest(String(payload(id)).replace("\"type\":\"booking\"", "\"type\":\"\"").toByteArray()) }
            ingest(payload(id))
            detail(id).communication.needsReply shouldBe true
        }
        test("delivery updates cannot fabricate staff activity or regress attention") {
            val id = app.createInquiry()
            val inbound = payload(id)
            ingest(inbound)
            val before = detail(id).communication
            val delivery =
                String(payload(id))
                    .replace("conversation.message.received", "conversation.message.delivery.updated")
                    .replace("\"participant\"", "\"system\"")
                    .replace("\"inbound_email\"", "\"resend_delivery_webhook\"")
                    .replace("\"schemaVersion\":1", "\"deliveryState\":\"delivered\",\"schemaVersion\":1")
            ingest(delivery.toByteArray())
            detail(id).communication shouldBe before
        }
        test("conversation consumer is independently disabled by default") {
            ConversationIntakeConfiguration.fromEnvironment(emptyMap()) shouldBe null
            ConversationIntakeConfiguration.fromEnvironment(mapOf("FIONAS_INTAKE_ENABLED" to "true")) shouldBe null
        }
    })
