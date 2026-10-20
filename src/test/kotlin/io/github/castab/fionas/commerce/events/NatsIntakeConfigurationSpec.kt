package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.fionas.commerce.fionaApplication
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
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
        val base =
            CommerceRuntimeConfiguration.Database(
                jdbcUrl = "jdbc:postgresql://localhost/test",
                username = "test",
                password = "test",
                connectionTimeoutMs = 5_000,
            )

        fun url(options: String) =
            base.jdbcUrl + "?sslmode=require&socketTimeout=5&options=" +
                URLEncoder.encode(options, StandardCharsets.UTF_8)

        test("equivalent timeout ordering whitespace URL encoding and PostgreSQL units are accepted") {
            listOf(
                "-c statement_timeout=5000 -c transaction_timeout=50000",
                "  -c\tstatement_timeout=5s  -c transaction_timeout=50000ms  ",
                "-c transaction_timeout=50s -c statement_timeout=5000ms",
            ).forEach { options -> requireIntakeDatabaseBounds(base.copy(jdbcUrl = url(options))) }
        }

        test("PostgreSQL applies the same limits for the reordered unit-bearing startup options") {
            TestDatabase.create().use { database ->
                val options = URLEncoder.encode("-c statement_timeout=5000ms -c transaction_timeout=50s", StandardCharsets.UTF_8)
                val jdbc =
                    database.configuration.jdbcUrl + (if ('?' in database.configuration.jdbcUrl) "&" else "?") +
                        "socketTimeout=5&options=$options"
                requireIntakeDatabaseBounds(database.configuration.copy(jdbcUrl = jdbc))
                DriverManager.getConnection(jdbc, database.configuration.username, database.configuration.password).use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT current_setting('statement_timeout'), current_setting('transaction_timeout')").use {
                            check(it.next())
                            it.getString(1) shouldBe "5s"
                            it.getString(2) shouldBe "50s"
                        }
                    }
                }
            }
        }

        test("diagnostics identify effective pool timeout missing duplicate and invalid query settings without leaking values") {
            val good = base.copy(jdbcUrl = url("-c transaction_timeout=50000 -c statement_timeout=5000"))
            val invalid =
                listOf(
                    good.copy(connectionTimeoutMs = 30_000) to "effective value is 30000ms",
                    base to "exactly one socketTimeout query parameter; found 0",
                    good.copy(jdbcUrl = good.jdbcUrl + "&socketTimeout=5") to "socketTimeout query parameter; found 2",
                    good.copy(jdbcUrl = base.jdbcUrl + "?socketTimeout=5") to "exactly one options query parameter; found 0",
                    good.copy(jdbcUrl = good.jdbcUrl + "&options=private-secret") to "options query parameter; found 2",
                    good.copy(
                        jdbcUrl = good.jdbcUrl.replace("socketTimeout=5", "socketTimeout=private-secret"),
                    ) to "socketTimeout must be 1..5",
                    good.copy(
                        jdbcUrl = url("-c transaction_timeout=private-secret -c statement_timeout=5000"),
                    ) to "transaction_timeout must be",
                    good.copy(jdbcUrl = url("-c transaction_timeout=50000 -c statement_timeout=private-secret")) to
                        "statement_timeout must be",
                    good.copy(jdbcUrl = good.jdbcUrl + "&password=private-secret%ZZ") to "malformed query encoding",
                )
            invalid.forEach { (database, expected) ->
                val failure = shouldThrowAny { requireIntakeDatabaseBounds(database) }
                failure.message.orEmpty() shouldContain expected
                failure.message.orEmpty() shouldNotContain "private-secret"
                failure.message.orEmpty() shouldNotContain "jdbc:postgresql:"
                failure.cause shouldBe null
            }
        }

        test("disabled relaxed duplicate overridden quoted and overflowing timeout settings fail closed") {
            listOf(
                "-c transaction_timeout=0 -c statement_timeout=5000",
                "-c transaction_timeout=50001 -c statement_timeout=5000",
                "-c transaction_timeout=50000 -c statement_timeout=5001",
                "-c transaction_timeout=50000 -c transaction_timeout=50000",
                "-c transaction_timeout=50000 -c statement_timeout=5000 -c statement_timeout=0",
                "'-c transaction_timeout=50000 -c statement_timeout=5000'",
                "-c transaction_timeout=9223372036854775807s -c statement_timeout=5000",
            ).forEach { options -> shouldThrowAny { requireIntakeDatabaseBounds(base.copy(jdbcUrl = url(options))) } }
        }
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
