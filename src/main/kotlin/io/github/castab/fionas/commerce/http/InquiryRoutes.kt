package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryDetails
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryMessage
import kotlinx.serialization.Serializable
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.Path
import org.http4k.lens.uuid
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes

/** The body of `POST /inquiries`. */
@Serializable
data class CreateInquiryRequest(
    val name: String,
    val email: String,
    val message: String? = null,
)

/** An inquiry as the HTTP API represents it. */
@Serializable
data class InquiryResponse(
    val id: String,
    val customerId: String,
    val name: String,
    val email: String,
    val message: String? = null,
    val createdAt: String,
)

private val createInquiryRequest = jsonBody(CreateInquiryRequest.serializer())
private val inquiryResponse = jsonBody(InquiryResponse.serializer())
private val inquiryId = Path.uuid().of("inquiryId")

/**
 * `POST /inquiries` and `GET /inquiries/{inquiryId}`. The routes only translate between
 * transport and application values; the operations do the work. Failures reach callers
 * through commerce-runtime's error handling.
 */
fun inquiryRoutes(
    createInquiry: CreateInquiry,
    getInquiry: GetInquiry,
): RoutingHttpHandler =
    routes(
        "/inquiries" bind Method.POST to { request ->
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
        },
        "/inquiries/{inquiryId}" bind Method.GET to { request ->
            val id = InquiryId(inquiryId(request))
            Response(Status.OK).with(inquiryResponse of getInquiry(id).toResponse())
        },
    )

private fun InquiryDetails.toResponse() =
    InquiryResponse(
        id = inquiry.id.value.toString(),
        customerId = customer.id.value.toString(),
        name = customer.name.value,
        email = customer.email.value,
        message = inquiry.message?.value,
        createdAt = inquiry.createdAt.toString(),
    )
