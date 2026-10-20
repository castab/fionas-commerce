package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.fionas.commerce.inquiry.IdempotencyKeyReused
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun submittedFixture(
    id: String = UUID.randomUUID().toString(),
    amount: String = "0.125000000000",
): String =
    """
    {"schemaVersion":1,"type":"fionas.inquiry.submitted","id":"$id",
    "occurredAt":"2026-10-09T18:22:03.123Z","source":"fionas-web","data":{
    "name":" Jane Doe ","email":" JANE@example.com ","zipCode":"01234","eventDate":"2026-12-05","eventType":"BIRTHDAY",
    "requestedService":{"guestCount":75,"guestCountIsMinimum":true,"pricingReference":"web-menu-1:synthetic-1",
    "items":[{"label":" Ice cream ","group":"Scoops","key":"vanilla"}]},"priceRevision":"synthetic-1",
    "lines":[{"description":"Service","quantity":"8.000000","unitPrice":"$amount","taxAmount":"0.00","currency":"USD"},
    {"description":"Travel","unitPrice":"10.00","taxAmount":"0.00","currency":"USD"}]}}
    """.trimIndent()

internal class TestDelivery(
    override val bytes: ByteArray = submittedFixture().toByteArray(),
    override val attempt: Long = 1,
) : IntakeDelivery {
    override val sequence = 42L
    override val storedAt: Instant = Instant.parse("2026-10-09T18:22:03.123Z")
    var ack = 0
    var term = 0
    var delay: Duration? = null
    var ackFailure = false

    override fun acknowledge() {
        if (ackFailure) throw SQLException()
        ack++
    }

    override fun retry(delay: Duration) {
        this.delay = delay
    }

    override fun terminate() {
        term++
    }
}

