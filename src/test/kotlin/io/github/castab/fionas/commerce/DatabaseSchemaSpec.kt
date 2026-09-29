package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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

        test("Fiona's tables live in the fionas schema, which Fiona owns, beside its own migration history") {
            application.database.tables(FIONA_MIGRATION_SCHEMA) shouldContainExactlyInAnyOrder
                listOf(
                    "flyway_schema_history",
                    "customers",
                    "inquiries",
                    "user_credentials",
                    "inquiry_financial_documents",
                    "financial_document_pricing",
                    "financial_document_pricing_categories",
                    "financial_document_pricing_selections",
                )
        }

        /** Every foreign key of Fiona's [table], as `columns → referenced table(columns)`. */
        fun TestDatabase.foreignKeys(table: String) =
            strings(
                """
                SELECT (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)
                        FROM unnest(con.conkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
                    || ' → ' || referenced_namespace.nspname || '.' || referenced_table.relname || '('
                    || (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)
                        FROM unnest(con.confkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum)
                    || ')'
                FROM pg_constraint con
                JOIN pg_class referenced_table ON referenced_table.oid = con.confrelid
                JOIN pg_namespace referenced_namespace ON referenced_namespace.oid = referenced_table.relnamespace
                WHERE con.contype = 'f' AND con.conrelid = 'fionas.$table'::regclass
                """.trimIndent(),
            )

        test("an inquiry owns financial-document lineages, each keyed to commerce-runtime's first snapshot of it") {
            application.database.foreignKeys("inquiry_financial_documents") shouldContainExactlyInAnyOrder
                listOf(
                    "document_id, initial_version → commerce.financial_document_snapshots(document_id, version)",
                    "inquiry_id → fionas.inquiries(id)",
                )
            // One inquiry may own several lineages; a lineage belongs to exactly one inquiry.
            application.database.strings(
                """
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'fionas.inquiry_financial_documents'::regclass AND contype IN ('p', 'u')
                """.trimIndent(),
            ) shouldContainExactly listOf("PRIMARY KEY (document_id)")
            application.database.strings(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'fionas' AND tablename = 'inquiry_financial_documents' " +
                    "AND indexname <> 'inquiry_financial_documents_pkey'",
            ) shouldContainExactly
                listOf(
                    "CREATE INDEX inquiry_financial_documents_inquiry_id_idx ON fionas.inquiry_financial_documents " +
                        "USING btree (inquiry_id, created_at)",
                )
        }

        test("every pricing source is keyed to commerce-runtime's exact snapshot of a lineage Fiona owns") {
            application.database.foreignKeys("financial_document_pricing") shouldContainExactlyInAnyOrder
                listOf(
                    "document_id → fionas.inquiry_financial_documents(document_id)",
                    "document_id, document_version → commerce.financial_document_snapshots(document_id, version)",
                )
            application.database.foreignKeys("financial_document_pricing_categories") shouldContainExactlyInAnyOrder
                listOf("document_id, document_version → fionas.financial_document_pricing(document_id, document_version)")
            application.database.foreignKeys("financial_document_pricing_selections") shouldContainExactlyInAnyOrder
                listOf(
                    "document_id, document_version, category_position → " +
                        "fionas.financial_document_pricing_categories(document_id, document_version, position)",
                )
        }

        test("Fiona duplicates no ledger fact: documents, payments, refunds, and reconciliation stay in commerce") {
            application.database
                .strings(
                    """
                    SELECT table_name || '.' || column_name FROM information_schema.columns
                    WHERE table_schema = 'fionas'
                      AND column_name ~ '(line|amount|price|total|tax|balance|stage|status|payment|refund|allocation|reconciliation|currency)'
                    """.trimIndent(),
                ).shouldBeEmpty()
            application.database
                .strings(
                    """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'fionas' AND table_name ~ '(line|payment|refund|allocation|reconciliation|balance|status|snapshot)'
                    """.trimIndent(),
                ).shouldBeEmpty()
        }

        test("commerce-runtime V6 owns both refund tables; Fiona owns neither") {
            application.database.tables("commerce").containsAll(listOf("refund_records", "refund_allocations")) shouldBe true
            application.database.tables("fionas").none { "refund" in it } shouldBe true
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

        test("each migration stream keeps its history in its own schema") {
            application.database.tables("commerce") shouldContain "flyway_schema_history"
            application.database.tables(FIONA_MIGRATION_SCHEMA) shouldContain "flyway_schema_history"
        }

        test("public holds no Fiona-owned objects or migration metadata") {
            application.database.objects("public").shouldBeEmpty()
            application.database.tables("public").shouldBeEmpty()
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
