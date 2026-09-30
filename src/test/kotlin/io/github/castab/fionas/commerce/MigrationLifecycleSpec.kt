package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.persistence.ApplicationMigrations
import io.github.castab.fionas.commerce.http.UiApiKey
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
 * only its own migration schema and location, and composing the runtime applies the runtime's
 * migrations first and Fiona's second, each stream with its history in its own schema
 * (`commerce.flyway_schema_history`, `fionas.flyway_schema_history`), before anything is served.
 *
 * The runtime's own suite covers the lifecycle's internals (discovery, locking, gating);
 * these specs prove only that Fiona uses the contract correctly.
 */
class MigrationLifecycleSpec :
    FunSpec({
        val runtimeDependentMigrations =
            ApplicationMigrations(
                schema = "fionas_test",
                locations = listOf("classpath:db/test-migrations/runtime-dependency"),
            )
        val brokenMigrations =
            ApplicationMigrations(
                schema = "fionas_broken_test",
                locations = listOf("classpath:db/test-migrations/broken"),
            )

        fun compose(
            database: TestDatabase,
            application: ApplicationContributions =
                fionaApplication(
                    testClock,
                    bootstrap = null,
                    uiApiKey = UiApiKey("migration-test-key"),
                ),
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

        fun TestDatabase.historyTables() =
            strings(
                "SELECT table_schema || '.' || table_name FROM information_schema.tables " +
                    "WHERE table_name = 'flyway_schema_history' ORDER BY 1",
            )

        test("an application migration may depend on what the runtime's migration phase just created") {
            TestDatabase.create().use { database ->
                compose(database, ApplicationContributions(migrations = runtimeDependentMigrations)).close()

                database.strings("SELECT runtime_schema FROM fionas_test.runtime_dependency") shouldContainExactly
                    listOf("commerce")
            }

            // The dependency is real: without the runtime's migration phase, the same SQL fails.
            // The stream's own schema exists, as Flyway would have created it, so the failure
            // is the missing commerce schema alone.
            TestDatabase.create().use { database ->
                database.execute("CREATE SCHEMA fionas_test")
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
                database.history(FIONA_MIGRATION_SCHEMA).map { it.substringBefore(' ') } shouldContainExactly listOf("1", "2", "3", "4")
                database.history(FIONA_MIGRATION_SCHEMA)[0] shouldContain "V1__customers_and_inquiries.sql"
                database.history(FIONA_MIGRATION_SCHEMA)[1] shouldContain "V2__user_credentials.sql"
                database.history(FIONA_MIGRATION_SCHEMA)[2] shouldContain "V3__financial_document_context.sql"
                database.history(FIONA_MIGRATION_SCHEMA)[3] shouldContain "V4__inquiry_list_and_pricing.sql"
                database.count("commerce.users") shouldBe 0
                database.history("commerce").map { it.substringBefore(' ') } shouldContain "1"
                database.history("commerce").map { it.substringBefore(' ') } shouldContain "7"
                database.history("commerce").last() shouldContain "V7__financial_document_created_at.sql"
                database
                    .strings(
                        "SELECT is_nullable || ' ' || data_type FROM information_schema.columns " +
                            "WHERE table_schema = 'commerce' AND table_name = 'financial_document_snapshots' AND column_name = 'created_at'",
                    ).single() shouldBe "NO timestamp with time zone"
                database
                    .strings(
                        "SELECT (SELECT max(installed_on) FROM commerce.flyway_schema_history) <= " +
                            "(SELECT min(installed_on) FROM fionas.flyway_schema_history WHERE type = 'SQL')",
                    ).single() shouldBe "t"
                // Fiona's V3 depends on the runtime's financial ledger, which is in place first.
                database
                    .strings(
                        "SELECT (SELECT installed_rank FROM commerce.flyway_schema_history WHERE script LIKE '%financial_ledger%') " +
                            "IS NOT NULL AND (SELECT max(installed_on) FROM commerce.flyway_schema_history) <= " +
                            "(SELECT installed_on FROM fionas.flyway_schema_history WHERE version = '3')",
                    ).single() shouldBe "t"
            }
        }

        test("each stream keeps its history in its own schema, and nothing of Fiona's migrations lands in public") {
            TestDatabase.create().use { database ->
                compose(database).close()

                database.historyTables() shouldContainExactly
                    listOf("commerce.flyway_schema_history", "fionas.flyway_schema_history")
                database.strings("SELECT to_regclass('public.flyway_schema_history') IS NULL").single() shouldBe "t"
            }
        }

        test("starting again against a current database applies nothing and serves") {
            TestDatabase.create().use { database ->
                compose(database).close()
                val runtimeHistory = database.history("commerce")
                val fionaHistory = database.history(FIONA_MIGRATION_SCHEMA)

                compose(database).close()
                compose(database, onStartup = OnStartup.VALIDATE).use { runtime ->
                    runtime.http(Request(Method.GET, "/ready")).status shouldBe Status.OK
                }

                database.history("commerce") shouldBe runtimeHistory
                database.history(FIONA_MIGRATION_SCHEMA) shouldBe fionaHistory
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
                        compose(database, ApplicationContributions(migrations = brokenMigrations))
                    }

                failure.message.orEmpty() shouldContain "V1__broken.sql"
                // The runtime's migrations ran first; the failed application stream recorded no migration.
                database.history("commerce").map { it.substringBefore(' ') } shouldContain "1"
                database.history("fionas_broken_test").shouldBeEmpty()
                database.historyTables() shouldContainExactly
                    listOf("commerce.flyway_schema_history", "fionas_broken_test.flyway_schema_history")
            }
        }
    })
