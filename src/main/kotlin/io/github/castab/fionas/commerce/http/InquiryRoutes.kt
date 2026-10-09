package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.financial.MAX_DOCUMENT_LINES
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.EventType
import io.github.castab.fionas.commerce.inquiry.IdempotencyKeyReused
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquiryDetails
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryListPosition
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.inquiry.InquiryPage
import io.github.castab.fionas.commerce.inquiry.InquirySubmissionKey
import io.github.castab.fionas.commerce.inquiry.InquirySummary
import io.github.castab.fionas.commerce.inquiry.ListInquiries
import io.github.castab.fionas.commerce.inquiry.RequestedService
import io.github.castab.fionas.commerce.inquiry.RequestedServiceItem
import io.github.castab.fionas.commerce.inquiry.ZipCode
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Header
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.int
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/** The body of `POST /inquiries`. */
@Serializable
data class CreateInquiryRequest(
    @ApiProperty(
        description =
            "The customer's name as they give it, for example `Jane Doe`. Surrounding whitespace is removed; " +
                "what remains must not be blank and is at most ${CustomerName.MAX_LENGTH} characters.",
        minLength = 1,
        maxLength = CustomerName.MAX_LENGTH,
    )
    val name: String,
    @ApiProperty(
        description =
            "The customer's email address. It is trimmed and lowercased, and must then be at most " +
                "${Email.MAX_LENGTH} characters with exactly one `@` after a non-empty local part and a domain such as " +
                "`example.com`. Validation is deliberately shallow: the address is neither fully parsed nor verified. " +
                "If a customer already has this address, the inquiry is theirs and their stored name is kept.",
        maxLength = Email.MAX_LENGTH,
    )
    val email: String,
    @ApiProperty(
        description =
            "What the customer has in mind, for example the occasion. Surrounding whitespace is removed, and " +
                "a message of only whitespace counts as none. At most ${InquiryMessage.MAX_LENGTH} characters.",
        maxLength = InquiryMessage.MAX_LENGTH,
    )
    val message: String? = null,
    @ApiProperty(
        description =
            "Required: what the customer asked Fiona to serve, as the pricing authority recorded it. Descriptive request " +
                "history for staff only: Fiona never prices, authorizes or validates the lines from it.",
    )
    val requestedService: RequestedServiceRequest,
    @ApiProperty(
        description =
            "Required: the exact already-priced lines of the inquiry's initial Estimate, in order, as the public pricing " +
                "authority (the web server) committed them: 1 to $MAX_DOCUMENT_LINES lines in one currency with a " +
                "nonnegative total. Fiona records them exactly, without repricing or any catalog check; the document " +
                "total is derived from them. Callers never send a total.",
    )
    val lines: List<PricedLineRequest>,
    @ApiProperty(
        description =
            "Required event ZIP code for staff service-area/travel review; not the customer's home address. " +
                "Trimmed; must contain exactly five ASCII digits, preserving leading zeroes. " +
                "No automatic service-area decision or surcharge is applied.",
        minLength = ZipCode.LENGTH,
        maxLength = ZipCode.LENGTH,
        pattern = ZipCode.PATTERN,
    )
    val zipCode: String,
    @ApiProperty(description = "Required event calendar date in YYYY-MM-DD, without a time or time zone. Years 0001–9999.", format = "date")
    val eventDate: String,
    @ApiProperty(description = "Required event type. Submit one of the form's option values.")
    val eventType: InquiryEventType,
)

@Serializable
enum class InquiryEventType {
    BIRTHDAY,
    WEDDING,
    CORPORATE,
    SCHOOL_EVENT,
    NEIGHBORHOOD_EVENT,
    OTHER,
    ;

    fun toDomain(): EventType = EventType.valueOf(name)
}

/** What the customer asked Fiona to serve: descriptive request history, never a price or pricing input. */
@Serializable
data class RequestedServiceRequest(
    @ApiProperty(description = "The guests the event is for: 1 to ${RequestedService.MAX_GUESTS}.")
    val guestCount: Int,
    @ApiProperty(description = "Whether `guestCount` is a lower bound (\"100+ guests\"); estimates then read \"from\". False when absent.")
    val guestCountIsMinimum: Boolean = false,
    @ApiProperty(description = "The requested service duration in minutes, when one was requested: 1 to 1440.")
    val durationMinutes: Int? = null,
    @ApiProperty(description = "What the customer chose or described, in presentation order; at most ${RequestedService.MAX_ITEMS}.")
    val items: List<RequestedServiceItemRequest> = emptyList(),
    @ApiProperty(
        description =
            "Optional documentary provenance of the prices, for example the web server's pricing policy version. Fiona " +
                "records it and never validates or interprets it. At most 200 characters.",
        maxLength = RequestedService.PRICING_REFERENCE_MAX_LENGTH,
    )
    val pricingReference: String? = null,
)

