package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.outbox.OutboxEvent
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquirySubmissionKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

const val FIONA_EVENT_PRODUCER = "fionas-commerce"
const val INQUIRY_PERSISTED_TYPE = "fionas.inquiry.persisted"
const val INQUIRY_PERSISTED_SUBJECT = "fionas.events.inquiry.persisted.v2"

/** The application-owned wire contract, separate from both domain and HTTP models. */
@Serializable
data class InquiryPersistedV2(
    val schemaVersion: Int,
    val type: String,
    val id: String,
    val correlationId: String,
    val occurredAt: String,
    val source: String,
    val data: InquiryPersistedData,
)

@Serializable
data class InquiryPersistedData(
    val inquiryId: String,
    val customer: InquiryPersistedCustomer,
    val eventDate: String,
)

@Serializable
data class InquiryPersistedCustomer(
    val name: String,
    val email: String,
)

/** Serialize exactly once during creation; delivery uses only the persisted opaque text. */
fun inquiryPersistedEvent(
    id: UUID,
    inquiry: Inquiry,
    customer: Customer,
    submissionKey: InquirySubmissionKey,
): OutboxEvent {
    val payload =
        InquiryPersistedV2(
            schemaVersion = 2,
            type = INQUIRY_PERSISTED_TYPE,
            id = id.toString(),
            correlationId = submissionKey.value,
            occurredAt = inquiry.createdAt.toString(),
            source = FIONA_EVENT_PRODUCER,
            data =
                InquiryPersistedData(
                    inquiryId = inquiry.id.value.toString(),
                    customer = InquiryPersistedCustomer(customer.name.value, customer.email.value),
                    eventDate = inquiry.eventDate.value.toString(),
                ),
        )
    return OutboxEvent(
        id = id,
        producerId = FIONA_EVENT_PRODUCER,
        aggregateType = "inquiry",
        aggregateId = inquiry.id.value.toString(),
        eventType = INQUIRY_PERSISTED_TYPE,
        schemaVersion = 2,
        destination = INQUIRY_PERSISTED_SUBJECT,
        payload = Json.encodeToString(InquiryPersistedV2.serializer(), payload),
        occurredAt = inquiry.createdAt,
    )
}
