package io.github.castab.fionas.commerce

import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.runtime.offering.GetOfferingsCatalog
import io.github.castab.commerce.runtime.offering.OfferingsHttpAccess
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.FinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.fionaApiRoutes
import io.github.castab.fionas.commerce.inquiry.InquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.InquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.InquirySubmissionRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquirySubmissionRepository
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import io.github.castab.fionas.commerce.offering.fionaOfferingsBinding
import io.github.castab.fionas.commerce.staff.CredentialRepository
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.JdbiCredentialRepository
import io.github.castab.fionas.commerce.testing.metadataAuth
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSingleElement
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.util.UUID
import java.util.jar.JarFile

/**
 * Structural guards for the boundaries in AGENTS.md. They are deliberately blunt: a
 * failure here means a change took a path the architecture rules out, and should be
 * rethought rather than the guard loosened.
 */
class ArchitectureSpec :
    FunSpec({
        val mainSources = File("src/main/kotlin/io/github/castab/fionas/commerce")

        fun sources(filter: (File) -> Boolean = { true }) =
            mainSources
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && filter(it) }
                .toList()

        fun migrations() =
            File("src/main/resources/db/fionas")
                .listFiles()
                .orEmpty()
                .filter { it.extension == "sql" }
                .sortedBy { it.name }

        /** The body of every `CREATE TABLE` in [migration], without comments. */
        fun createdTableDefinitions(migration: File) =
            Regex("""(?is)CREATE\s+TABLE\s+(.+?)\);""")
                .findAll(migration.readLines().filterNot { it.trim().startsWith("--") }.joinToString("\n"))
                .map { it.groupValues[1] }
                .toList()

        /** The names of the tables [migration] creates. */
        fun createdTables(migration: File) = createdTableDefinitions(migration).map { it.substringBefore('(').trim() }

        fun List<File>.containing(tokens: List<String>) =
            flatMap { file ->
                val text = file.readText()
                tokens.filter { it in text }.map { "${file.relativeTo(mainSources).invariantSeparatorsPath}: $it" }
            }

        /** Keep structural checks about executable source from matching explanatory comments. */
        fun File.codeWithoutComments() =
            readText()
                .replace(Regex("""(?s)/\*.*?\*/"""), "")
                .replace(Regex("""(?m)//.*$"""), "")

        // Ways to obtain a connection, a JDBI root, or a transaction without the runtime's Transactor.
        val transactionInfrastructure =
            listOf(
                "Jdbi.create",
                "Jdbi.open",
                "HikariDataSource",
                "HikariConfig",
                "createDataSource",
                "DriverManager",
                "DataSource",
                "Transactor(",
                ".begin()",
                ".commit()",
                ".rollback()",
                "useTransaction",
                "inTransaction",
                "Handle",
            )

        test("every repository operation takes the caller's Transaction first") {
            listOf(
                CustomerRepository::class.java,
                InquiryRepository::class.java,
                InquiryFulfillmentRepository::class.java,
                InquiryCommunicationRepository::class.java,
                InquirySubmissionRepository::class.java,
                CredentialRepository::class.java,
                InquiryFinancialDocumentRepository::class.java,
                FinancialDocumentPricingRepository::class.java,
            ).forEach { repository ->
                repository.declaredMethods.forEach { method ->
                    method.parameterTypes.first() shouldBe Transaction::class.java
                }
            }
        }

        test("repository implementations hold no connection, JDBI, or transaction infrastructure") {
            listOf(
                JdbiCustomerRepository::class.java,
                JdbiInquiryRepository::class.java,
                JdbiInquiryFulfillmentRepository::class.java,
                JdbiInquiryCommunicationRepository::class.java,
                JdbiInquirySubmissionRepository::class.java,
                JdbiCredentialRepository::class.java,
                JdbiInquiryFinancialDocumentRepository::class.java,
                JdbiFinancialDocumentPricingRepository::class.java,
            ).forEach { repository ->
                repository.declaredFields.map { it.type.name }.shouldBeEmpty()
                repository.declaredConstructors.single().parameterCount shouldBe 0
            }
            sources { it.name.endsWith("Repository.kt") }.containing(transactionInfrastructure).shouldBeEmpty()
        }

        test("only operations open runtime transactions, and nothing builds its own transaction infrastructure") {
            // Approval and its multi-query response must not gain an inner transaction or a second projection.
            val approval = File(mainSources, "financial/SetDepositRequirement.kt").codeWithoutComments()
            Regex("inTransaction").findAll(approval).count() shouldBe 1
            Regex("bookIfDepositSatisfied").findAll(approval).count() shouldBe 1
            val operational = File(mainSources, "inquiry/ReadInquiryOperationalStates.kt").codeWithoutComments()
            Regex("inTransaction").findAll(operational).count() shouldBe 1
            operational.contains("inTransaction(TransactionIsolation.REPEATABLE_READ)") shouldBe true
            val request = File(mainSources, "staff/ReadStaffRequest.kt").codeWithoutComments()
            Regex("inTransaction").findAll(request).count() shouldBe 1
            request.contains("inTransaction(TransactionIsolation.REPEATABLE_READ)") shouldBe true
            request.contains("inquiries.read(transaction, id)") shouldBe true
            request.contains("financial.read(transaction, inquiry.lifecycle.documentId)") shouldBe true
            sources()
                .containing(listOf("inTransaction"))
                .shouldContainExactlyInAnyOrder(
                    "inquiry/CreateInquiry.kt: inTransaction",
                    "inquiry/RecordInquiryCommunication.kt: inTransaction",
                    "inquiry/GetInquiry.kt: inTransaction",
                    "inquiry/ReadInquiryOperationalStates.kt: inTransaction",
                    "inquiry/ManageInquiryFulfillment.kt: inTransaction",
                    "inquiry/ListInquiries.kt: inTransaction",
                    "staff/StaffAuthentication.kt: inTransaction",
                    "staff/ReadStaffRequest.kt: inTransaction",
                    "staff/ReadStaffDashboard.kt: inTransaction",
                    "financial/GetDepositRequirement.kt: inTransaction",
                    "financial/GetDepositRequirementHistory.kt: inTransaction",
                    "financial/SetDepositRequirement.kt: inTransaction",
                    "financial/WithdrawDepositRequirement.kt: inTransaction",
                    "financial/QueryFinancialLineages.kt: inTransaction",
                    "financial/CreateInquiryFinancialDocument.kt: inTransaction",
                    "financial/CreateChangeOrder.kt: inTransaction",
                    "financial/IssueQuote.kt: inTransaction",
                    "financial/IssueInvoice.kt: inTransaction",
                    "financial/RecordDocumentPayment.kt: inTransaction",
                    "financial/RecordPayment.kt: inTransaction",
                    "financial/AllocatePayment.kt: inTransaction",
                    "financial/RecordRefund.kt: inTransaction",
                    "financial/GetFinancialDocument.kt: inTransaction",
                    "financial/GetFinancialDocumentHistory.kt: inTransaction",
                    "financial/ListInquiryFinancialDocuments.kt: inTransaction",
                    "financial/ListFinancialDocumentPaymentHistories.kt: inTransaction",
                )
            sources().containing(transactionInfrastructure - "inTransaction" - "Handle").shouldBeEmpty()
        }

        test("routes contain no SQL and no persistence") {
            sources { it.path.contains("${File.separator}http${File.separator}") }
                .containing(listOf("SELECT ", "INSERT ", "UPDATE ", "DELETE ", "Transaction", "Repository", ".handle"))
                .shouldBeEmpty()
        }

        test("repositories and operations know nothing of HTTP") {
            // FionaOfferings.kt owns the runtime HTTP binding; its pricing operations stay pure.
            sources {
                !it.path.contains("${File.separator}http${File.separator}") &&
                    it.name != "FionaApplication.kt" &&
                    it.name != "FionaOfferings.kt" &&
                    it.name != "PersistedPricingInputs.kt"
            }.containing(listOf("org.http4k", "kotlinx.serialization", "Serializable"))
                .shouldBeEmpty()
            // The one serialization outside http is Fiona's persisted pricing-inputs JSON: a database
            // representation, never a wire format. It knows no HTTP and no HTTP code uses it.
            listOf(File(mainSources, "offering/PersistedPricingInputs.kt"))
                .containing(listOf("org.http4k", ".http.", "CommerceJson"))
                .shouldBeEmpty()
            sources { it.path.contains("${File.separator}http${File.separator}") }
                .containing(listOf("toPersistedJson", "restorePersistedPricingInputs"))
                .shouldBeEmpty()
        }

        test("persisted pricing inputs are encoded and restored only by the repositories whose rows own them") {
            sources()
                .filter { file ->
                    file.name != "PersistedPricingInputs.kt" &&
                        Regex("""\b(?:toPersistedJson|restorePersistedPricingInputs)\b""").containsMatchIn(file.codeWithoutComments())
                }.map { it.relativeTo(mainSources).invariantSeparatorsPath } shouldContainExactlyInAnyOrder
                listOf("inquiry/JdbiInquiryRepository.kt", "financial/JdbiFinancialDocumentPricingRepository.kt")
        }

        test("Fiona runs no migration lifecycle of its own; commerce-runtime orchestrates both streams") {
            sources().containing(listOf("org.flywaydb", "Flyway", "MigrationLifecycle")).shouldBeEmpty()
            // No Flyway dependency of Fiona's own: it arrives only through commerce-runtime.
            File("build.gradle.kts").readText().contains("org.flywaydb") shouldBe false
            File("gradle/libs.versions.toml").readText().contains("flyway", ignoreCase = true) shouldBe false
        }

        test("every Fiona endpoint is a contract route, so none can escape the OpenAPI document") {
            // Ordinary http4k routing is documentation and routing plumbing only, all of it in
            // FionaApi.kt: Swagger UI, and the 405 answer derived from the contract's own paths.
            sources { it.name != "FionaApi.kt" }.containing(listOf(" bind ", "routes(", "RoutingHttpHandler(")).shouldBeEmpty()
            File(mainSources, "http/FionaApi.kt")
                .readLines()
                .map { it.trim() }
                .filter { " bind" in it } shouldContainExactlyInAnyOrder
                listOf(
                    "API_DOCS_PATH bind",
                    ".map { path -> path bind Method.OPTIONS to { Response(Status.METHOD_NOT_ALLOWED) } },",
                )
        }

        test("every Fiona endpoint states its stable operationId, summary, tags, and responses") {
            val routes =
                fionaApiRoutes(
                    FionaOperations(
                        acknowledgeInquiryCommunication = { error("not called while rendering") },
                        readStaffRequest = { error("not called while rendering") },
                        readStaffDashboard = { error("not called") },
                        markInquiryServed = { error("not called") },
                        closeInquiry = { error("not called") },
                        getInquiryForm = { error("not called") },
                        createInquiry = { error("not called") },
                        listInquiries = { error("not called") },
                        getInquiry = { error("not called") },
                        previewEstimate = { error("not called") },
                        createInquiryEstimate = { _, _ -> error("not called") },
                        createInquiryFinancialDocument = { error("not called") },
                        listInquiryFinancialDocuments = { error("not called") },
                        getFinancialDocument = { error("not called") },
                        getFinancialDocumentHistory = { error("not called") },
                        getDepositRequirement = { error("not called while rendering") },
                        getDepositRequirementHistory = { error("not called while rendering") },
                        setDepositRequirement = { error("not called while rendering") },
                        withdrawDepositRequirement = { error("not called while rendering") },
                        queryFinancialLineages = { error("not called while rendering") },
                        issueQuote = { _, _ -> error("not called") },
                        issueInvoice = { _, _ -> error("not called") },
                        createChangeOrder = { _, _, _ -> error("not called") },
                        recordPayment = { error("not called") },
                        listFinancialDocumentPayments = { error("not called") },
                        listUnappliedPayments = { error("not called") },
                        recordStandalonePayment = { error("not called") },
                        allocatePayment = { error("not called") },
                        recordRefund = { error("not called") },
                        login = { _, _ -> error("not called") },
                        currentUser = { error("not called") },
                        currentPermissions = { error("not called") },
                        setStaffPassword = { _, _ -> error("not called") },
                    ),
                    metadataAuth,
                )

            routes.map { it.meta.operationId.shouldNotBeNull() }.shouldBeUnique()
            routes.forEach { route ->
                route.meta.summary shouldNotBe "<unknown>"
                route.meta.tags.shouldNotBeEmpty()
                route.meta.responses.shouldNotBeEmpty()
            }
        }

        test("no OpenAPI document is maintained by hand; it is always rendered from the contract") {
            File(".")
                .walkTopDown()
                .onEnter { it.name !in setOf("build", ".gradle", ".git", ".kotlin", ".idea") }
                .filter { it.isFile && Regex("""(openapi|swagger).*\.(json|ya?ml)""", RegexOption.IGNORE_CASE).matches(it.name) }
                .toList()
                .shouldBeEmpty()
        }

        test("commerce runtime and transitive domain resolve at the adopted 0.0.22 release") {
            listOf(
                io.github.castab.commerce.runtime.financial.FinancialLedger::class.java to "commerce-runtime",
                io.github.castab.commerce.financial.Money::class.java to "commerce-domain",
            ).forEach { (type, artifact) ->
                type.protectionDomain.codeSource.location.path
                    .substringAfterLast('/') shouldBe "$artifact-0.0.22.jar"
            }
            File("gradle/libs.versions.toml").readText().contains("http4k = \"6.58.0.0\"") shouldBe true
        }

        test("every http4k module is the one version commerce-runtime is built against") {
            val versions =
                Thread
                    .currentThread()
                    .contextClassLoader
                    .getResources("META-INF/MANIFEST.MF")
                    .toList()
                    .mapNotNull { Regex("""/(http4k-[a-z-]+)-(\d[^/]*)\.jar!""").find(it.toString())?.destructured }
                    .map { (module, version) -> "$module:$version" }
            versions.shouldNotBeEmpty()
            versions.map { it.substringAfter(':') }.distinct() shouldHaveSingleElement
                Regex("""^http4k = "(.+)"$""", RegexOption.MULTILINE)
                    .find(File("gradle/libs.versions.toml").readText())!!
                    .groupValues[1]
        }

        test("Fiona's catalog is commerce-runtime's Offerings capability, bound once to Fiona's stable catalog id") {
            // The id is data: every revision of Fiona's catalog is recorded under it. Never change it.
            FIONA_OFFERINGS_CATALOG_ID shouldBe OfferingsCatalogId(UUID.fromString("0cde8e0b-aa9c-4129-9853-8db2cbbb909b"))
            val binding = fionaOfferingsBinding(metadataAuth.access)
            binding.catalogId shouldBe FIONA_OFFERINGS_CATALOG_ID
            binding.basePath shouldBe "/offering-catalog"
            binding.operationIdPrefix shouldBe "fionasOfferings"
            binding.tags.map { it.name }.toSet() shouldBe setOf("Offerings catalog")
            (binding.access is OfferingsHttpAccess.ReadWrite) shouldBe true
            sources().containing(listOf("offeringsHttpCapability(")) shouldContainExactly
                listOf("FionaApplication.kt: offeringsHttpCapability(")
        }

        test("Fiona implements no Offerings mechanics: no repositories, operations, DTOs, or SQL of its own") {
            // Generic catalog behavior is commerce-runtime's. A need it does not meet is a runtime
            // requirement (AGENTS.md, commerce-runtime gap rule), never a local copy. No Fiona
            // type re-declares one of the runtime's Offerings operations, DTOs, or bindings.
            val runtimeOfferingTypes =
                JarFile(
                    File(
                        GetOfferingsCatalog::class.java.protectionDomain.codeSource.location
                            .toURI(),
                    ),
                ).use { jar ->
                    val directory = GetOfferingsCatalog::class.java.packageName.replace('.', '/') + "/"
                    jar
                        .entries()
                        .toList()
                        .map { it.name }
                        .filter { it.startsWith(directory) && it.endsWith(".class") }
                        .map { it.removePrefix(directory).removeSuffix(".class").substringBefore('$') }
                        .filterNot { it.endsWith("Kt") }
                        .toSet()
                }
            runtimeOfferingTypes.shouldNotBeEmpty()
            sources()
                .flatMap { file -> Regex("""\b(?:class|interface|object)\s+(\w+)""").findAll(file.readText()).map { it.groupValues[1] } }
                .filter { it in runtimeOfferingTypes }
                .shouldBeEmpty()
            // The runtime's snapshot repository is named only in the composition root, which hands
            // it to the runtime's own read operation (previews) and its transaction-bound read to
            // FionasPricing (persisted documents); Fiona never calls it itself.
            sources()
                .filter {
                    Regex(
                        """\b(?:OfferingsSnapshotRepository|offeringsSnapshotRepository)\b""",
                    ).containsMatchIn(it.codeWithoutComments())
                }.map { it.relativeTo(mainSources).invariantSeparatorsPath } shouldContainExactly
                listOf("FionaApplication.kt")
            // SQL naming a runtime Offerings table (`commerce.offerings…`), as opposed to the
            // `io.github.castab.commerce.offering` package.
            val runtimeOfferingsTable = Regex("""(?<![\w.])commerce\.offering""")
            sources()
                .filter { runtimeOfferingsTable.containsMatchIn(it.readText()) }
                .shouldBeEmpty()
            // Fiona's migrations create no catalog tables and never reach the runtime's. The keys
            // a financial snapshot was priced from are Fiona's pricing source, not a catalog.
            migrations()
                .filter { migration ->
                    runtimeOfferingsTable.containsMatchIn(migration.readText()) || createdTables(migration).any { "offering" in it }
                }.shouldBeEmpty()
        }

        test("Fiona's pricing is pure policy: it knows nothing of HTTP, persistence, the runtime, or serialization") {
            // The engine, its policy, and its context depend only on commerce-domain and the JDK.
            val allowed =
                listOf(
                    "io.github.castab.commerce.financial.",
                    "io.github.castab.commerce.offering.",
                    "java.math.",
                    "java.time.",
                    "java.util.",
                )
            listOf("FionasOfferingsEngine.kt", "FionasPricingPolicy.kt", "FionasOfferingsContext.kt", "FionasPricingInputs.kt")
                .map { File(mainSources, "offering/$it") }
                .flatMap { file ->
                    file
                        .readLines()
                        .filter { it.startsWith("import ") }
                        .map { it.removePrefix("import ") }
                        .filterNot { import -> allowed.any(import::startsWith) }
                        .map { "${file.name}: $it" }
                }.shouldBeEmpty()
        }

        test("Fiona's pricing names no offering: every per-offering price comes from the catalog") {
            // No `if (offering == "waffle-cone")`: the policy knows the event and the topping
            // category, never an individual offering, so new surcharges need no deployment.
            listOf(
                "FionasOfferingsEngine.kt",
                "FionasPricingPolicy.kt",
                "FionasOfferingsContext.kt",
                "FionasPricingInputs.kt",
                "FionasPricing.kt",
                "PreviewEstimate.kt",
            ).map { File(mainSources, "offering/$it") }
                .filter { "OfferingKey(" in it.readText() }
                .shouldBeEmpty()
        }

        test("an estimate preview reads the catalog through commerce-runtime and records nothing") {
            File(mainSources, "offering/PreviewEstimate.kt")
                .readText()
                .let { preview ->
                    listOf("Repository", "persistence", "Transactor", "Transaction", "org.jdbi", "SELECT ").filter {
                        it in
                            preview
                    }
                }.shouldBeEmpty()
        }

        test("financial documents, payments, and refunds are commerce-runtime's ledger; Fiona stores only its own context") {
            // Fiona reaches documents and payments only through the runtime's FinancialLedger,
            // never its repositories or tables, and writes no SQL against them.
            val runtimeRepositoryNames =
                Regex("""\b(?:financialDocumentRepository|paymentRepository|FinancialDocumentRepository|PaymentRepository)\b""")
            sources().filter { runtimeRepositoryNames.containsMatchIn(it.codeWithoutComments()) }.shouldBeEmpty()
            sources()
                // Table names, as opposed to permission keys such as `commerce.payment.record`.
                .filter { Regex("""(?<![\w.])commerce\.(financial_document|payment_|refund_)""").containsMatchIn(it.readText()) }
                .shouldBeEmpty()
            sources().containing(listOf("financialLedger")) shouldContainExactly listOf("FionaApplication.kt: financialLedger")
            // No Fiona table restates a ledger fact: no lines, amounts, totals, balances, stages,
            // payment status, payments, or allocations of its own.
            val ledgerFacts =
                Regex(
                    """\b(line_items?|lines|amount|price|subtotal|tax|total|balance|stage|status|payments?|refunds?|allocations?)\b""",
                )
            migrations()
                .flatMap { migration -> createdTableDefinitions(migration).map { migration.name to it } }
                .filter { (_, definition) -> ledgerFacts.containsMatchIn(definition) }
                .shouldBeEmpty()
        }

        test("Fiona writes and reads the ledger only inside its operation's transaction") {
            // Every ledger call in Fiona passes the operation's Transaction, so ledger writes commit
            // or roll back with Fiona's. A convenience overload would open a second transaction.
            val ledgerCall = Regex("""\bledger\s*\.\s*(\w+)\s*\(\s*(\w*)""")
            val calls =
                sources()
                    .flatMap { file ->
                        ledgerCall.findAll(file.readText()).map { "${file.name}: ${it.groupValues[1]}(${it.groupValues[2]}" }
                    }
            calls.shouldNotBeEmpty()
            calls.filterNot { it.endsWith("(transaction") }.shouldBeEmpty()
        }

        test("materialization accepts concrete lines and has no catalog, pricing source, or transaction boundary") {
            val source = File(mainSources, "financial/MaterializeInquiryFinancialDocument.kt").codeWithoutComments()
            listOf("FionasPricing", "Offerings", "OfferingKey", "catalogRevision", "FinancialDocumentPricingRepository", "inTransaction")
                .filter { it in source }
                .shouldBeEmpty()
            source.contains("lines: List<LineItem>") shouldBe true
            source.contains("ledger.create(transaction, first)") shouldBe true
        }

        test("recording a refund joins one runtime transaction and reconciles in it") {
            val source = File(mainSources, "financial/RecordRefund.kt").readText()
            source.contains("transactor.inTransaction { transaction ->") shouldBe true
            source.contains("ledger.recordRefund(") shouldBe true
            source.contains("transaction = transaction") shouldBe true
            source.contains("ledger.reconcilePayment(transaction, command.paymentId)") shouldBe true
            listOf("Jdbi", "Handle", "DataSource", "jdbc:").filter { it in source }.shouldBeEmpty()
        }

        test("Jackson renders only the Offerings schemas; kotlinx.serialization stays the wire format") {
            sources().containing(listOf("org.http4k.format.Jackson", "com.fasterxml")) shouldContainExactlyInAnyOrder
                listOf("http/OpenApi.kt: org.http4k.format.Jackson", "http/OpenApi.kt: com.fasterxml")
        }

        test("runtime is the sole session authority and Fiona persists no sessions") {
            sources { it.name.contains("Session") }.shouldBeEmpty()
            File("src/main/resources/db/fionas").listFiles().orEmpty().forEach { migration ->
                Regex("(?i)CREATE\\s+TABLE\\s+[^;]*session").containsMatchIn(migration.readText()) shouldBe false
            }
            File(mainSources, "FionaApplication.kt").readText().contains("context.sessions") shouldBe true
        }

        test("Fiona ships no runtime migrations and references only published runtime keys") {
            File("src/main/resources/db/commerce").exists() shouldBe false
            // The sanctioned runtime schema references: the credential's runtime user, and the
            // exact financial-document snapshots Fiona's inquiry association and pricing
            // sources describe.
            migrations()
                .flatMap { file -> Regex("""commerce\.[a-z_]+""").findAll(file.readText()).map { it.value }.toList() }
                .toSet() shouldBe setOf("commerce.users", "commerce.financial_document_snapshots")
            migrations()
                .flatMap { file -> Regex("""REFERENCES\s+commerce\.[a-z_]+\s*\([^)]*\)""").findAll(file.readText()).map { it.value } }
                .map { it.replace(Regex("""\s+"""), " ") }
                .toSet() shouldBe
                setOf(
                    "REFERENCES commerce.users(principal_id)",
                    "REFERENCES commerce.financial_document_snapshots (document_id, version)",
                )
        }

        test("the bootstrap Administrator is granted the financial permissions explicitly, from commerce's own keys") {
            val bootstrap = File(mainSources, "staff/StaffAuthentication.kt").readText()
            listOf(
                "CommercePermissions.FinancialDocumentRead",
                "CommercePermissions.FinancialDocumentCreate",
                "CommercePermissions.PaymentRecord",
                "CommercePermissions.RefundRecord",
            ).filterNot { it in bootstrap }.shouldBeEmpty()
            // Fiona defines no duplicates of generic commerce permissions: only Fiona-specific actions.
            FionaPermissions.definitions.map { it.key } shouldContainExactly
                listOf(
                    FionaPermissions.CredentialsManage,
                    FionaPermissions.InquiriesRead,
                    FionaPermissions.InquiriesCreate,
                    FionaPermissions.CommunicationsAcknowledge,
                    FionaPermissions.InquiriesManage,
                    FionaPermissions.InquiryFormRead,
                    FionaPermissions.EstimatePreviewCreate,
                )
            FionaPermissions.definitions.forEach { it.key.value shouldStartWith "fionas." }
            // Fresh Administrators can discover inquiries, use the customer operations, and issue
            // service credentials; existing roles are never changed at startup.
            listOf(
                "FionaPermissions.InquiriesRead",
                "FionaPermissions.InquiriesCreate",
                "FionaPermissions.InquiryFormRead",
                "FionaPermissions.EstimatePreviewCreate",
                "RuntimePermissions.ServiceCredentialManage",
            ).filterNot { it in bootstrap }.shouldBeEmpty()
        }

        test("no static API key remains: callers authenticate as principals through commerce-runtime") {
            val retired = listOf("UiApiKey", "uiApiKey", "FIONAS_UI_API_KEY", "fionasUiApiKey")
            sources().containing(retired).shouldBeEmpty()
            listOf(File("src/main/resources/application.conf"), File(".env.example"), File("src/openapi"))
                .flatMap { root -> root.walk().filter { it.isFile }.toList() }
                .filter { file -> retired.any { it in file.readText() } }
                .shouldBeEmpty()
        }
    })
