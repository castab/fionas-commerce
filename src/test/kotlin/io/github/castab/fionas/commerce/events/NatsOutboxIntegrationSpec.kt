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
import io.github.castab.fionas.commerce.testing.TestNats
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.github.castab.fionas.commerce.testing.requestedServiceJson
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.nats.client.Message
import kotlinx.serialization.json.Json
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.time.Duration
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
                        received.data.toList() shouldBe event.payload.toByteArray(Charsets.UTF_8).toList()
                        received.headers.getFirst("Nats-Msg-Id") shouldBe event.id.toString()
                        received.headers.getFirst("Nats-Expected-Stream") shouldBe FIONA_EVENTS_STREAM
                        received.headers.getFirst(PRODUCER_HEADER) shouldBe FIONA_EVENT_PRODUCER
                        received.headers.getFirst(AGGREGATE_TYPE_HEADER) shouldBe "inquiry"
                        received.headers.getFirst(AGGREGATE_ID_HEADER) shouldBe inquiry.id.value.toString()
                        received.headers.getFirst(AGGREGATE_SEQUENCE_HEADER) shouldBe "1"
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
                            claim.record.event.payload
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
                                Json.decodeFromString(InquiryPersistedV2.serializer(), payload).data.inquiryId
                            }
                        ids.contains(inquiry.id.value.toString()) shouldBe true
                    }
                }
            }
        }
    })
