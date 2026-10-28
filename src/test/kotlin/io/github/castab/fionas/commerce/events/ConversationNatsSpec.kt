package io.github.castab.fionas.commerce.events

import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryConversationRepository
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestNats
import io.github.castab.fionas.commerce.testing.createInquiry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.nats.client.api.ConsumerConfiguration
import io.nats.client.api.StreamConfiguration
import java.util.UUID

class ConversationNatsSpec :
    FunSpec({
        test("dedicated least-privilege consumer persists before confirmed ACK and replay creates one communication") {
            TestNats().use { broker ->
                TestApplication.create().use { app ->
                    val inquiry = app.createInquiry()
                    val configuration =
                        ConversationIntakeConfiguration.fromEnvironment(
                            mapOf(
                                "FIONAS_CONVERSATION_INTAKE_ENABLED" to "true",
                                "FIONAS_CONVERSATION_INTAKE_NATS_URL" to broker.url,
                                "FIONAS_CONVERSATION_INTAKE_NATS_USER" to "conversations",
                                "FIONAS_CONVERSATION_INTAKE_NATS_PASSWORD" to "test-conversations",
                            ),
                        )!!
                    broker.admin().use { admin ->
                        admin.jetStreamManagement().addStream(
                            StreamConfiguration
                                .builder()
                                .name(CONVERSATION_STREAM)
                                .subjects(CONVERSATION_SUBJECT)
                                .build(),
                        )
                        admin.jetStreamManagement().addOrUpdateConsumer(
                            CONVERSATION_STREAM,
                            ConsumerConfiguration
                                .builder(
                                    inputConsumerConfiguration(),
                                ).name(CONVERSATION_CONSUMER)
                                .durable(CONVERSATION_CONSUMER)
                                .filterSubject(CONVERSATION_SUBJECT)
                                .build(),
                        )
                        val payload =
                            """{"schemaVersion":1,"id":"${UUID.randomUUID()}","type":"conversation.message.received",
                        "occurredAt":"2026-10-01T12:00:00Z","conversationId":"${UUID.randomUUID()}","sequence":7,
                        "topic":{"type":"booking","externalId":"$inquiry"},"messageId":"${UUID.randomUUID()}",
                        "actor":"participant","cause":"inbound_email"}""".toByteArray()
                        repeat(2) { admin.jetStream().publish(CONVERSATION_SUBJECT, payload) }
                        val communications = JdbiInquiryCommunicationRepository()
                        val handle = RecordConversationEvent(app.transactor, communications, JdbiInquiryConversationRepository())

                        fun worker() =
                            ManagedJetStreamWorker(
                                NatsIntakeTransport(
                                    configuration.transport,
                                    CONVERSATION_STREAM,
                                    CONVERSATION_SUBJECT,
                                    CONVERSATION_CONSUMER,
                                ),
                                handle::invoke,
                                name = "conversation-test",
                                processDelivery = {
                                    delivery,
                                    cancelled,
                                    ->
                                    processConversationDelivery(delivery, handle::invoke, cancelled)
                                },
                            )
                        val first = worker()
                        try {
                            first.start()
                            awaitIntake {
                                val info = admin.jetStreamManagement().getConsumerInfo(CONVERSATION_STREAM, CONVERSATION_CONSUMER)
                                info.numPending == 0L && info.numAckPending == 0L
                            }
                            app.transactor.inTransaction { communications.history(it, InquiryId(UUID.fromString(inquiry))).size } shouldBe 1
                        } finally {
                            first.close()
                        }
                        admin.jetStream().publish(CONVERSATION_SUBJECT, payload)
                        val restarted = worker()
                        try {
                            restarted.start()
                            awaitIntake {
                                val info = admin.jetStreamManagement().getConsumerInfo(CONVERSATION_STREAM, CONVERSATION_CONSUMER)
                                info.numPending == 0L && info.numAckPending == 0L
                            }
                            app.transactor.inTransaction { communications.history(it, InquiryId(UUID.fromString(inquiry))).size } shouldBe 1
                        } finally {
                            restarted.close()
                        }
                    }
                }
            }
        }
    })
