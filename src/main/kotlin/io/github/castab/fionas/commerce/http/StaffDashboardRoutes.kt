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
    val needsQuote: StaffWorkQueueResponse,
    @ApiProperty(
        description =
            "Canonical Quote is the firm-proposal boundary; " +
                "this is not evidence of message delivery or a communications reply queue.",
    )
    val awaitingQuoteReply: StaffWorkQueueResponse,
    val needsClosing: StaffWorkQueueResponse,
    val needsReply: StaffWorkQueueResponse,
    val needsResolution: StaffWorkQueueResponse,
)

/** Stable transport codes describing unimplemented business capability, never caller permissions. */
@Serializable
enum class StaffQueueUnavailableReason { COMMUNICATIONS_NOT_IMPLEMENTED, RESOLUTION_POLICY_NOT_DEFINED }

@Serializable
data class StaffWorkQueueResponse(
    @ApiProperty(description = "True for supported queues even when empty; false for unimplemented business capability.") val available:
        Boolean,
    @ApiProperty(
        description =
            "All qualifying items, oldest inquiry creation time first then lexical inquiry UUID ascending; " +
                "no cap or pagination. Not longest customer waits.",
    )
    val items: List<StaffDashboardItemResponse>,
    @ApiProperty(description = "Absent for available queues. Unavailable queues have no items and one stable reason code.")
    val unavailableReason: StaffQueueUnavailableReason? = null,
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
    @ApiProperty(description = "Exact decimal current reconciliation balance, in currency; may be negative.") val balance: String,
    @ApiProperty(description = "ISO 4217 currency of total and balance.") val currency: String,
    @ApiProperty(format = "date-time") val inquiryCreatedAt: String,
    @ApiProperty(
        description = "Creation time of the latest canonical financial version; not the start of a customer wait.",
        format = "date-time",
    )
    val latestDocumentVersionAt: String,
    @ApiProperty(format = "date-time") val servedAt: String? = null,
)

internal fun StaffDashboard.toResponse() =
    StaffDashboardResponse(
        asOf.toString(),
        StaffDashboardSummaryResponse(summary.new, summary.quoted, summary.booked, summary.needsClosing),
        StaffDashboardWorkQueueResponse(
            workQueue.needsQuote.toResponse(),
            workQueue.awaitingQuoteReply.toResponse(),
            workQueue.needsClosing.toResponse(),
            workQueue.needsReply.toResponse(),
            workQueue.needsResolution.toResponse(),
        ),
    )

private fun StaffWorkQueue.toResponse(): StaffWorkQueueResponse =
    when (this) {
        is StaffWorkQueue.Available -> StaffWorkQueueResponse(true, items.map { it.toResponse() })
        is StaffWorkQueue.Unavailable -> StaffWorkQueueResponse(false, emptyList(), StaffQueueUnavailableReason.valueOf(reason.name))
    }

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
        balance.decimal(),
        document.currency.currencyCode,
        inquiryCreatedAt.toString(),
        latestFinancialVersion.createdAt.toString(),
        servedAt?.toString(),
    )
}

internal fun readStaffDashboardRoute(
    read: () -> StaffDashboard,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(StaffDashboardResponse.serializer())
    val empty = StaffWorkQueueResponse(true, emptyList())
    return "/staff/dashboard" meta {
        operationId = "readStaffDashboard"
        summary = "Read the staff dashboard"
        description = "One coherent unlocked REPEATABLE READ snapshot of all inquiries, canonical financial facts, customers and events. " +
            "Requires BOTH `${FionaPermissions.InquiriesRead.value}` and `${CommercePermissions.FinancialDocumentRead.value}` " +
            "for USER sessions or SERVICE tokens. Queues are oldest inquiries first, not customer wait durations. " +
            "Needs quote = REQUESTED; awaiting quote reply = QUOTED; needs closing = SERVED with exactly zero canonical Invoice balance. " +
            "Booked and needs closing overlap; CLOSED, positive balances and negative overpayments are excluded from needs closing. " +
            "Needs reply and needs resolution are unavailable capabilities, independent of permissions. Reads never promote booking. " +
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
                    StaffDashboardWorkQueueResponse(
                        empty,
                        empty,
                        empty,
                        StaffWorkQueueResponse(false, emptyList(), StaffQueueUnavailableReason.COMMUNICATIONS_NOT_IMPLEMENTED),
                        StaffWorkQueueResponse(false, emptyList(), StaffQueueUnavailableReason.RESOLUTION_POLICY_NOT_DEFINED),
                    ),
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
