package io.github.castab.fionas.commerce.staff

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BootstrapAdminEnvironmentSpec :
    FunSpec({
        val username = "FIONAS_BOOTSTRAP_ADMIN_USERNAME"
        val password = "FIONAS_BOOTSTRAP_ADMIN_PASSWORD"
        val displayName = "FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME"
        val suppliedPassword = "  test-password-with-spaces  "
        val complete =
            mapOf(
                username to "admin",
                password to suppliedPassword,
                displayName to "Administrator",
            )

        test("no required bootstrap variables disables bootstrap") {
            BootstrapAdmin.fromEnvironment(emptyMap()) shouldBe null
            BootstrapAdmin.fromEnvironment(mapOf("FIONAS_BOOTSTRAP_ADMIN_FIRST_NAME" to "Test")) shouldBe null
        }

        test("all required bootstrap variables create the input without changing its password") {
            val input = BootstrapAdmin.fromEnvironment(complete)!!
            input.username shouldBe "admin"
            input.displayName shouldBe "Administrator"
            input.password.use { secret ->
                secret.withCharacters { characters -> String(characters) shouldBe suppliedPassword }
            }
        }

        test("each partial combination fails and names its missing required variables") {
            listOf(
                mapOf(username to "admin") to listOf(password, displayName),
                complete - displayName to listOf(displayName),
                complete - password to listOf(password),
                complete - username to listOf(username),
            ).forEach { (environment, missing) ->
                val message = shouldThrow<IllegalArgumentException> { BootstrapAdmin.fromEnvironment(environment) }.message!!
                missing.forEach { name -> message shouldContain name }
                message shouldNotContain suppliedPassword
            }
        }

        test("blank username and display name fail after trimming") {
            listOf(username, displayName).forEach { key ->
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        BootstrapAdmin.fromEnvironment(complete + (key to "   "))
                    }
                val message = failure.message!!
                message shouldContain key
                message shouldNotContain suppliedPassword
            }
        }

        test("blank and short passwords fail without exposing their values") {
            listOf("", "   ", "too-short").forEach { value ->
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        BootstrapAdmin.fromEnvironment(complete + (password to value))
                    }
                val message = failure.message!!
                message shouldContain password
                if (value.isNotEmpty()) message shouldNotContain value
            }
        }
    })
