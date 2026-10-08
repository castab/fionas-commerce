package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.authorization.authorizationAdministrationHttpCapability
import io.github.castab.commerce.runtime.authorization.currentPrincipalHttpCapability
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.persistence.ApplicationMigrations
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokenAuthenticator
import io.github.castab.commerce.runtime.session.SessionAuthenticator
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.AllocatePayment
import io.github.castab.fionas.commerce.financial.CreateChangeOrder
import io.github.castab.fionas.commerce.financial.CreateInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.GetDepositRequirement
import io.github.castab.fionas.commerce.financial.GetDepositRequirementHistory
import io.github.castab.fionas.commerce.financial.GetFinancialDocument
import io.github.castab.fionas.commerce.financial.GetFinancialDocumentHistory
import io.github.castab.fionas.commerce.financial.InquiryProposals
import io.github.castab.fionas.commerce.financial.IssueInquiryProposal
import io.github.castab.fionas.commerce.financial.IssueInvoice
import io.github.castab.fionas.commerce.financial.IssueQuote
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryServicePlanRepository
import io.github.castab.fionas.commerce.financial.ListFinancialDocumentPaymentHistories
import io.github.castab.fionas.commerce.financial.ListInquiryFinancialDocuments
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.PreviewInquiryQuote
import io.github.castab.fionas.commerce.financial.QueryFinancialLineages
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
import io.github.castab.fionas.commerce.financial.ReviseInquiryProposalDeposit
import io.github.castab.fionas.commerce.financial.ReviseInquiryQuoteProposal
import io.github.castab.fionas.commerce.financial.SetDepositRequirement
import io.github.castab.fionas.commerce.financial.WithdrawDepositRequirement
import io.github.castab.fionas.commerce.http.BrowserOrigin
import io.github.castab.fionas.commerce.http.FionaAuthRoutes
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.LoginRateLimit
import io.github.castab.fionas.commerce.http.STAFF_SESSION_COOKIE
import io.github.castab.fionas.commerce.http.apiDocs
import io.github.castab.fionas.commerce.http.authorizationTag
import io.github.castab.fionas.commerce.http.fionaApi
import io.github.castab.fionas.commerce.http.fionaServiceAuthentication
import io.github.castab.fionas.commerce.http.staffAdministrationTag
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquirySubmissionRepository
import io.github.castab.fionas.commerce.inquiry.ListInquiries
import io.github.castab.fionas.commerce.inquiry.ManageInquiryFulfillment
import io.github.castab.fionas.commerce.inquiry.ReadInquiryLifecycle
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import io.github.castab.fionas.commerce.inquiry.RecordInquiryCommunication
import io.github.castab.fionas.commerce.staff.BootstrapAdmin
import io.github.castab.fionas.commerce.staff.BootstrapFirstAdmin
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.JdbiCredentialRepository
import io.github.castab.fionas.commerce.staff.Login
import io.github.castab.fionas.commerce.staff.PasswordHasher
import io.github.castab.fionas.commerce.staff.ReadStaffDashboard
import io.github.castab.fionas.commerce.staff.ReadStaffRequest
import io.github.castab.fionas.commerce.staff.SetStaffPassword
import io.github.castab.fionas.commerce.staff.StaffPasswordAuthenticator
import org.http4k.core.then
import java.time.Clock
import java.time.ZoneId
import java.util.Properties

/**
 * The schema Fiona owns: commerce-runtime's migration phase creates it when missing, makes it the
 * default schema of Fiona's migration stream, and keeps `flyway_schema_history` in it.
 */
const val FIONA_MIGRATION_SCHEMA = "fionas"

/**
 * Where Fiona's own migrations live: Fiona's migration stream, applied by commerce-runtime
 * after its own. Never `db/commerce`; the runtime discovers and applies its migrations itself.
 */
const val FIONA_MIGRATION_LOCATION = "classpath:db/fionas"

val FIONA_DEFAULT_EVENT_CALENDAR_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/** Event LocalDate boundaries are Fiona configuration, independent of the server timestamp Clock. */
fun fionaEventCalendarZone(environment: Map<String, String> = System.getenv()): ZoneId =
    environment["FIONAS_EVENT_TIME_ZONE"]?.let(ZoneId::of) ?: FIONA_DEFAULT_EVENT_CALENDAR_ZONE

