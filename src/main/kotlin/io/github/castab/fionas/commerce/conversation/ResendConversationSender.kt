package io.github.castab.fionas.commerce.conversation

import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.InquiryId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Outbound conversation identity and policy. Disabled unless explicitly enabled; enabled sending requires the
 * shared provider connection, an allow-listed From and an untagged Reply-To base, and an explicit recipient policy.
 */
class ConversationSendConfiguration private constructor(
    internal val base: URI,
    internal val credential: String,
    internal val timeout: Duration,
    val fromAddress: String,
    val fromName: String,
    val replyToAddress: String,
    private val recipients: Set<String>?,
) {
    /** Pre-production safety: only exact normalized addresses, unless the operator configured `*`. */
    fun permits(recipient: Email): Boolean = recipients == null || recipient.value in recipients

    companion object {
        private val address = Regex("^[^\\s@<>,;\"]+@[^\\s@<>,;\"]+\\.[^\\s@<>,;\"]+$")

        fun fromEnvironment(env: Map<String, String> = System.getenv()): ConversationSendConfiguration? {
            when (env["FIONAS_CONVERSATION_SEND_ENABLED"]) {
                null, "false" -> return null
                "true" -> Unit
                else -> error("FIONAS_CONVERSATION_SEND_ENABLED must be true or false")
            }
            val (base, credential) = providerConnection(env)

            fun mailbox(name: String): String =
                requireNotNull(env[name]?.trim()?.lowercase()?.takeIf { address.matches(it) }) { "$name must be one email address" }
            val from = mailbox("FIONAS_CONVERSATION_FROM_ADDRESS")
            val replyTo = mailbox("FIONAS_CONVERSATION_REPLY_TO_ADDRESS")
            // The provider appends the routing tag; a tagged base would be rejected and must never be manufactured here.
            require('+' !in replyTo.substringBefore('@')) { "FIONAS_CONVERSATION_REPLY_TO_ADDRESS must be an untagged base mailbox" }
            val fromName = (env["FIONAS_CONVERSATION_FROM_NAME"] ?: "Fiona's Ice Cream").trim()
            require(fromName.length in 1..256 && fromName.none { it.isISOControl() || it == '<' || it == '>' }) {
                "FIONAS_CONVERSATION_FROM_NAME must be header-safe text"
            }
            val allowList =
                requireNotNull(env["FIONAS_CONVERSATION_RECIPIENT_ALLOWLIST"]?.trim()?.takeIf { it.isNotEmpty() }) {
                    "FIONAS_CONVERSATION_RECIPIENT_ALLOWLIST is required when sending is enabled (addresses, or * for any)"
                }
            val recipients =
                if (allowList == "*") {
                    null
                } else {
                    allowList.split(',').map { it.trim().lowercase() }.toSet().also { entries ->
                        require(entries.all { address.matches(it) }) { "FIONAS_CONVERSATION_RECIPIENT_ALLOWLIST holds an invalid address" }
                    }
                }
            return ConversationSendConfiguration(
                base,
                credential,
                providerTimeout(env, "FIONAS_CONVERSATION_SEND_TIMEOUT_MS", 10000, 1000L..30000L),
                from,
                fromName,
                replyTo,
                recipients,
            )
        }
    }
}

/** The topic conversation's identity and newest message, read immediately before a send decision. */
sealed interface TopicLookup {
    data class Found(
        val conversationId: UUID,
        val latestMessageId: UUID?,
    ) : TopicLookup

    /** The provider answered 404 for the topic: no conversation exists for this inquiry. */
    data object Missing : TopicLookup

    data object Unavailable : TopicLookup

    data object Forbidden : TopicLookup
}

/** One exact provider request; its digest is persisted so a replay can never send a different request under its key. */
class ProviderSendRequest(
    val path: String,
    val idempotencyKey: String,
    val body: JsonObject,
) {
    val digest: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$path\n$idempotencyKey\n$body".toByteArray())
            .joinToString("") { "%02x".format(it) }
}

sealed interface ProviderSendOutcome {
    /** Durable provider intent: `pending` or `accepted`. Never delivery. */
    data class Accepted(
        val conversationId: UUID,
        val messageId: UUID,
        val state: String,
    ) : ProviderSendOutcome

    /** Definitively not sent: rejected before persistence, or a terminal known `failed` send. */
    data object Rejected : ProviderSendOutcome

    /** Neither confirmed nor refuted. Reconcile only by replaying the exact request and key. */
    data object Unknown : ProviderSendOutcome
}

interface ConversationSender {
    val configuration: ConversationSendConfiguration

    fun lookup(inquiry: InquiryId): TopicLookup

    fun send(request: ProviderSendRequest): ProviderSendOutcome
}