class InquiryIntakeSpec :
    FunSpec({
        val author = ServiceId(UUID.randomUUID())

        test("targeted recovery shares processing without issuing source disposition") {
            val persisted = TestDelivery()
            IntakeProcessor({}, { _, _ -> }).process(persisted, sourceDisposition = false) shouldBe IntakeResult.PERSISTED
            persisted.ack shouldBe 0
            val failed = TestDelivery()
            IntakeProcessor({ throw IntakeFailure("bad", FailureKind.PERMANENT) }, { _, _ -> })
                .process(failed, sourceDisposition = false) shouldBe IntakeResult.FAILED
            failed.term shouldBe 0
            val transient = TestDelivery()
            IntakeProcessor({ throw IntakeFailure("outage", FailureKind.TRANSIENT) }, { _, _ -> })
                .process(transient, sourceDisposition = false) shouldBe IntakeResult.RETRY
            transient.delay shouldBe null
        }

        test("JSON numeric integer notation matches the producer worker rather than accepting string coercion") {
            val input =
                submittedFixture()
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":1.0")
                    .replace("\"guestCount\":75", "\"guestCount\":7.5e1")
            submittedCommand(input.toByteArray(), author).requestedService.guestCount shouldBe 75
        }

        test("shutdown after commit leaves input unacknowledged; shutdown during diagnostic leaves it unterminated") {
            var cancelled = false
            val committed = TestDelivery()
            IntakeProcessor({ cancelled = true }, { _, _ -> error("No diagnostic") })
                .process(committed) { cancelled } shouldBe IntakeResult.INTERRUPTED
            committed.ack shouldBe 0
            cancelled = false
            val invalid = TestDelivery()
            IntakeProcessor({ throw IntakeFailure("bad", FailureKind.PERMANENT) }, { _, _ -> cancelled = true })
                .process(invalid) { cancelled } shouldBe IntakeResult.INTERRUPTED
            invalid.term shouldBe 0
            invalid.ack shouldBe 0
        }

        test("expired shutdown grace interrupts blocking work and joins before returning") {
            val entered = CountDownLatch(1)
            val delivery = TestDelivery()
            val transport =
                object : IntakeTransport {
                    override fun next(): IntakeDelivery = delivery

                    override fun publishDiagnostic(
                        id: String,
                        bytes: ByteArray,
                    ) = Unit

                    override fun close() = Unit
                }
            val worker =
                ManagedJetStreamWorker(transport, {
                    entered.countDown()
                    CountDownLatch(1).await()
                }, grace = Duration.ofMillis(10))
            worker.start()
            check(entered.await(5, TimeUnit.SECONDS))
            worker.close()
            worker.status.state shouldBe IntakeState.STOPPED
            delivery.ack shouldBe 0
            delivery.term shouldBe 0
            worker.close()
        }

        test("producer projection preserves exact decimals, order, UUID spelling and configured authority") {
            val id = UUID.randomUUID().toString().uppercase()
            val command = submittedCommand(submittedFixture(id).toByteArray(), author)
            command.submissionKey.value shouldBe id
            command.submittedBy shouldBe author
            command.lines.map { it.description } shouldBe listOf("Service", "Travel")
            command.lines
                .first()
                .unitPrice.amount
                .toPlainString() shouldBe "0.125000000000"
            command.lines
                .first()
                .quantity
                ?.toPlainString() shouldBe "8.000000"
            command.requestedService.guestCountIsMinimum shouldBe true
            command.requestedService.durationMinutes shouldBe null
            command.requestedService.pricingReference shouldBe "web-menu-1:synthetic-1"
            command.requestedService.items
                .single()
                .label shouldBe "Ice cream"
            command.email.value shouldBe "jane@example.com"
            command.zipCode.value shouldBe "01234"
        }

        test("unknown compatible fields are ignored including duration and payload submittedBy; priceRevision is not business intent") {
            val original = submittedFixture()
            val extended =
                original
                    .replace("\"guestCount\":75", "\"durationMinutes\":120,\"guestCount\":75")
                    .replace("\"priceRevision\":\"synthetic-1\"", "\"submittedBy\":\"untrusted\",\"priceRevision\":\"another\"")
            submittedCommand(extended.toByteArray(), author) shouldBe submittedCommand(original.toByteArray(), author)
        }

        test("malformed UTF-8 JSON envelopes, timestamps and explicit null optionals are permanent input failures") {
            val invalid =
                listOf(
                    byteArrayOf(0xc3.toByte(), 0x28),
                    "{".toByteArray(),
                    "[]".toByteArray(),
                    submittedFixture().replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray(),
                    submittedFixture().replace("fionas.inquiry.submitted", "other").toByteArray(),
                    submittedFixture().replace("fionas-web", "another").toByteArray(),
                    submittedFixture().replace("2026-10-09T18:22:03.123Z", "2026-02-30T18:22:03Z").toByteArray(),
                    submittedFixture().replace("2026-10-09T18:22:03.123Z", "2026-10-09T18:22:03+00:00").toByteArray(),
                    submittedFixture().replace("\"quantity\":\"8.000000\"", "\"quantity\":null").toByteArray(),
                    submittedFixture().replace("\"unitPrice\":\"0.125000000000\"", "\"unitPrice\":0.125").toByteArray(),
                    submittedFixture().replace("\"guestCount\":75", "\"guestCount\":\"75\"").toByteArray(),
                    submittedFixture().replace("\"priceRevision\":\"synthetic-1\",", "").toByteArray(),
                    submittedFixture(amount = "0.123").toByteArray(),
                )
            invalid.forEach { shouldThrow<IntakeFailure> { submittedCommand(it, author) }.kind shouldBe FailureKind.PERMANENT }
        }

        test("failure classification distinguishes expected rejection, contention, outages and invariant/programming failures") {
            classifyIntakeFailure(CommerceFailure.ValidationFailed("safe")).kind shouldBe FailureKind.PERMANENT
            classifyIntakeFailure(CommerceFailure.Conflict("safe", IdempotencyKeyReused())).code shouldBe "idempotency_key_reused"
            classifyIntakeFailure(CommerceFailure.Conflict("safe")).kind shouldBe FailureKind.TRANSIENT
            listOf("08006", "40001", "40P01", "57014", "25P04").forEach {
                classifyIntakeFailure(RuntimeException(SQLException("private SQL", it))).kind shouldBe FailureKind.TRANSIENT
            }
            listOf(
                IllegalArgumentException("bug"),
                IllegalStateException("bug"),
                CommerceFailure.InvariantViolated("bug"),
                SQLException("bug", "42P01"),
            ).forEach { classifyIntakeFailure(it).kind shouldBe FailureKind.SYSTEMIC }
            (1L..7L).map(::intakeRetryDelay).map(Duration::toSeconds) shouldBe listOf(10, 30, 120, 300, 900, 900, 900)
        }

        test("ACK happens only after handler returns, and lost ACK retries without false diagnostic") {
            val order = mutableListOf<String>()
            val delivery = TestDelivery().apply { ackFailure = true }
            IntakeProcessor({ order += "committed" }, { _, _ -> error("No diagnostic for lost ACK") }).process(delivery) shouldBe
                IntakeResult.RETRY
            order shouldBe listOf("committed")
            delivery.ack shouldBe 0
            delivery.term shouldBe 0
            delivery.delay shouldBe Duration.ofSeconds(10)
        }

        test("confirmed minimal deterministic diagnostic precedes TERM; failed confirmation leaves original retryable") {
            val delivery = TestDelivery()
            var diagnostic: ByteArray? = null
            val processor =
                IntakeProcessor({ throw IntakeFailure("invalid_event_json", FailureKind.PERMANENT) }, { id, bytes ->
                    delivery.term shouldBe 0
                    id shouldBe "fionas.inquiry.processing-failed.v2:FIONAS_INQUIRIES:42"
                    diagnostic = bytes
                })
            processor.process(delivery) shouldBe IntakeResult.FAILED
            delivery.term shouldBe 1
            val json = Json.parseToJsonElement(checkNotNull(diagnostic).decodeToString()).jsonObject
            json["source"] shouldBe JsonPrimitive("fionas-commerce")
            json["data"]?.jsonObject?.keys shouldBe setOf("inputStream", "inputSequence", "errorCode")
            failureDiagnostic(delivery, "invalid_event_json").second.toList() shouldBe checkNotNull(diagnostic).toList()
            val rejected = TestDelivery()
            IntakeProcessor(
                { throw IntakeFailure("bad", FailureKind.PERMANENT) },
                { _, _ -> throw IntakeFailure("outage", FailureKind.TRANSIENT) },
            ).process(rejected) shouldBe IntakeResult.RETRY
            rejected.term shouldBe 0
            rejected.ack shouldBe 0
        }

        test("exhaustion and unknown failures halt without ACK or TERM") {
            val exhausted = TestDelivery(attempt = 10)
            shouldThrow<IntakeFailure> { IntakeProcessor({ throw SQLException("private", "08006") }, { _, _ -> }).process(exhausted) }
                .code shouldBe "retry_exhausted"
            exhausted.ack shouldBe 0
            exhausted.term shouldBe 0
            shouldThrow<IntakeFailure> { IntakeProcessor({ error("bug") }, { _, _ -> }).process(TestDelivery()) }.kind shouldBe
                FailureKind.SYSTEMIC
        }

        test("shutdown drains one in-flight handler, stops acquisition, and close is idempotent before and after start") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val delivery = TestDelivery()
            val transport =
                object : IntakeTransport {
                    var pulls = 0

                    override fun next(): IntakeDelivery? {
                        pulls++
                        return delivery
                    }

                    override fun publishDiagnostic(
                        id: String,
                        bytes: ByteArray,
                    ) = Unit

                    override fun close() = Unit
                }
            val worker =
                ManagedJetStreamWorker(transport, {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                })
            worker.start()
            worker.start()
            check(entered.await(5, TimeUnit.SECONDS))
            val closer = Thread.ofVirtual().start(worker::close)
            Thread.sleep(50)
            release.countDown()
            closer.join(Duration.ofSeconds(5))
            closer.isAlive shouldBe false
            transport.pulls shouldBe 1
            delivery.ack shouldBe 1
            worker.status.state shouldBe IntakeState.STOPPED
            worker.close()
            ManagedJetStreamWorker(transport, {})
                .apply {
                    close()
                    close()
                }.status.state shouldBe IntakeState.STOPPED
        }
    })