/** One requested item, as people read it, with the web catalog's optional grouping and key. */
@Serializable
data class RequestedServiceItemRequest(
    @ApiProperty(
        description = "What was requested, for example `Horchata soft serve`. Trimmed; nonblank; at most 200 characters.",
        maxLength = RequestedServiceItem.LABEL_MAX_LENGTH,
    )
    val label: String,
    @ApiProperty(
        description = "The web catalog's grouping, for example `Soft serve`; at most 120 characters.",
        maxLength = RequestedServiceItem.GROUP_MAX_LENGTH,
    )
    val group: String? = null,
    @ApiProperty(
        description = "The web catalog's stable key; it identifies nothing in Fiona. At most 120 characters.",
        maxLength = RequestedServiceItem.KEY_MAX_LENGTH,
    )
    val key: String? = null,
)

/** What `POST /inquiries` answers: the new inquiry's identity, and nothing of any stored customer. */
@Serializable
data class InquiryReceiptResponse(
    @ApiProperty(description = "The new inquiry's id.", format = "uuid")
    val id: String,
    @ApiProperty(
        description = "When the inquiry was recorded: an RFC 3339 timestamp in UTC, to at most microsecond precision.",
        format = "date-time",
    )
    val createdAt: String,
)

/** An inquiry as staff read it. */
@Serializable
data class InquiryResponse(
    @ApiProperty(description = "The inquiry's id.", format = "uuid")
    val id: String,
    @ApiProperty(description = "The id of the customer who made the inquiry.", format = "uuid")
    val customerId: String,
    @ApiProperty(description = "The customer's stored name.")
    val name: String,
    @ApiProperty(description = "The customer's normalized (trimmed, lowercase) email address.")
    val email: String,
    @ApiProperty(description = "The inquiry's message; absent when it has none.")
    val message: String? = null,
    @ApiProperty(
        description = "When the inquiry was recorded: an RFC 3339 timestamp in UTC, to at most microsecond precision.",
        format = "date-time",
    )
    val createdAt: String,
    @ApiProperty(
        description =
            "What the customer asked Fiona to serve, exactly as recorded with the inquiry: descriptive history, never " +
                "the committed financial lines, which are the canonical Estimate's.",
    )
    val requestedService: RequestedServiceRequest,
    @ApiProperty(description = "The event's required ZIP code as recorded with this inquiry.", pattern = ZipCode.PATTERN)
    val zipCode: String,
    @ApiProperty(description = "The recorded event calendar date, without a time or time zone.", format = "date")
    val eventDate: String,
    val eventType: InquiryEventType,
    val lifecycle: InquiryLifecycleResponse,
)

/** One page of the newest-first inquiry list. */
@Serializable
data class InquiryListResponse(
    @ApiProperty(description = "The page's inquiries, newest first; empty when there are none.")
    val inquiries: List<InquiryListItem>,
    @ApiProperty(
        description =
            "An opaque cursor for the next page, passed back unchanged as `cursor`; absent on the last page. Its " +
                "contents are not part of the API.",
    )
    val nextCursor: String? = null,
)

/** An inquiry in the staff inquiry list. */
@Serializable
data class InquiryListItem(
    @ApiProperty(description = "The inquiry's id.", format = "uuid")
    val id: String,
    @ApiProperty(description = "The id of the customer who made the inquiry.", format = "uuid")
    val customerId: String,
    @ApiProperty(description = "The customer's stored name.")
    val name: String,
    @ApiProperty(description = "The customer's normalized (trimmed, lowercase) email address.")
    val email: String,
    @ApiProperty(description = "The inquiry's message; absent when it has none.")
    val message: String? = null,
    @ApiProperty(
        description = "When the inquiry was recorded: an RFC 3339 timestamp in UTC, to at most microsecond precision.",
        format = "date-time",
    )
    val createdAt: String,
    @ApiProperty(description = "The inquiry's required event ZIP code for staff travel review.", pattern = ZipCode.PATTERN)
    val zipCode: String,
    @ApiProperty(description = "The recorded event calendar date, without a time or time zone.", format = "date")
    val eventDate: String,
    val eventType: InquiryEventType,
)

