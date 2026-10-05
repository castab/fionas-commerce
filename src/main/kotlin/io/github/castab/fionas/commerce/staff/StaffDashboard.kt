package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.runtime.financial.FinancialDocumentVersion
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.EventType
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryOperationalCounts
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import java.time.Instant

/** One evaluation of the complete operational population; asOf is not a database commit watermark. */
data class StaffDashboard(
    val asOf: Instant,
    val summary: InquiryOperationalCounts,
    val workQueue: StaffDashboardWorkQueue,
)

data class StaffDashboardWorkQueue(
    val needsQuote: StaffWorkQueue.Available,
    val awaitingQuoteReply: StaffWorkQueue.Available,
    val needsClosing: StaffWorkQueue.Available,
    val needsReply: StaffWorkQueue.Unavailable = StaffWorkQueue.Unavailable(StaffQueueUnavailability.COMMUNICATIONS_NOT_IMPLEMENTED),
    val needsResolution: StaffWorkQueue.Unavailable = StaffWorkQueue.Unavailable(StaffQueueUnavailability.RESOLUTION_POLICY_NOT_DEFINED),
)

enum class StaffQueueUnavailability { COMMUNICATIONS_NOT_IMPLEMENTED, RESOLUTION_POLICY_NOT_DEFINED }

sealed interface StaffWorkQueue {
    /** All items, oldest inquiry first, then lexical UUID order. This is not customer wait duration. */
    data class Available(
        val items: List<StaffDashboardItem>,
    ) : StaffWorkQueue

    data class Unavailable(
        val reason: StaffQueueUnavailability,
    ) : StaffWorkQueue
}

data class StaffDashboardItem(
    val inquiryId: InquiryId,
    val customerId: CustomerId,
    val customerName: CustomerName,
    val eventDate: EventDate,
    val eventType: EventType,
    val stage: InquiryStage,
    val latestFinancialVersion: FinancialDocumentVersion,
    val balance: Money,
    val inquiryCreatedAt: Instant,
    val servedAt: Instant?,
)
