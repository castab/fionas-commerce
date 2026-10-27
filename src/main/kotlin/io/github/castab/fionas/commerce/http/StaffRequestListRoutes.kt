package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryListPosition
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.ListStaffRequests
import io.github.castab.fionas.commerce.staff.StaffRequestFilter
import io.github.castab.fionas.commerce.staff.StaffRequestPage
import io.github.castab.fionas.commerce.staff.StaffRequestSearch
import io.github.castab.fionas.commerce.staff.StaffRequestSummary
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Query
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Serializable
data class StaffRequestListResponse(
    val items: List<StaffRequestListItemResponse>,
    @ApiProperty(
        description = "Opaque continuation bound to normalized filter/search; omitted on the last page (application null).",
    ) val nextCursor: String?,
    @ApiProperty(description = "All matches before pagination in this request's snapshot.") val totalCount: Int,
)

@Serializable
data class StaffRequestListItemResponse(
    @ApiProperty(format = "uuid") val inquiryId: String,
    @ApiProperty(format = "uuid") val customerId: String,
    val customerName: String,
    val customerEmail: String,
    @ApiProperty(format = "date") val eventDate: String,
    val eventType: InquiryEventType,
    val stage: InquiryStageResponse,
    @ApiProperty(description = "SERVED with exactly zero canonical Invoice balance; scale-independent.") val needsClosing: Boolean,
    @ApiProperty(format = "uuid") val documentId: String,
    val version: Int,
    @ApiProperty(description = "Latest canonical ESTIMATE, QUOTE, or INVOICE stage.") val financialStage: String,
    @ApiProperty(description = "Exact decimal canonical total in currency.", pattern = SIGNED_DECIMAL) val total: String,
    @ApiProperty(description = "FROM only for minimum-guest Estimates; Quotes and Invoices are EXACT.")
    val totalQualifier: DashboardTotalQualifierResponse,
    @ApiProperty(description = "Exact decimal reconciliation balance; may be negative.", pattern = SIGNED_DECIMAL) val balance: String,
    val currency: String,
    @ApiProperty(format = "date-time") val inquiryCreatedAt: String,
    @ApiProperty(format = "date-time") val latestDocumentVersionAt: String,
)

private val requestFilter = Query.optional("filter", "all (default), new, quoted, booked, needs-closing, closed, declined.")
private val requestSearch =
    Query.optional(
        "q",
        "Literal case-insensitive name/email substring or exact inquiry UUID; " +
            "trimmed, blank ignored; max 200 characters, no controls.",
    )
private val requestLimit = Query.optional("limit", "Integer 1..100; default 25.")
private val requestCursor = Query.optional("cursor", "Opaque continuation for the same normalized filter and search. Page size may change.")

private fun queryIdentity(
    filter: StaffRequestFilter,
    search: StaffRequestSearch?,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("${filter.name}|${search?.value.orEmpty()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun encodePosition(
    position: InquiryListPosition,
    identity: String,
): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        "1|$identity|${position.createdAt}|${position.id.value}".toByteArray(Charsets.US_ASCII),
    )

private fun decodePosition(
    cursor: String,
    identity: String,
): InquiryListPosition =
    try {
        require(cursor.length <= 300 && cursor.matches(Regex("[A-Za-z0-9_-]+")))
        val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.US_ASCII).split('|')
        require(parts.size == 4 && parts[0] == "1" && parts[1] == identity)
        val position = InquiryListPosition(Instant.parse(parts[2]), InquiryId(UUID.fromString(parts[3])))
        require(encodePosition(position, identity) == cursor)
        position
    } catch (failure: RuntimeException) {
        throw LensFailure(Invalid(requestCursor.meta))
    }

private fun StaffRequestSummary.toListResponse(): StaffRequestListItemResponse {
    val latest = operational.financial.latestVersion
    val document = latest.document
    return StaffRequestListItemResponse(
        inquiry.id.value.toString(),
        customer.id.value.toString(),
        customer.name.value,
        customer.email.value,
        inquiry.eventDate.value.toString(),
        InquiryEventType.valueOf(inquiry.eventType.name),
        InquiryStageResponse.valueOf(operational.lifecycle.stage.name),
        operational.needsClosing,
        document.id.toString(),
        document.version.number,
        when (document) {
            is FinancialDocument.Estimate -> "ESTIMATE"
            is FinancialDocument.Quote -> "QUOTE"
            is FinancialDocument.Invoice -> "INVOICE"
        },
        document.total.decimal(),
        DashboardTotalQualifierResponse.valueOf(totalQualifier.name),
        operational.financial.reconciliation.balance
            .decimal(),
        document.currency.currencyCode,
        inquiry.createdAt.toString(),
        latest.createdAt.toString(),
    )
}

