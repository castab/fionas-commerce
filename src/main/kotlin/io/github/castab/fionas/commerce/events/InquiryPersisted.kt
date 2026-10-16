package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.outbox.OutboxEventDraft
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquirySubmissionKey
import io.github.castab.fionas.commerce.inquiry.RequestedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

const val FIONA_EVENT_PRODUCER = "fionas-commerce"
const val INQUIRY_PERSISTED_TYPE = "fionas.inquiry.persisted"
const val INQUIRY_PERSISTED_SUBJECT = "fionas.events.inquiry.persisted.v3"

/** Application-owned v3 wire snapshots, independent of HTTP and persistence DTOs. */
@Serializable
data class InquiryPersistedV3(
    val schemaVersion: Int,
    val type: String,
    val id: String,
    val correlationId: String,
    val occurredAt: String,
    val source: String,
    val aggregate: InquiryPersistedV3Aggregate,
    val data: InquiryPersistedV3Data,
)

@Serializable
data class InquiryPersistedV3Aggregate(
    val type: String,
    val id: String,
    val sequence: Long,
)

@Serializable
data class InquiryPersistedV3Data(
    val inquiry: InquiryPersistedV3Inquiry,
    val customer: InquiryPersistedV3Customer,
    val requestedService: InquiryPersistedV3RequestedService,
    val initialEstimate: InquiryPersistedV3Estimate,
)

@Serializable
data class InquiryPersistedV3Inquiry(
    val id: String,
    val eventDate: String,
    val eventType: String,
    val zipCode: String,
    val message: String?,
)

@Serializable
data class InquiryPersistedV3Customer(
    val id: String,
    val name: String,
    val email: String,
)

@Serializable
data class InquiryPersistedV3RequestedService(
    val guestCount: Int,
    val guestCountIsMinimum: Boolean,
    val durationMinutes: Int?,
    val items: List<InquiryPersistedV3RequestedItem>,
    val pricingReference: String?,
)

@Serializable
data class InquiryPersistedV3RequestedItem(
    val label: String,
    val group: String?,
    val key: String?,
)

@Serializable
data class InquiryPersistedV3Estimate(
    val id: String,
    val version: Int,
    val stage: String,
    val currency: String,
    val lineItems: List<InquiryPersistedV3Line>,
    val total: String,
)

@Serializable
data class InquiryPersistedV3Line(
    val id: String,
    val description: String,
    val subDescription: String?,
    val quantity: String?,
    val unitPrice: String,
    val taxAmount: String,
)

fun inquiryPersistedDraft(
    id: UUID,
    inquiry: Inquiry,
) = OutboxEventDraft(
    id = id,
    producerId = FIONA_EVENT_PRODUCER,
    aggregateType = "inquiry",
    aggregateId = inquiry.id.value.toString(),
    eventType = INQUIRY_PERSISTED_TYPE,
    schemaVersion = 3,
    destination = INQUIRY_PERSISTED_SUBJECT,
    occurredAt = inquiry.createdAt,
)

/** Called synchronously by runtime append with its authoritative sequence and transaction-owned facts. */
fun inquiryPersistedPayload(
    draft: OutboxEventDraft,
    aggregateSequence: Long,
    inquiry: Inquiry,
    customer: Customer,
    requestedService: RequestedService,
    initialEstimate: FinancialDocument,
    submissionKey: InquirySubmissionKey,
): JsonObject {
    check(initialEstimate is FinancialDocument.Estimate) { "Inquiry creation must materialize an Estimate" }
    val payload =
        InquiryPersistedV3(
            schemaVersion = draft.schemaVersion,
            type = draft.eventType,
            id = draft.id.toString(),
            correlationId = submissionKey.value,
            occurredAt = draft.occurredAt.toString(),
            source = draft.producerId,
            aggregate = InquiryPersistedV3Aggregate(draft.aggregateType, draft.aggregateId, aggregateSequence),
            data =
                InquiryPersistedV3Data(
                    inquiry =
                        InquiryPersistedV3Inquiry(
                            inquiry.id.value.toString(),
                            inquiry.eventDate.value.toString(),
                            inquiry.eventType.name,
                            inquiry.zipCode.value,
                            inquiry.message?.value,
                        ),
                    customer = InquiryPersistedV3Customer(customer.id.value.toString(), customer.name.value, customer.email.value),
                    requestedService =
                        InquiryPersistedV3RequestedService(
                            requestedService.guestCount,
                            requestedService.guestCountIsMinimum,
                            requestedService.durationMinutes,
                            requestedService.items.map { InquiryPersistedV3RequestedItem(it.label, it.group, it.key) },
                            requestedService.pricingReference,
                        ),
                    initialEstimate =
                        InquiryPersistedV3Estimate(
                            initialEstimate.id.toString(),
                            initialEstimate.version.number,
                            "ESTIMATE",
                            initialEstimate.currency.currencyCode,
                            initialEstimate.lineItems.map {
                                InquiryPersistedV3Line(
                                    it.id.toString(),
                                    it.description,
                                    it.subDescription,
                                    it.quantity?.toPlainString(),
                                    it.price.amount.toPlainString(),
                                    it.taxAmount.amount.toPlainString(),
                                )
                            },
                            initialEstimate.total.amount.toPlainString(),
                        ),
                ),
        )
    return Json.encodeToJsonElement(InquiryPersistedV3.serializer(), payload).jsonObject
}
