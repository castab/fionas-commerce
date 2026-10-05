package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.authorization.AuthorizationAdministrationHttpCapability
import io.github.castab.commerce.runtime.authorization.CurrentPrincipalHttpCapability
import io.github.castab.commerce.runtime.financial.DepositRequirementVersion
import io.github.castab.commerce.runtime.financial.FinancialLineageView
import io.github.castab.commerce.runtime.financial.PaymentHistory
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.offering.OfferingsHttpCapability
import io.github.castab.commerce.runtime.serviceauth.ServiceAuthenticationHttpCapability
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.financial.AllocatePayment
import io.github.castab.fionas.commerce.financial.AllocatedPayment
import io.github.castab.fionas.commerce.financial.CreateInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentHistory
import io.github.castab.fionas.commerce.financial.InquiryFinancialLineage
import io.github.castab.fionas.commerce.financial.QueryFinancialLineages
import io.github.castab.fionas.commerce.financial.ReconciledRefund
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
import io.github.castab.fionas.commerce.financial.RecordedPayment
import io.github.castab.fionas.commerce.financial.SetDepositRequirement
import io.github.castab.fionas.commerce.financial.WithdrawDepositRequirement
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquiryDetails
import io.github.castab.fionas.commerce.inquiry.InquiryForm
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryLifecycle
import io.github.castab.fionas.commerce.inquiry.InquiryPage
import io.github.castab.fionas.commerce.inquiry.ListInquiries
import io.github.castab.fionas.commerce.inquiry.ManageInquiryFulfillment
import io.github.castab.fionas.commerce.inquiry.RecordInquiryCommunication
import io.github.castab.fionas.commerce.offering.EstimatePreview
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.staff.SecretPassword
import io.github.castab.fionas.commerce.staff.StaffDashboard
import io.github.castab.fionas.commerce.staff.StaffRequest
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
import java.util.UUID

/** Where the OpenAPI document of the Fiona API is served. */
const val OPENAPI_PATH = "/openapi.json"

/** Where Swagger UI for the Fiona API is served. */
const val API_DOCS_PATH = "/docs"

/**
 * The operations the Fiona API calls, one function per operation. The composition root
 * supplies the real operations; rendering the contract never calls any of them.
 */
class FionaOperations(
    val acknowledgeInquiryCommunication: (RecordInquiryCommunication.Acknowledge) -> Unit,
    val readStaffRequest: (InquiryId) -> StaffRequest,
    val readStaffDashboard: () -> StaffDashboard,
    val createInquiry: (CreateInquiry.Command) -> Inquiry,
    val listInquiries: (ListInquiries.Command) -> InquiryPage,
    val getInquiry: (InquiryId) -> InquiryDetails,
    val markInquiryServed: (ManageInquiryFulfillment.Command) -> InquiryLifecycle,
    val closeInquiry: (ManageInquiryFulfillment.Command) -> InquiryLifecycle,
    val getInquiryForm: () -> InquiryForm,
    val previewEstimate: (FionasPricingInputs) -> EstimatePreview,
    val createInquiryEstimate: (InquiryId, FionasPricingInputs) -> InquiryFinancialDocument,
    val createInquiryFinancialDocument: (CreateInquiryFinancialDocument.Command) -> InquiryFinancialDocument,
    val listInquiryFinancialDocuments: (InquiryId) -> List<InquiryFinancialDocument>,
    val getFinancialDocument: (UUID) -> InquiryFinancialDocument,
    val getFinancialDocumentHistory: (UUID) -> InquiryFinancialDocumentHistory,
    val getDepositRequirement: (UUID) -> FinancialLineageView,
    val getDepositRequirementHistory: (UUID) -> List<DepositRequirementVersion>,
    val setDepositRequirement: (SetDepositRequirement.Command) -> FinancialLineageView,
    val withdrawDepositRequirement: (WithdrawDepositRequirement.Command) -> DepositRequirementVersion,
    val queryFinancialLineages: (QueryFinancialLineages.Command) -> List<InquiryFinancialLineage>,
    val issueQuote: (UUID, Version) -> InquiryFinancialDocument,
    val issueInvoice: (UUID, Version) -> InquiryFinancialDocument,
    val createChangeOrder: (UUID, Version, FionasPricingInputs) -> InquiryFinancialDocument,
    val recordPayment: (RecordDocumentPayment.Command) -> RecordedPayment,
    val listFinancialDocumentPayments: (UUID) -> List<PaymentHistory>,
    val listUnappliedPayments: () -> List<PaymentHistory>,
    val recordStandalonePayment: (RecordPayment.Command) -> PaymentRecord,
    val allocatePayment: (AllocatePayment.Command) -> AllocatedPayment,
    val recordRefund: (RecordRefund.Command) -> ReconciledRefund,
    val login: (String, SecretPassword) -> IssuedSession?,
    val currentUser: (UserId) -> User?,
    val currentPermissions: (UserId) -> Set<PermissionKey>,
    val setStaffPassword: (UserId, SecretPassword) -> Unit,
)

