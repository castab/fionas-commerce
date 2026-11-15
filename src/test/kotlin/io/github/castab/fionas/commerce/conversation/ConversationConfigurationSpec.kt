package io.github.castab.fionas.commerce.conversation

import io.github.castab.fionas.commerce.customer.Email
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.URI

class ConversationConfigurationSpec :
    FunSpec({
        val container = "http://rcs-dev:3000"
        val railway = "http://resend-conversation-service.railway.internal:3000"
        val settings =
            mapOf(
                "FIONAS_CONVERSATION_BASE_URL" to container,
                "FIONAS_CONVERSATION_PRIVATE_HTTP_HOSTS" to "rcs-dev, Resend-Conversation-Service.railway.internal",
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
        val acceptedByDefault =
            listOf(
                "https://resend.example.com",
                "https://resend.example.com:8443",
                "https://service.railway.internal",
                "https://rcs-dev:3000",
                "http://localhost:3000",
                "http://127.0.0.1:3000",
                "http://[::1]:3000",
            )
        val rejectedByDefault =
            listOf(
                "http://resend.example.com",
                "http://api.internal:3000",
                "http://10.0.0.5:3000",
                container,
                railway,
                "http://resend.railway.internal:3000",
                "http://a.railway.internal",
                "https://user:password@resend.example.com",
                "https://resend.example.com/api",
                "https://resend.example.com?debug=true",
                "https://resend.example.com#fragment",
                "http:///missing-host",
                "ftp://resend.example.com",
            )
        val acceptedWithAllowlist =
            listOf(
                container,
                "http://rcs-dev",
                "http://RCS-Dev:3000",
                railway,
                "http://resend-conversation-service.railway.internal",
                "http://Resend-Conversation-Service.Railway.Internal:3000",
            )
        val rejectedWithAllowlist =
            listOf(
                "http://rcs-prod:3000",
                "http://rcs-devx:3000",
                "http://rcs-de:3000",
                "http://rcs-dev.example.com:3000",
                "http://evil.rcs-dev:3000",
                "http://rcs_dev:3000",
                "http://other-service.railway.internal:3000",
                "http://conversation-service.railway.internal:3000",
                "http://railway.internal:3000",
                "http://resend-conversation-service.railway.internal.evil.com",
                "http://resend-conversation-service.railway.internal.",
                "http://resend.example.com",
                "http://10.0.0.5:3000",
                "http://user:password@rcs-dev:3000",
                "http://token@rcs-dev:3000",
                "http://user:password@resend-conversation-service.railway.internal:3000",
                "$container/api",
                "$container?debug=true",
                "$container#fragment",
                "$railway/api",
                "ftp://rcs-dev:3000",
            )
        val validHostLists =
            listOf(
                "a",
                "a".repeat(63),
                "resend.lan",
                "my-service.railway.internal",
                "${List(3) { "a".repeat(63) }.joinToString(".")}.${"a".repeat(61)}",
                "rcs-dev,rcs-dev",
            )
        val invalidHostLists =
            listOf(
                "rcs-dev,",
                "rcs-dev,,other",
                "*",
                "*.railway.internal",
                ".railway.internal",
                "rcs-*",
                "rcs-dev.",
                ".rcs-dev",
                "rcs..dev",
                "rcs-dev:3000",
                "http://rcs-dev",
                "rcs-dev/api",
                "rcs dev",
                "rcs_dev",
                "-rcs-dev",
                "rcs-dev-",
                "service-.railway.internal",
                "a".repeat(64),
                "${"a".repeat(64)}.railway.internal",
                List(4) { "a".repeat(63) }.joinToString("."),
                "10.0.0.5",
                "127",
                "service.123",
                "[::1]",
                "::1",
                "user@rcs-dev",
            )

        configurations.forEach { (feature, configure) ->
            val enabled = settings + ("FIONAS_CONVERSATION_${feature}_ENABLED" to "true")
            val defaults = enabled - "FIONAS_CONVERSATION_PRIVATE_HTTP_HOSTS"
            acceptedByDefault.forEach { origin ->
                test("$feature accepts provider origin $origin without a private HTTP allowlist") {
                    configure(defaults + ("FIONAS_CONVERSATION_BASE_URL" to origin)) shouldBe URI(origin)
                }
            }
            rejectedByDefault.forEach { origin ->
                test("$feature rejects provider origin $origin without a private HTTP allowlist") {
                    shouldThrowAny { configure(defaults + ("FIONAS_CONVERSATION_BASE_URL" to origin)) }
                }
            }
            acceptedWithAllowlist.forEach { origin ->
                test("$feature accepts allowlisted private HTTP origin $origin") {
                    configure(enabled + ("FIONAS_CONVERSATION_BASE_URL" to origin)) shouldBe URI(origin)
                }
            }
            rejectedWithAllowlist.forEach { origin ->
                test("$feature rejects $origin despite the private HTTP allowlist") {
                    shouldThrowAny { configure(enabled + ("FIONAS_CONVERSATION_BASE_URL" to origin)) }
                }
            }
            listOf(container, railway).forEach { origin ->
                test("$feature normalizes a trailing slash on private HTTP origin $origin") {
                    configure(enabled + ("FIONAS_CONVERSATION_BASE_URL" to "$origin/")) shouldBe URI(origin)
                }
            }
            validHostLists.forEach { hosts ->
                test("$feature accepts private HTTP allowlist $hosts") {
                    val host = hosts.substringBefore(',')
                    configure(
                        enabled + ("FIONAS_CONVERSATION_PRIVATE_HTTP_HOSTS" to hosts) +
                            ("FIONAS_CONVERSATION_BASE_URL" to "http://$host:3000"),
                    ) shouldBe URI("http://$host:3000")
                }
            }
            invalidHostLists.forEach { hosts ->
                test("$feature rejects private HTTP allowlist $hosts") {
                    shouldThrowAny {
                        configure(
                            enabled + ("FIONAS_CONVERSATION_PRIVATE_HTTP_HOSTS" to hosts) +
                                ("FIONAS_CONVERSATION_BASE_URL" to "https://resend.example.com"),
                        )
                    }
                }
            }
            test("$feature treats a blank private HTTP allowlist as absent") {
                listOf("", "  ").forEach { hosts ->
                    val blank = enabled + ("FIONAS_CONVERSATION_PRIVATE_HTTP_HOSTS" to hosts)
                    configure(blank + ("FIONAS_CONVERSATION_BASE_URL" to "https://resend.example.com")) shouldBe
                        URI("https://resend.example.com")
                    shouldThrowAny { configure(blank) }
                    shouldThrowAny { configure(blank + ("FIONAS_CONVERSATION_BASE_URL" to railway)) }
                }
            }
            test("$feature remains disabled without provider or sender settings") {
                configure(emptyMap()) shouldBe null
                configure(mapOf("FIONAS_CONVERSATION_${feature}_ENABLED" to "false")) shouldBe null
                configure(
                    mapOf(
                        "FIONAS_CONVERSATION_${feature}_ENABLED" to "false",
                        "FIONAS_CONVERSATION_BASE_URL" to "invalid",
                        "FIONAS_CONVERSATION_PRIVATE_HTTP_HOSTS" to "*",
                    ),
                ) shouldBe null
            }
            test("$feature still requires a provider origin and valid credential over private HTTP") {
                shouldThrowAny { configure(enabled - "FIONAS_CONVERSATION_BASE_URL") }
                shouldThrowAny { configure(enabled - "FIONAS_CONVERSATION_API_KEY") }
                listOf("", " ", "secret\n", "secret\u0000").forEach { credential ->
                    shouldThrowAny { configure(enabled + ("FIONAS_CONVERSATION_API_KEY" to credential)) }
                }
            }
        }

        val sending = settings + ("FIONAS_CONVERSATION_SEND_ENABLED" to "true")
        test("private HTTP sending preserves sender, Reply-To and exact recipient policy") {
            val config = checkNotNull(ConversationSendConfiguration.fromEnvironment(sending))
            config.base shouldBe URI(container)
            config.fromAddress shouldBe "bookings@fionas.test"
            config.replyToAddress shouldBe "replies@fionas.test"
            config.permits(Email.of("customer@example.com")) shouldBe true
            config.permits(Email.of("other@example.com")) shouldBe false
        }
        test("private HTTP sending still requires valid sender, Reply-To and recipient allowlist") {
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
