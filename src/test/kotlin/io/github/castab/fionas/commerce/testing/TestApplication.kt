package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.authorization.AuthorizationDirectory
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.fionas.commerce.fionaApplication
import io.github.castab.fionas.commerce.staff.BootstrapAdmin
import io.github.castab.fionas.commerce.staff.SecretPassword
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A fixed test time with nanoseconds, which PostgreSQL cannot store. */
val TEST_INSTANT: Instant = Instant.parse("2026-09-26T18:30:00.123456789Z")

val testClock: Clock = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC)

/**
 * fionas-commerce composed as `main()` composes it, `commerceRuntime(configuration,
 * fionaApplication())`, against a throwaway database, with commerce-runtime migrating on
 * startup: its own migrations, then Fiona's.
 *
 * [context] is the `CommerceRuntimeContext` the runtime hands to Fiona's route factory, and
 * [transactor] its own `Transactor`, so specs drive repositories and operations inside real
 * runtime transactions, with the runtime's own financial ledger, rather than test-built
 * stand-ins.
 */
class TestApplication private constructor(
    val database: TestDatabase,
    val runtime: CommerceRuntime,
    val context: CommerceRuntimeContext,
) : AutoCloseable {
    val transactor: Transactor get() = context.transactor
    val sessions: SessionManager get() = context.sessions
    val authorization: AuthorizationDirectory get() = context.authorization

    /** The complete HTTP handler, including the runtime's error handling, without a server. */
    val http: HttpHandler get() = runtime.http

    val adminCookie: String by lazy {
        val response =
            http(
                Request(Method.POST, "/auth/login")
                    .header("Origin", TEST_ORIGIN)
                    .header("Content-Type", "application/json")
                    .body("""{"username":"admin","password":"test-admin-password"}"""),
            )
        check(response.status == Status.NO_CONTENT) { "Test admin login failed: ${response.status} ${response.bodyString()}" }
        checkNotNull(response.header("Set-Cookie")).substringBefore(';')
    }

    fun adminPost(
        path: String,
        body: String = "",
    ): Response = adminRequest(Method.POST, path, body)

    fun adminGet(path: String): Response = adminRequest(Method.GET, path)

    /** A request from the bootstrap administrator's browser session, from a trusted origin. */
    fun adminRequest(
        method: Method,
        path: String,
        body: String = "",
    ): Response =
        http(
            Request(method, path)
                .header("Origin", TEST_ORIGIN)
                .header("Cookie", adminCookie)
                .header("Content-Type", "application/json")
                .body(body),
        )

    override fun close() {
        runtime.close()
        database.close()
    }

    companion object {
        fun create(
            clock: Clock = testClock,
            bootstrap: BootstrapAdmin? =
                BootstrapAdmin("admin", "Test Administrator", null, null, SecretPassword.of("test-admin-password")),
        ): TestApplication {
            val database = TestDatabase.create()
            try {
                val fiona =
                    fionaApplication(
                        clock,
                        bootstrap,
                        setOf(TEST_ORIGIN),
                    )
                var context: CommerceRuntimeContext? = null
                val runtime =
                    commerceRuntime(
                        configuration =
                            CommerceRuntimeConfiguration(
                                server = CommerceRuntimeConfiguration.Server(port = 0),
                                database = database.configuration,
                                migrations = CommerceRuntimeConfiguration.Migrations(onStartup = OnStartup.MIGRATE),
                            ),
                        application =
                            ApplicationContributions(
                                migrationLocations = fiona.migrationLocations,
                                permissionDefinitions = fiona.permissionDefinitions,
                                routes = { runtimeContext ->
                                    context = runtimeContext
                                    fiona.routes(runtimeContext)
                                },
                            ),
                    )
                return TestApplication(database, runtime, checkNotNull(context))
            } catch (e: Exception) {
                database.close()
                throw e
            }
        }
    }
}

const val TEST_ORIGIN = "https://fionas.test"
