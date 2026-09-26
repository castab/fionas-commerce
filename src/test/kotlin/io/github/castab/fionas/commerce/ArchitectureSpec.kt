package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.fionaApiRoutes
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSingleElement
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

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

        fun List<File>.containing(tokens: List<String>) =
            flatMap { file ->
                val text = file.readText()
                tokens.filter { it in text }.map { "${file.relativeTo(mainSources).invariantSeparatorsPath}: $it" }
            }

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
            listOf(CustomerRepository::class.java, InquiryRepository::class.java).forEach { repository ->
                repository.declaredMethods.forEach { method ->
                    method.parameterTypes.first() shouldBe Transaction::class.java
                }
            }
        }

        test("repository implementations hold no connection, JDBI, or transaction infrastructure") {
            listOf(JdbiCustomerRepository::class.java, JdbiInquiryRepository::class.java).forEach { repository ->
                repository.declaredFields.map { it.type.name }.shouldBeEmpty()
                repository.declaredConstructors.single().parameterCount shouldBe 0
            }
            sources { it.name.endsWith("Repository.kt") }.containing(transactionInfrastructure).shouldBeEmpty()
        }

        test("only operations open runtime transactions, and nothing builds its own transaction infrastructure") {
            sources()
                .containing(listOf("inTransaction"))
                .shouldContainExactlyInAnyOrder("inquiry/CreateInquiry.kt: inTransaction", "inquiry/GetInquiry.kt: inTransaction")
            sources().containing(transactionInfrastructure - "inTransaction" - "Handle").shouldBeEmpty()
        }

        test("routes contain no SQL and no persistence") {
            sources { it.path.contains("${File.separator}http${File.separator}") }
                .containing(listOf("SELECT ", "INSERT ", "UPDATE ", "DELETE ", "Transaction", "Repository", ".handle"))
                .shouldBeEmpty()
        }

        test("repositories and operations know nothing of HTTP") {
            sources { !it.path.contains("${File.separator}http${File.separator}") && it.name != "FionaApplication.kt" }
                .containing(listOf("org.http4k", "kotlinx.serialization", "Serializable"))
                .shouldBeEmpty()
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
            val routes = fionaApiRoutes(FionaOperations(createInquiry = { error("not called") }, getInquiry = { error("not called") }))

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

        test("Fiona ships no runtime migrations and its migrations never touch the commerce schema") {
            File("src/main/resources/db/commerce").exists() shouldBe false
            // Deliberately blunt: the first reference to a runtime structure that commerce-runtime
            // publishes as a persistence contract must update this guard on purpose.
            File("src/main/resources/db/fionas")
                .listFiles()
                .orEmpty()
                .map { it.readText() }
                .filter { "commerce." in it }
                .shouldBeEmpty()
        }
    })