/**
 * Everything Fiona's contributes to commerce-runtime: the schema and location of its own
 * migrations (never the runtime's) and its routes: the Fiona API contract and its Swagger UI.
 *
 * This is the application's composition root. Repositories and operations are built here
 * with ordinary Kotlin from the runtime's `CommerceRuntimeContext`, so every Fiona write
 * goes through the runtime's single `Transactor`. The runtime's authorization administration
 * (which serves the permission catalog), current-principal and service-token capabilities
 * join the same API contract as Fiona's own routes, all bound to one `AccessControl` over
 * `context.authorization`.
 *
 * Fiona owns no product catalog and no pricing policy. Financial documents are
 * commerce-runtime's `FinancialLedger`, called with the caller's transaction and the exact
 * version the caller reviewed; the lines they record are committed by an authorized actor: the
 * web server's SERVICE principal for a public inquiry's Estimate, a verified staff USER for
 * negotiated terms. Fiona's own repositories store which inquiry owns each lineage, who
 * authored each snapshot's lines, proposals and approved service plans.
 *
 * Every protected route uses one `AccessControl`. It authenticates a staff browser session
 * first and otherwise a SERVICE principal's short-lived access token (both runtime-owned
 * mechanisms), so a request is exactly one principal, USER or SERVICE, authorized by that
 * principal's current roles. A session wins over a token on the same request; nothing merges
 * identities. Fiona's browser Origin policy applies only to login and cookie-carrying unsafe
 * requests, never to a token-only request. Services obtain tokens at the runtime's token
 * endpoint, which Fiona mounts; startup never provisions a service or a credential.
 */
