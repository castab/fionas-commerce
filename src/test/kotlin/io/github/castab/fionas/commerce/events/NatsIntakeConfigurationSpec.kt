package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.fionas.commerce.fionaApplication
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

internal fun boundedIntakeDatabase(database: CommerceRuntimeConfiguration.Database): CommerceRuntimeConfiguration.Database =
    database.copy(
        jdbcUrl =
            database.jdbcUrl + (if ('?' in database.jdbcUrl) "&" else "?") +
                "socketTimeout=5&options=-c%20transaction_timeout%3D50000%20-c%20statement_timeout%3D5000",
        connectionTimeoutMs = 5_000,
    )

class NatsIntakeConfigurationSpec :
    FunSpec({
        test("recovery uses the runtime loader with database credentials only and validation-only migrations") {
            val recovery =
                CommerceRuntimeConfiguration.load(
                    environment =
                        mapOf(
                            "DATABASE_JDBC_URL" to "jdbc:postgresql://localhost/test",
                            "DATABASE_USERNAME" to "test",
                            "DATABASE_PASSWORD" to "test",
                        ),
                    resource = "/inquiry-recovery.conf",
                )
            recovery.serviceTokens shouldBe null
            recovery.server.port shouldBe 0
            recovery.migrations.onStartup shouldBe CommerceRuntimeConfiguration.Migrations.OnStartup.VALIDATE
        }
        val environment =
            mapOf(
                "FIONAS_INTAKE_ENABLED" to "true",
                "FIONAS_INTAKE_NATS_URL" to "nats://127.0.0.1:1",
                "FIONAS_INTAKE_NATS_TOKEN" to "test-private-token",
                "FIONAS_INTAKE_AUTHOR_SERVICE_ID" to UUID.randomUUID().toString(),
            )
        test("disabled ignores unrelated intake settings; enabled config and service factory never connect") {
            NatsIntakeConfiguration.fromEnvironment(emptyMap()) shouldBe null
            NatsIntakeConfiguration.fromEnvironment(environment + ("FIONAS_INTAKE_ENABLED" to "false")) shouldBe null
            fionaApplication(bootstrap = null, outboxPublisher = null, intake = null).backgroundServices.size shouldBe 0
            val enabled = checkNotNull(NatsIntakeConfiguration.fromEnvironment(environment))
            fionaApplication(bootstrap = null, outboxPublisher = null, intake = enabled).backgroundServices.size shouldBe 1
            NatsIntakeTransport(enabled).use { }
            enabled.toString() shouldNotContain "test-private-token"
        }
        test("invalid enabled authentication URL and author settings fail without exposing secrets") {
            val invalid =
                listOf(
                    environment + ("FIONAS_INTAKE_ENABLED" to "yes"),
                    environment + ("FIONAS_INTAKE_AUTHOR_SERVICE_ID" to "bad"),
                    environment - "FIONAS_INTAKE_AUTHOR_SERVICE_ID",
                    environment - "FIONAS_INTAKE_NATS_TOKEN",
                    environment + ("FIONAS_INTAKE_NATS_URL" to "nats://test-private-token@example.com"),
                    environment + ("FIONAS_INTAKE_NATS_TOKEN" to ""),
                    environment + ("FIONAS_INTAKE_NATS_USER" to "test-private-token"),
                    environment + ("FIONAS_INTAKE_NATS_CREDS_FILE" to "missing-test-private-token"),
                )
            invalid.forEach {
                shouldThrowAny { NatsIntakeConfiguration.fromEnvironment(it) }.message.orEmpty() shouldNotContain
                    "test-private-token"
            }
        }
        test("enabled intake requires finite driver pool and whole-transaction bounds through existing runtime configuration") {
            val database =
                CommerceRuntimeConfiguration.Database(
                    jdbcUrl = "jdbc:postgresql://localhost/test",
                    username = "test",
                    password = "test",
                )
            requireIntakeDatabaseBounds(boundedIntakeDatabase(database))
            shouldThrowAny { requireIntakeDatabaseBounds(database) }
            shouldThrowAny { requireIntakeDatabaseBounds(boundedIntakeDatabase(database).copy(connectionTimeoutMs = 30_000)) }
            shouldThrowAny {
                requireIntakeDatabaseBounds(
                    boundedIntakeDatabase(database).copy(
                        jdbcUrl =
                            boundedIntakeDatabase(database).jdbcUrl + "&socketTimeout=0",
                    ),
                )
            }
        }
    })