/** Typed V2 adapter for the two send routes and the topic lookup; no generic SDK. */
class ResendConversationSender(
    override val configuration: ConversationSendConfiguration,
) : ConversationSender {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(configuration.timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    override fun lookup(inquiry: InquiryId): TopicLookup =
        try {
            val response =
                client.send(
                    request("/api/conversations/v2/topics/booking/${inquiry.value}?limit=1").GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            when (response.statusCode()) {
                200 -> {
                    check(response.body().length <= 4_000_000)
                    val data = Json.parseToJsonElement(response.body()).jsonObject
                    val topic = data.getValue("topic").jsonObject
                    check(topic.text("type") == "booking" && UUID.fromString(topic.text("externalId")) == inquiry.value)
                    TopicLookup.Found(
                        UUID.fromString(data.text("id")),
                        data
                            .getValue("messages")
                            .jsonArray
                            .lastOrNull()
                            ?.let { UUID.fromString(it.jsonObject.text("id")) },
                    )
                }
                404 -> TopicLookup.Missing
                401, 403 -> TopicLookup.Forbidden
                else -> TopicLookup.Unavailable
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            TopicLookup.Unavailable
        } catch (_: Exception) {
            TopicLookup.Unavailable
        }

    /** Ambiguous results get exactly one replay with the identical request and key, never a new key. */
    override fun send(request: ProviderSendRequest): ProviderSendOutcome =
        when (val first = attempt(request)) {
            null -> attempt(request) ?: ProviderSendOutcome.Unknown
            else -> first
        }

    /** Null means ambiguous. */
    private fun attempt(send: ProviderSendRequest): ProviderSendOutcome? =
        try {
            val response =
                client.send(
                    request(send.path)
                        .header("Idempotency-Key", send.idempotencyKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(send.body.toString()))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            classifySend(response.statusCode(), response.body())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ProviderSendOutcome.Unknown
        } catch (_: Exception) {
            null
        }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(configuration.base.resolve(path))
            .timeout(configuration.timeout)
            .header("Authorization", "Bearer ${configuration.credential}")
            .header("Accept", "application/json")
}

/** Provider contract 0.7.2-rc.2: only a well-formed `pending`/`accepted` SendResult is success; null is ambiguous. */
internal fun classifySend(
    status: Int,
    body: String,
): ProviderSendOutcome? {
    val result =
        try {
            Json.parseToJsonElement(body).jsonObject.let { data ->
                val message = data.getValue("message").jsonObject
                Triple(UUID.fromString(data.text("conversationId")), UUID.fromString(message.text("id")), message.text("state"))
            }
        } catch (_: Exception) {
            null
        }
    return when {
        status in setOf(200, 201, 202) && result != null && result.third in setOf("pending", "accepted") ->
            ProviderSendOutcome.Accepted(result.first, result.second, result.third)
        status == 502 && result?.third == "failed" -> ProviderSendOutcome.Rejected
        status == 502 && result?.third == "indeterminate" -> ProviderSendOutcome.Unknown
        // Validation, identity policy, credentials, missing conversation/parent and transport limits fail before intent.
        status in setOf(400, 401, 404, 413, 415) -> ProviderSendOutcome.Rejected
        // 409 (idempotency/topic conflict), 5xx and malformed success are ambiguous.
        else -> null
    }
}

/** The exact provider request for one staff reply, rebuilt identically on every replay. */
internal fun staffReplyRequest(
    configuration: ConversationSendConfiguration,
    key: StaffReplyKey,
    inquiry: InquiryId,
    conversationId: UUID?,
    participant: Email,
    participantName: String,
    text: String,
): ProviderSendRequest {
    val providerKey = "fionas-commerce:inquiry:${inquiry.value}:staff-reply:${key.value}:v1"

    fun JsonObjectBuilder.identities() {
        putJsonObject("from") {
            put("address", configuration.fromAddress)
            put("name", configuration.fromName)
        }
        putJsonObject("replyTo") {
            put("address", configuration.replyToAddress)
            put("name", configuration.fromName)
        }
    }
    return if (conversationId == null) {
        ProviderSendRequest(
            "/api/conversations/v2",
            providerKey,
            buildJsonObject {
                putJsonObject("topic") {
                    put("type", "booking")
                    put("externalId", inquiry.value.toString())
                    put("title", "Inquiry ${inquiry.value}")
                }
                putJsonObject("participant") {
                    put("email", participant.value)
                    put("name", participantName)
                }
                put("subject", STAFF_CONVERSATION_SUBJECT)
                putJsonObject("message") {
                    put("text", text)
                    identities()
                }
            },
        )
    } else {
        ProviderSendRequest(
            "/api/conversations/v2/$conversationId/messages",
            providerKey,
            buildJsonObject {
                put("text", text)
                identities()
            },
        )
    }
}

const val STAFF_CONVERSATION_SUBJECT = "Your Fiona's Ice Cream inquiry"
