package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.fionas.commerce.testing.TEST_SERVICE_TOKENS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.http4k.client.JavaHttpClient
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

/** fionas-commerce as a running application: its configuration, the runtime's health routes, and a real server. */
class FionaApplicationSpec :
    FunSpec({
        lateinit var application: TestApplication

        // Test-only values: the base64 of 32 distinct bytes, never a deployment key.
        val signingKey = TEST_SERVICE_TOKENS.signingKey
        val serviceTokenEnvironment =
            mapOf("SERVICE_TOKENS_SIGNING_KEY" to signingKey, "SERVICE_TOKENS_ISSUER" to "fionas-commerce-staging")

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("the application's own application.conf loads, with the environment supplying the connection") {
            val configuration =
                CommerceRuntimeConfiguration.load(
                    environment =
                        mapOf(
                            "DATABASE_JDBC_URL" to "jdbc:postgresql://db.internal:5432/fionas",
                            "DATABASE_USERNAME" to "fionas",
                            "DATABASE_PASSWORD" to "not-a-real-secret",
                            "PORT" to "9090",
                        ) + serviceTokenEnvironment,
                )

            configuration.server.port shouldBe 9090
            configuration.database.jdbcUrl shouldBe "jdbc:postgresql://db.internal:5432/fionas"
            configuration.migrations.onStartup shouldBe OnStartup.MIGRATE
            configuration.toString() shouldNotContain "not-a-real-secret"
            configuration.serviceTokens?.issuer shouldBe "fionas-commerce-staging"
            configuration.serviceTokens?.lifetimeMinutes shouldBe 15
            configuration.toString() shouldNotContain signingKey
        }

        test("application.conf requires service token signing; there is no UI key and no generated fallback") {
            val connection =
                mapOf(
                    "DATABASE_JDBC_URL" to "jdbc:postgresql://db.internal:5432/fionas",
                    "DATABASE_USERNAME" to "fionas",
                    "DATABASE_PASSWORD" to "not-a-real-secret",
                )
            shouldThrow<IllegalArgumentException> { CommerceRuntimeConfiguration.load(environment = connection) }.message shouldBe
                "SERVICE_TOKENS_SIGNING_KEY is required"
            shouldThrow<IllegalArgumentException> {
                CommerceRuntimeConfiguration.load(environment = connection + ("SERVICE_TOKENS_SIGNING_KEY" to signingKey))
            }.message shouldBe "SERVICE_TOKENS_ISSUER is required when service tokens are configured"
            // The retired UI key is neither required nor read.
            CommerceRuntimeConfiguration.load(
                environment = connection + serviceTokenEnvironment + ("FIONAS_UI_API_KEY" to "former-ui-key"),
            ) shouldBe CommerceRuntimeConfiguration.load(environment = connection + serviceTokenEnvironment)
        }

        test("composing Fiona without service token configuration fails at startup") {
            TestDatabase.create().use { database ->
                val failure =
                    shouldThrow<IllegalStateException> {
                        commerceRuntime(
                            CommerceRuntimeConfiguration(
                                server = CommerceRuntimeConfiguration.Server(port = 0),
                                database = database.configuration,
                                migrations = CommerceRuntimeConfiguration.Migrations(onStartup = OnStartup.MIGRATE),
                            ),
                            fionaApplication(testClock, bootstrap = null),
                        )
                    }
                failure.message shouldContain "SERVICE_TOKENS_SIGNING_KEY"
            }
        }

        test("a deployment that migrates separately switches instances to validation through the environment") {
            val configuration =
                CommerceRuntimeConfiguration.load(
                    environment =
                        mapOf(
                            "DATABASE_JDBC_URL" to "jdbc:postgresql://db.internal:5432/fionas",
                            "DATABASE_USERNAME" to "fionas",
                            "DATABASE_PASSWORD" to "not-a-real-secret",
                            "MIGRATIONS_ON_STARTUP" to "validate",
                        ) + serviceTokenEnvironment,
                )

            configuration.migrations.onStartup shouldBe OnStartup.VALIDATE
        }

        test("the runtime's liveness and readiness routes are served by the Fiona application") {
            application.http(Request(Method.GET, "/health")).let {
                it.status shouldBe Status.OK
                it.bodyString() shouldBe """{"status":"ok"}"""
            }
            application.http(Request(Method.GET, "/ready")).let {
                it.status shouldBe Status.OK
                it.bodyString() shouldBe """{"status":"ready"}"""
            }
        }

        test("started, the application serves HTTP on a real port") {
            application.runtime.start()
            val client = JavaHttpClient()

            val response = client(Request(Method.GET, "http://127.0.0.1:${application.runtime.port()}/health"))

            response.status shouldBe Status.OK
        }
    })
