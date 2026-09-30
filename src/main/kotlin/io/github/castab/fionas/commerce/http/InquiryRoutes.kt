package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.EventDate
import io.github.castab.fionas.commerce.inquiry.EventType
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquiryDetails
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryListPosition
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import io.github.castab.fionas.commerce.inquiry.InquiryPage
import io.github.castab.fionas.commerce.inquiry.InquirySummary
import io.github.castab.fionas.commerce.inquiry.ListInquiries
import io.github.castab.fionas.commerce.inquiry.ZipCode
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
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
            "The configuration the customer chose, as `POST /estimate-preview` takes it; absent for a plain contact " +
                "inquiry. It is checked with the same pricing, from exactly the catalog revision it names, and recorded " +
                "with the inquiry pinned to that revision. Inputs the pricing rejects fail the request as they fail a " +
                "preview, and nothing is recorded. The accepted concrete lines materialize an initial Estimate in the " +
                "same transaction. Amounts are never accepted; the inquiry records customer intent and the ledger records financial lines.",
    )
    val pricingInputs: InquiryPricingInputs? = null,
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

/** The commercial inputs a customer configured, submitted with an inquiry: never amounts, lines, or totals. */
@Serializable
data class InquiryPricingInputs(
    @ApiProperty(
        description =
            "The revision of Fiona's Offerings catalog the choices were made from, as `GET /offering-catalog` " +
                "returned it. It is recorded as submitted and never replaced by a later one. At least 1.",
    )
    val catalogRevision: Int,
    @ApiProperty(description = "The guests the event is for. At least 1.")
    val guestCount: Int,
    @ApiProperty(description = "Whether `guestCount` is a lower bound (\"100+ guests\"). False when absent.")
    val guestCountIsMinimum: Boolean = false,
    @ApiProperty(description = "The service duration in minutes: one of 90, 120, 150, or 180.")
    val durationMinutes: Int,
    @ApiProperty(description = "The chosen offerings, one entry per catalog category, in the order they are shown.")
    val selections: List<PricingSelection>,
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
            "The configuration the customer submitted with the inquiry, exactly as recorded; absent when none was. " +
                "Its properties are those `POST /inquiries/{inquiryId}/estimates` takes.",
    )
    val pricingInputs: InquiryRequestedPricing? = null,
    @ApiProperty(description = "The event's required ZIP code as recorded with this inquiry.", pattern = ZipCode.PATTERN)
    val zipCode: String,
    @ApiProperty(description = "The recorded event calendar date, without a time or time zone.", format = "date")
    val eventDate: String,
    val eventType: InquiryEventType,
)

