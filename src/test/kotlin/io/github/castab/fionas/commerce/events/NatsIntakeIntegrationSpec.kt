package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.fionas.commerce.fionaApplication
import io.github.castab.fionas.commerce.testing.TEST_SERVICE_TOKENS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.github.castab.fionas.commerce.testing.TestNats
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.DeliverPolicy
import io.nats.client.api.ReplayPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun intakeConfig(
    broker: TestNats,
    user: String = "intake",
): NatsIntakeConfiguration =
    checkNotNull(
        NatsIntakeConfiguration.fromEnvironment(
            mapOf(
                "FIONAS_INTAKE_ENABLED" to "true",
                "FIONAS_INTAKE_NATS_URL" to broker.url,
                "FIONAS_INTAKE_NATS_USER" to user,
                "FIONAS_INTAKE_NATS_PASSWORD" to "test-$user",
            ),
        ),
    )

internal fun inputConsumerConfiguration(): ConsumerConfiguration =
    ConsumerConfiguration
        .builder()
        .durable(INQUIRY_CONSUMER)
        .name(INQUIRY_CONSUMER)
        .filterSubject(INQUIRY_INPUT_SUBJECT)
        .ackPolicy(AckPolicy.Explicit)
        .deliverPolicy(DeliverPolicy.All)
        .replayPolicy(ReplayPolicy.Instant)
        .ackWait(Duration.ofSeconds(90))
        .maxDeliver(10)
        .maxAckPending(1)
        .maxPullWaiting(2)
        .maxBatch(1)
        .maxExpires(Duration.ofSeconds(5))
        .build()

internal fun awaitIntake(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (!condition()) {
        check(System.nanoTime() < deadline) { "Intake condition did not converge" }
        Thread.sleep(50)
    }
}

