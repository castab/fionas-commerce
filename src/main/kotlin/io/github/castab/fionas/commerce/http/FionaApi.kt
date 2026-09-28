package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.offering.OfferingsHttpCapability
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryDetails
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.EstimatePreview
import io.github.castab.fionas.commerce.offering.PreviewEstimate
import io.github.castab.fionas.commerce.staff.SecretPassword
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.Root
import org.http4k.contract.contract
import org.http4k.contract.ui.swagger.swaggerUiWebjar
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes

/** Where the OpenAPI document of the Fiona API is served. */
const val OPENAPI_PATH = "/openapi.json"

/** Where Swagger UI for the Fiona API is served. */
const val API_DOCS_PATH = "/docs"

/**
 * The operations the Fiona API calls, one function per operation. The composition root
 * supplies the real operations; rendering the contract never calls any of them.
 */
class FionaOperations(
    val createInquiry: (CreateInquiry.Command) -> InquiryDetails,
    val getInquiry: (InquiryId) -> InquiryDetails,
    val previewEstimate: (PreviewEstimate.Command) -> EstimatePreview,
    val login: (String, SecretPassword) -> IssuedSession?,
    val currentUser: (UserId) -> User?,
)

class FionaAuthRoutes(
    val sessions: SessionManager,
    val cookie: SessionCookie,
    val access: AccessControl,
    val origin: Filter,
)

/**
 * Every externally supported Fiona endpoint. Each is a [ContractRoute] carrying its own
 * OpenAPI description, so the running API and its document cannot drift apart.
 */
fun fionaApiRoutes(
    operations: FionaOperations,
    auth: FionaAuthRoutes,
): List<ContractRoute> =
    listOf(
        createInquiryRoute(operations.createInquiry),
        getInquiryRoute(operations.getInquiry),
        previewEstimateRoute(operations.previewEstimate),
        loginRoute(operations.login, auth.cookie, auth.access, auth.origin),
        logoutRoute(auth.sessions, auth.cookie, auth.access),
        currentUserRoute(operations.currentUser, auth.access),
    )

/**
 * The Fiona API: one http4k contract of [fionaApiRoutes] and the contract routes of Fiona's
 * Offerings catalog, which commerce-runtime's [offerings] capability implements and
 * describes. The contract also serves its own OpenAPI document at [OPENAPI_PATH], rendered
 * from those same routes. [version] is the document's `info.version`.
 *
 * Only the route handlers read request bodies, once (no pre-flight extraction), and
 * failures reach commerce-runtime's error handling as they did before the API was a
 * contract.
 */
fun fionaApi(
    operations: FionaOperations,
    offerings: OfferingsHttpCapability,
    version: String,
    auth: FionaAuthRoutes,
): RoutingHttpHandler {
    val apiRoutes = fionaApiRoutes(operations, auth) + offerings.contractRoutes
    return routes(
        undeclaredMethods(apiRoutes),
        contract {
            renderer = fionaOpenApi(version)
            descriptionPath = OPENAPI_PATH
            preFlightExtraction = PreFlightExtraction.IgnoreBody
            routes += apiRoutes
        },
    )
}

/**
 * Swagger UI for the Fiona API, reading [OPENAPI_PATH]: `/docs` redirects to
 * `/docs/index.html`. Served from the Swagger UI WebJar in the application's own jar,
 * never from a CDN. Documentation plumbing, not an API endpoint.
 */
fun apiDocs(): RoutingHttpHandler =
    API_DOCS_PATH bind
        swaggerUiWebjar {
            pageTitle = API_TITLE
            url = OPENAPI_PATH
        }

/**
 * `405` without a body for a method an API path does not declare, OPTIONS included, as
 * the plain http4k routes the API had before answered. A contract alone answers `404` for
 * such a method and `200` for OPTIONS. Derived from the contract's own routes, so it never
 * describes an endpoint. It must precede the contract: for OPTIONS both match, and http4k
 * takes the first of equally good matches.
 */
private fun undeclaredMethods(apiRoutes: List<ContractRoute>): RoutingHttpHandler =
    routes(
        apiRoutes
            .map { it.describeFor(Root) }
            .distinct()
            .map { path -> path bind Method.OPTIONS to { Response(Status.METHOD_NOT_ALLOWED) } },
    )
