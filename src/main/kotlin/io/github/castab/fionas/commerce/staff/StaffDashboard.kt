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
    val needsReply: StaffWorkQueue,
    val needsQuote: StaffWorkQueue,
    val needsResolution: StaffWorkQueue,
)

/** All items ordered by attentionSince ascending, then lexical inquiry UUID. Queues may overlap. */
data class StaffWorkQueue(
    val items: List<StaffDashboardItem>,
)

enum class StaffAttentionReason {
    CUSTOMER_COMMUNICATION_UNACKNOWLEDGED,
    NEEDS_QUOTE,
    QUOTE_STALE,
    EVENT_DATE_PASSED_UNSERVED,
    SERVED_WITH_BALANCE_DUE,
    READY_TO_CLOSE,
}

enum class DashboardTotalQualifier { EXACT, FROM }

data class StaffDashboardItem(
    val inquiryId: InquiryId,
    val customerId: CustomerId,
    val customerName: CustomerName,
    val eventDate: EventDate,
    val eventType: EventType,
    val stage: InquiryStage,
    val latestFinancialVersion: FinancialDocumentVersion,
    val totalQualifier: DashboardTotalQualifier,
    val balance: Money,
    val inquiryCreatedAt: Instant,
    val servedAt: Instant?,
    val attentionSince: Instant,
    val reasons: Set<StaffAttentionReason>,
)
