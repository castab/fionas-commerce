package io.github.castab.fionas.commerce.events

import io.github.castab.fionas.commerce.fionaApplication
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.nats.client.NKey
import java.security.SecureRandom

class NatsOutboxConfigurationSpec :
    FunSpec({
        test("NATS NKey signing interoperates with the single runtime-provided Bouncy Castle provider") {
            val key = NKey.createUser(SecureRandom())
            try {
                val challenge = "synthetic-nats-challenge".toByteArray()
                val signature = key.sign(challenge)
                NKey.fromPublicKey(key.publicKey).verify(challenge, signature) shouldBe true
            } finally {
                key.clear()
            }
        }
        val enabled = mapOf("FIONAS_OUTBOX_PUBLISHER_ENABLED" to "true", "FIONAS_NATS_URL" to "nats://127.0.0.1:4222")
        test("disabled requires no broker configuration or background service") {
            NatsOutboxConfiguration.fromEnvironment(emptyMap()) shouldBe null
            NatsOutboxConfiguration.fromEnvironment(
                mapOf("FIONAS_OUTBOX_PUBLISHER_ENABLED" to "false", "FIONAS_NATS_URL" to "bad"),
            ) shouldBe
                null
            fionaApplication(bootstrap = null, outboxPublisher = null).backgroundServices.size shouldBe 0
        }
        test("enabled composes without contacting a broker") {
            val config = NatsOutboxConfiguration.fromEnvironment(enabled)
            fionaApplication(bootstrap = null, outboxPublisher = config).backgroundServices.size shouldBe 1
            NatsOutboxTransport(checkNotNull(config)).use { /* close before start is safe */ }
        }
        test("invalid deployment settings fail clearly without echoing secrets") {
            listOf(
                enabled - "FIONAS_NATS_URL",
                enabled + ("FIONAS_OUTBOX_PUBLISHER_ENABLED" to "yes"),
                enabled + ("FIONAS_NATS_URL" to "nats://user:secret@localhost:4222"),
                enabled + ("FIONAS_NATS_URL" to "https://localhost"),
                enabled + ("FIONAS_NATS_USER" to "user"),
                enabled + ("FIONAS_NATS_PASSWORD" to "secret"),
                enabled + ("FIONAS_NATS_CREDS_FILE" to "missing-secret-file"),
            ).forEach { environment ->
                val failure = shouldThrowAny { NatsOutboxConfiguration.fromEnvironment(environment) }
                failure.message.orEmpty().contains("secret") shouldBe false
                failure.cause shouldBe null
            }
        }
    })