private val createInquiryRequest = jsonBody(CreateInquiryRequest.serializer())
private val inquiryConflictBody = jsonBody(ErrorResponse.serializer())
internal const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
private val submissionKeyHeader =
    Header.required(
        "Idempotency-Key",
        "Opaque submission identity, 1–128 ASCII letters, digits, underscores or hyphens (UUIDs are supported). " +
            "Use the SAME key for every delivery/retry of one logical submission, including concurrent double delivery. " +
            "Not a credential. A successful identical replay returns the original receipt without writing anything; " +
            "changed intent with that key fails with 409 IDEMPOTENCY_KEY_REUSED. Keys do not expire.",
        mapOf(
            "schema" to
                mapOf(
                    "minLength" to 1,
                    "maxLength" to InquirySubmissionKey.MAX_LENGTH,
                    "pattern" to InquirySubmissionKey.PATTERN,
                ),
        ),
    )
private val inquiryReceiptResponse = jsonBody(InquiryReceiptResponse.serializer())
private val inquiryResponse = jsonBody(InquiryResponse.serializer())
private val inquiryListResponse = jsonBody(InquiryListResponse.serializer())

// A plain string for the contract: a contract treats a path value its lens rejects as an
// unmatched route (404), while an id that is not a UUID is a malformed request (400).
internal val inquiryDetailIdPath =
    Path.of("inquiryId", "The inquiry's id.", mapOf("schema" to mapOf("format" to "uuid")))

private val limitQuery =
    Query
        .int()
        .optional(
            "limit",
            "How many inquiries the page holds at most: from 1 to ${ListInquiries.MAX_LIMIT}, " +
                "${ListInquiries.DEFAULT_LIMIT} when absent.",
            mapOf("schema" to mapOf("minimum" to 1, "maximum" to ListInquiries.MAX_LIMIT, "default" to ListInquiries.DEFAULT_LIMIT)),
        )

private val cursorQuery =
    Query.optional(
        "cursor",
        "The `nextCursor` of the previous page, unchanged; absent for the first page. The page starts strictly after " +
            "the previous page's last inquiry.",
    )

internal val inquiries = Tag("Inquiries", "Configured service requests through quotation, booking, service and closeout.")

internal val exampleRequestedService =
    RequestedServiceRequest(
        guestCount = 75,
        durationMinutes = 120,
        items =
            listOf(
                RequestedServiceItemRequest("Vanilla soft serve", "Soft serve", "vanilla"),
                RequestedServiceItemRequest("Horchata soft serve", "Soft serve", "horchata"),
                RequestedServiceItemRequest("Waffle cones", "Cones", "waffle-cone"),
            ),
        pricingReference = "fionas-web-pricing@2026-10-01",
    )

private val exampleRequest =
    CreateInquiryRequest(
        name = "Jane Doe",
        email = "jane@example.com",
        message = "Ice cream service for a birthday.",
        requestedService = exampleRequestedService,
        lines =
            listOf(
                PricedLineRequest("Base service", "2 hours · setup, staff & local travel", null, "250.00", "0.00", "USD"),
                PricedLineRequest("Ice cream service", "75 guests", "75", "4.00", "0.00", "USD"),
                PricedLineRequest("Horchata", "Premium soft serve", "75", "0.50", "0.00", "USD"),
                PricedLineRequest("Waffle cones", null, "75", "0.75", "0.00", "USD"),
            ),
        zipCode = "92626",
        eventDate = "2026-12-05",
        eventType = InquiryEventType.BIRTHDAY,
    )
private val exampleReceipt =
    InquiryReceiptResponse(id = "c755f7cd-1e28-4c75-a85f-d066ede7387d", createdAt = "2026-09-26T21:19:39.321012Z")
