package io.github.castab.fionas.commerce.conversation

import io.github.castab.fionas.commerce.inquiry.InquiryId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A sanitized read model: no HTML, routing tokens or provider diagnostic text crosses this boundary. */
data class ConversationMessage(
    val id: UUID,
    val direction: String,
    val state: String,
    val deliveryState: String?,
    val senderAddress: String,
    val senderName: String?,
    val recipient: String,
    val text: String?,
    val createdAt: Instant,
)

data class EmailConversation(
    val id: UUID,
    val subject: String,
    val state: String,
    val messages: List<ConversationMessage>,
)

enum class ConversationAvailability { READY, EMPTY, UNAVAILABLE, FORBIDDEN, MISSING }

data class ConversationRead(
    val availability: ConversationAvailability,
    val conversation: EmailConversation? = null,
)

fun interface ConversationReader {
    fun read(inquiry: InquiryId): ConversationRead
}

/** Secrets deliberately have no generated toString; disabled reads do not require credentials. */
class ConversationReadConfiguration private constructor(
    internal val base: URI,
    internal val credential: String,
    internal val timeout: Duration,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ConversationReadConfiguration? {
            when (env["FIONAS_CONVERSATION_READ_ENABLED"]) {
                null, "false" -> return null
                "true" -> Unit
                else -> error("FIONAS_CONVERSATION_READ_ENABLED must be true or false")
            }
            val (base, credential) = providerConnection(env)
            return ConversationReadConfiguration(
                base,
                credential,
                providerTimeout(
                    env,
                    "FIONAS_CONVERSATION_READ_TIMEOUT_MS",
                    5000,
                    100L..10000L,
                ),
            )
        }
    }
}

/** The provider origin and V2 bearer credential, shared by reads and sends; never logged or echoed in errors. */
internal fun providerConnection(env: Map<String, String>): Pair<URI, String> {
    val base =
        try {
            URI(env.getValue("FIONAS_CONVERSATION_BASE_URL").trimEnd('/'))
        } catch (_: Exception) {
            error("Invalid FIONAS_CONVERSATION_BASE_URL")
        }
    require(
        base.host != null &&
            base.userInfo == null &&
            base.query == null &&
            base.fragment == null &&
            base.path.isNullOrEmpty() &&
            (base.scheme == "https" || (base.scheme == "http" && permitsProviderHttp(base.host))),
    ) { "FIONAS_CONVERSATION_BASE_URL requires HTTPS (HTTP only for loopback or Railway private DNS) and an origin without credentials" }
    val credential = env["FIONAS_CONVERSATION_API_KEY"]
    require(!credential.isNullOrBlank() && credential.none { it.isISOControl() }) { "FIONAS_CONVERSATION_API_KEY is required" }
    return base to credential
}

private val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", RegexOption.IGNORE_CASE)

private fun permitsProviderHttp(host: String): Boolean {
    if (host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) return true
    // Adjacent Railway services use HTTP over private networking; only their complete private DNS names qualify.
    val suffix = ".railway.internal"
    if (host.length > 253 || !host.endsWith(suffix, ignoreCase = true)) return false
    return host.dropLast(suffix.length).split('.').all { dnsLabel.matches(it) }
}

internal fun providerTimeout(
    env: Map<String, String>,
    name: String,
    default: Long,
    bounds: LongRange,
): Duration {
    val millis = env[name]?.let { requireNotNull(it.toLongOrNull()) { "Invalid $name" } } ?: default
    require(millis in bounds) { "$name must be ${bounds.first}..${bounds.last}" }
    return Duration.ofMillis(millis)
}

/** API v2 topic lookup. A bounded total read follows chronological pages; partial history is never called complete. */
class ResendConversationReader(
    private val config: ConversationReadConfiguration,
) : ConversationReader {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(config.timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun read(inquiry: InquiryId): ConversationRead {
        val deadline = System.nanoTime() + config.timeout.toNanos()
        return try {
            val messages = linkedMapOf<UUID, ConversationMessage>()
            // The first page is the newest; each `before` page is older. Pages are prepended to stay chronological.
            val pages = mutableListOf<List<ConversationMessage>>()
            var before: UUID? = null
            var identity: UUID? = null
            var subject: String? = null
            var state: String? = null
            val cursors = mutableSetOf<UUID>()
            do {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && cursors.size < 100)
                val path = "/api/conversations/v2/topics/booking/${inquiry.value}?limit=100" + (before?.let { "&before=$it" } ?: "")
                val request =
                    HttpRequest
                        .newBuilder(config.base.resolve(path))
                        .timeout(Duration.ofNanos(remaining))
                        .header("Authorization", "Bearer ${config.credential}")
                        .header("Accept", "application/json")
                        .GET()
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                when (response.statusCode()) {
                    401, 403 -> return ConversationRead(ConversationAvailability.FORBIDDEN)
                    404 -> return ConversationRead(
                        if (identity ==
                            null
                        ) {
                            ConversationAvailability.EMPTY
                        } else {
                            ConversationAvailability.UNAVAILABLE
                        },
                    )
                    200 -> Unit
                    else -> return ConversationRead(ConversationAvailability.UNAVAILABLE)
                }
                check(response.body().length <= 4_000_000)
                val data = Json.parseToJsonElement(response.body()).jsonObject
                val id = UUID.fromString(data.text("id"))
                check(identity == null || identity == id)
                identity = id
                val topic = data.getValue("topic").jsonObject
                check(topic.text("type") == "booking" && UUID.fromString(topic.text("externalId")) == inquiry.value)
                subject = data.text("subject")
                state = data.text("state").also { check(it in setOf("awaiting_us", "awaiting_participant", "concluded", "terminated")) }
                val page = mutableListOf<ConversationMessage>()
                data.getValue("messages").jsonArray.forEach { element ->
                    val value = element.jsonObject
                    val from = value.getValue("from").jsonObject
                    val message =
                        ConversationMessage(
                            UUID.fromString(value.text("id")),
                            value.text("direction").also { check(it in setOf("inbound", "outbound")) },
                            value.text("state").also { check(it in setOf("received", "pending", "accepted", "failed", "indeterminate")) },
                            value.optionalText("deliveryState").also {
                                check(
                                    it == null ||
                                        it in
                                        setOf(
                                            "unknown",
                                            "delivered",
                                            "delivery_delayed",
                                            "bounced",
                                            "complained",
                                            "suppressed",
                                            "failed",
                                        ),
                                )
                            },
                            from.text("address"),
                            from.optionalText("name"),
                            value.text("to"),
                            value.optionalText("text"),
                            Instant.parse(value.text("createdAt")),
                        )
                    val previous = messages.putIfAbsent(message.id, message)
                    check(previous == null || previous == message)
                    if (previous == null) page += message
                }
                pages.add(0, page)
                val cursor = data.getValue("page").jsonObject
                before = if (cursor.getValue("hasMoreBefore").jsonPrimitive.boolean) UUID.fromString(cursor.text("before")) else null
                before?.let { check(cursors.add(it)) }
            } while (before != null)
            ConversationRead(
                ConversationAvailability.READY,
                EmailConversation(checkNotNull(identity), checkNotNull(subject), checkNotNull(state), pages.flatten()),
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ConversationRead(ConversationAvailability.UNAVAILABLE)
        } catch (_: Exception) {
            ConversationRead(ConversationAvailability.UNAVAILABLE)
        }
    }
}

internal fun JsonObject.text(key: String): String =
    getValue(key).jsonPrimitive.let {
        check(it.isString)
        it.content
    }

internal fun JsonObject.optionalText(key: String): String? = if (getValue(key) == JsonNull) null else text(key)
