package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.StaffDashboard
import io.github.castab.fionas.commerce.staff.StaffDashboardItem
import io.github.castab.fionas.commerce.staff.StaffWorkQueue
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with

@Serializable
data class StaffDashboardResponse(
    @ApiProperty(
        description = "Clock-derived projection evaluation time in UTC, truncated to microseconds; not a database commit watermark.",
        format = "date-time",
    )
    val asOf: String,
    val summary: StaffDashboardSummaryResponse,
    val workQueue: StaffDashboardWorkQueueResponse,
)

@Serializable
data class StaffDashboardSummaryResponse(
    @ApiProperty(description = "REQUESTED inquiries in the complete population.") val new: Int,
    @ApiProperty(description = "QUOTED inquiries; excludes durable bookings.") val quoted: Int,
    @ApiProperty(description = "BOOKED or SERVED inquiries; overlaps needsClosing and excludes CLOSED.") val booked: Int,
    @ApiProperty(
        description = "SERVED with canonical current Invoice balance exactly zero, independent of decimal scale.",
    ) val needsClosing: Int,
)

@Serializable
data class StaffDashboardWorkQueueResponse(
    val needsReply: StaffWorkQueueResponse,
    val needsQuote: StaffWorkQueueResponse,
    val needsResolution: StaffWorkQueueResponse,
)

@Serializable
enum class StaffAttentionReasonResponse {
    CUSTOMER_COMMUNICATION_UNACKNOWLEDGED,
    NEEDS_QUOTE,
    QUOTE_STALE,
    EVENT_DATE_PASSED_UNSERVED,
    SERVED_WITH_BALANCE_DUE,
    READY_TO_CLOSE,
}

@Serializable
enum class DashboardTotalQualifierResponse { EXACT, FROM }

@Serializable
data class StaffWorkQueueResponse(
    @ApiProperty(
        description =
            "All qualifying items, attentionSince ascending then lexical inquiry UUID ascending; " +
                "queues may overlap. No pagination.",
    )
    val items: List<StaffDashboardItemResponse>,
)

@Serializable
data class StaffDashboardItemResponse(
    @ApiProperty(format = "uuid") val inquiryId: String,
    @ApiProperty(format = "uuid") val customerId: String,
    val customerName: String,
    @ApiProperty(format = "date") val eventDate: String,
    val eventType: InquiryEventType,
    val stage: InquiryStageResponse,
    @ApiProperty(description = "Canonical INITIAL_ESTIMATE lineage only.", format = "uuid") val documentId: String,
    val version: Int,
    @ApiProperty(description = "Latest canonical financial stage: ESTIMATE, QUOTE, or INVOICE.") val financialStage: String,
    @ApiProperty(description = "Exact decimal current document total, in currency.") val total: String,
    @ApiProperty(
        description =
            "FROM means the current Estimate is based on a minimum guest count. " +
                "Quote and Invoice totals are EXACT. No currency formatting is implied.",
    )
    val totalQualifier: DashboardTotalQualifierResponse,
    @ApiProperty(description = "Exact decimal current reconciliation balance, in currency; may be negative.") val balance: String,
    @ApiProperty(description = "ISO 4217 currency of total and balance.") val currency: String,
    @ApiProperty(format = "date-time") val inquiryCreatedAt: String,
    @ApiProperty(
        description = "Creation time of the latest canonical financial version; not the start of a customer wait.",
        format = "date-time",
    )
    val latestDocumentVersionAt: String,
    @ApiProperty(description = "When this queue condition began; earliest onset when multiple reasons apply.", format = "date-time")
    val attentionSince: String,
    val reasons: List<StaffAttentionReasonResponse>,
    @ApiProperty(format = "date-time") val servedAt: String? = null,
)

internal fun StaffDashboard.toResponse() =
    StaffDashboardResponse(
        asOf.toString(),
        StaffDashboardSummaryResponse(summary.new, summary.quoted, summary.booked, summary.needsClosing),
        StaffDashboardWorkQueueResponse(
            workQueue.needsReply.toResponse(),
            workQueue.needsQuote.toResponse(),
            workQueue.needsResolution.toResponse(),
        ),
    )