/** The commercial inputs recorded with an inquiry, pinned to the catalog revision the customer chose from. */
@Serializable
data class InquiryRequestedPricing(
    @ApiProperty(description = "The catalog revision the customer chose from, as submitted; never a later one.")
    val catalogRevision: Int,
    @ApiProperty(description = "The guests the event is for.")
    val guestCount: Int,
    @ApiProperty(description = "Whether the guest count is a lower bound.")
    val guestCountIsMinimum: Boolean,
    @ApiProperty(description = "The service duration in minutes.")
    val durationMinutes: Int,
    @ApiProperty(description = "The chosen offerings, in the order they were submitted.")
    val selections: List<PricingSelection>,
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
private val inquiryReceiptResponse = jsonBody(InquiryReceiptResponse.serializer())
private val inquiryResponse = jsonBody(InquiryResponse.serializer())
private val inquiryListResponse = jsonBody(InquiryListResponse.serializer())

// A plain string for the contract: a contract treats a path value its lens rejects as an
// unmatched route (404), while an id that is not a UUID is a malformed request (400).
private val inquiryIdPath =
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

internal val inquiries = Tag("Inquiries", "A prospective customer's request to Fiona's, before any booking exists.")

private val exampleSelections =
    listOf(
        PricingSelection("soft-serve-flavor", listOf("vanilla", "horchata")),
        PricingSelection("topping", listOf("sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough")),
        PricingSelection("cone-option", listOf("waffle-cone")),
    )

private val exampleRequest =
    CreateInquiryRequest(
        name = "Jane Doe",
        email = "jane@example.com",
        message = "Ice cream service for a birthday.",
        pricingInputs =
            InquiryPricingInputs(catalogRevision = 12, guestCount = 75, durationMinutes = 120, selections = exampleSelections),
        zipCode = "92626",
        eventDate = "2026-12-05",
        eventType = InquiryEventType.BIRTHDAY,
    )
private val exampleReceipt =
    InquiryReceiptResponse(id = "c755f7cd-1e28-4c75-a85f-d066ede7387d", createdAt = "2026-09-26T21:19:39.321012Z")
private val exampleInquiry =
    InquiryResponse(
        id = "c755f7cd-1e28-4c75-a85f-d066ede7387d",
        customerId = "602df298-8d54-45b6-a40c-bbf80949a3b8",
        name = "Jane Doe",
        email = "jane@example.com",
        message = "Ice cream service for a birthday.",
        createdAt = "2026-09-26T21:19:39.321012Z",
        zipCode = "92626",
        eventDate = "2026-12-05",
        eventType = InquiryEventType.BIRTHDAY,
        pricingInputs =
            InquiryRequestedPricing(
                catalogRevision = 12,
                guestCount = 75,
                guestCountIsMinimum = false,
                durationMinutes = 120,
                selections = exampleSelections,
            ),
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

/** The errors every staff-protected inquiry route answers, in addition to its own. */
private fun RouteMetaDsl.staffErrors() {
    returningError(ErrorCategory.UNAUTHENTICATED, "there is no active staff session.", "Authentication is required")
    returningError(
        ErrorCategory.FORBIDDEN,
        "the staff user lacks `${FionaPermissions.InquiriesRead.value}`.",
        "The authenticated principal is not permitted to perform this request",
    )
    returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
}

/**
 * `POST /inquiries`: records an inquiry, establishing its customer. Public. The route only
 * translates between transport and application values; [createInquiry] does the work, and
 * failures reach callers through commerce-runtime's error handling. The response is a
 * receipt of the new inquiry only: a public caller never reads a stored customer back.
 */
fun createInquiryRoute(
    createInquiry: (CreateInquiry.Command) -> Inquiry,
    uiApiKey: UiApiKey,
): ContractRoute =
    "/inquiries" meta {
        operationId = "createInquiry"
        security = uiApiKeySecurity(uiApiKey)
        returningError(
            ErrorCategory.UNAUTHENTICATED,
            "the trusted server-side UI Bearer credential is missing or invalid.",
            "Authentication is required",
        )
        summary = "Record an inquiry"
        description =
            "Records a prospective customer's inquiry. With pricing inputs, prices exactly once and atomically materializes " +
            "a canonical initial Estimate with self-contained financial lines; a plain inquiry creates no Estimate. " +
            "Requested pricing inputs remain inquiry history, not dependencies of the financial snapshot. The " +
            "customer is found by normalized email, or created with the inquiry in the same transaction; an existing " +
            "customer's stored name is never changed. The response is a receipt of the new inquiry alone and " +
            "describes no stored customer. The `Location` response header holds the new inquiry's path, " +
            "`/inquiries/{inquiryId}`, which staff read."
        tags += inquiries
        receiving(createInquiryRequest to exampleRequest)
        returning(Status.CREATED, inquiryReceiptResponse to exampleReceipt, "The recorded inquiry's receipt. `Location` holds its path.")
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "the body is not JSON, lacks a required name, email, ZIP, event date or type, " +
                "has an unknown event type, or a field has the wrong type.",
            "Malformed request: body 'body'",
        )
        returningError(
            ErrorCategory.NOT_FOUND,
            "`pricingInputs` names a catalog revision that does not exist.",
            "Offerings catalog revision r12 was not found",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "a value is invalid: a blank name, an email without `@`, a ZIP code without five digits, an invalid event date, " +
                "or `pricingInputs` cannot " +
                "be priced: they do not fit the catalog revision (for example `TOO_MANY_SELECTIONS`, `UNKNOWN_OFFERING`) " +
                "or Fiona's pricing (for example `INVALID_GUEST_COUNT`, `UNSUPPORTED_DURATION`). Optional `violations` " +
                "expose stable codes; the message is diagnostic.",
            "Email must contain exactly one @ after a non-empty local part",
        )
        returningError(
            ErrorCategory.CONFLICT,
            "a concurrent request created a customer with the same email first; retrying succeeds.",
            "A customer with this email already exists; retry the request",
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
    } bindContract Method.POST to { request: Request ->
        val body = createInquiryRequest(request)
        val command =
            validating {
                CreateInquiry.Command(
                    name = CustomerName.of(body.name),
                    email = Email.of(body.email),
                    message = InquiryMessage.ofOptional(body.message),
                    zipCode = ZipCode.of(body.zipCode),
                    eventDate = EventDate.of(body.eventDate),
                    eventType = body.eventType.toDomain(),
                    pricingInputs =
                        body.pricingInputs?.let {
                            pricingInputs(
                                it.catalogRevision,
                                it.guestCount,
                                it.guestCountIsMinimum,
                                it.durationMinutes,
                                it.selections.map { selection -> selection.category to selection.offerings },
                            )
                        },
                )
            }
        val created = createInquiry(command)
        Response(Status.CREATED)
            .header("Location", "/inquiries/${created.id.value}")
            .with(inquiryReceiptResponse of InquiryReceiptResponse(created.id.value.toString(), created.createdAt.toString()))
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
        staffErrors()
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
    "/inquiries" / inquiryIdPath meta {
        operationId = "getInquiry"
        summary = "Read an inquiry"
        description =
            "The persisted inquiry, the customer who made it, and the configuration submitted with it, which a staff " +
            "estimate can start from. Requires `${FionaPermissions.InquiriesRead.value}`."
        tags += inquiries
        returning(Status.OK, inquiryResponse to exampleInquiry, "The inquiry.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "`inquiryId` is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(
            ErrorCategory.NOT_FOUND,
            "no inquiry has this id.",
            "Inquiry c755f7cd-1e28-4c75-a85f-d066ede7387d was not found",
        )
        staffErrors()
    } bindContract Method.GET to { id: String ->
        access.requirePermission(FionaPermissions.InquiriesRead).then { _: Request ->
            Response(Status.OK).with(inquiryResponse of getInquiry(inquiryId(id)).toResponse())
        }
    }

/** The id in the path segment; one that is not a UUID is reported as the unreadable path value it is. */
private fun inquiryId(segment: String): InquiryId =
    try {
        InquiryId(UUID.fromString(segment))
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(inquiryIdPath.meta), cause = e)
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

private fun InquiryDetails.toResponse() =
    InquiryResponse(
        id = inquiry.id.value.toString(),
        customerId = customer.id.value.toString(),
        name = customer.name.value,
        email = customer.email.value,
        message = inquiry.message?.value,
        createdAt = inquiry.createdAt.toString(),
        pricingInputs = pricingInputs?.toResponse(),
        zipCode = inquiry.zipCode.value,
        eventDate = inquiry.eventDate.value.toString(),
        eventType = InquiryEventType.valueOf(inquiry.eventType.name),
    )

private fun FionasPricingInputs.toResponse() =
    InquiryRequestedPricing(
        catalogRevision = catalogRevision.number,
        guestCount = context.guestCount,
        guestCountIsMinimum = context.guestCountIsMinimum,
        durationMinutes = Math.toIntExact(context.duration.toMinutes()),
        selections = selections.categories.map { block -> PricingSelection(block.category.value, block.offerings.map { it.value }) },
    )

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
