package io.github.castab.fionas.commerce

import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/** The database shape fionas-commerce ends up with after commerce-runtime's migration phase. */
class DatabaseSchemaSpec :
    FunSpec({
        lateinit var application: TestApplication

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun tables(schema: String) =
            application.database.strings(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' ORDER BY table_name",
            )

        test("Fiona's tables live in the fionas schema, which Fiona owns") {
            tables("fionas") shouldContainExactlyInAnyOrder listOf("customers", "inquiries")
        }

        test("no Fiona table is created in the runtime-owned commerce schema or left in public") {
            // Each schema holds only the migration history commerce-runtime keeps for its stream.
            tables("commerce") shouldContainExactly listOf("flyway_schema_history")
            tables("public") shouldContainExactly listOf("flyway_schema_history")
        }

        test("inquiries reference their customer, and customer emails are unique") {
            application.database.strings(
                """
                SELECT tc.constraint_type || ' ' || tc.table_name || '(' || kcu.column_name || ')'
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_schema = tc.constraint_schema AND kcu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'fionas'
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
