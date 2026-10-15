package io.github.castab.fionas.commerce.events

import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class InquiryOutboxSpec :
    FunSpec({
        lateinit var app: TestApplication
        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }

        val tables =
            listOf(
                "fionas.customers",
                "fionas.inquiries",
                "fionas.inquiry_submissions",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
                "fionas.financial_document_authorship",
                "commerce.outbox_events",
                "commerce.outbox_aggregates",
            )

        fun counts() = tables.associateWith(app.database::count)

        test("canonical stored contact and exact opaque correlation become one immutable v2 record") {
            val customer =
                Customer(
                    CustomerId(UUID.randomUUID()),
                    CustomerName("Canonical Jane"),
                    Email.of(" JANE@example.com "),
                    Instant.parse("2026-01-01T00:00:00Z"),
                )
            app.transactor.inTransaction {
                JdbiCustomerRepository().insert(it, customer)
            }
            val eventId = UUID.randomUUID()
            val command = app.inquiryCommand(name = "Submitted different name", email = "JANE@example.com", key = "Case_Sensitive-key-001")
            val inquiry = app.createInquiryOperation(newEventId = { eventId })(command)
            inquiry.customerId shouldBe customer.id
            val publisher = app.context.outbox.publisher(FIONA_EVENT_PRODUCER, "contract-test")
            val record = publisher.claim(100, Duration.ofMinutes(1)).single { it.record.event.id == eventId }.record
            val event = record.event
            event.id shouldBe eventId
            event.producerId shouldBe FIONA_EVENT_PRODUCER
            event.aggregateType shouldBe "inquiry"
            event.aggregateId shouldBe inquiry.id.value.toString()
            record.aggregateSequence shouldBe 1L
            event.destination shouldBe INQUIRY_PERSISTED_SUBJECT
            event.eventType shouldBe INQUIRY_PERSISTED_TYPE
            event.schemaVersion shouldBe 2
            event.occurredAt shouldBe inquiry.createdAt
            inquiry.createdAt shouldBe inquiry.createdAt.truncatedTo(ChronoUnit.MICROS)
            val dto = Json.decodeFromString(InquiryPersistedV2.serializer(), event.payload)
            dto shouldBe
                InquiryPersistedV2(
                    2,
                    INQUIRY_PERSISTED_TYPE,
                    eventId.toString(),
                    command.submissionKey.value,
                    inquiry.createdAt.toString(),
                    FIONA_EVENT_PRODUCER,
                    InquiryPersistedData(
                        inquiry.id.value.toString(),
                        InquiryPersistedCustomer("Canonical Jane", "jane@example.com"),
                        "2026-12-05",
                    ),
                )
            event.payload shouldBe Json.encodeToString(InquiryPersistedV2.serializer(), dto)
            // Exact keys checked against AsyncAPI too; no note, ZIP, requested service or financial facts.
            validateInquiryEventContract(event.payload)
            val committed = counts()
            app.createInquiryOperation(newEventId = { error("Replay must never generate an event ID") })(command) shouldBe inquiry
            counts() shouldBe committed
        }

        test("an outbox append failure after materialization rolls back every business row and the claim; the key can retry") {
            val command = app.inquiryCommand()
            val before = counts()
            app.database.execute(
                """
                CREATE FUNCTION fionas.reject_outbox() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'injected outbox append failure'; END; ${'$'}${'$'};
                CREATE TRIGGER reject_outbox AFTER INSERT ON commerce.outbox_events
                FOR EACH ROW EXECUTE FUNCTION fionas.reject_outbox();
                """.trimIndent(),
            )
            try {
                shouldThrowAny { app.createInquiryOperation()(command) }
                counts() shouldBe before
            } finally {
                app.database.execute("DROP TRIGGER reject_outbox ON commerce.outbox_events; DROP FUNCTION fionas.reject_outbox()")
            }
            app.createInquiryOperation()(command)
            tables.forEach { app.database.count(it) shouldBe before.getValue(it) + 1 }
        }

        test("a committed historical submission without an outbox record replays without backfilling") {
            val command = app.inquiryCommand()
            val create = app.createInquiryOperation()
            val inquiry = create(command)
            // Test-only simulation of an inquiry committed by the pre-outbox application.
            app.database.execute("DELETE FROM commerce.outbox_events WHERE aggregate_id = '${inquiry.id.value}'")
            val before = counts()
            create(command) shouldBe inquiry
            counts() shouldBe before
            app.transactor.inTransaction { JdbiInquiryRepository().findById(it, inquiry.id) } shouldBe inquiry
        }
    })
