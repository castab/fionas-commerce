package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.outbox.OutboxEvent
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.RequestedService
import io.github.castab.fionas.commerce.inquiry.RequestedServiceItem
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

        test("canonical stored contact and exact opaque correlation become one immutable complete v3 record") {
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
            val requested =
                RequestedService(
                    75,
                    true,
                    120,
                    listOf(
                        RequestedServiceItem("Horchata", "Soft serve", "horchata"),
                        RequestedServiceItem("Waffle cones", null, null),
                    ),
                    "web-pricing@2026-10-01",
                )
            val command =
                app.inquiryCommand(
                    name = "Submitted different name",
                    email = "JANE@example.com",
                    key = "Case_Sensitive-key-001",
                    requested = requested,
                    lines =
                        listOf(
                            TestLine("Precise rate", "Four servings", "4", "0.125", "0.00"),
                            TestLine("Flat service", unitPrice = "250.00", taxAmount = "20.00"),
                        ),
                )
            val before = counts()
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
            event.schemaVersion shouldBe 3
            event.occurredAt shouldBe inquiry.createdAt
            inquiry.createdAt shouldBe inquiry.createdAt.truncatedTo(ChronoUnit.MICROS)
            val dto = Json.decodeFromString(InquiryPersistedV3.serializer(), record.payloadJson)
            dto.id shouldBe eventId.toString()
            dto.schemaVersion shouldBe 3
            dto.correlationId shouldBe command.submissionKey.value
            dto.occurredAt shouldBe inquiry.createdAt.toString()
            dto.source shouldBe FIONA_EVENT_PRODUCER
            dto.type shouldBe INQUIRY_PERSISTED_TYPE
            dto.aggregate shouldBe InquiryPersistedV3Aggregate("inquiry", inquiry.id.value.toString(), record.aggregateSequence)
            dto.data.customer shouldBe InquiryPersistedV3Customer(customer.id.value.toString(), "Canonical Jane", "jane@example.com")
            dto.data.inquiry shouldBe
                InquiryPersistedV3Inquiry(inquiry.id.value.toString(), "2026-12-05", "BIRTHDAY", "92626", command.message?.value)
            dto.data.requestedService shouldBe
                InquiryPersistedV3RequestedService(
                    75,
                    true,
                    120,
                    listOf(
                        InquiryPersistedV3RequestedItem("Horchata", "Soft serve", "horchata"),
                        InquiryPersistedV3RequestedItem("Waffle cones", null, null),
                    ),
                    "web-pricing@2026-10-01",
                )
            val documentId =
                app.transactor.inTransaction {
                    checkNotNull(
                        JdbiInquiryFinancialDocumentRepository().initialEstimateOf(it, inquiry.id),
                    )
                }
            val estimate = checkNotNull(app.context.financialLedger.latest(documentId))
            val snapshot = dto.data.initialEstimate
            app.transactor.inTransaction {
                JdbiInquiryRepository().findRequested(it, inquiry.id)?.requestedService shouldBe requested
                JdbiInquiryRepository().findById(it, inquiry.id) shouldBe inquiry
                JdbiFinancialDocumentAuthorshipRepository().find(it, estimate.reference)?.author shouldBe command.submittedBy
            }
            snapshot.id shouldBe estimate.id.toString()
            snapshot.version shouldBe estimate.version.number
            snapshot.stage shouldBe "ESTIMATE"
            snapshot.currency shouldBe estimate.currency.currencyCode
            snapshot.total shouldBe estimate.total.amount.toPlainString()
            snapshot.lineItems shouldBe
                estimate.lineItems.map {
                    InquiryPersistedV3Line(
                        it.id.toString(),
                        it.description,
                        it.subDescription,
                        it.quantity?.toPlainString(),
                        it.price.amount.toPlainString(),
                        it.taxAmount.amount.toPlainString(),
                    )
                }
            snapshot.lineItems.first().unitPrice shouldBe "0.125"
            snapshot.lineItems.first().quantity shouldBe "4"
            snapshot.total.toBigDecimal().compareTo("270.50".toBigDecimal()) shouldBe 0
            event.payload shouldBe Json.parseToJsonElement(record.payloadJson)
            app.database.strings("SELECT payload::text FROM commerce.outbox_events WHERE id = '$eventId'").single() shouldBe
                record.payloadJson
            app.database
                .strings(
                    "SELECT data_type FROM information_schema.columns WHERE table_schema='commerce' AND table_name='outbox_events' AND column_name='payload'",
                ).single() shouldBe
                "jsonb"
            validateInquiryEventContract(record.payloadJson)
            tables.forEach { app.database.count(it) shouldBe before.getValue(it) + if (it == "fionas.customers") 0 else 1 }
            val committed = counts()
            app.createInquiryOperation(newEventId = { error("Replay must never generate an event ID") })(command) shouldBe inquiry
            counts() shouldBe committed
        }

        test("payload uses the runtime-assigned sequence even when the aggregate already has history") {
            val inquiryId = InquiryId(UUID.randomUUID())
            app.transactor.inTransaction {
                app.context.outbox.append(
                    it,
                    OutboxEvent(
                        UUID.randomUUID(),
                        FIONA_EVENT_PRODUCER,
                        "inquiry",
                        inquiryId.value.toString(),
                        "test.predecessor",
                        1,
                        "test.predecessor",
                        buildJsonObject {},
                        Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )
            }
            app.createInquiryOperation(newInquiryId = { inquiryId })(app.inquiryCommand())
            val payload =
                app.database
                    .strings(
                        "SELECT payload::text FROM commerce.outbox_events WHERE aggregate_id='${inquiryId.value}' AND schema_version=3",
                    ).single()
            val dto = Json.decodeFromString(InquiryPersistedV3.serializer(), payload)
            dto.aggregate.sequence shouldBe 2L
            app.database.strings("SELECT aggregate_sequence FROM commerce.outbox_events WHERE id='${dto.id}'").single() shouldBe "2"
            validateInquiryEventContract(payload)
        }

        test("explicit null snapshots satisfy the contract") {
            val command = app.inquiryCommand(message = null, requested = RequestedService(1, false, null, emptyList(), null))
            val inquiry = app.createInquiryOperation()(command)
            val payload =
                app.database
                    .strings(
                        "SELECT payload::text FROM commerce.outbox_events WHERE aggregate_id='${inquiry.id.value}'",
                    ).single()
            val dto = Json.decodeFromString(InquiryPersistedV3.serializer(), payload)
            dto.data.inquiry.message shouldBe null
            dto.data.requestedService.durationMinutes shouldBe null
            dto.data.requestedService.pricingReference shouldBe null
            validateInquiryEventContract(payload)
        }

        test("invalid Unicode event content rolls back business writes, sequence and submission claim") {
            val command = app.inquiryCommand(name = "Invalid \uD800 customer")
            val before = counts()
            shouldThrowAny { app.createInquiryOperation()(command) }
            counts() shouldBe before
            // The key was never committed and a corrected submission can use it.
            app.createInquiryOperation()(app.inquiryCommand(key = command.submissionKey.value))
            tables.forEach { app.database.count(it) shouldBe before.getValue(it) + 1 }
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

        test("a submission with an existing historical v2 event replays without v3 backfill or sequence allocation") {
            val command = app.inquiryCommand()
            val inquiry = app.createInquiryOperation()(command)
            // Test-only replacement models a submission committed by the previous v2 producer.
            app.database.execute(
                "DELETE FROM commerce.outbox_events WHERE aggregate_id='${inquiry.id.value}'; DELETE FROM commerce.outbox_aggregates WHERE aggregate_id='${inquiry.id.value}'",
            )
            val id = UUID.randomUUID()
            val payload =
                buildJsonObject {
                    put("schemaVersion", 2)
                    put("type", INQUIRY_PERSISTED_TYPE)
                    put("id", id.toString())
                    put("correlationId", command.submissionKey.value)
                    put("occurredAt", inquiry.createdAt.toString())
                    put("source", FIONA_EVENT_PRODUCER)
                    put(
                        "data",
                        buildJsonObject {
                            put("inquiryId", inquiry.id.value.toString())
                            put("eventDate", inquiry.eventDate.value.toString())
                            put(
                                "customer",
                                buildJsonObject {
                                    put("name", command.name.value)
                                    put("email", command.email.value)
                                },
                            )
                        },
                    )
                }
            app.transactor.inTransaction {
                app.context.outbox.append(
                    it,
                    OutboxEvent(
                        id,
                        FIONA_EVENT_PRODUCER,
                        "inquiry",
                        inquiry.id.value.toString(),
                        INQUIRY_PERSISTED_TYPE,
                        2,
                        "fionas.events.inquiry.persisted.v2",
                        payload,
                        inquiry.createdAt,
                    ),
                )
            }
            val before = counts()
            app.createInquiryOperation(newEventId = { error("Historical replay must not construct an event") })(command) shouldBe inquiry
            counts() shouldBe before
            app.database.strings("SELECT payload::text FROM commerce.outbox_events WHERE id='$id'").single().let {
                Json.parseToJsonElement(it)
            } shouldBe
                payload
            app.database
                .strings(
                    "SELECT last_sequence FROM commerce.outbox_aggregates WHERE aggregate_id='${inquiry.id.value}'",
                ).single() shouldBe
                "1"
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
