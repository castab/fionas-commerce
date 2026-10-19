package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.outbox.OutboxFailureCode
import io.github.castab.commerce.runtime.outbox.PublicationResult
import io.github.castab.fionas.commerce.fionaApplication
import io.github.castab.fionas.commerce.testing.TEST_SERVICE_TOKENS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.github.castab.fionas.commerce.testing.TestNats
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.github.castab.fionas.commerce.testing.requestedServiceJson
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.nats.client.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class NatsOutboxIntegrationSpec :
    FunSpec({
        lateinit var broker: TestNats
        beforeSpec { broker = TestNats() }
        beforeTest { broker.resetStream() }
        afterSpec { broker.close() }

        fun waitUntil(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Expected eventual outbox publication" }
                Thread.sleep(100)
            }
        }

        test("least-privilege publication preserves payload, identity and business sequence; duplicate PubAck confirms") {
            TestApplication.create().use { app ->
                val inquiry = app.createInquiryOperation()(app.inquiryCommand())
                val publisher = app.context.outbox.publisher(FIONA_EVENT_PRODUCER, "publication-test")
                val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                val event = claim.record.event
                broker.admin().use { observer ->
                    val sub = observer.subscribe(INQUIRY_PERSISTED_SUBJECT)
                    observer.flush(Duration.ofSeconds(1))
                    NatsOutboxTransport(broker.publisherConfiguration()).use { transport ->
                        transport.publish(claim.record) shouldBe PublicationResult.CONFIRMED
                        transport.publish(claim.record) shouldBe PublicationResult.CONFIRMED
                        val received = sub.nextMessage(Duration.ofSeconds(5))
                        received.subject shouldBe event.destination
                        received.data.toList() shouldBe
                            claim.record.payloadJson
                                .toByteArray(Charsets.UTF_8)
                                .toList()
                        received.headers.getFirst("Nats-Msg-Id") shouldBe event.id.toString()
                        received.headers.getFirst("Nats-Expected-Stream") shouldBe FIONA_EVENTS_STREAM
                        received.headers.getFirst(PRODUCER_HEADER) shouldBe FIONA_EVENT_PRODUCER
                        received.headers.getFirst(AGGREGATE_TYPE_HEADER) shouldBe "inquiry"
                        received.headers.getFirst(AGGREGATE_ID_HEADER) shouldBe inquiry.id.value.toString()
                        received.headers.getFirst(AGGREGATE_SEQUENCE_HEADER) shouldBe "1"
                        Json
                            .decodeFromString(
                                InquiryPersistedV3.serializer(),
                                received.data.toString(Charsets.UTF_8),
                            ).aggregate.sequence shouldBe
                            claim.record.aggregateSequence
                        observer
                            .jetStreamManagement()
                            .getStreamInfo(FIONA_EVENTS_STREAM)
                            .streamState.msgCount shouldBe 1L
                        publisher.confirm(claim) shouldBe true
                        app.context.outbox
                            .backlog(FIONA_EVENT_PRODUCER)
                            .unpublished shouldBe 0L
                    }
                    sub.unsubscribe()
                }
            }
        }

        test("historical v2 text migrates to JSONB and publishes its original destination and content") {
            TestDatabase.create().use { database ->
                // Test-only setup uses the actual released runtime migrations, stopping before JSONB.
                val configuration = database.configuration
                // Flyway is runtime-only, so this setup deliberately adds no application dependency.
                val flywayType = Class.forName("org.flywaydb.core.Flyway")
                val migration = flywayType.getMethod("configure").invoke(null)
                val fluent = migration.javaClass
                fluent
                    .getMethod("dataSource", String::class.java, String::class.java, String::class.java)
                    .invoke(migration, configuration.jdbcUrl, configuration.username, configuration.password)
                fluent.getMethod("defaultSchema", String::class.java).invoke(migration, "commerce")
                fluent.getMethod("schemas", Array<String>::class.java).invoke(migration, arrayOf("commerce") as Any)
                fluent.getMethod("locations", Array<String>::class.java).invoke(migration, arrayOf("classpath:db/commerce") as Any)
                fluent.getMethod("target", String::class.java).invoke(migration, "2")
                flywayType.getMethod("migrate").invoke(fluent.getMethod("load").invoke(migration))
                val historical =
                    Json
                        .parseToJsonElement(File("asyncapi.json").readText())
                        .jsonObject
                        .getValue("components")
                        .jsonObject
                        .getValue("messages")
                        .jsonObject
                        .getValue("InquiryPersistedV2")
                        .jsonObject
                        .getValue(
                            "examples",
                        ).let { it as JsonArray }
                        .single()
                        .jsonObject
                        .getValue("payload")
                        .jsonObject
                val eventId = historical.getValue("id").jsonPrimitive.content
                val aggregateId =
                    historical
                        .getValue("data")
                        .jsonObject
                        .getValue("inquiryId")
                        .toString()
                        .trim('"')
                database.execute("INSERT INTO commerce.outbox_aggregates VALUES ('fionas-commerce', 'inquiry', '$aggregateId', 1)")
                database.connect { connection ->
                    connection
                        .prepareStatement(
                            """INSERT INTO commerce.outbox_events
                        (id, producer_id, aggregate_type, aggregate_id, aggregate_sequence, event_type, schema_version, destination, payload, occurred_at)
                        VALUES (?, 'fionas-commerce', 'inquiry', ?, 1, 'fionas.inquiry.persisted', 2, 'fionas.events.inquiry.persisted.v2', ?, '2026-10-15T18:00:00.123456Z')""",
                        ).use {
                            it.setObject(1, UUID.fromString(eventId))
                            it.setString(2, aggregateId)
                            it.setString(3, historical.toString())
                            it.executeUpdate()
                        }
                }
                lateinit var context: CommerceRuntimeContext
                commerceRuntime(
                    CommerceRuntimeConfiguration(
                        server = CommerceRuntimeConfiguration.Server(port = 0),
                        database = configuration,
                        migrations =
                            CommerceRuntimeConfiguration.Migrations(
                                onStartup = CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE,
                            ),
                        serviceTokens = TEST_SERVICE_TOKENS,
                    ),
                    ApplicationContributions(routes = {
                        context = it
                        emptyList()
                    }),
                ).use {
                    database.strings("SELECT pg_typeof(payload)::text FROM commerce.outbox_events").single() shouldBe "jsonb"
                    val publisher = context.outbox.publisher(FIONA_EVENT_PRODUCER, "v2-transition")
                    val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                    claim.record.event.payload shouldBe historical
                    claim.record.event.id
                        .toString() shouldBe eventId
                    claim.record.event.schemaVersion shouldBe 2
                    claim.record.event.destination shouldBe "fionas.events.inquiry.persisted.v2"
                    // The normal identity is v3-only; the temporary transition identity can drain v2.
                    NatsOutboxTransport(broker.publisherConfiguration()).use { transport ->
                        transport.publish(claim.record) shouldBe PublicationResult.UNCONFIRMED
                    }
                    NatsOutboxTransport(broker.publisherConfiguration("transition")).use { transport ->
                        transport.publish(claim.record) shouldBe PublicationResult.CONFIRMED
                    }
                    broker.admin().use { observer ->
                        val stored = observer.jetStreamManagement().getMessage(FIONA_EVENTS_STREAM, 1)
                        stored.subject shouldBe "fionas.events.inquiry.persisted.v2"
                        checkNotNull(stored.data).toList() shouldBe
                            claim.record.payloadJson
                                .toByteArray(Charsets.UTF_8)
                                .toList()
                        Json.parseToJsonElement(checkNotNull(stored.data).toString(Charsets.UTF_8)) shouldBe historical
                    }
                    publisher.confirm(claim) shouldBe true
                }
            }
        }

        test("permission denial retains a retryable event and cannot finalize delivery") {
            TestApplication.create().use { app ->
                app.createInquiryOperation()(app.inquiryCommand())
                val publisher = app.context.outbox.publisher(FIONA_EVENT_PRODUCER, "denial-test")
                val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                NatsOutboxTransport(broker.publisherConfiguration("denied")).use {
                    it.publish(claim.record) shouldBe PublicationResult.UNCONFIRMED
                }
                publisher.fail(claim, OutboxFailureCode.UNCONFIRMED, Duration.ofMillis(1)) shouldBe true
                app.context.outbox
                    .backlog(FIONA_EVENT_PRODUCER)
                    .unpublished shouldBe 1L
                app.context.outbox
                    .backlog(FIONA_EVENT_PRODUCER)
                    .failed shouldBe 1L
            }
        }

        listOf("""{"stream":"WRONG_STREAM","seq":1}""", """{"stream":"FIONAS_EVENTS","seq":0}""", "not-json").forEach { response ->
            test("invalid JetStream confirmation $response never confirms the pending event") {
                TestApplication.create().use { app ->
                    app.createInquiryOperation()(app.inquiryCommand())
                    val claim =
                        app.context.outbox
                            .publisher(FIONA_EVENT_PRODUCER, "invalid-ack-test")
                            .claim(1, Duration.ofMinutes(1))
                            .single()
                    broker.admin().use { responder ->
                        responder.jetStreamManagement().deleteStream(FIONA_EVENTS_STREAM)
                        val dispatcher =
                            responder.createDispatcher { message ->
                                responder.publish(message.replyTo, response.toByteArray())
                            }
                        dispatcher.subscribe(INQUIRY_PERSISTED_SUBJECT)
                        responder.flush(Duration.ofSeconds(1))
                        NatsOutboxTransport(broker.publisherConfiguration()).use {
                            it.publish(claim.record) shouldBe
                                PublicationResult.UNCONFIRMED
                        }
                        app.context.outbox
                            .backlog(FIONA_EVENT_PRODUCER)
                            .unpublished shouldBe 1L
                        responder.closeDispatcher(dispatcher)
                    }
                }
            }
        }

        test("lost acknowledgment retries identical bytes and identity after reconnect") {
            TestApplication.create().use { app ->
                app.createInquiryOperation()(app.inquiryCommand())
                val publisher = app.context.outbox.publisher(FIONA_EVENT_PRODUCER, "timeout-test")
                val claim = publisher.claim(1, Duration.ofMinutes(1)).single()
                broker.admin().use { observer ->
                    observer.jetStreamManagement().deleteStream(FIONA_EVENTS_STREAM)
                    val attempted = CompletableFuture<Message>()
                    val listener = observer.createDispatcher { attempted.complete(it) } // Deliberately lose the acknowledgment.
                    listener.subscribe(INQUIRY_PERSISTED_SUBJECT)
                    observer.flush(Duration.ofSeconds(1))
                    NatsOutboxTransport(broker.publisherConfiguration()).use { transport ->
                        transport.publish(claim.record) shouldBe PublicationResult.UNCONFIRMED
                        val original = attempted.get(5, TimeUnit.SECONDS)
                        original.headers.getFirst("Nats-Msg-Id") shouldBe
                            claim.record.event.id
                                .toString()
                        original.data.toList() shouldBe
                            claim.record.payloadJson
                                .toByteArray()
                                .toList()
                        publisher.fail(claim, OutboxFailureCode.UNCONFIRMED, Duration.ofMillis(1)) shouldBe true
                        observer.closeDispatcher(listener)
                        broker.resetStream()
                        broker.stop()
                        transport.publish(claim.record) shouldBe PublicationResult.UNCONFIRMED
                        broker.start()
                        val retry = publisher.claim(1, Duration.ofMinutes(1)).single()
                        retry.record.event shouldBe claim.record.event
                        retry.record.payloadJson shouldBe claim.record.payloadJson
                        waitUntil { transport.publish(retry.record) == PublicationResult.CONFIRMED }
                        publisher.confirm(retry) shouldBe true
                    }
                }
            }
        }

        test("closing during a pending publication releases network resources without waiting for PubAck") {
            TestApplication.create().use { app ->
                app.createInquiryOperation()(app.inquiryCommand())
                val claim =
                    app.context.outbox
                        .publisher(FIONA_EVENT_PRODUCER, "shutdown-test")
                        .claim(1, Duration.ofMinutes(1))
                        .single()
                broker.admin().use { observer ->
                    observer.jetStreamManagement().deleteStream(FIONA_EVENTS_STREAM)
                    val received = CompletableFuture<Message>()
                    val listener = observer.createDispatcher { received.complete(it) }
                    listener.subscribe(INQUIRY_PERSISTED_SUBJECT)
                    observer.flush(Duration.ofSeconds(1))
                    NatsOutboxTransport(broker.publisherConfiguration()).use { transport ->
                        val publication = CompletableFuture.supplyAsync { transport.publish(claim.record) }
                        received.get(5, TimeUnit.SECONDS)
                        CompletableFuture.runAsync { transport.close() }.get(2, TimeUnit.SECONDS)
                        publication.get(2, TimeUnit.SECONDS) shouldBe PublicationResult.UNCONFIRMED
                        transport.close()
                        app.context.outbox
                            .backlog(FIONA_EVENT_PRODUCER)
                            .unpublished shouldBe 1L
                    }
                    observer.closeDispatcher(listener)
                }
            }
        }

        test("external acceptance followed by failed database confirmation recovers the same event after lease expiry") {
            TestApplication.create().use { app ->
                app.createInquiryOperation()(app.inquiryCommand())
                val publisher = app.context.outbox.publisher(FIONA_EVENT_PRODUCER, "crashed-process")
                val claim = publisher.claim(1, Duration.ofSeconds(2)).single()
                NatsOutboxTransport(broker.publisherConfiguration()).use { transport ->
                    transport.publish(claim.record) shouldBe PublicationResult.CONFIRMED
                    app.database.execute(
                        """
                        CREATE FUNCTION fionas.reject_confirmation() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                        BEGIN IF NEW.published_at IS NOT NULL THEN RAISE EXCEPTION 'confirmation failed'; END IF; RETURN NEW; END; ${'$'}${'$'};
                        CREATE TRIGGER reject_confirmation BEFORE UPDATE ON commerce.outbox_events
                        FOR EACH ROW EXECUTE FUNCTION fionas.reject_confirmation();
                        """.trimIndent(),
                    )
                    try {
                        shouldThrowAny { publisher.confirm(claim) }
                    } finally {
                        app.database.execute(
                            "DROP TRIGGER reject_confirmation ON commerce.outbox_events; DROP FUNCTION fionas.reject_confirmation()",
                        )
                    }
                    app.context.outbox
                        .backlog(FIONA_EVENT_PRODUCER)
                        .unpublished shouldBe 1L
                    val recovered = app.context.outbox.publisher(FIONA_EVENT_PRODUCER, "replacement-process")
                    waitUntil {
                        app.context.outbox
                            .backlog(FIONA_EVENT_PRODUCER)
                            .claimed == 0L
                    }
                    val retry = recovered.claim(1, Duration.ofMinutes(1)).single()
                    retry.record.event shouldBe claim.record.event
                    retry.record.payloadJson shouldBe claim.record.payloadJson
                    retry.record.aggregateSequence shouldBe claim.record.aggregateSequence
                    transport.publish(retry.record) shouldBe PublicationResult.CONFIRMED
                    recovered.confirm(retry) shouldBe true
                    broker.admin().use {
                        it
                            .jetStreamManagement()
                            .getStreamInfo(FIONA_EVENTS_STREAM)
                            .streamState.msgCount shouldBe 1L
                    }
                }
            }
        }

        test("HTTP application restart with publisher enabled delivers events committed while broker was down") {
            TestApplication.create().use { app ->
                broker.stop()
                val inquiry = app.createInquiryOperation()(app.inquiryCommand())
                app.context.outbox
                    .backlog(FIONA_EVENT_PRODUCER)
                    .unpublished shouldBe 1L
                val token = app.webToken
                app.runtime.close()
                val contributions = fionaApplication(bootstrap = null, outboxPublisher = broker.publisherConfiguration())
                lateinit var restartedContext: CommerceRuntimeContext
                commerceRuntime(
                    CommerceRuntimeConfiguration(
                        server = CommerceRuntimeConfiguration.Server(port = 0),
                        database = app.database.configuration,
                        migrations =
                            CommerceRuntimeConfiguration.Migrations(
                                onStartup = CommerceRuntimeConfiguration.Migrations.OnStartup.MIGRATE,
                            ),
                        serviceTokens = TEST_SERVICE_TOKENS,
                    ),
                    ApplicationContributions(
                        migrations = contributions.migrations,
                        permissionDefinitions = contributions.permissionDefinitions,
                        routes = { context ->
                            restartedContext = context
                            contributions.routes(context)
                        },
                        backgroundServices = contributions.backgroundServices,
                    ),
                ).use { runtime ->
                    runtime.start() // Ordinary broker unavailability must not fail startup or business operations.
                    waitUntil { restartedContext.outbox.backlog(FIONA_EVENT_PRODUCER).failed > 0 }
                    runtime
                        .http(
                            Request(Method.POST, "/inquiries")
                                .header("Authorization", "Bearer $token")
                                .header("Idempotency-Key", "restart-broker-down")
                                .header("Content-Type", "application/json")
                                .body(
                                    """{"name":"Synthetic","email":"restart@example.com","zipCode":"92626","eventDate":"2026-12-05","eventType":"BIRTHDAY","requestedService":${requestedServiceJson()},"lines":[{"description":"Test service","unitPrice":"10.00","taxAmount":"0.00","currency":"USD"}]}""",
                                ),
                        ).status shouldBe Status.CREATED
                    restartedContext.outbox.backlog(FIONA_EVENT_PRODUCER).unpublished shouldBe 2L
                    broker.start()
                    waitUntil { restartedContext.outbox.backlog(FIONA_EVENT_PRODUCER).unpublished == 0L }
                    broker.admin().use { observer ->
                        val ids =
                            (1L..2L).map { sequence ->
                                val stored = observer.jetStreamManagement().getMessage(FIONA_EVENTS_STREAM, sequence)
                                val payload = checkNotNull(stored.data).toString(Charsets.UTF_8)
                                Json
                                    .decodeFromString(InquiryPersistedV3.serializer(), payload)
                                    .data.inquiry.id
                            }
                        ids.contains(inquiry.id.value.toString()) shouldBe true
                    }
                }
            }
        }
    })
