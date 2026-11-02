package io.github.castab.fionas.commerce

import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.issueProposal
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Status
import java.io.File
import java.sql.SQLException
import java.util.UUID

/**
 * The operator reset script (scripts/reset-preproduction-database.sql) against real PostgreSQL.
 * psql substitutes `:'name'` variables before sending the script, leaving undefined ones as
 * written; [reset] does the same, so the guards and drops under test are the file's own.
 */
class PreproductionDatabaseResetSpec :
    FunSpec({
        val script = File("scripts/reset-preproduction-database.sql").readText()

        fun reset(
            database: TestDatabase,
            variables: Map<String, String>,
        ) = database.connect { connection ->
            val sql =
                variables.entries.fold(script) { text, (name, value) ->
                    text.replace(":'$name'", "'" + value.replace("'", "''") + "'")
                }
            connection.createStatement().use { it.execute(sql) }
        }

        fun name(database: TestDatabase) = database.strings("SELECT current_database()").single()

        fun authorized(
            database: TestDatabase,
            environment: String = "staging",
        ) = mapOf("database" to name(database), "environment" to environment, "confirm" to "RESET ${name(database)}")

        fun designate(
            database: TestDatabase,
            environment: String,
        ) = database.execute("COMMENT ON DATABASE ${name(database)} IS 'fionas-commerce disposable environment=$environment'")

        fun designation(database: TestDatabase) =
            database.strings("SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()").single()

        fun schemas(database: TestDatabase) =
            database.strings("SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%' ORDER BY 1")

        /** Everything a refused reset must leave exactly as it was. */
        fun state(database: TestDatabase) =
            schemas(database).filter { it == "commerce" || it == "fionas" } +
                listOf(
                    "fionas.inquiries",
                    "fionas.inquiry_proposals",
                    "fionas.flyway_schema_history",
                    "commerce.flyway_schema_history",
                    "commerce.financial_document_snapshots",
                    "commerce.payment_records",
                    "commerce.users",
                ).map { "$it=${database.count(it)}" }

        /** A stopped instance's backends exit asynchronously after its pool closes. */
        fun awaitNoOtherSessions(database: TestDatabase) {
            repeat(100) {
                val others =
                    database.strings(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                            "AND pid <> pg_backend_pid() AND backend_type = 'client backend'",
                    )
                if (others.single() == "0") return
                Thread.sleep(50)
            }
            error("Sessions remain connected")
        }

        fun book(app: TestApplication): String {
            val id = app.createInquiry()
            val proposal = app.issueProposal(InquiryId(UUID.fromString(id)), "100").proposal
            app
                .adminPost(
                    "/financial-documents/${proposal.documentReference.id}/payments",
                    """{"documentVersion":2,"amount":"100","method":"CASH","expectedProposalId":"${proposal.id.value}"}""",
                ).status shouldBe Status.CREATED
            return id
        }

        test("an authorized reset clears a booked database V9 refuses, and the next start recreates V1–V9 with business revisions") {
            TestDatabase.create().use { database ->
                TestApplication.create(existingDatabase = database).use { book(it) }
                // Return the booked database to the V8 shape an earlier release left behind.
                database.execute(
                    "DROP TABLE fionas.inquiry_invoice_amendments; " +
                        "ALTER TABLE fionas.inquiry_proposals DROP COLUMN quote_revision; " +
                        "DELETE FROM fionas.flyway_schema_history WHERE version = '9'",
                )
                awaitNoOtherSessions(database)
                val incompatible = state(database)
                // Without an authorized reset, normal startup still fails fast and changes nothing.
                shouldThrowAny { TestApplication.create(existingDatabase = database).close() }
                state(database) shouldBe incompatible

                designate(database, "staging")
                awaitNoOtherSessions(database)
                reset(database, authorized(database))
                schemas(database) shouldNotContainAnyOf listOf("commerce", "fionas")
                designation(database) shouldBe "fionas-commerce disposable environment=staging"

                TestApplication.create(existingDatabase = database).use { app ->
                    database.strings("SELECT version FROM fionas.flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank") shouldBe
                        (1..9).map { it.toString() }
                    database.strings(
                        "SELECT version FROM commerce.flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank",
                    ) shouldBe
                        listOf("1", "2", "3")
                    database.strings("SELECT count(*) FROM pg_class WHERE relnamespace = 'public'::regnamespace").single() shouldBe "0"
                    // Bootstrap provisioned a fresh Administrator; only new facts exist.
                    database.count("commerce.users") shouldBe 1
                    val id = book(app)
                    database.count("fionas.inquiries") shouldBe 1
                    database.strings("SELECT quote_revision FROM fionas.inquiry_proposals") shouldBe listOf("2")
                    database.strings("SELECT kind || ':' || invoice_revision FROM fionas.inquiry_invoice_amendments") shouldBe
                        listOf("INITIAL:3")
                    app.adminGet("/staff/requests/$id").bodyString() shouldContain "\"currentInvoiceRevision\":3"
                }
            }
        }

        test("unauthorized, ambiguous, production, undesignated, shared and in-use targets are refused without changes") {
            TestDatabase.create().use { database ->
                TestApplication.create(existingDatabase = database).use { book(it) }
                designate(database, "staging")
                awaitNoOtherSessions(database)
                val before = state(database)

                fun refused(
                    variables: Map<String, String>,
                    reason: String,
                ) {
                    shouldThrow<SQLException> { reset(database, variables) }.message.orEmpty() shouldContain reason
                    state(database) shouldBe before
                }

                val valid = authorized(database)
                // Unauthorized: psql leaves an undefined variable as invalid SQL, so nothing runs.
                refused(valid - "confirm", "syntax error")
                refused(valid + ("confirm" to "RESET"), "confirm must be exactly")
                refused(valid + ("confirm" to "reset ${name(database)}"), "confirm must be exactly")
                // Ambiguous: the named target must be the connected database.
                refused(valid + ("database" to "postgres"), "not the named target")
                refused(valid - "database", "syntax error")
                // Production names are refused even if someone designated them.
                listOf("production", "prod-eu", "preprod", "live").forEach { environment ->
                    designate(database, environment)
                    refused(authorized(database, environment), "is not a non-production environment name")
                }
                designate(database, "staging")
                // Undesignated, or designated for another environment.
                refused(authorized(database, "development"), "is not designated disposable")
                database.execute("COMMENT ON DATABASE ${name(database)} IS NULL")
                refused(valid, "is not designated disposable")
                designate(database, "staging")
                // Shared: another application's schema or public objects.
                database.execute("CREATE SCHEMA other_application")
                refused(valid, "unexpected schemas: other_application")
                database.execute("DROP SCHEMA other_application")
                database.execute("CREATE TABLE public.other_application (id integer)")
                refused(valid, "public schema or extensions hold other objects")
                database.execute("DROP TABLE public.other_application")
                // In use: a running service or console keeps a session open.
                database.connect { refused(valid, "other sessions are connected") }
                awaitNoOtherSessions(database)
                state(database) shouldBe before
            }
        }

        test("the application never runs or packages the reset, and migrations never drop schemas") {
            File("src/main")
                .walk()
                .filter { it.isFile }
                .filter { "reset-preproduction-database" in it.readText() || "DROP SCHEMA" in it.readText() }
                .toList()
                .shouldBeEmpty()
            // The image is built from src alone; the script stays an operator tool outside it.
            File("Dockerfile").readText().contains("scripts") shouldBe false
        }
    })