internal val exampleInquiry =
    InquiryResponse(
        lifecycle = InquiryLifecycleResponse("aec8f5a3-9d32-470b-a519-55b17f0cfb27", InquiryStageResponse.REQUESTED),
        id = "c755f7cd-1e28-4c75-a85f-d066ede7387d",
        customerId = "602df298-8d54-45b6-a40c-bbf80949a3b8",
        name = "Jane Doe",
        email = "jane@example.com",
        message = "Ice cream service for a birthday.",
        createdAt = "2026-09-26T21:19:39.321012Z",
        zipCode = "92626",
        eventDate = "2026-12-05",
        eventType = InquiryEventType.BIRTHDAY,
        requestedService = exampleRequestedService,
    )
private val exampleList =
    InquiryListResponse(
        inquiries =
            listOf(
                InquiryListItem(
                    id = "c755f7cd-1e28-4c75-a85f-d066ede7387d",
                    customerId = "602df298-8d54-45b6-a40c-bbf80949a3b8",
                    name = "Jane Doe",
                    email = "jane@example.com",
                    message = "Ice cream service for a birthday.",
                    createdAt = "2026-09-26T21:19:39.321012Z",
                    zipCode = "92626",
                    eventDate = "2026-12-05",
                    eventType = InquiryEventType.BIRTHDAY,
                ),
            ),
        nextCursor = "MjAyNi0wOS0yNlQyMToxOTozOS4zMjEwMTJafGM3NTVmN2NkLTFlMjgtNGM3NS1hODVmLWQwNjZlZGU3Mzg3ZA",
    )

/** The errors every inquiry read answers, in addition to its own: any principal holding `fionas.inquiries.read`. */
private fun RouteMetaDsl.inquiryReadErrors() {
    principalAccess(FionaPermissions.InquiriesRead)
    returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
}

/**
 * `POST /inquiries`: records an inquiry, establishing its customer, with the exact already-priced
 * lines of its initial Estimate. Only the public pricing authority may call it: a SERVICE
 * principal holding `fionas.inquiries.create` (a staff USER holding it is still `403`). The route
 * only translates between transport and application values; [createInquiry] does the work. The
 * response is a receipt of the new inquiry only: the caller never reads a stored customer back.
 */
