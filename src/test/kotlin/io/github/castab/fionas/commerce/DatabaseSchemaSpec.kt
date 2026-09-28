package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldStartWith

/** The database shape fionas-commerce ends up with after commerce-runtime's migration phase. */
class DatabaseSchemaSpec :
    FunSpec({
        lateinit var application: TestApplication

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun TestDatabase.tables(schema: String) =
            strings("SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' ORDER BY table_name")

        /** Every object in [schema] (relations, indexes, sequences, constraints, functions, types), by kind and name. */
        fun TestDatabase.objects(schema: String) =
            strings(
                """
                SELECT 'relation ' || c.relkind::text || ' ' || c.relname FROM pg_class c WHERE c.relnamespace = '$schema'::regnamespace
                UNION ALL
                SELECT 'constraint ' || con.conname FROM pg_constraint con WHERE con.connamespace = '$schema'::regnamespace
                UNION ALL
                SELECT 'function ' || p.proname FROM pg_proc p WHERE p.pronamespace = '$schema'::regnamespace
                UNION ALL
                SELECT 'type ' || t.typname FROM pg_type t WHERE t.typnamespace = '$schema'::regnamespace
                ORDER BY 1
                """.trimIndent(),
            )

        test("Fiona's tables live in the fionas schema, which Fiona owns") {
            application.database.tables("fionas") shouldContainExactlyInAnyOrder
                listOf("customers", "inquiries", "user_credentials")
        }

        test("Fiona credentials reference the runtime-owned human user") {
            application.database.strings(
                """
                SELECT ccu.table_schema || '.' || ccu.table_name || '(' || ccu.column_name || ')'
                FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_schema = tc.constraint_schema AND ccu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'fionas' AND tc.table_name = 'user_credentials'
                  AND tc.constraint_type = 'FOREIGN KEY'
                """.trimIndent(),
            ) shouldContainExactly listOf("commerce.users(principal_id)")
        }

        test("the commerce schema holds exactly what commerce-runtime creates on its own, nothing of Fiona's") {
            // Composing the runtime with no application contributions creates the runtime's
            // own structures (its migration history and published tables, such as the Offerings
            // snapshot tables). Fiona adds, changes, and removes nothing in that schema, and
            // this spec never needs to know which structures a runtime release owns.
            val runtimeOnly =
                TestDatabase.create().use { database ->
                    commerceRuntime(
                        CommerceRuntimeConfiguration(
                            server = CommerceRuntimeConfiguration.Server(port = 0),
                            database = database.configuration,
                            migrations = CommerceRuntimeConfiguration.Migrations(onStartup = OnStartup.MIGRATE),
                        ),
                        ApplicationContributions(),
                    ).close()
                    database.objects("commerce")
                }

            application.database.objects("commerce") shouldContainExactly runtimeOnly
        }

        test("no Fiona table is left in public, which holds only the history commerce-runtime keeps for Fiona's stream") {
            application.database.tables("public") shouldContainExactly listOf("flyway_schema_history")
        }

        test("Fiona creates no Offerings persistence: the catalog lives only in commerce-runtime's tables") {
            application.database
                .strings(
                    "SELECT table_schema || '.' || table_name FROM information_schema.tables " +
                        "WHERE table_name LIKE '%offering%' ORDER BY 1",
                ).forEach { it shouldStartWith "commerce." }
        }

        test("inquiries reference their customer, and customer emails are unique") {
            application.database.strings(
                """
                SELECT tc.constraint_type || ' ' || tc.table_name || '(' || kcu.column_name || ')'
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_schema = tc.constraint_schema AND kcu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'fionas' AND tc.table_name IN ('customers', 'inquiries')
                ORDER BY 1
                """.trimIndent(),
            ) shouldContainExactly
                listOf(
                    "FOREIGN KEY inquiries(customer_id)",
                    "PRIMARY KEY customers(id)",
                    "PRIMARY KEY inquiries(id)",
                    "UNIQUE customers(email)",
                )
            application.database.strings(
                """
                SELECT ccu.table_schema || '.' || ccu.table_name || '(' || ccu.column_name || ')'
                FROM information_schema.constraint_column_usage ccu
                WHERE ccu.constraint_schema = 'fionas' AND ccu.constraint_name = 'inquiries_customer_id_fkey'
                """.trimIndent(),
            ) shouldContainExactly listOf("fionas.customers(id)")
        }
    })
