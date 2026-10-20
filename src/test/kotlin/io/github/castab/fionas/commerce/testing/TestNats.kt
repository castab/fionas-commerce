package io.github.castab.fionas.commerce.testing

import io.github.castab.fionas.commerce.events.FIONA_EVENTS_STREAM
import io.github.castab.fionas.commerce.events.INQUIRY_PERSISTED_SUBJECT
import io.github.castab.fionas.commerce.events.NatsOutboxConfiguration
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Disposable authenticated JetStream with the exact publisher grant documented for operators.
 * With [token], the server instead accepts only that preshared token, which grants every client
 * the same unscoped identity (NATS does not combine a token with a users list).
 */
class TestNats(
    private val token: String? = null,
) : AutoCloseable {
    private val name = "fionas-test-nats-${UUID.randomUUID()}"
    private val directory = Files.createDirectories(Path.of("build", name).toAbsolutePath())
    private val config = directory.resolve("nats.conf")
    val url: String

    init {
        Files.writeString(
            config,
            if (token != null) tokenConfiguration(token) else usersConfiguration(),
        )
        try {
            val port = ServerSocket(0).use { it.localPort }
            docker(
                "run",
                "--detach",
                "--name",
                name,
                "--publish",
                "127.0.0.1:$port:4222",
                "--mount",
                "type=bind,src=$config,dst=/etc/nats/nats.conf,readonly",
                "nats:2.14.7",
                "--config",
                "/etc/nats/nats.conf",
            )
            url = "nats://127.0.0.1:$port"
            awaitReady()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    private fun tokenConfiguration(token: String) =
        """
        jetstream { store_dir: "/data" }
        authorization { token: "$token" }
        """.trimIndent()

    private fun usersConfiguration() =
        """
        jetstream { store_dir: "/data" }
        authorization {
          users: [
            { user: "admin", password: "test-admin", permissions: { publish: ">", subscribe: ">" } },
            { user: "commerce", password: "test-commerce", permissions: {
                publish: ["$INQUIRY_PERSISTED_SUBJECT"], subscribe: ["_INBOX.>"]
            } },
            { user: "transition", password: "test-transition", permissions: {
                publish: ["$INQUIRY_PERSISTED_SUBJECT", "fionas.events.inquiry.persisted.v2"], subscribe: ["_INBOX.>"]
            } },
            { user: "intake", password: "test-intake", permissions: {
                publish: ["${'$'}JS.API.CONSUMER.INFO.FIONAS_INQUIRIES.inquiry-to-commerce",
                  "${'$'}JS.API.CONSUMER.MSG.NEXT.FIONAS_INQUIRIES.inquiry-to-commerce",
                  "${'$'}JS.ACK.FIONAS_INQUIRIES.inquiry-to-commerce.>",
                  "fionas.events.inquiry.processing-failed.v2"], subscribe: ["_INBOX.>"]
            } },
            { user: "intake-no-diagnostic", password: "test-intake-no-diagnostic", permissions: {
                publish: ["${'$'}JS.API.CONSUMER.INFO.FIONAS_INQUIRIES.inquiry-to-commerce",
                  "${'$'}JS.API.CONSUMER.MSG.NEXT.FIONAS_INQUIRIES.inquiry-to-commerce",
                  "${'$'}JS.ACK.FIONAS_INQUIRIES.inquiry-to-commerce.>"], subscribe: ["_INBOX.>"]
            } },
            { user: "denied", password: "test-denied", permissions: {
                publish: ["unrelated.subject"], subscribe: ["_INBOX.>"]
            } }
          ]
        }
        """.trimIndent()

    fun admin(): Connection =
        Nats.connect(
            Options
                .builder()
                .server(url)
                .also { builder ->
                    if (token == null) builder.userInfo("admin", "test-admin") else builder.token(token.toCharArray())
                }.connectionTimeout(Duration.ofSeconds(1))
                .build(),
        )

    fun publisherConfiguration(user: String = "commerce") =
        configurationWith("FIONAS_NATS_USER" to user, "FIONAS_NATS_PASSWORD" to "test-$user")

    fun tokenPublisherConfiguration(token: String) = configurationWith("FIONAS_NATS_TOKEN" to token)

    private fun configurationWith(vararg authentication: Pair<String, String>) =
        checkNotNull(
            NatsOutboxConfiguration.fromEnvironment(
                mapOf("FIONAS_OUTBOX_PUBLISHER_ENABLED" to "true", "FIONAS_NATS_URL" to url) + authentication,
            ),
        )

    fun resetStream() {
        admin().use { connection ->
            val management = connection.jetStreamManagement()
            if (FIONA_EVENTS_STREAM in management.streamNames) management.deleteStream(FIONA_EVENTS_STREAM)
            management.addStream(
                StreamConfiguration
                    .builder()
                    .name(
                        FIONA_EVENTS_STREAM,
                    ).subjects("fionas.events.>")
                    .storageType(StorageType.File)
                    .duplicateWindow(Duration.ofHours(24))
                    .build(),
            )
        }
    }

    fun stop() {
        docker("stop", "--time", "2", name)
    }

    fun start() {
        docker("start", name)
        awaitReady()
    }

    private fun awaitReady() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            try {
                admin().use { it.flush(Duration.ofSeconds(1)) }
                return
            } catch (failure: Exception) {
                if (System.nanoTime() > deadline) throw failure
                Thread.sleep(100)
            }
        }
    }

    override fun close() {
        docker("rm", "--force", "--volumes", name)
        Files.deleteIfExists(config)
        Files.deleteIfExists(directory)
    }

    private fun docker(vararg arguments: String): String {
        val process = ProcessBuilder("docker", *arguments).redirectErrorStream(true).start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        check(process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0) { "Disposable NATS command failed: $output" }
        return output
    }
}
