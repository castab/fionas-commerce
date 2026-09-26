package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.http4k.client.JavaHttpClient
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

/** fionas-commerce as a running application: its configuration, the runtime's health routes, and a real server. */
class FionaApplicationSpec :
    FunSpec({
        lateinit var application: TestApplication

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
                        ),
                )

            configuration.server.port shouldBe 9090
            configuration.database.jdbcUrl shouldBe "jdbc:postgresql://db.internal:5432/fionas"
            configuration.flyway.enabled shouldBe false
            configuration.toString() shouldNotContain "not-a-real-secret"
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
