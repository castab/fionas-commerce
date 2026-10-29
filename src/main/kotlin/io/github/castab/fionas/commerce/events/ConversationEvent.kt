package io.github.castab.fionas.commerce.events

import io.github.castab.fionas.commerce.inquiry.InquiryId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Provider-owned v1 boundary, verified against resend-conversation-service 0.7.2-rc.2. */
internal data class ConversationEvent(
    val id: UUID,
    val conversationId: UUID,
    val sequence: Long,
    val type: String,
    val inquiryId: InquiryId?,
    val messageId: UUID?,
    val occurredAt: Instant,
    val fingerprint: String,
)

private val conversationUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val states = setOf("awaiting_us", "awaiting_participant", "concluded", "terminated")
private val eventPairs =
    mapOf(
        "conversation.created" to setOf("participant/inbound_email", "service/outbound_intent"),
        "conversation.message.received" to setOf("participant/inbound_email"),
        "conversation.message.outbound.intended" to setOf("service/outbox_enqueue", "service/synchronous_send"),
        "conversation.message.delivery.updated" to setOf("system/resend_delivery_webhook"),
        "conversation.state.changed" to
            setOf("participant/inbound_email", "service/outbound_intent", "operator/manual_override", "system/unreachable_delivery"),
        "conversation.assigned" to setOf("operator/topic_assignment"),
        "conversation.merged" to setOf("system/thread_reconciliation"),
        "conversation.deleted" to setOf("operator/manual_delete"),
    )

private fun fionaTopic(externalId: String): InquiryId? =
    externalId
        .takeIf { conversationUuid.matches(it) }
        ?.let(UUID::fromString)
        ?.takeIf { it.toString() == externalId }
        ?.let(::InquiryId)

private val baseFields = setOf("schemaVersion", "id", "type", "occurredAt", "conversationId", "sequence", "topic", "actor", "cause")
private val eventFields =
    mapOf(
        "conversation.created" to (emptySet<String>() to emptySet()),
        "conversation.message.received" to (setOf("messageId") to setOf("attachmentCount")),
        "conversation.message.outbound.intended" to (setOf("messageId") to emptySet()),
        "conversation.message.delivery.updated" to (setOf("messageId", "deliveryState") to emptySet()),
        "conversation.state.changed" to (setOf("state") to emptySet()),
        "conversation.assigned" to (emptySet<String>() to emptySet()),
        "conversation.merged" to (emptySet<String>() to setOf("mergedIntoConversationId")),
        "conversation.deleted" to (emptySet<String>() to emptySet()),
    )

internal fun conversationEvent(bytes: ByteArray): ConversationEvent =
    try {
        require(bytes.size <= 65536)
        val text =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        val data = Json.parseToJsonElement(text).jsonObject

        fun string(
            key: String,
            source: JsonObject = data,
        ): String =
            source.getValue(key).jsonPrimitive.let {
                require(it.isString)
                it.content
            }

        fun uuid(key: String): UUID =
            string(key).let {
                require(conversationUuid.matches(it))
                UUID.fromString(it)
            }
        require(data.getValue("schemaVersion").jsonPrimitive.let { !it.isString && it.int == 1 })
        val type = string("type")
        // Schema v1 is closed (additionalProperties false): an extra property is a contract mismatch, never ignored.
        val (required, optional) = eventFields.getValue(type)
        require(data.keys.containsAll(baseFields + required) && (baseFields + required + optional).containsAll(data.keys))
        require("${string("actor")}/${string("cause")}" in eventPairs.getValue(type))
        val sequence =
            data.getValue("sequence").jsonPrimitive.let {
                require(!it.isString)
                it.long.also { n -> require(n > 0) }
            }
        val topic = data.getValue("topic")
        val inquiry =
            if (topic == JsonNull) {
                require(type != "conversation.assigned")
                null
            } else {
                val obj = topic.jsonObject
                val topicType = string("type", obj)
                val externalId = string("externalId", obj)
                require(topicType.length in 1..64 && externalId.length in 1..255)
                // Topic ids are generic provider text: only a booking topic whose id is exactly an inquiry's canonical
                // UUID text (as Fiona names its topics) relates to an inquiry; any other valid topic is unrelated.
                if (topicType == "booking") fionaTopic(externalId) else null
            }
        val message = if (type.startsWith("conversation.message.")) uuid("messageId") else null
        if (type == "conversation.state.changed") {
            val state = data.getValue("state").jsonObject
            require(state.getValue("from") == JsonNull || string("from", state) in states)
            require(string("to", state) in states)
        }
        if (type ==
            "conversation.message.delivery.updated"
        ) {
            require(
                string("deliveryState") in setOf("delivered", "delivery_delayed", "bounced", "complained", "suppressed", "failed"),
            )
        }
        if (type == "conversation.merged" && "mergedIntoConversationId" in data) uuid("mergedIntoConversationId")
        if ("attachmentCount" in data) require(data.getValue("attachmentCount").jsonPrimitive.let { !it.isString && it.long > 0 })
        val at = Instant.parse(string("occurredAt"))

        fun canonical(value: JsonElement): JsonElement =
            when (value) {
                is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
                is JsonArray -> JsonArray(value.map { canonical(it) })
                else -> value
            }
        val fingerprint =
            MessageDigest.getInstance("SHA-256").digest(canonical(data).toString().toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        ConversationEvent(uuid("id"), uuid("conversationId"), sequence, type, inquiry, message, at, fingerprint)
    } catch (_: Exception) {
        // Halt rather than discard an unsupported contract; logs never retain raw input or parser causes.
        throw IntakeFailure("conversation_event_invalid_or_unsupported", FailureKind.SYSTEMIC)
    }
