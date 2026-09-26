package io.github.castab.fionas.commerce

import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/** What commerce-runtime's migration orchestration creates for fionas-commerce. */
class DatabaseSchemaSpec :
    FunSpec({
        lateinit var application: TestApplication

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun tables(schema: String) =
            application.database.strings(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' ORDER BY table_name",
            )

        test("the Fiona migrations create Fiona's tables in the application schema, with Fiona's own history") {
            tables("public") shouldContainExactlyInAnyOrder listOf("customers", "inquiries", "flyway_schema_history")
            application.database.strings(
                "SELECT script FROM public.flyway_schema_history WHERE success ORDER BY installed_rank",
            ) shouldContainExactly listOf("V20260926210000__customers_and_inquiries.sql")
        }

        test("no Fiona table is created in the commerce schema") {
            tables("commerce") shouldContainExactly listOf("flyway_schema_history")
            application.database.strings("SELECT script FROM commerce.flyway_schema_history") shouldContain
                "V20260926180000__drop_commerce_customers.sql"
        }

        test("inquiries reference their customer, and customer emails are unique") {
            application.database.strings(
                """
                SELECT tc.constraint_type || ' ' || tc.table_name || '(' || kcu.column_name || ')'
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_schema = tc.constraint_schema AND kcu.constraint_name = tc.constraint_name
                WHERE tc.table_schema = 'public'
                ORDER BY 1
                """.trimIndent(),
            ) shouldContainExactly
                listOf(
                    "FOREIGN KEY inquiries(customer_id)",
                    "PRIMARY KEY customers(id)",
                    "PRIMARY KEY flyway_schema_history(installed_rank)",
                    "PRIMARY KEY inquiries(id)",
                    "UNIQUE customers(email)",
                )
            application.database.strings(
                """
                SELECT ccu.table_name || '(' || ccu.column_name || ')'
                FROM information_schema.constraint_column_usage ccu
                WHERE ccu.constraint_name = 'inquiries_customer_id_fkey'
                """.trimIndent(),
            ) shouldContainExactly listOf("customers(id)")
        }
    })
