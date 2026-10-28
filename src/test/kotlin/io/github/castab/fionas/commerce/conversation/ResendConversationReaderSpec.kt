package io.github.castab.fionas.commerce.conversation

import com.sun.net.httpserver.HttpServer
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.util.UUID

class ResendConversationReaderSpec :
    FunSpec({
        val inquiry = InquiryId(UUID.randomUUID())
        val conversation = UUID.randomUUID()
        val message = UUID.randomUUID()
        var status = 200
        var response = ""
        var calls = 0
        lateinit var server: HttpServer
        lateinit var reader: ResendConversationReader
        beforeTest {
            calls = 0
            status = 200
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/conversations/v2/topics/booking/${inquiry.value}") { exchange ->
                calls++
                exchange.requestHeaders.getFirst("Authorization") shouldBe "Bearer test-secret"
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            reader =
                ResendConversationReader(
                    ConversationReadConfiguration.fromEnvironment(
                        mapOf(
                            "FIONAS_CONVERSATION_READ_ENABLED" to "true",
                            "FIONAS_CONVERSATION_BASE_URL" to "http://127.0.0.1:${server.address.port}",
                            "FIONAS_CONVERSATION_API_KEY" to "test-secret",
                        ),
                    )!!,
                )
            response = """{"id":"$conversation","topic":{"type":"booking","externalId":"${inquiry.value}"},"subject":"Booking",
          "state":"awaiting_us","messages":[{"id":"$message","direction":"inbound","state":"received","deliveryState":null,
          "from":{"address":"customer@example.com","name":null},"to":"staff@example.com","text":"  hello\nworld  ",
          "html":"<script>untrusted</script>","createdAt":"2026-10-01T12:00:00Z"}],"page":{"hasMoreBefore":false,"before":null}}"""
        }
        afterTest { server.stop(0) }
        test("reads topic identity and plain text without trimming, HTML or routing tokens") {
            val result = reader.read(inquiry)
            result.availability shouldBe ConversationAvailability.READY
            result.conversation!!
                .messages
                .single()
                .text shouldBe "  hello\nworld  "
            result.conversation.messages
                .single()
                .deliveryState shouldBe null
            calls shouldBe 1
        }
        test("missing, unavailable and forbidden remain distinct and never trigger a write") {
            status = 404
            reader.read(inquiry).availability shouldBe ConversationAvailability.EMPTY
            status = 500
            reader.read(inquiry).availability shouldBe ConversationAvailability.UNAVAILABLE
            status = 401
            reader.read(inquiry).availability shouldBe ConversationAvailability.FORBIDDEN
            calls shouldBe 3
        }
        test("malformed success, wrong topic and repeated pagination cursor fail closed") {
            val valid = response
            response = "{}"
            reader.read(inquiry).availability shouldBe ConversationAvailability.UNAVAILABLE
            response = valid.replace(inquiry.value.toString(), UUID.randomUUID().toString())
            reader.read(inquiry).availability shouldBe ConversationAvailability.UNAVAILABLE
            response = valid.replace("\"hasMoreBefore\":false,\"before\":null", "\"hasMoreBefore\":true,\"before\":\"$message\"")
            reader.read(inquiry).availability shouldBe ConversationAvailability.UNAVAILABLE
        }
        test("reading is disabled without credentials by default") {
            ConversationReadConfiguration.fromEnvironment(emptyMap()) shouldBe null
        }
    })