class FionaAuthRoutes(
    val sessions: SessionManager,
    val cookie: SessionCookie,
    val access: AccessControl,
    val origin: Filter,
    val loginRateLimit: LoginRateLimit = LoginRateLimit(),
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
        acknowledgeInquiryCommunicationRoute(operations.acknowledgeInquiryCommunication, auth.access),
        readStaffRequestRoute(operations.readStaffRequest, auth.access),
        readStaffDashboardRoute(operations.readStaffDashboard, auth.access),
        getInquiryFormRoute(operations.getInquiryForm, auth.access),
        createInquiryRoute(operations.createInquiry, auth.access),
        listInquiriesRoute(operations.listInquiries, auth.access),
        getInquiryRoute(operations.getInquiry, auth.access),
        inquiryFulfillmentRoute("served", "markInquiryServed", operations.markInquiryServed, auth.access),
        inquiryFulfillmentRoute("close", "closeInquiry", operations.closeInquiry, auth.access),
        previewEstimateRoute(operations.previewEstimate, auth.access),
        createInquiryEstimateRoute(operations.createInquiryEstimate, auth.access),
        createInquiryFinancialDocumentRoute(operations.createInquiryFinancialDocument, auth.access),
        listInquiryFinancialDocumentsRoute(operations.listInquiryFinancialDocuments, auth.access),
        getFinancialDocumentRoute(operations.getFinancialDocument, auth.access),
        getFinancialDocumentHistoryRoute(operations.getFinancialDocumentHistory, auth.access),
        getDepositRequirementRoute(operations.getDepositRequirement, auth.access),
        getDepositRequirementHistoryRoute(operations.getDepositRequirementHistory, auth.access),
        setDepositRequirementRoute(operations.setDepositRequirement, auth.access),
        withdrawDepositRequirementRoute(operations.withdrawDepositRequirement, auth.access),
        queryFinancialLineagesRoute(operations.queryFinancialLineages, auth.access),
        issueQuoteRoute(operations.issueQuote, auth.access),
        issueInvoiceRoute(operations.issueInvoice, auth.access),
        createChangeOrderRoute(operations.createChangeOrder, auth.access),
        recordPaymentRoute(operations.recordPayment, auth.access),
        listFinancialDocumentPaymentsRoute(operations.listFinancialDocumentPayments, auth.access),
        listUnappliedPaymentsRoute(operations.listUnappliedPayments, auth.access),
        recordStandalonePaymentRoute(operations.recordStandalonePayment, auth.access),
        allocatePaymentRoute(operations.allocatePayment, auth.access),
        recordRefundRoute(operations.recordRefund, auth.access),
        loginRoute(operations.login, auth.cookie, auth.access, auth.origin, auth.loginRateLimit),
        logoutRoute(auth.sessions, auth.cookie, auth.access, auth.origin),
        currentUserRoute(operations.currentUser, operations.currentPermissions, auth.access),
        setStaffPasswordRoute(operations.setStaffPassword, auth.access),
    )

/**
 * The Fiona API: one http4k contract of [fionaApiRoutes] and the contract routes of the
 * commerce-runtime capabilities Fiona mounts, each implemented and described by the runtime:
 * Fiona's Offerings catalog ([offerings]), staff and service access administration
 * ([authorizationAdmin], which also serves the one permission catalog route), the
 * request's principal ([currentPrincipal]), and the service token endpoint
 * ([serviceAuthentication]). The contract also serves its own OpenAPI document at
 * [OPENAPI_PATH], rendered from those same routes. [version] is the document's
 * `info.version`.
 *
 * Only the route handlers read request bodies, once (no pre-flight extraction), and
 * failures reach commerce-runtime's error handling as they did before the API was a
 * contract.
 */
fun fionaApi(
    operations: FionaOperations,
    offerings: OfferingsHttpCapability,
    authorizationAdmin: AuthorizationAdministrationHttpCapability,
    currentPrincipal: CurrentPrincipalHttpCapability,
    serviceAuthentication: ServiceAuthenticationHttpCapability,
    version: String,
    auth: FionaAuthRoutes,
): RoutingHttpHandler {
    val apiRoutes =
        fionaApiRoutes(operations, auth) +
            offerings.contractRoutes +
            authorizationAdmin.contractRoutes +
            currentPrincipal.contractRoutes +
            serviceAuthentication.contractRoutes
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
