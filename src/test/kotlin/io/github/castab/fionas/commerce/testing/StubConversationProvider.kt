package io.github.castab.fionas.commerce.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.castab.fionas.commerce.conversation.ConversationReadConfiguration
import io.github.castab.fionas.commerce.conversation.ConversationSendConfiguration
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A deterministic in-process stand-in for the Resend Conversation Service V2 routes Fiona uses, following its
 * 0.7.2-rc.2 idempotency contract: a key is bound to its exact request, a replay returns the stored result, and a
 * different request under a used key is 409. Behaviors script provider failures per send attempt.
 */
class StubConversationProvider : AutoCloseable {
    enum class Behavior { NORMAL, ERROR_AFTER_PERSIST, TIMEOUT_AFTER_PERSIST, REJECT, FAILED, INDETERMINATE }

    data class Message(
        val id: UUID,
        val direction: String,
        val state: String,
        val text: String,
        val createdAt: Instant,
    )

    class Conversation(
        val id: UUID,
        val messages: MutableList<Message> = mutableListOf(),
    )

    private data class Stored(
        val request: String,
        val status: Int,
        val response: String,
    )

    val conversations = ConcurrentHashMap<String, Conversation>()
    private val stored = ConcurrentHashMap<String, Stored>()
    val script = ConcurrentLinkedDeque<Behavior>()
    val sendAttempts = AtomicInteger()
    val created = AtomicInteger()
    val requests = ConcurrentLinkedDeque<JsonObject>()

    @Volatile var lookupStatus: Int? = null

    /** Delays same-key replays, so a scripted timeout can also defeat the client's single reconciliation attempt. */
    @Volatile var replayDelayMillis = 0L

    /** Runs inside the first (non-replayed) send, before the provider persists it: a hook for concurrent Fiona writes. */
    @Volatile var onSend: (() -> Unit)? = null

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/api/conversations/v2") { exchange -> exchange.use { handle(it) } }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    val url get() = "http://127.0.0.1:${server.address.port}"

    fun readConfiguration(): ConversationReadConfiguration =
        checkNotNull(
            ConversationReadConfiguration.fromEnvironment(
                mapOf(
                    "FIONAS_CONVERSATION_READ_ENABLED" to "true",
                    "FIONAS_CONVERSATION_BASE_URL" to url,
                    "FIONAS_CONVERSATION_API_KEY" to STUB_KEY,
                ),
            ),
        )

    fun sendConfiguration(allowList: String = "*"): ConversationSendConfiguration =
        checkNotNull(
            ConversationSendConfiguration.fromEnvironment(
                mapOf(
                    "FIONAS_CONVERSATION_SEND_ENABLED" to "true",
                    "FIONAS_CONVERSATION_BASE_URL" to url,
                    "FIONAS_CONVERSATION_API_KEY" to STUB_KEY,
                    "FIONAS_CONVERSATION_FROM_ADDRESS" to "bookings@mail.fionas.test",
                    "FIONAS_CONVERSATION_REPLY_TO_ADDRESS" to "replies@mail.fionas.test",
                    "FIONAS_CONVERSATION_RECIPIENT_ALLOWLIST" to allowList,
                    "FIONAS_CONVERSATION_SEND_TIMEOUT_MS" to "1000",
                ),
            ),
        )

    /** A customer email arriving in [inquiry]'s conversation, creating it as provider-side threading would. */
    fun inbound(
        inquiry: String,
        text: String = "Can we change the date?",
        at: Instant = Instant.parse("2026-10-01T12:00:00Z"),
    ): UUID {
        val conversation = conversations.computeIfAbsent(inquiry) { Conversation(UUID.randomUUID()) }
        val message = Message(UUID.randomUUID(), "inbound", "received", text, at)
        synchronized(conversation) { conversation.messages += message }
        return message.id
    }