fun createInquiryRoute(
    createInquiry: (CreateInquiry.Command) -> Inquiry,
    access: AccessControl,
): ContractRoute =
    "/inquiries" meta {
        operationId = "createInquiry"
        headers += submissionKeyHeader
        // The key is read by the handler, after authorization, so an unauthorized caller always gets `401` or `403`.
        preFlightExtraction = PreFlightExtraction.None
        principalAccess(
            FionaPermissions.InquiriesCreate,
            "or the authenticated principal is not a SERVICE (a staff session holding the permission is still refused)",
        )
        // Enforcement admits only the pricing authority's service token, so that is all the document advertises.
        security = serviceTokenSecurity
        summary = "Record a service-priced inquiry"
        description =
            "Records a prospective customer's inquiry together with the exact already-priced lines of its canonical " +
            "initial Estimate (version 1). Only the public pricing authority, the web server's SERVICE principal holding " +
            "`${FionaPermissions.InquiriesCreate.value}`, may call it: it owns the public catalog, selection rules, " +
            "availability and prices, evaluates the customer's untrusted choices server-side, and submits final lines. " +
            "Fiona records those lines exactly; it never reprices them, requests a catalog revision, checks option " +
            "eligibility, or infers a price from the guest count or requested items. `requestedService` is descriptive " +
            "history for staff. Lines must form a valid document: 1 to $MAX_DOCUMENT_LINES lines, one currency, exact " +
            "decimal strings (precise unit rates allowed; every subtotal, tax and total exact in minor units), and a " +
            "nonnegative total, which Fiona derives. " +
            "Idempotency-Key is required: a successful same-key/same-intent replay returns the original 201 receipt and " +
            "Location without writing anything. The fingerprint binds every value, including each line's amounts and " +
            "order; a successful key reused for different intent fails with 409 IDEMPOTENCY_KEY_REUSED. Failed attempts " +
            "do not consume keys. Customer, inquiry, requested service, Estimate v1, its association and line authorship " +
            "commit together or not at all. The customer is found by normalized email, or created; an existing " +
            "customer's stored name is never changed. The response is a receipt of the new inquiry alone: it describes " +
            "no stored customer and does not expose the Estimate. `Location` holds `/inquiries/{inquiryId}`."
        tags += inquiries
        receiving(createInquiryRequest to exampleRequest)
        returning(Status.CREATED, inquiryReceiptResponse to exampleReceipt, "The recorded inquiry's receipt. `Location` holds its path.")
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "Idempotency-Key is missing, repeated or invalid, or the body is not JSON, lacks (or nulls) a required name, " +
                "email, ZIP, event date or type, `requestedService` or `lines`, has an unknown event type, or a field has " +
                "the wrong JSON type (amounts are strings, never numbers).",
            "Malformed request: body 'body'",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "a value is invalid: a blank name, an email without `@`, a ZIP code without five digits, an invalid event " +
                "date, an invalid requested service, no lines or too many, a line with a blank description, a malformed " +
                "or overlong decimal, $LINE_PRECISION_REJECTED, mixed currencies, or a negative document total. " +
                "Nothing is written.",
            "A line's subtotal (unit price × quantity) must be exact in USD minor units; nothing is rounded",
        )
        returning(
            Status.CONFLICT,
            inquiryConflictBody to
                ErrorResponse(IDEMPOTENCY_KEY_REUSED, "This key already represents a different successful inquiry submission"),
            "`IDEMPOTENCY_KEY_REUSED`: this key already belongs to a different successful inquiry command (including " +
                "different line amounts or order); use a new key for different intent. No-store. An identical successful " +
                "replay returns 201 instead. The runtime envelope uses `conflict` if a concurrent request created the " +
                "customer first; retry that request.",
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
    } bindContract Method.POST to
        access.requirePermission(FionaPermissions.InquiriesCreate).then(requireService).then { request: Request ->
            val submissionKey = submissionKey(request)
            val body = createInquiryRequest(request)
            val command =
                validating {
                    CreateInquiry.Command(
                        name = CustomerName.of(body.name),
                        email = Email.of(body.email),
                        message = InquiryMessage.ofOptional(body.message),
                        requestedService = body.requestedService.domain(),
                        lines = body.lines.domain(),
                        zipCode = ZipCode.of(body.zipCode),
                        eventDate = EventDate.of(body.eventDate),
                        eventType = body.eventType.toDomain(),
                        submissionKey = submissionKey,
                        submittedBy = servicePrincipal(request),
                    )
                }
            try {
                val created = createInquiry(command)
                Response(Status.CREATED)
                    .header("Location", "/inquiries/${created.id.value}")
                    .with(inquiryReceiptResponse of InquiryReceiptResponse(created.id.value.toString(), created.createdAt.toString()))
            } catch (failure: CommerceFailure.Conflict) {
                if (failure.cause !is IdempotencyKeyReused) throw failure
                Response(Status.CONFLICT)
                    .with(inquiryConflictBody of ErrorResponse(IDEMPOTENCY_KEY_REUSED, failure.message!!))
                    .header("Cache-Control", "no-store")
            }
        }

private fun submissionKey(request: Request): InquirySubmissionKey {
    val value = submissionKeyHeader(request)
    try {
        require(request.headerValues("Idempotency-Key").size == 1)
        return InquirySubmissionKey(value)
    } catch (failure: IllegalArgumentException) {
        throw LensFailure(Invalid(submissionKeyHeader.meta), cause = failure)
    }
}

/**
 * `GET /inquiries`: staff's inquiry inbox, newest first, a page at a time. Requires
 * `fionas.inquiries.read`. Not a query API: no search, filters, or offsets.
 */
fun listInquiriesRoute(
    listInquiries: (ListInquiries.Command) -> InquiryPage,
    access: AccessControl,
): ContractRoute =
    "/inquiries" meta {
        operationId = "listInquiries"
        summary = "List inquiries"
        description =
            "Fiona's inquiries, newest first (by creation time, then by id), at most `limit` per page. When more follow, " +
            "`nextCursor` names the next page: pass it back as `cursor`. A page continues strictly after the previous " +
            "page's last inquiry, so inquiries recorded at the same instant are neither repeated nor skipped. " +
            "Requires `${FionaPermissions.InquiriesRead.value}`."
        tags += inquiries
        // Queries are read by the handler, after authentication, so an anonymous caller always gets `401`.
        preFlightExtraction = PreFlightExtraction.None
        queries += limitQuery
        queries += cursorQuery
        returning(Status.OK, inquiryListResponse to exampleList, "The page of inquiries.")
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "`limit` is not an integer, or `cursor` is not a cursor this API issued.",
            "Malformed request: query 'cursor'",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "`limit` is outside 1 to ${ListInquiries.MAX_LIMIT}.",
            "limit must be between 1 and ${ListInquiries.MAX_LIMIT}",
        )
        inquiryReadErrors()
    } bindContract Method.GET to
        access.requirePermission(FionaPermissions.InquiriesRead).then { request: Request ->
            val after = cursorQuery(request)?.let(::position)
            val limit = limitQuery(request) ?: ListInquiries.DEFAULT_LIMIT
            val page = listInquiries(validating { ListInquiries.Command(after, limit) })
            Response(Status.OK).with(inquiryListResponse of page.toResponse())
        }

