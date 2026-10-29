package io.github.castab.fionas.commerce.conversation

import io.github.castab.fionas.commerce.customer.Email
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI

class ConversationConfigurationSpec :
    FunSpec({
        val railway = "http://resend-conversation-service.railway.internal:3000"
        val settings =
            mapOf(
                "FIONAS_CONVERSATION_BASE_URL" to railway,
                "FIONAS_CONVERSATION_API_KEY" to "test-secret",
                "FIONAS_CONVERSATION_FROM_ADDRESS" to "bookings@fionas.test",
                "FIONAS_CONVERSATION_REPLY_TO_ADDRESS" to "replies@fionas.test",
                "FIONAS_CONVERSATION_RECIPIENT_ALLOWLIST" to "customer@example.com",
            )
        val configurations =
            mapOf<String, (Map<String, String>) -> URI?>(
                "READ" to { ConversationReadConfiguration.fromEnvironment(it)?.base },
                "SEND" to { ConversationSendConfiguration.fromEnvironment(it)?.base },
            )
        val accepted =
            listOf(
                "https://resend.example.com",
                "https://resend.example.com:8443",
                "http://localhost:3000",
                "http://127.0.0.1:3000",
                "http://[::1]:3000",
                railway,
                "http://resend.railway.internal:3000",
                "http://my-service.railway.internal",
                "https://service.railway.internal",
                "http://My-Service.Railway.Internal:3000",
                "http://a.railway.internal",
                "http://${"a".repeat(63)}.railway.internal",
            )
        val rejected =
            listOf(
                "http://resend.example.com",
                "http://api.internal:3000",
                "http://10.0.0.5:3000",
                "http://resend.railway.internal.evil.com",
                "http://evilrailway.internal",
                "http://railway.internal",
                "http://.railway.internal",
                "http://service..railway.internal",
                "http://-service.railway.internal",
                "http://service-.railway.internal",
                "http://service_name.railway.internal",
                "http://${"a".repeat(64)}.railway.internal",
                "http://${List(4) { "a".repeat(63) }.joinToString(".")}.railway.internal",
                "http://service.railway.internal.",
                "http://user:password@service.railway.internal:3000",
                "$railway/api",
                "$railway?debug=true",
                "$railway#fragment",
                "https://user:password@resend.example.com",
                "https://resend.example.com/api",
                "https://resend.example.com?debug=true",
                "https://resend.example.com#fragment",
                "http:///missing-host",
                "ftp://service.railway.internal",
            )

        configurations.forEach { (feature, configure) ->
            val enabled = settings + ("FIONAS_CONVERSATION_${feature}_ENABLED" to "true")
            accepted.forEach { origin ->
                test("$feature accepts provider origin $origin") {
                    configure(enabled + ("FIONAS_CONVERSATION_BASE_URL" to origin)) shouldBe URI(origin)
                }
            }
            rejected.forEach { origin ->
                test("$feature rejects provider origin $origin") {
                    shouldThrowAny {
                        configure(enabled + ("FIONAS_CONVERSATION_BASE_URL" to origin))
                    }
                }
            }
            test("$feature normalizes a trailing slash on a Railway origin") {
                configure(enabled + ("FIONAS_CONVERSATION_BASE_URL" to "$railway/")) shouldBe URI(railway)
            }
            test("$feature remains disabled without provider or sender settings") {
                configure(emptyMap()) shouldBe null
                configure(mapOf("FIONAS_CONVERSATION_${feature}_ENABLED" to "false")) shouldBe null
                configure(
                    mapOf(
                        "FIONAS_CONVERSATION_${feature}_ENABLED" to "false",
                        "FIONAS_CONVERSATION_BASE_URL" to "invalid",
                    ),
                ) shouldBe null
            }
            test("$feature still requires a provider origin and valid credential on Railway") {
                shouldThrowAny { configure(enabled - "FIONAS_CONVERSATION_BASE_URL") }
                shouldThrowAny { configure(enabled - "FIONAS_CONVERSATION_API_KEY") }
                listOf("", " ", "secret\n", "secret\u0000").forEach { credential ->
                    shouldThrowAny { configure(enabled + ("FIONAS_CONVERSATION_API_KEY" to credential)) }
                }
            }
        }

        val sending = settings + ("FIONAS_CONVERSATION_SEND_ENABLED" to "true")
        test("Railway sending preserves sender, Reply-To and exact recipient policy") {
            val config = checkNotNull(ConversationSendConfiguration.fromEnvironment(sending))
            config.fromAddress shouldBe "bookings@fionas.test"
            config.replyToAddress shouldBe "replies@fionas.test"
            config.permits(Email.of("customer@example.com")) shouldBe true
            config.permits(Email.of("other@example.com")) shouldBe false
        }
        test("Railway sending still requires valid sender, Reply-To and recipient allowlist") {
            listOf(
                "FIONAS_CONVERSATION_FROM_ADDRESS",
                "FIONAS_CONVERSATION_REPLY_TO_ADDRESS",
                "FIONAS_CONVERSATION_RECIPIENT_ALLOWLIST",
            ).forEach { name ->
                shouldThrowAny { ConversationSendConfiguration.fromEnvironment(sending - name) }
                listOf("", "not-an-address", "customer@example.com,invalid").forEach { value ->
                    shouldThrowAny { ConversationSendConfiguration.fromEnvironment(sending + (name to value)) }
                }
            }
            shouldThrowAny {
                ConversationSendConfiguration.fromEnvironment(
                    sending + ("FIONAS_CONVERSATION_REPLY_TO_ADDRESS" to "replies+tag@fionas.test"),
                )
            }
            shouldThrowAny {
                ConversationSendConfiguration.fromEnvironment(
                    sending + ("FIONAS_CONVERSATION_FROM_NAME" to "unsafe\r\nname"),
                )
            }
        }
    })
