package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryDetails
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
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
)

/** An inquiry as the HTTP API represents it. */
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
)

private val createInquiryRequest = jsonBody(CreateInquiryRequest.serializer())
private val inquiryResponse = jsonBody(InquiryResponse.serializer())

// A plain string for the contract: a contract treats a path value its lens rejects as an
// unmatched route (404), while an id that is not a UUID is a malformed request (400).
private val inquiryIdPath =
    Path.of("inquiryId", "The inquiry's id.", mapOf("schema" to mapOf("format" to "uuid")))

private val inquiries = Tag("Inquiries", "A prospective customer's request to Fiona's, before any booking exists.")

private val exampleRequest =
    CreateInquiryRequest(name = "Jane Doe", email = "jane@example.com", message = "Ice cream service for a birthday.")
private val exampleInquiry =
    InquiryResponse(
        id = "c755f7cd-1e28-4c75-a85f-d066ede7387d",
        customerId = "602df298-8d54-45b6-a40c-bbf80949a3b8",
        name = "Jane Doe",
        email = "jane@example.com",
        message = "Ice cream service for a birthday.",
        createdAt = "2026-09-26T21:19:39.321012Z",
    )

/**
 * `POST /inquiries`: records an inquiry, establishing its customer. The route only
 * translates between transport and application values; [createInquiry] does the work, and
 * failures reach callers through commerce-runtime's error handling.
 */
fun createInquiryRoute(createInquiry: (CreateInquiry.Command) -> InquiryDetails): ContractRoute =
    "/inquiries" meta {
        operationId = "createInquiry"
        summary = "Record an inquiry"
        description =
            "Records a prospective customer's inquiry. The customer is found by normalized email, or created with the " +
            "inquiry in the same transaction; an existing customer's stored name is never changed. The `Location` " +
            "response header holds the new inquiry's path, `/inquiries/{inquiryId}`."
        tags += inquiries
        receiving(createInquiryRequest to exampleRequest)
        returning(Status.CREATED, inquiryResponse to exampleInquiry, "The recorded inquiry. `Location` holds its path.")
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "the body is not JSON, or lacks `name` or `email`, or a field has the wrong type.",
            "Malformed request: body 'body'",
        )
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "a value is invalid, for example a blank name or an email address without `@`.",
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
                )
            }
        val created = createInquiry(command)
        Response(Status.CREATED)
            .header("Location", "/inquiries/${created.inquiry.id.value}")
            .with(inquiryResponse of created.toResponse())
    }

/** `GET /inquiries/{inquiryId}`: the persisted inquiry, as [getInquiry] reads it. */
fun getInquiryRoute(getInquiry: (InquiryId) -> InquiryDetails): ContractRoute =
    "/inquiries" / inquiryIdPath meta {
        operationId = "getInquiry"
        summary = "Read an inquiry"
        description = "The persisted inquiry and the customer who made it."
        tags += inquiries
        returning(Status.OK, inquiryResponse to exampleInquiry, "The inquiry.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "`inquiryId` is not a UUID.", "Malformed request: path 'inquiryId'")
        returningError(
            ErrorCategory.NOT_FOUND,
            "no inquiry has this id.",
            "Inquiry c755f7cd-1e28-4c75-a85f-d066ede7387d was not found",
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
    } bindContract Method.GET to { id: String ->
        { _: Request -> Response(Status.OK).with(inquiryResponse of getInquiry(inquiryId(id)).toResponse()) }
    }

/** The id in the path segment; one that is not a UUID is reported as the unreadable path value it is. */
private fun inquiryId(segment: String): InquiryId =
    try {
        InquiryId(UUID.fromString(segment))
    } catch (e: IllegalArgumentException) {
        throw LensFailure(Invalid(inquiryIdPath.meta), cause = e)
    }

private fun InquiryDetails.toResponse() =
    InquiryResponse(
        id = inquiry.id.value.toString(),
        customerId = customer.id.value.toString(),
        name = customer.name.value,
        email = customer.email.value,
        message = inquiry.message?.value,
        createdAt = inquiry.createdAt.toString(),
    )
