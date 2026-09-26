package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.fionaApplication
import org.http4k.core.HttpHandler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** A fixed test time with nanoseconds, which PostgreSQL cannot store. */
val TEST_INSTANT: Instant = Instant.parse("2026-09-26T18:30:00.123456789Z")

val testClock: Clock = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC)

/**
 * fionas-commerce composed as `main()` composes it, `commerceRuntime(configuration,
 * fionaApplication())`, against a throwaway database, with commerce-runtime applying the
 * commerce and Fiona migrations.
 *
 * [transactor] is the runtime's own `Transactor`, taken from the `CommerceRuntimeContext`
 * the runtime hands to Fiona's route factory, so specs drive repositories and operations
 * inside real runtime transactions rather than a test-built stand-in.
 */
class TestApplication private constructor(
    val database: TestDatabase,
    val runtime: CommerceRuntime,
    val transactor: Transactor,
) : AutoCloseable {
    /** The complete HTTP handler, including the runtime's error handling, without a server. */
    val http: HttpHandler get() = runtime.http

    override fun close() {
        runtime.close()
        database.close()
    }

    companion object {
        fun create(clock: Clock = testClock): TestApplication {
            val database = TestDatabase.create()
            try {
                val fiona = fionaApplication(clock)
                var context: CommerceRuntimeContext? = null
                val runtime =
                    commerceRuntime(
                        configuration =
                            CommerceRuntimeConfiguration(
                                server = CommerceRuntimeConfiguration.Server(port = 0),
                                database = database.configuration,
                                flyway = CommerceRuntimeConfiguration.Flyway(enabled = true),
                            ),
                        application =
                            ApplicationContributions(
                                migrationLocations = fiona.migrationLocations,
                                routes = { runtimeContext ->
                                    context = runtimeContext
                                    fiona.routes(runtimeContext)
                                },
                            ),
                    )
                return TestApplication(database, runtime, checkNotNull(context).transactor)
            } catch (e: Exception) {
                database.close()
                throw e
            }
        }
    }
}
