package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.github.castab.fionas.commerce.testing.sqlState
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

/**
 * fionas-commerce as a consumer of commerce-runtime's migration lifecycle: Fiona contributes
 * only its own migration location, and composing the runtime applies the runtime's
 * migrations first and Fiona's second, in independent histories, before anything is served.
 *
 * The runtime's own suite covers the lifecycle's internals (discovery, locking, gating);
 * these specs prove only that Fiona uses the contract correctly.
 */
class MigrationLifecycleSpec :
    FunSpec({
        val runtimeDependentMigrations = "classpath:db/test-migrations/runtime-dependency"
        val brokenMigrations = "classpath:db/test-migrations/broken"

        fun compose(
            database: TestDatabase,
            application: ApplicationContributions = fionaApplication(testClock, bootstrap = null),
            onStartup: OnStartup = OnStartup.MIGRATE,
        ): CommerceRuntime =
            commerceRuntime(
                CommerceRuntimeConfiguration(
                    server = CommerceRuntimeConfiguration.Server(port = 0),
                    database = database.configuration,
                    migrations = CommerceRuntimeConfiguration.Migrations(onStartup = onStartup),
                ),
                application,
            )

        /** The versioned migrations a stream's history records, as `version script checksum`. */
        fun TestDatabase.history(schema: String) =
            strings(
                "SELECT version || ' ' || script || ' ' || checksum FROM $schema.flyway_schema_history " +
                    "WHERE type = 'SQL' AND success ORDER BY installed_rank",
            )

        fun TestDatabase.schemas() = strings("SELECT nspname FROM pg_namespace ORDER BY nspname")

        test("an application migration may depend on what the runtime's migration phase just created") {
            TestDatabase.create().use { database ->
                compose(database, ApplicationContributions(migrationLocations = listOf(runtimeDependentMigrations))).close()

                database.strings("SELECT runtime_schema FROM fionas_test.runtime_dependency") shouldContainExactly
                    listOf("commerce")
            }

            // The dependency is real: without the runtime's migration phase, the same SQL fails.
            TestDatabase.create().use { database ->
                val sql =
                    checkNotNull(javaClass.getResource("/db/test-migrations/runtime-dependency/V1__depends_on_runtime_schema.sql"))
                        .readText()

                shouldThrowAny { database.execute(sql) }.sqlState() shouldBe "3F000"
            }
        }

        test("Fiona's migrations run after the runtime's, in their own history and version space") {
            TestDatabase.create().use { database ->
                compose(database).close()

                // Both streams have a version 1; neither numbers its migrations after the other's.
                database.history("public").map { it.substringBefore(' ') } shouldContainExactly listOf("1", "2")
                database.history("public").first() shouldContain "V1__customers_and_inquiries.sql"
                database.history("public").last() shouldContain "V2__staff_identities.sql"
                database.count("fionas.users") shouldBe 0
                database.history("commerce").map { it.substringBefore(' ') } shouldContain "1"
                database
                    .strings(
                        "SELECT (SELECT max(installed_on) FROM commerce.flyway_schema_history) <= " +
                            "(SELECT min(installed_on) FROM public.flyway_schema_history WHERE type = 'SQL')",
                    ).single() shouldBe "t"
            }
        }

        test("starting again against a current database applies nothing and serves") {
            TestDatabase.create().use { database ->
                compose(database).close()
                val runtimeHistory = database.history("commerce")
                val fionaHistory = database.history("public")

                compose(database).close()
                compose(database, onStartup = OnStartup.VALIDATE).use { runtime ->
                    runtime.http(Request(Method.GET, "/ready")).status shouldBe Status.OK
                }

                database.history("commerce") shouldBe runtimeHistory
                database.history("public") shouldBe fionaHistory
            }
        }

        test("a deployment that only validates cannot start against a database that was never migrated") {
            TestDatabase.create().use { database ->
                shouldThrowAny { compose(database, onStartup = OnStartup.VALIDATE) }

                database.schemas().filter { it == "fionas" }.shouldBeEmpty()
            }
        }

        test("a failing application migration prevents the runtime from being composed at all") {
            TestDatabase.create().use { database ->
                val failure =
                    shouldThrowAny {
                        compose(database, ApplicationContributions(migrationLocations = listOf(brokenMigrations)))
                    }

                failure.message.orEmpty() shouldContain "V1__broken.sql"
                // The runtime's migrations ran first; the failed application stream recorded nothing.
                database.history("commerce").map { it.substringBefore(' ') } shouldContain "1"
                database.strings("SELECT count(*) FROM public.flyway_schema_history WHERE success").single() shouldBe "0"
            }
        }
    })