    private fun handle(exchange: HttpExchange) {
        check(exchange.requestHeaders.getFirst("Authorization") == "Bearer $STUB_KEY")
        val path = exchange.requestURI.path
        if (exchange.requestMethod == "GET") {
            lookupStatus?.let { return exchange.respond(it, """{"error":"Unavailable"}""") }
            val inquiry = path.removePrefix("/api/conversations/v2/topics/booking/")
            val conversation = conversations[inquiry] ?: return exchange.respond(404, """{"error":"Not found"}""")
            val limit =
                exchange.requestURI.query
                    ?.substringAfter("limit=")
                    ?.substringBefore('&')
                    ?.toInt() ?: 100
            val messages = synchronized(conversation) { conversation.messages.takeLast(limit) }
            return exchange.respond(200, conversation(inquiry, conversation, messages))
        }
        val key = checkNotNull(exchange.requestHeaders.getFirst("Idempotency-Key"))
        val text = exchange.requestBody.readAllBytes().decodeToString()
        sendAttempts.incrementAndGet()
        stored[key]?.let { previous ->
            Thread.sleep(replayDelayMillis)
            return if (previous.request == text) {
                exchange.respond(if (previous.status == 201) 200 else previous.status, previous.response)
            } else {
                exchange.respond(409, """{"error":"Idempotency conflict"}""")
            }
        }
        val body = Json.parseToJsonElement(text).jsonObject
        requests += body
        onSend?.invoke()
        val behavior = script.pollFirst() ?: Behavior.NORMAL
        if (behavior == Behavior.REJECT) return exchange.respond(400, """{"error":"The requested email identity is not allowed."}""")
        val opening = path == "/api/conversations/v2"
        val conversation =
            if (opening) {
                val inquiry =
                    body
                        .getValue("topic")
                        .jsonObject
                        .getValue("externalId")
                        .jsonPrimitive.content
                if (conversations.containsKey(inquiry)) return exchange.respond(409, """{"error":"Topic conflict"}""")
                Conversation(UUID.randomUUID()).also { conversations[inquiry] = it }
            } else {
                val id = UUID.fromString(path.removePrefix("/api/conversations/v2/").substringBefore('/'))
                conversations.values.singleOrNull { it.id == id } ?: return exchange.respond(404, """{"error":"Not found"}""")
            }
        val state =
            when (behavior) {
                Behavior.FAILED -> "failed"
                Behavior.INDETERMINATE -> "indeterminate"
                else -> "accepted"
            }
        val content = if (opening) body.getValue("message").jsonObject else body
        val message = Message(UUID.randomUUID(), "outbound", state, content.getValue("text").jsonPrimitive.content, Instant.now())
        synchronized(conversation) { conversation.messages += message }
        if (state == "accepted") created.incrementAndGet()
        val result = """{"conversationId":"${conversation.id}","message":${message(message)}}"""
        val status = if (state == "accepted") 201 else 502
        stored[key] = Stored(text, status, result)
        when (behavior) {
            Behavior.ERROR_AFTER_PERSIST -> exchange.respond(500, """{"error":"Internal"}""")
            Behavior.TIMEOUT_AFTER_PERSIST -> {
                Thread.sleep(1500)
                exchange.respond(status, result)
            }
            else -> exchange.respond(status, result)
        }
    }

    private fun conversation(
        inquiry: String,
        conversation: Conversation,
        messages: List<Message>,
    ) = """{"id":"${conversation.id}","topic":{"type":"booking","externalId":"$inquiry"},"subject":"Your inquiry",
        "state":"awaiting_us","messages":[${messages.joinToString(",") { message(it) }}],
        "page":{"hasMoreBefore":false,"before":null}}"""

    private fun message(message: Message): String {
        val outbound = message.direction == "outbound"
        val from = if (outbound) "bookings@mail.fionas.test" else "customer@example.com"
        return """{"id":"${message.id}","direction":"${message.direction}","state":"${message.state}",
            "deliveryState":${if (outbound) "\"unknown\"" else "null"},"from":{"address":"$from","name":null},
            "to":"${if (outbound) "customer@example.com" else "replies@mail.fionas.test"}",
            "text":${Json.encodeToString(String.serializer(), message.text)},"html":"<p>untrusted</p>",
            "createdAt":"${message.createdAt}"}"""
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        try {
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        } catch (_: IOException) {
            // The client gave up (a scripted timeout); the provider has already persisted the outcome.
        }
    }

    override fun close() = server.stop(0)

    companion object {
        const val STUB_KEY = "stub-provider-key"
    }
}
