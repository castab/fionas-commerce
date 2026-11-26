package io.github.castab.fionas.commerce.passkey

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class WebAuthnConfigurationSpec :
    FunSpec({
        val complete =
            mapOf(
                "FIONAS_WEBAUTHN_RP_ID" to "admin-dev.fionasicecream.com",
                "FIONAS_WEBAUTHN_RP_NAME" to "Fiona's Admin",
                "FIONAS_WEBAUTHN_ORIGINS" to " https://admin-dev.fionasicecream.com ,",
            )

        test("all three variables enable passkeys; none disables them; some fail startup") {
            WebAuthnConfiguration.fromEnvironment(emptyMap()).shouldBeNull()
            WebAuthnConfiguration.fromEnvironment(complete)!!.let {
                it.rpId shouldBe "admin-dev.fionasicecream.com"
                it.origins shouldBe setOf("https://admin-dev.fionasicecream.com")
            }
            shouldThrow<IllegalArgumentException> { WebAuthnConfiguration.fromEnvironment(complete - "FIONAS_WEBAUTHN_ORIGINS") }
                .message shouldContain "FIONAS_WEBAUTHN_ORIGINS"
        }

        test("origins must be bare https origins on the relying party id, or http on localhost") {
            WebAuthnConfiguration("localhost", "Fiona's Admin", setOf("http://localhost:5174"))
            WebAuthnConfiguration("fionas.test", "Fiona's Admin", setOf("https://admin.fionas.test"))
            listOf(
                setOf("http://admin-dev.fionasicecream.com"),
                setOf("https://fionasicecream.com"),
                setOf("https://evil-admin-dev.fionasicecream.com.example"),
                setOf("https://admin-dev.fionasicecream.com/login"),
                emptySet(),
            ).forEach { origins ->
                shouldThrow<IllegalArgumentException> { WebAuthnConfiguration("admin-dev.fionasicecream.com", "Fiona's Admin", origins) }
            }
            shouldThrow<IllegalArgumentException> { WebAuthnConfiguration("https://admin.fionasicecream.com", "Fiona's Admin", setOf()) }
        }

        test("every passkey origin must also be a trusted browser origin") {
            val configuration = WebAuthnConfiguration.fromEnvironment(complete)!!
            configuration.requireTrusted(setOf("https://admin-dev.fionasicecream.com"))
            shouldThrow<IllegalArgumentException> { configuration.requireTrusted(emptySet()) }.message shouldContain
                "FIONAS_TRUSTED_ORIGINS"
        }
    })
