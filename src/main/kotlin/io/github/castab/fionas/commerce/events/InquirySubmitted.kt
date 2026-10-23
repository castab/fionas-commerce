package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.operation.OperationOrigin
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.financial.PricedLine
import io.github.castab.fionas.commerce.financial.parseExactDecimal
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.EventType
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.inquiry.InquirySubmissionKey
import io.github.castab.fionas.commerce.inquiry.RequestedService
import io.github.castab.fionas.commerce.inquiry.RequestedServiceItem
import io.github.castab.fionas.commerce.inquiry.ZipCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.Instant
import java.util.Currency

internal val submittedUuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val utcTimestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z")

/** Producer-specific projection, deliberately separate from HTTP and persisted DTOs. Unknown fields are ignored. */
internal fun submittedObject(bytes: ByteArray): JsonObject =
    try {
        val text =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        Json.parseToJsonElement(text) as? JsonObject ?: throw IntakeFailure("invalid_inquiry_submitted", FailureKind.PERMANENT)
    } catch (failure: IntakeFailure) {
        throw failure
    } catch (_: Exception) {
        throw IntakeFailure("invalid_event_json", FailureKind.PERMANENT)
    }

/** Translate business content using attribution supplied only by the trusted entry boundary. */
internal fun submittedCommand(
    bytes: ByteArray,
    origin: OperationOrigin,
): CreateInquiry.Command {
    val event = submittedObject(bytes)
    if (event["schemaVersion"].integerOrNull() != 1 || event["type"] != JsonPrimitive("fionas.inquiry.submitted")) {
        throw IntakeFailure("unsupported_event_type_or_version", FailureKind.PERMANENT)
    }
    try {
        val id = event.text("id").also { require(submittedUuid.matches(it)) }
        val timestamp = event.text("occurredAt").also { require(utcTimestamp.matches(it)) }
        Instant.parse(timestamp)
        EventDate.of(timestamp.take(10))
        require(event.text("source") == "fionas-web")
        val data = event.obj("data")
        data.text("priceRevision") // Required transport metadata; intentionally excluded from business intent.
        val service = data.obj("requestedService")
        val guests = service["guestCount"].integerOrNull()
        require(guests != null)
        val minimum =
            service["guestCountIsMinimum"]?.let {
                require(it is JsonPrimitive && !it.isString)
                requireNotNull(it.booleanOrNull)
            } ?: false
        val items =
            service.array("items", optional = true).map {
                require(it is JsonObject)
                RequestedServiceItem.of(it.text("label"), it.optionalText("group"), it.optionalText("key"))
            }
        val lines =
            data.array("lines").map {
                require(it is JsonObject)
                val currency = Currency.getInstance(it.text("currency"))
                PricedLine.of(
                    it.text("description"),
                    it.optionalText("subDescription"),
                    it.optionalText("quantity")?.let(::decimal),
                    Money(decimal(it.text("unitPrice")), currency),
                    Money(decimal(it.text("taxAmount")), currency),
                )
            }
        return CreateInquiry.Command(
            CustomerName.of(data.text("name")),
            Email.of(data.text("email")),
            InquiryMessage.ofOptional(data.optionalText("message")),
            RequestedService(guests, minimum, null, items, service.optionalText("pricingReference")?.trim()?.takeIf(String::isNotEmpty)),
            lines,
            ZipCode.of(data.text("zipCode")),
            EventDate.of(data.text("eventDate")),
            EventType.valueOf(data.text("eventType")),
            InquirySubmissionKey(id),
            origin,
        )
    } catch (_: DateTimeException) {
        throw IntakeFailure("invalid_inquiry_submitted", FailureKind.PERMANENT)
    } catch (_: IllegalArgumentException) {
        // Only input conversion is inside this catch; operation/invariant failures are never customer errors.
        throw IntakeFailure("invalid_inquiry_submitted", FailureKind.PERMANENT)
    }
}

private fun JsonObject.obj(key: String): JsonObject = requireNotNull(this[key] as? JsonObject)

private fun JsonObject.text(key: String): String = requireNotNull(optionalText(key)).also { require(it.isNotEmpty()) }

private fun JsonObject.optionalText(key: String): String? {
    if (!containsKey(key)) return null
    val value = this[key]
    require(value is JsonPrimitive && value.isString)
    return value.content
}

private fun JsonObject.array(
    key: String,
    optional: Boolean = false,
): JsonArray = if (optional && !containsKey(key)) JsonArray(emptyList()) else requireNotNull(this[key] as? JsonArray)

private fun decimal(text: String): BigDecimal = parseExactDecimal(text, "An event amount or quantity")

private fun JsonElement?.integerOrNull(): Int? {
    if (this !is JsonPrimitive || isString) return null
    return try {
        content.toBigDecimal().intValueExact()
    } catch (_: ArithmeticException) {
        null
    } catch (_: NumberFormatException) {
        null
    }
}