/** `GET /inquiries/{inquiryId}`: the persisted inquiry, as [getInquiry] reads it. Requires `fionas.inquiries.read`. */
fun getInquiryRoute(
    getInquiry: (InquiryId) -> InquiryDetails,
    access: AccessControl,
): ContractRoute =
    "/inquiries" / inquiryDetailIdPath meta {
        operationId = "getInquiry"
        summary = "Read an inquiry"
        description =
            "The persisted inquiry, the customer who made it, the service requested with it, and its lifecycle projected " +
            "from the canonical INITIAL_ESTIMATE lineage plus " +
            "served/closed facts in one REPEATABLE_READ snapshot. RELATED documents and event date never advance it. " +
            "Requires `${FionaPermissions.InquiriesRead.value}`."
        tags += inquiries
        returning(Status.OK, inquiryResponse to exampleInquiry, "The inquiry.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "`inquiryId` is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(
            ErrorCategory.NOT_FOUND,
            "no inquiry has this id.",
            "Inquiry c755f7cd-1e28-4c75-a85f-d066ede7387d was not found",
        )
        inquiryReadErrors()
    } bindContract Method.GET to { id: String ->
        access.requirePermission(FionaPermissions.InquiriesRead).then { _: Request ->
            Response(Status.OK).with(inquiryResponse of getInquiry(inquiryId(id)).toResponse())
        }
    }

/** The id in the path segment; one that is not a UUID is reported as the unreadable path value it is. */
internal fun inquiryId(segment: String): InquiryId =
    try {
        InquiryId(UUID.fromString(segment))
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(inquiryDetailIdPath.meta), cause = e)
    }

/*
 * A cursor is the list position of a page's last inquiry, `<createdAt>|<id>`, base64url-encoded
 * so clients treat it as opaque. One this API did not issue is an unreadable query value.
 */
private fun cursor(position: InquiryListPosition): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString("${position.createdAt}|${position.id.value}".toByteArray())

private fun position(cursor: String): InquiryListPosition =
    try {
        val (createdAt, id) = String(Base64.getUrlDecoder().decode(cursor)).split('|').also { require(it.size == 2) }
        InquiryListPosition(Instant.parse(createdAt), InquiryId(UUID.fromString(id)))
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(cursorQuery.meta), cause = e)
    } catch (e: DateTimeParseException) {
        throw LensFailure(Invalid(cursorQuery.meta), cause = e)
    }

private fun InquiryPage.toResponse() =
    InquiryListResponse(
        inquiries = inquiries.map { it.toResponse() },
        nextCursor = next?.let(::cursor),
    )

private fun InquirySummary.toResponse() =
    InquiryListItem(
        id = inquiry.id.value.toString(),
        customerId = customer.id.value.toString(),
        name = customer.name.value,
        email = customer.email.value,
        message = inquiry.message?.value,
        createdAt = inquiry.createdAt.toString(),
        zipCode = inquiry.zipCode.value,
        eventDate = inquiry.eventDate.value.toString(),
        eventType = InquiryEventType.valueOf(inquiry.eventType.name),
    )

internal fun RequestedServiceRequest.domain(): RequestedService =
    RequestedService(
        guestCount = guestCount,
        guestCountIsMinimum = guestCountIsMinimum,
        durationMinutes = durationMinutes,
        items = items.map { RequestedServiceItem.of(it.label, it.group, it.key) },
        pricingReference = pricingReference?.trim()?.takeIf { it.isNotEmpty() },
    )

internal fun RequestedService.toResponse() =
    RequestedServiceRequest(
        guestCount = guestCount,
        guestCountIsMinimum = guestCountIsMinimum,
        durationMinutes = durationMinutes,
        items = items.map { RequestedServiceItemRequest(it.label, it.group, it.key) },
        pricingReference = pricingReference,
    )