fun fionaApplication(
    clock: Clock = Clock.systemUTC(),
    bootstrap: BootstrapAdmin? = BootstrapAdmin.fromEnvironment(),
    trustedOrigins: Set<String> =
        System
            .getenv("FIONAS_TRUSTED_ORIGINS")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.toSet()
            ?: emptySet(),
    loginRateLimit: LoginRateLimit = LoginRateLimit(),
    eventCalendarZone: ZoneId = fionaEventCalendarZone(),
): ApplicationContributions =
    ApplicationContributions(
        migrations =
            ApplicationMigrations(
                schema = FIONA_MIGRATION_SCHEMA,
                locations = listOf(FIONA_MIGRATION_LOCATION),
            ),
        permissionDefinitions = FionaPermissions.definitions,
        routes = { context ->
            val customers = JdbiCustomerRepository()
            val inquiries = JdbiInquiryRepository()
            val credentials = JdbiCredentialRepository()
            val hasher = PasswordHasher()
            BootstrapFirstAdmin(context.transactor, context.authorization, credentials, hasher, clock).invoke(bootstrap)
            val cookie = SessionCookie(STAFF_SESSION_COOKIE)
            val origin = BrowserOrigin(trustedOrigins, cookie)
            // Session first: a request carrying a staff session stays that USER even if it also carries a token.
            val authenticate =
                authentication(
                    SessionAuthenticator(context.sessions, cookie),
                    ServiceAccessTokenAuthenticator(context.serviceAccessTokens),
                )
            val access = AccessControl(origin.filter.then(authenticate), context.authorization)
            val auth = FionaAuthRoutes(context.sessions, cookie, access, origin.filter, loginRateLimit)
            // Generic financial persistence is commerce-runtime's ledger; Fiona stores only its context.
            val ledger = context.financialLedger
            val documentOwners = JdbiInquiryFinancialDocumentRepository()
            val authorship = JdbiFinancialDocumentAuthorshipRepository()
            val communications = JdbiInquiryCommunicationRepository()
            val fulfillment = JdbiInquiryFulfillmentRepository()
            val lifecycle = ReadInquiryLifecycle(ledger, documentOwners, fulfillment)
            val manageFulfillment = ManageInquiryFulfillment(context.transactor, inquiries, documentOwners, ledger, fulfillment, clock)
            val materialize = MaterializeInquiryFinancialDocument(ledger, documentOwners, authorship)
            val createDocument =
                CreateInquiryFinancialDocument(context.transactor, inquiries, ledger, documentOwners, authorship, materialize, clock)
            val getInquiry = GetInquiry(context.transactor, customers, inquiries, lifecycle)
            val getFinancialDocument = GetFinancialDocument(context.transactor, ledger, documentOwners, authorship)
            val paymentHistories = ListFinancialDocumentPaymentHistories(context.transactor, ledger, documentOwners)
            val proposalHistory = JdbiInquiryProposalRepository()
            val servicePlans = JdbiInquiryServicePlanRepository()
            val proposals = InquiryProposals(ledger, documentOwners, authorship, proposalHistory, servicePlans, clock)
            val operations =
                FionaOperations(
                    readStaffRequest = ReadStaffRequest(
                        context.transactor,
                        getInquiry,
                        getFinancialDocument,
                        ledger,
                        proposalHistory,
                        paymentHistories,
                        servicePlans,
                    )::invoke,
                    issueInquiryProposal = IssueInquiryProposal(context.transactor, proposals)::invoke,
                    previewInquiryQuote = PreviewInquiryQuote(context.transactor, ledger, documentOwners, proposalHistory)::invoke,
                    reviseInquiryQuoteProposal = ReviseInquiryQuoteProposal(context.transactor, proposals)::invoke,
                    reviseInquiryProposalDeposit = ReviseInquiryProposalDeposit(context.transactor, proposals)::invoke,
                    readStaffDashboard = ReadStaffDashboard(
                        context.transactor,
                        ReadInquiryOperationalStates(context.transactor, inquiries, documentOwners, ledger, fulfillment),
                        inquiries,
                        customers,
                        clock,
                        communications,
                        eventCalendarZone,
                    )::invoke,
                    createInquiry = CreateInquiry(
                        context.transactor,
                        customers,
                        inquiries,
                        JdbiInquirySubmissionRepository(),
                        clock,
                        materialize,
                    )::invoke,
                    listInquiries = ListInquiries(context.transactor, customers, inquiries)::invoke,
                    getInquiry = getInquiry::invoke,
                    acknowledgeInquiryCommunication = RecordInquiryCommunication(
                        context.transactor,
                        communications,
                        clock,
                    )::acknowledge,
                    markInquiryServed = manageFulfillment::markServed,
                    closeInquiry = manageFulfillment::close,
                    createInquiryEstimate = createDocument::invoke,
                    createInquiryFinancialDocument = createDocument::invoke,
                    listInquiryFinancialDocuments =
                        ListInquiryFinancialDocuments(context.transactor, inquiries, ledger, documentOwners, authorship)::invoke,
                    getFinancialDocument = getFinancialDocument::invoke,
                    getFinancialDocumentHistory =
                        GetFinancialDocumentHistory(context.transactor, ledger, documentOwners, authorship)::invoke,
                    getDepositRequirement = GetDepositRequirement(context.transactor, ledger, documentOwners)::invoke,
                    getDepositRequirementHistory = GetDepositRequirementHistory(context.transactor, ledger, documentOwners)::invoke,
                    setDepositRequirement = SetDepositRequirement(
                        context.transactor,
                        ledger,
                        documentOwners,
                        authorship,
                        proposalHistory,
                    )::invoke,
                    withdrawDepositRequirement = WithdrawDepositRequirement(
                        context.transactor,
                        ledger,
                        documentOwners,
                        proposalHistory,
                    )::invoke,
                    queryFinancialLineages = QueryFinancialLineages(context.transactor, ledger, documentOwners)::invoke,
                    issueQuote = IssueQuote(context.transactor, ledger, documentOwners, authorship)::invoke,
                    issueInvoice = IssueInvoice(context.transactor, ledger, documentOwners, authorship)::invoke,
                    createChangeOrder = CreateChangeOrder(
                        context.transactor,
                        ledger,
                        documentOwners,
                        authorship,
                        fulfillment,
                        clock,
                    )::invoke,
                    recordPayment = RecordDocumentPayment(
                        context.transactor,
                        ledger,
                        documentOwners,
                        authorship,
                        clock,
                        proposalHistory,
                    )::invoke,
                    listFinancialDocumentPayments =
                        paymentHistories::invoke,
                    listUnappliedPayments = ledger::unappliedPayments,
                    recordStandalonePayment = RecordPayment(context.transactor, ledger, clock)::invoke,
                    allocatePayment = AllocatePayment(context.transactor, ledger, documentOwners, authorship, clock)::invoke,
                    recordRefund = RecordRefund(context.transactor, ledger, clock)::invoke,
                    login = Login(
                        StaffPasswordAuthenticator(context.authorization, context.transactor, credentials, hasher),
                        context.sessions,
                    )::invoke,
                    currentUser = context.authorization::getUser,
                    currentPermissions = context.authorization.permissionResolver::permissionsFor,
                    setStaffPassword = SetStaffPassword(context.authorization, context.transactor, credentials, hasher, clock)::invoke,
                )
            val authorizationAdmin =
                authorizationAdministrationHttpCapability(context, access, "/admin/access", setOf(staffAdministrationTag))
            // The request's principal, USER or SERVICE, resolved through the same AccessControl and catalog.
            // The permission catalog is served once, by the administration capability: commerce-runtime
            // 0.0.23's standalone catalog route has the same fixed operationId, so it is not also mounted.
            val currentPrincipal = currentPrincipalHttpCapability(access, "/authorization/me", setOf(authorizationTag))
            val serviceAuthentication = fionaServiceAuthentication(context)
            listOf(
                fionaApi(operations, authorizationAdmin, currentPrincipal, serviceAuthentication, fionaVersion(), auth),
                apiDocs(),
            )
        },
    )

private const val BUILD_INFO = "/fionas-commerce.properties"

/**
 * The version of this build of fionas-commerce: the Gradle project version, which the
 * build writes into `fionas-commerce.properties`. It is the OpenAPI document's
 * `info.version`.
 */
fun fionaVersion(): String {
    val version =
        object {}.javaClass.getResourceAsStream(BUILD_INFO)?.use { Properties().apply { load(it) }.getProperty("version") }
    check(version != null && !version.contains("\${")) { "$BUILD_INFO has no version; it is written by the Gradle build" }
    return version
}