class NatsIntakeIntegrationSpec :
    FunSpec({
        lateinit var broker: TestNats
        beforeSpec { broker = TestNats() }
        beforeTest {
            broker.resetStream()
            broker.admin().use {
                val management = it.jetStreamManagement()
                if (INQUIRY_INPUT_STREAM in management.streamNames) management.deleteStream(INQUIRY_INPUT_STREAM)
                management.addStream(
                    StreamConfiguration
                        .builder()
                        .name(INQUIRY_INPUT_STREAM)
                        .subjects("fionas.inquiries.>")
                        .storageType(StorageType.File)
                        .build(),
                )
                management.addOrUpdateConsumer(INQUIRY_INPUT_STREAM, inputConsumerConfiguration())
            }
        }
        afterSpec { broker.close() }

        test("transient database failure rolls back every fact and later redelivery succeeds") {
            TestApplication.create().use { app ->
                app.database.execute(
                    """
                    CREATE FUNCTION fionas.reject_test_inquiry() RETURNS trigger LANGUAGE plpgsql AS
                    'BEGIN RAISE EXCEPTION SQLSTATE ''57P03''; END';
                    CREATE TRIGGER reject_test_inquiry BEFORE INSERT ON fionas.inquiries
                    FOR EACH ROW EXECUTE FUNCTION fionas.reject_test_inquiry();
                    """.trimIndent(),
                )
                val config =
                    intakeConfig(
                        broker,
                    )
                val failed = CountDownLatch(1)
                val real = inquiryIntakeHandler(app.context, testClock)
                val handler: (ByteArray) -> Unit = { bytes ->
                    try {
                        real(bytes)
                    } catch (error: Exception) {
                        failed.countDown()
                        throw error
                    }
                }
                broker.admin().use { operator ->
                    operator.jetStream().publish(INQUIRY_INPUT_SUBJECT, submittedFixture().toByteArray())
                    ManagedJetStreamWorker(NatsIntakeTransport(config), handler).use { worker ->
                        worker.start()
                        check(failed.await(10, TimeUnit.SECONDS))
                        listOf(
                            "fionas.inquiries",
                            "fionas.customers",
                            "fionas.inquiry_submissions",
                            "commerce.financial_document_snapshots",
                        ).forEach { app.database.count(it) shouldBe 0 }
                        app.context.outbox
                            .backlog(FIONA_EVENT_PRODUCER)
                            .unpublished shouldBe 0
                        app.database.execute("DROP TRIGGER reject_test_inquiry ON fionas.inquiries")
                        awaitIntake { app.database.count("fionas.inquiries") == 1 }
                        awaitIntake {
                            operator.jetStreamManagement().getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER).numAckPending == 0L
                        }
                        worker.status.state shouldBe IntakeState.RUNNING
                    }
                }
            }
        }

        test("broker unavailable at start and disconnected later both recover without stopping HTTP") {
            TestApplication.create().use { app ->
                val config =
                    intakeConfig(
                        broker,
                    )
                broker.admin().use { it.jetStream().publish(INQUIRY_INPUT_SUBJECT, submittedFixture().toByteArray()) }
                broker.stop()
                try {
                    ManagedJetStreamWorker(
                        NatsIntakeTransport(config),
                        inquiryIntakeHandler(app.context, testClock),
                    ).use { worker ->
                        worker.start()
                        awaitIntake { worker.status.state == IntakeState.RETRYING }
                        app.http(Request(Method.GET, "/health")).status shouldBe Status.OK
                        broker.start()
                        awaitIntake { app.database.count("fionas.inquiries") == 1 }
                        broker.admin().use { operator ->
                            awaitIntake {
                                operator.jetStreamManagement().getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER).numAckPending == 0L
                            }
                        }
                        broker.stop()
                        Thread.sleep(2_500)
                        app.http(Request(Method.GET, "/health")).status shouldBe Status.OK
                        broker.start()
                        broker.admin().use { it.jetStream().publish(INQUIRY_INPUT_SUBJECT, submittedFixture().toByteArray()) }
                        awaitIntake { app.database.count("fionas.inquiries") == 2 }
                    }
                } finally {
                    broker.start()
                }
            }
        }

        test("runtime composes enabled intake and outbox independently and shuts down during a real transaction") {
            TestDatabase.create().use { database ->
                val config = intakeConfig(broker)
                val fiona = fionaApplication(bootstrap = null, outboxPublisher = broker.publisherConfiguration(), intake = config)
                val runtime =
                    commerceRuntime(
                        CommerceRuntimeConfiguration(
                            server = CommerceRuntimeConfiguration.Server(port = 0),
                            database = boundedIntakeDatabase(database.configuration),
                            migrations =
                                CommerceRuntimeConfiguration.Migrations(
                                    onStartup = CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE,
                                ),
                            serviceTokens = TEST_SERVICE_TOKENS,
                        ),
                        ApplicationContributions(
                            migrations = fiona.migrations,
                            permissionDefinitions = fiona.permissionDefinitions,
                            backgroundServices = fiona.backgroundServices,
                            routes = fiona.routes,
                        ),
                    )
                runtime.use {
                    database.connect { lock ->
                        lock.autoCommit = false
                        lock.createStatement().use { it.execute("LOCK TABLE fionas.inquiries IN ACCESS EXCLUSIVE MODE") }
                        broker.admin().use { it.jetStream().publish(INQUIRY_INPUT_SUBJECT, submittedFixture().toByteArray()) }
                        runtime.start()
                        awaitIntake {
                            database
                                .strings(
                                    "SELECT wait_event FROM pg_stat_activity " +
                                        "WHERE datname = current_database() AND wait_event = 'relation'",
                                ).isNotEmpty()
                        }
                        val closer = Thread.ofVirtual().start(runtime::close)
                        Thread.sleep(100)
                        lock.commit()
                        closer.join(Duration.ofSeconds(20))
                        closer.isAlive shouldBe false
                        database.count("fionas.inquiries") shouldBe 1
                        database.count("commerce.financial_document_snapshots") shouldBe 1
                        broker.admin().use { operator ->
                            operator.jetStreamManagement().getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER).numAckPending shouldBe 0
                        }
                    }
                }
            }
        }

        test("disabled external SERVICE does not affect trusted internal intake") {
            TestApplication.create().use { app ->
                app.authorization.setStatus(app.web.id, PrincipalStatus.DISABLED)
                val config =
                    intakeConfig(
                        broker,
                    )
                broker.admin().use { it.jetStream().publish(INQUIRY_INPUT_SUBJECT, submittedFixture().toByteArray()) }
                ManagedJetStreamWorker(
                    NatsIntakeTransport(config),
                    inquiryIntakeHandler(app.context, testClock),
                ).use { worker ->
                    worker.start()
                    awaitIntake { app.database.count("fionas.inquiries") == 1 }
                    worker.status.state shouldBe IntakeState.RUNNING
                    app.context.outbox
                        .backlog(FIONA_EVENT_PRODUCER)
                        .unpublished shouldBe 1
                }
            }
        }

        test("real intake commits inquiry Estimate authorship and outbox before ACK; only outbox publishes success; HTTP parity") {
            TestApplication.create().use { app ->
                val config =
                    intakeConfig(
                        broker,
                    )
                val input = submittedFixture()
                val handler = inquiryIntakeHandler(app.context, testClock)
                val committed = CountDownLatch(1)
                val releaseAck = CountDownLatch(1)
                broker.admin().use { operator ->
                    val management = operator.jetStreamManagement()
                    val before = management.getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER)
                    operator.jetStream().publish(INQUIRY_INPUT_SUBJECT, input.toByteArray())
                    ManagedJetStreamWorker(NatsIntakeTransport(config), { bytes ->
                        handler(bytes)
                        committed.countDown()
                        check(releaseAck.await(10, TimeUnit.SECONDS))
                    }).use { worker ->
                        worker.start()
                        check(committed.await(10, TimeUnit.SECONDS))
                        app.database.count("fionas.inquiries") shouldBe 1
                        app.database.count("commerce.financial_document_snapshots") shouldBe 1
                        app.context.outbox
                            .backlog(FIONA_EVENT_PRODUCER)
                            .unpublished shouldBe 1
                        management.getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER).numAckPending shouldBe 1
                        management.getStreamInfo(FIONA_EVENTS_STREAM).streamState.msgCount shouldBe 0
                        app.database.strings("SELECT author_component FROM fionas.financial_document_authorship") shouldBe
                            listOf("fionas.inquiry-intake")
                        releaseAck.countDown()
                        awaitIntake { management.getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER).numAckPending == 0L }
                        val after = management.getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER)
                        after.creationTime shouldBe before.creationTime
                        after.consumerConfiguration.toJson() shouldBe before.consumerConfiguration.toJson()
                        InquiryOutboxPublisher(app.context, broker.publisherConfiguration()).use { publisher ->
                            publisher.start()
                            awaitIntake { management.getStreamInfo(FIONA_EVENTS_STREAM).streamState.msgCount == 1L }
                            awaitIntake {
                                app.context.outbox
                                    .backlog(FIONA_EVENT_PRODUCER)
                                    .unpublished == 0L
                            }
                        }
                        val body =
                            Json
                                .parseToJsonElement(input)
                                .jsonObject
                                .getValue("data")
                                .jsonObject
                        val response =
                            app.http(
                                Request(Method.POST, "/inquiries")
                                    .header("Authorization", "Bearer ${app.webToken}")
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .header("Content-Type", "application/json")
                                    .body(JsonObject(body.filterKeys { it != "priceRevision" }).toString()),
                            )
                        response.status shouldBe Status.CREATED
                        app.database
                            .strings("SELECT requested_service::text FROM fionas.inquiries")
                            .distinct()
                            .size shouldBe 1
                        val snapshots =
                            app.database
                                .strings("SELECT lines::text FROM commerce.financial_document_snapshots")
                                .map { Json.parseToJsonElement(it).toString().replace(Regex("\"id\":\"[^\"]+\""), "\"id\":\"generated\"") }
                        snapshots.distinct().size shouldBe 1
                    }
                }
            }
        }

        test("real redelivery after lost ACK reuses committed facts; changed intent records diagnostic then TERM") {
            TestApplication.create().use { app ->
                val config =
                    intakeConfig(
                        broker,
                    )
                val handler = inquiryIntakeHandler(app.context, testClock)
                val input = submittedFixture()
                val lost = AtomicBoolean(false)
                val transport = NatsIntakeTransport(config)
                val loseAck =
                    object : IntakeTransport by transport {
                        override fun next(): IntakeDelivery? =
                            transport.next()?.let { delivery ->
                                object : IntakeDelivery by delivery {
                                    override fun acknowledge() {
                                        if (lost.compareAndSet(false, true)) throw java.util.concurrent.TimeoutException()
                                        delivery.acknowledge()
                                    }
                                }
                            }
                    }
                broker.admin().use { operator ->
                    val js = operator.jetStream()
                    js.publish(INQUIRY_INPUT_SUBJECT, input.toByteArray())
                    ManagedJetStreamWorker(loseAck, handler).use { worker ->
                        worker.start()
                        awaitIntake { lost.get() }
                        app.database.count("fionas.inquiries") shouldBe 1
                        awaitIntake {
                            operator
                                .jetStreamManagement()
                                .getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER)
                                .ackFloor.streamSequence >=
                                1
                        }
                        app.database.count("fionas.inquiries") shouldBe 1
                        app.database.count("commerce.financial_document_snapshots") shouldBe 1
                        app.context.outbox
                            .backlog(FIONA_EVENT_PRODUCER)
                            .unpublished shouldBe 1
                        js.publish(INQUIRY_INPUT_SUBJECT, input.replace("0.125000000000", "0.250000000000").toByteArray())
                        awaitIntake {
                            operator
                                .jetStreamManagement()
                                .getStreamInfo(FIONA_EVENTS_STREAM)
                                .streamState.msgCount == 1L
                        }
                        val failure = operator.jetStreamManagement().getMessage(FIONA_EVENTS_STREAM, 1)
                        failure.subject shouldBe INQUIRY_FAILURE_SUBJECT
                        Json
                            .parseToJsonElement(checkNotNull(failure.data).decodeToString())
                            .jsonObject
                            .getValue("data")
                            .jsonObject
                            .getValue("errorCode")
                            .jsonPrimitive.content shouldBe "idempotency_key_reused"
                        app.database.count("fionas.inquiries") shouldBe 1
                    }
                    val before = operator.jetStreamManagement().getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER)
                    recoverInquiry(
                        1,
                        operator.jetStreamManagement().getMessage(INQUIRY_INPUT_STREAM, 1),
                        handler,
                        transport::publishDiagnostic,
                    ) shouldBe
                        IntakeResult.PERSISTED
                    val after = operator.jetStreamManagement().getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER)
                    after.ackFloor.streamSequence shouldBe before.ackFloor.streamSequence
                    after.delivered.streamSequence shouldBe before.delivered.streamSequence
                    app.database.count("fionas.inquiries") shouldBe 1
                    app.database.strings("SELECT author_kind || ':' || author_component FROM fionas.financial_document_authorship") shouldBe
                        listOf("INTERNAL:fionas.inquiry-intake")
                }
            }
        }

        test("malformed input publishes minimal confirmed v2 diagnostic and releases pending slot for valid input") {
            TestApplication.create().use { app ->
                val config =
                    intakeConfig(
                        broker,
                    )
                broker.admin().use { operator ->
                    operator.jetStream().publish(INQUIRY_INPUT_SUBJECT, byteArrayOf(0xc3.toByte(), 0x28))
                    operator.jetStream().publish(INQUIRY_INPUT_SUBJECT, submittedFixture().toByteArray())
                    ManagedJetStreamWorker(
                        NatsIntakeTransport(config),
                        inquiryIntakeHandler(app.context, testClock),
                    ).use { worker ->
                        worker.start()
                        awaitIntake { app.database.count("fionas.inquiries") == 1 }
                        val failure = operator.jetStreamManagement().getMessage(FIONA_EVENTS_STREAM, 1)
                        failure.subject shouldBe INQUIRY_FAILURE_SUBJECT
                        val diagnostic = Json.parseToJsonElement(checkNotNull(failure.data).decodeToString()).jsonObject
                        diagnostic.keys shouldBe setOf("schemaVersion", "type", "id", "occurredAt", "source", "data")
                        diagnostic.getValue("id").jsonPrimitive.content shouldBe "fionas.inquiry.processing-failed.v2:FIONAS_INQUIRIES:1"
                        operator.jetStreamManagement().getMessage(INQUIRY_INPUT_STREAM, 1).seq shouldBe 1
                    }
                }
            }
        }

        test("diagnostic permission failure halts intake and preserves original; HTTP and outbox remain available") {
            TestApplication.create().use { app ->
                val config =
                    intakeConfig(
                        broker,
                        "intake-no-diagnostic",
                    )
                broker.admin().use { operator ->
                    operator.jetStream().publish(INQUIRY_INPUT_SUBJECT, "{".toByteArray())
                    ManagedJetStreamWorker(
                        NatsIntakeTransport(config),
                        inquiryIntakeHandler(app.context, testClock),
                    ).use { worker ->
                        worker.start()
                        awaitIntake { worker.status.state == IntakeState.HALTED }
                        operator.jetStreamManagement().getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER).numAckPending shouldBe 1
                        operator
                            .jetStreamManagement()
                            .getStreamInfo(FIONA_EVENTS_STREAM)
                            .streamState.msgCount shouldBe 0
                        app.http(Request(Method.GET, "/health")).status shouldBe Status.OK
                        inquiryIntakeHandler(
                            app.context,
                            testClock,
                        )(submittedFixture().toByteArray())
                        InquiryOutboxPublisher(app.context, broker.publisherConfiguration()).use { publisher ->
                            publisher.start()
                            awaitIntake {
                                app.context.outbox
                                    .backlog(FIONA_EVENT_PRODUCER)
                                    .unpublished == 0L
                            }
                        }
                    }
                }
            }
        }

        test("consumer mismatch and missing INFO permissions halt without modifying durable") {
            TestApplication.create().use { app ->
                broker.admin().use { operator ->
                    val management = operator.jetStreamManagement()
                    val changed = ConsumerConfiguration.builder(inputConsumerConfiguration()).maxBatch(2).build()
                    management.addOrUpdateConsumer(INQUIRY_INPUT_STREAM, changed)
                    ManagedJetStreamWorker(
                        NatsIntakeTransport(
                            intakeConfig(
                                broker,
                            ),
                        ),
                        {},
                    ).use { worker ->
                        worker.start()
                        awaitIntake { worker.status.state == IntakeState.HALTED }
                        worker.status.code shouldBe "consumer_configuration_mismatch"
                    }
                    management.getConsumerInfo(INQUIRY_INPUT_STREAM, INQUIRY_CONSUMER).consumerConfiguration.maxBatch shouldBe 2
                    ManagedJetStreamWorker(
                        NatsIntakeTransport(
                            intakeConfig(
                                broker,
                                "denied",
                            ),
                        ),
                        {},
                    ).use { worker ->
                        worker.start()
                        awaitIntake { worker.status.state == IntakeState.HALTED }
                    }
                }
            }
        }
    })
