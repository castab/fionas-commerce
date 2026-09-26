package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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

        test("Fiona ships no migration in the runtime-owned commerce location") {
            File("src/main/resources/db/commerce").exists() shouldBe false
            File("src/main/resources/db/fionas")
                .listFiles()
                .orEmpty()
                .map { it.readText() }
                .filter { "commerce." in it }
                .shouldBeEmpty()
        }
    })