private fun StaffWorkQueue.toResponse() = StaffWorkQueueResponse(items.map { it.toResponse() })

private fun StaffDashboardItem.toResponse(): StaffDashboardItemResponse {
    val document = latestFinancialVersion.document
    return StaffDashboardItemResponse(
        inquiryId.value.toString(),
        customerId.value.toString(),
        customerName.value,
        eventDate.value.toString(),
        InquiryEventType.valueOf(eventType.name),
        InquiryStageResponse.valueOf(stage.name),
        document.id.toString(),
        document.version.number,
        when (document) {
            is FinancialDocument.Estimate -> "ESTIMATE"
            is FinancialDocument.Quote -> "QUOTE"
            is FinancialDocument.Invoice -> "INVOICE"
        },
        document.total.decimal(),
        DashboardTotalQualifierResponse.valueOf(totalQualifier.name),
        balance.decimal(),
        document.currency.currencyCode,
        inquiryCreatedAt.toString(),
        latestFinancialVersion.createdAt.toString(),
        attentionSince.toString(),
        reasons.map { StaffAttentionReasonResponse.valueOf(it.name) },
        servedAt?.toString(),
    )
}

internal fun readStaffDashboardRoute(
    read: () -> StaffDashboard,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(StaffDashboardResponse.serializer())
    val empty = StaffWorkQueueResponse(emptyList())
    return "/staff/dashboard" meta {
        operationId = "readStaffDashboard"
        summary = "Read the staff dashboard"
        description = "One coherent unlocked REPEATABLE READ snapshot of all inquiries, canonical financial facts, customers and events. " +
            "Requires BOTH `${FionaPermissions.InquiriesRead.value}` and `${CommercePermissions.FinancialDocumentRead.value}` " +
            "for USER sessions or SERVICE tokens. Queues sort by attentionSince then lexical inquiry UUID and may overlap. " +
            "Needs reply = customer email durably recorded after the latest staff reply or acknowledgement; needs quote = REQUESTED. " +
            "Resolution reasons are QUOTE_STALE (3 days since latest Quote version or inbound/staff-sent email), " +
            "EVENT_DATE_PASSED_UNSERVED (BOOKED after event date in Fiona's configured event calendar zone, " +
            "default America/Los_Angeles), " +
            "SERVED_WITH_BALANCE_DUE (positive), " +
            "and READY_TO_CLOSE (SERVED with exactly zero canonical Invoice balance). Acknowledgement does not reset quote inactivity. " +
            "Served resolution attention starts at served time; negative overpayment is not ready to close. Reads never promote booking. " +
            "All items are returned without pagination. Successful responses use Cache-Control: no-store."
        tags += Tag("Staff dashboard", "Fiona operational queues composed from canonical commerce facts and inquiry/customer data.")
        principalAuthentication()
        returningError(
            ErrorCategory.FORBIDDEN,
            "The principal lacks either fionas.inquiries.read or commerce.financial-document.read.",
            "The authenticated principal is not permitted to perform this request",
        )
        returning(
            Status.OK,
            body to
                StaffDashboardResponse(
                    "2026-10-05T01:00:00.000001Z",
                    StaffDashboardSummaryResponse(0, 0, 0, 0),
                    StaffDashboardWorkQueueResponse(empty, empty, empty),
                ),
        )
        returningError(
            ErrorCategory.INTERNAL_FAILURE,
            "Missing/corrupt canonical or enrichment data, or an unexpected failure. No partial dashboard.",
            INTERNAL_FAILURE,
        )
    } bindContract Method.GET to
        access
            .requirePermission(FionaPermissions.InquiriesRead)
            .then(access.requirePermission(CommercePermissions.FinancialDocumentRead))
            .then { _: Request -> Response(Status.OK).header("Cache-Control", "no-store").with(body of read().toResponse()) }
}
