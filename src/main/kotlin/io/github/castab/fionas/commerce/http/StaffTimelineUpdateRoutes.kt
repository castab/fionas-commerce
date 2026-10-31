package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.http.sseHandshake
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Header
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.string
import org.http4k.routing.RoutingSseHandler
import org.http4k.routing.path
import org.http4k.routing.sse.bind
import org.http4k.sse.SseResponse

internal const val STAFF_UPDATES_PATH = "/staff/requests/{inquiryId}/updates"

internal fun AccessControl.timelineUpdatesAccess() =
    requirePermission(FionaPermissions.InquiriesRead)
        .then(requirePermission(CommercePermissions.FinancialDocumentRead))
        .then(requirePermission(FionaPermissions.CommunicationsRead))
        .then(requireStaffTimelineReader)

/** Native transport and HTTP metadata use the same authorization and resource preparation. */
internal fun staffTimelineUpdatesRoute(
    prepare: (InquiryId) -> Boolean,
    access: AccessControl,
): ContractRoute =
    "/staff/requests" / inquiryDetailIdPath / "updates" meta {
        operationId = "subscribeStaffRequestUpdates"
        summary = "Subscribe to staff timeline invalidation hints"
        description = "Requires a human USER session and fionas.inquiries.read, commerce.financial-document.read, " +
            "and fionas.communications.read. Send Accept: text/event-stream. Enabled with FIONAS_TIMELINE_UPDATES_ENABLED; " +
            "disabled deployments return 503. Each connection emits initial timeline.changed, including revision 0. " +
            "Event id is the opaque decimal revision; data contains only inquiryId and revision strings. " +
            "Changes coalesce; Last-Event-ID is ignored and provides no replay. Fetch the authoritative timeline after every hint. " +
            "Maximum lifetime 60s, keepalive comments every 20s, retry 3000ms; credentials and permissions rechecked every second " +
            "and before change events. Expiry/revocation, read failure, disconnect or shutdown closes the stream. " +
            "Capacity exhaustion after commitment closes without synchronization. Cache-Control: no-store, no-transform; " +
            "X-Accel-Buffering: no. Proxies must disable buffering/compression and flush promptly."
        tags += Tag("Staff requests", "Coherent inquiry and canonical financial detail for staff request review.")
        security = staffSessionSecurity
        returning(
            Status.OK,
            Body.string(ContentType("text/event-stream")).toLens() to
                "event: timeline.changed\nid: 0\ndata: {\"inquiryId\":\"00000000-0000-0000-0000-000000000000\",\"revision\":\"0\"}\n\n",
        )
        returningError(ErrorCategory.MALFORMED_REQUEST, "Invalid inquiry UUID or missing SSE Accept header.", "Malformed request")
        returningError(ErrorCategory.UNAUTHENTICATED, "Missing or invalid staff session.", "Unauthenticated")
        returningError(ErrorCategory.FORBIDDEN, "Requires a USER and all three read permissions.", "Not permitted")
        returningError(ErrorCategory.NOT_FOUND, "Inquiry does not exist.", "Inquiry was not found")
        returningError(ErrorCategory.INTERNAL_FAILURE, "Unexpected preparation failure.", INTERNAL_FAILURE)
        returning(
            Status.SERVICE_UNAVAILABLE,
            updatesError to unavailableUpdates,
            description = "`timeline_updates_unavailable`: Updates disabled.",
        )
    } bindContract Method.GET to { id: String, _: String ->
        access.timelineUpdatesAccess().then { _: Request ->
            if (!prepare(inquiryId(id))) return@then unavailableUpdatesResponse()
            // Native GET/Accept dispatch precedes this HTTP contract. Never fake a streaming response.
            throw LensFailure(Invalid(Header.required("Accept").meta))
        }
    }

internal fun nativeStaffTimelineUpdatesRoute(
    prepare: (InquiryId) -> Boolean,
    access: AccessControl,
    updates: () -> StaffTimelineUpdates,
): RoutingSseHandler =
    STAFF_UPDATES_PATH bind
        sseHandshake(
            access.timelineUpdatesAccess().then(
                Filter { next ->
                    { request ->
                        val id = inquiryId(checkNotNull(request.path("inquiryId")))
                        if (prepare(id)) next(request) else unavailableUpdatesResponse()
                    }
                },
            ),
        ) { request ->
            val id = inquiryId(checkNotNull(request.path("inquiryId")))
            val principal = authenticatedPrincipal(request)
            // A new request deliberately has no cached authenticatedPrincipal; every check resolves the original credential.
            val credentialRequest = Request(Method.GET, request.uri).headers(request.headers)
            val revalidate =
                access.timelineUpdatesAccess().then { fresh: Request ->
                    Response(if (authenticatedPrincipal(fresh) == principal) Status.OK else Status.FORBIDDEN)
                }
            SseResponse(headers = listOf("Cache-Control" to "no-store, no-transform", "X-Accel-Buffering" to "no")) { stream ->
                updates().open(stream, id) { revalidate(credentialRequest).status == Status.OK }
            }
        }

private val updatesError = jsonBody(ErrorResponse.serializer())
private val unavailableUpdates = ErrorResponse("timeline_updates_unavailable", "Timeline updates are disabled")

private fun unavailableUpdatesResponse() =
    Response(Status.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store")
        .with(updatesError of unavailableUpdates)