internal fun listStaffRequestsRoute(
    read: (ListStaffRequests.Command) -> StaffRequestPage,
    access: AccessControl,
): ContractRoute {
    val body = jsonBody(StaffRequestListResponse.serializer())
    return "/staff/requests" meta {
        operationId = "listStaffRequests"
        summary = "Browse and search staff requests"
        description = "Requires BOTH fionas.inquiries.read and commerce.financial-document.read for USER sessions or SERVICE tokens. " +
            "One unlocked REPEATABLE READ snapshot; totalCount counts filter AND search matches before pagination. " +
            "all includes CLOSED and DECLINED; new=REQUESTED; quoted=QUOTED; booked=BOOKED or SERVED; " +
            "needs-closing=SERVED with exactly zero canonical Invoice balance (not positive or negative). " +
            "Booked and Needs closing overlap and agree with dashboard counts when q is absent. " +
            "closed=CLOSED; declined=DECLINED. Only INITIAL_ESTIMATE lineages classify requests. " +
            "Search is a literal case-insensitive customer name/email substring or exact inquiry UUID; trims surrounding whitespace, " +
            "ignores blank text, permits at most 200 characters and rejects controls. No customer deduplication. " +
            "Orders inquiry creation time descending then lexical UUID descending. " +
            "Cursor binds normalized filter/search without customer text; " +
            "independent pages do not freeze membership during concurrent activity. Exact-decimal strings preserve financial amounts. " +
            "Successful responses use Cache-Control: no-store."
        tags += Tag("Staff requests", "Coherent inquiry and canonical financial detail for staff request review.")
        preFlightExtraction = PreFlightExtraction.None
        queries += listOf(requestFilter, requestSearch, requestLimit, requestCursor)
        principalAuthentication()
        returning(Status.OK, body to StaffRequestListResponse(emptyList(), null, 0))
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "Invalid/repeated filter, search, limit or cursor; cursor query mismatch.",
            "Malformed request",
        )
        returningError(
            ErrorCategory.FORBIDDEN,
            "Either required permission is missing.",
            "The authenticated principal is not permitted to perform this request",
        )
        returningError(
            ErrorCategory.INTERNAL_FAILURE,
            "Missing/corrupt projection data or unexpected failure; no partial results.",
            INTERNAL_FAILURE,
        )
    } bindContract Method.GET to
        access
            .requirePermission(FionaPermissions.InquiriesRead)
            .then(access.requirePermission(CommercePermissions.FinancialDocumentRead))
            .then { request: Request ->
                listOf(requestFilter, requestSearch, requestLimit, requestCursor).forEach { lens ->
                    if (request.queries(lens.meta.name).size > 1) throw LensFailure(Invalid(lens.meta))
                }
                val filter =
                    when (requestFilter(request) ?: "all") {
                        "all" -> StaffRequestFilter.ALL
                        "new" -> StaffRequestFilter.NEW
                        "quoted" -> StaffRequestFilter.QUOTED
                        "booked" -> StaffRequestFilter.BOOKED
                        "needs-closing" -> StaffRequestFilter.NEEDS_CLOSING
                        "closed" -> StaffRequestFilter.CLOSED
                        "declined" -> StaffRequestFilter.DECLINED
                        else -> throw LensFailure(Invalid(requestFilter.meta))
                    }
                val search =
                    try {
                        StaffRequestSearch.of(requestSearch(request))
                    } catch (failure: IllegalArgumentException) {
                        throw LensFailure(Invalid(requestSearch.meta))
                    }
                val limit =
                    requestLimit(request)?.let {
                        if (!it.matches(Regex("[0-9]+"))) throw LensFailure(Invalid(requestLimit.meta))
                        it.toIntOrNull()?.takeIf { value -> value in 1..ListStaffRequests.MAX_LIMIT }
                            ?: throw LensFailure(Invalid(requestLimit.meta))
                    } ?: ListStaffRequests.DEFAULT_LIMIT
                val identity = queryIdentity(filter, search)
                val after = requestCursor(request)?.let { decodePosition(it, identity) }
                val result = read(ListStaffRequests.Command(filter, search, after, limit))
                Response(Status.OK).header("Cache-Control", "no-store").with(
                    body of
                        StaffRequestListResponse(
                            result.items.map { it.toListResponse() },
                            result.next?.let { encodePosition(it, identity) },
                            result.totalCount,
                        ),
                )
            }
}
