package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.outbox.OutboxEventDraft
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquiryDecline
import io.github.castab.fionas.commerce.inquiry.RequestedService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

const val INQUIRY_DECLINED_TYPE = "fionas.inquiry.declined"
const val INQUIRY_DECLINED_SUBJECT = "fionas.events.inquiry.declined.v1"

/**
 * Application-owned v1 wire snapshot of a committed decline, independent of HTTP, persistence and
 * the InquiryPersisted v3 DTOs: interpretable without any earlier event of the inquiry.
 */
@Serializable
data class InquiryDeclinedV1(
    val schemaVersion: Int,
    val type: String,
    val id: String,
    val occurredAt: String,
    val source: String,
    val aggregate: InquiryDeclinedV1Aggregate,
    val data: InquiryDeclinedV1Data,
)

@Serializable
data class InquiryDeclinedV1Aggregate(
    val type: String,
    val id: String,
    val sequence: Long,
)

@Serializable
data class InquiryDeclinedV1Data(
    val inquiry: InquiryDeclinedV1Inquiry,
    val decline: InquiryDeclinedV1Decline,
    val customer: InquiryDeclinedV1Customer,
    val requestedService: InquiryDeclinedV1RequestedService,
    val estimate: InquiryDeclinedV1Estimate,
)

@Serializable
data class InquiryDeclinedV1Inquiry(
    val id: String,
    val createdAt: String,
    val stage: String,
    val eventDate: String,
    val eventType: String,
    val zipCode: String,
    val message: String?,
)

@Serializable
data class InquiryDeclinedV1Decline(
    val declinedAt: String,
    val declinedBy: InquiryDeclinedV1Principal,
    val reason: String,
)

@Serializable
data class InquiryDeclinedV1Principal(
    val kind: String,
    val id: String,
)

@Serializable
data class InquiryDeclinedV1Customer(
    val id: String,
    val name: String,
    val email: String,
)

@Serializable
data class InquiryDeclinedV1RequestedService(
    val guestCount: Int,
    val guestCountIsMinimum: Boolean,
    val durationMinutes: Int?,
    val items: List<InquiryDeclinedV1RequestedItem>,
    val pricingReference: String?,
)

@Serializable
data class InquiryDeclinedV1RequestedItem(
    val label: String,
    val group: String?,
    val key: String?,
)

@Serializable
data class InquiryDeclinedV1Estimate(
    val id: String,
    val version: Int,
    val stage: String,
    val currency: String,
    val lineItems: List<InquiryDeclinedV1Line>,
    val total: String,
)

@Serializable
data class InquiryDeclinedV1Line(
    val id: String,
    val description: String,
    val subDescription: String?,
    val quantity: String?,
    val unitPrice: String,
    val taxAmount: String,
)

/** The same inquiry aggregate as InquiryPersisted; the runtime assigns the next sequence. */
fun inquiryDeclinedDraft(
    id: UUID,
    decline: InquiryDecline,
) = OutboxEventDraft(
    id = id,
    producerId = FIONA_EVENT_PRODUCER,
    aggregateType = "inquiry",
    aggregateId = decline.inquiryId.value.toString(),
    eventType = INQUIRY_DECLINED_TYPE,
    schemaVersion = 1,
    destination = INQUIRY_DECLINED_SUBJECT,
    occurredAt = decline.declinedAt,
)

/** Called synchronously by runtime append with its authoritative sequence and the decline transaction's facts. */
fun inquiryDeclinedPayload(
    draft: OutboxEventDraft,
    aggregateSequence: Long,
    inquiry: Inquiry,
    customer: Customer,
    requestedService: RequestedService,
    estimate: FinancialDocument,
    decline: InquiryDecline,
): JsonObject {
    check(estimate is FinancialDocument.Estimate && estimate.reference == decline.estimate) {
        "A decline event describes exactly the declined canonical Estimate"
    }
    check(inquiry.id == decline.inquiryId && inquiry.customerId == customer.id) { "A decline event describes one inquiry" }
    val payload =
        InquiryDeclinedV1(
            schemaVersion = draft.schemaVersion,
            type = draft.eventType,
            id = draft.id.toString(),
            occurredAt = draft.occurredAt.toString(),
            source = draft.producerId,
            aggregate = InquiryDeclinedV1Aggregate(draft.aggregateType, draft.aggregateId, aggregateSequence),
            data =
                InquiryDeclinedV1Data(
                    inquiry =
                        InquiryDeclinedV1Inquiry(
                            inquiry.id.value.toString(),
                            inquiry.createdAt.toString(),
                            "DECLINED",
                            inquiry.eventDate.value.toString(),
                            inquiry.eventType.name,
                            inquiry.zipCode.value,
                            inquiry.message?.value,
                        ),
                    decline =
                        InquiryDeclinedV1Decline(
                            decline.declinedAt.toString(),
                            InquiryDeclinedV1Principal("USER", decline.declinedBy.value.toString()),
                            decline.reason.value,
                        ),
                    customer = InquiryDeclinedV1Customer(customer.id.value.toString(), customer.name.value, customer.email.value),
                    requestedService =
                        InquiryDeclinedV1RequestedService(
                            requestedService.guestCount,
                            requestedService.guestCountIsMinimum,
                            requestedService.durationMinutes,
                            requestedService.items.map { InquiryDeclinedV1RequestedItem(it.label, it.group, it.key) },
                            requestedService.pricingReference,
                        ),
                    estimate =
                        InquiryDeclinedV1Estimate(
                            estimate.id.toString(),
                            estimate.version.number,
                            "ESTIMATE",
                            estimate.currency.currencyCode,
                            estimate.lineItems.map {
                                InquiryDeclinedV1Line(
                                    it.id.toString(),
                                    it.description,
                                    it.subDescription,
                                    it.quantity?.toPlainString(),
                                    it.price.amount.toPlainString(),
                                    it.taxAmount.amount.toPlainString(),
                                )
                            },
                            estimate.total.amount.toPlainString(),
                        ),
                ),
        )
    return Json.encodeToJsonElement(InquiryDeclinedV1.serializer(), payload).jsonObject
}
