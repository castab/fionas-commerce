package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.authorization.authorizationAdministrationHttpCapability
import io.github.castab.commerce.runtime.authorization.currentPrincipalHttpCapability
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.offering.GetOfferingsCatalog
import io.github.castab.commerce.runtime.offering.offeringsHttpCapability
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
import io.github.castab.fionas.commerce.financial.IssueInvoice
import io.github.castab.fionas.commerce.financial.IssueQuote
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.ListFinancialDocumentPaymentHistories
import io.github.castab.fionas.commerce.financial.ListInquiryFinancialDocuments
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.QueryFinancialLineages
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
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
import io.github.castab.fionas.commerce.inquiry.GetInquiryForm
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryCommunicationRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquirySubmissionRepository
import io.github.castab.fionas.commerce.inquiry.ListInquiries
import io.github.castab.fionas.commerce.inquiry.ManageInquiryFulfillment
import io.github.castab.fionas.commerce.inquiry.PublicInquiryPricing
import io.github.castab.fionas.commerce.inquiry.ReadInquiryLifecycle
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import io.github.castab.fionas.commerce.inquiry.RecordInquiryCommunication
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.PreviewEstimate
import io.github.castab.fionas.commerce.offering.fionaOfferingsBinding
import io.github.castab.fionas.commerce.staff.BootstrapAdmin
import io.github.castab.fionas.commerce.staff.BootstrapFirstAdmin
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.JdbiCredentialRepository
import io.github.castab.fionas.commerce.staff.Login
import io.github.castab.fionas.commerce.staff.PasswordHasher
import io.github.castab.fionas.commerce.staff.ReadStaffDashboard
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

/** Event LocalDate boundaries are Fiona configuration, independent of the server timestamp Clock. */
fun fionaEventCalendarZone(environment: Map<String, String> = System.getenv()): ZoneId =
    ZoneId.of(environment["FIONAS_EVENT_TIME_ZONE"] ?: "America/Los_Angeles")

/**
 * Everything Fiona's contributes to commerce-runtime: the schema and location of its own
 * migrations (never the runtime's) and its routes: the Fiona API contract and its Swagger UI.
 *
 * This is the application's composition root. Repositories and operations are built here
 * with ordinary Kotlin from the runtime's `CommerceRuntimeContext`, so every Fiona write
 * goes through the runtime's single `Transactor`. Fiona's Offerings catalog is
 * commerce-runtime's Offerings capability bound to Fiona's catalog; its contract routes join
 * the same API contract as Fiona's own, as do the runtime's authorization administration
 * (which serves the permission catalog) and current-principal capabilities, all bound to one
 * `AccessControl` over `context.authorization`. Estimate previews read the current catalog
 * through the runtime's own `GetOfferingsCatalog`, reject stale inputs, and price it with Fiona's
 * [FionasOfferingsEngine]. Persisted financial documents are commerce-runtime's
 * `FinancialLedger`, called with the caller's transaction; Fiona's own repositories store
 * only which inquiry owns each lineage and the pricing inputs of each snapshot, and the
 * operations that write them price from the runtime's snapshot read in that same transaction.
 * An inquiry's requested pricing inputs are checked with the same pricing, in the transaction
 * that records the inquiry.
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
            // Fiona's pricing, over the current catalog read in the caller's transaction.
            val pricing =
                FionasPricing(FionasOfferingsEngine(FIONAS_PRICING_POLICY), context.offeringsSnapshotRepository::retrieveLatestVersion)
            // Generic financial persistence is commerce-runtime's ledger; Fiona stores only its context.
            val ledger = context.financialLedger
            val documentOwners = JdbiInquiryFinancialDocumentRepository()
            val pricingSources = JdbiFinancialDocumentPricingRepository()
            val communications = JdbiInquiryCommunicationRepository()
            val fulfillment = JdbiInquiryFulfillmentRepository()
            val lifecycle = ReadInquiryLifecycle(ledger, documentOwners, fulfillment)
            val manageFulfillment = ManageInquiryFulfillment(context.transactor, inquiries, documentOwners, ledger, fulfillment, clock)
            val materialize = MaterializeInquiryFinancialDocument(ledger, documentOwners, clock)
            val createDocument =
                CreateInquiryFinancialDocument(
                    context.transactor,
                    inquiries,
                    ledger,
                    documentOwners,
                    pricingSources,
                    pricing,
                    clock,
                    materialize = materialize,
                )
            val operations =
                FionaOperations(
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
                        PublicInquiryPricing(pricing),
                        clock,
                        materialize,
                    )::invoke,
                    listInquiries = ListInquiries(context.transactor, customers, inquiries)::invoke,
                    getInquiry = GetInquiry(context.transactor, customers, inquiries, lifecycle)::invoke,
                    acknowledgeInquiryCommunication = RecordInquiryCommunication(
                        context.transactor,
                        inquiries,
                        communications,
                        clock,
                    )::acknowledge,
                    markInquiryServed = manageFulfillment::markServed,
                    closeInquiry = manageFulfillment::close,
                    getInquiryForm = GetInquiryForm(
                        GetOfferingsCatalog(context.transactor, context.offeringsSnapshotRepository)::invoke,
                    )::invoke,
                    previewEstimate =
                        PreviewEstimate(
                            getCatalog = GetOfferingsCatalog(context.transactor, context.offeringsSnapshotRepository)::invoke,
                            pricing = pricing,
                        )::invoke,
                    createInquiryEstimate =
                        { inquiryId, inputs ->
                            createDocument(
                                CreateInquiryFinancialDocument.Command(inquiryId, CreateInquiryFinancialDocument.Stage.ESTIMATE, inputs),
                            )
                        },
                    createInquiryFinancialDocument = createDocument::invoke,
                    listInquiryFinancialDocuments =
                        ListInquiryFinancialDocuments(context.transactor, inquiries, ledger, documentOwners, pricingSources)::invoke,
                    getFinancialDocument = GetFinancialDocument(context.transactor, ledger, documentOwners, pricingSources)::invoke,
                    getFinancialDocumentHistory =
                        GetFinancialDocumentHistory(context.transactor, ledger, documentOwners, pricingSources)::invoke,
                    getDepositRequirement = GetDepositRequirement(context.transactor, ledger, documentOwners)::invoke,
                    getDepositRequirementHistory = GetDepositRequirementHistory(context.transactor, ledger, documentOwners)::invoke,
                    setDepositRequirement = SetDepositRequirement(context.transactor, ledger, documentOwners, pricingSources)::invoke,
                    withdrawDepositRequirement = WithdrawDepositRequirement(context.transactor, ledger, documentOwners)::invoke,
                    queryFinancialLineages = QueryFinancialLineages(context.transactor, ledger, documentOwners)::invoke,
                    issueQuote = IssueQuote(context.transactor, ledger, documentOwners, pricingSources)::invoke,
                    issueInvoice = IssueInvoice(context.transactor, ledger, documentOwners, pricingSources)::invoke,
                    createChangeOrder = CreateChangeOrder(context.transactor, ledger, documentOwners, pricingSources, pricing)::invoke,
                    recordPayment = RecordDocumentPayment(context.transactor, ledger, documentOwners, pricingSources, clock)::invoke,
                    listFinancialDocumentPayments =
                        ListFinancialDocumentPaymentHistories(context.transactor, ledger, documentOwners)::invoke,
                    listUnappliedPayments = ledger::unappliedPayments,
                    recordStandalonePayment = RecordPayment(context.transactor, ledger, clock)::invoke,
                    allocatePayment = AllocatePayment(context.transactor, ledger, documentOwners, pricingSources, clock)::invoke,
                    recordRefund = RecordRefund(context.transactor, ledger, clock)::invoke,
                    login = Login(
                        StaffPasswordAuthenticator(context.authorization, context.transactor, credentials, hasher),
                        context.sessions,
                    )::invoke,
                    currentUser = context.authorization::getUser,
                    currentPermissions = context.authorization.permissionResolver::permissionsFor,
                    setStaffPassword = SetStaffPassword(context.authorization, context.transactor, credentials, hasher, clock)::invoke,
                )
            val offerings = offeringsHttpCapability(context, fionaOfferingsBinding(access))
            val authorizationAdmin =
                authorizationAdministrationHttpCapability(context, access, "/admin/access", setOf(staffAdministrationTag))
            // The request's principal, USER or SERVICE, resolved through the same AccessControl and catalog.
            // The permission catalog is served once, by the administration capability: commerce-runtime
            // 0.0.22's standalone catalog route has the same fixed operationId, so it is not also mounted.
            val currentPrincipal = currentPrincipalHttpCapability(access, "/authorization/me", setOf(authorizationTag))
            val serviceAuthentication = fionaServiceAuthentication(context)
            listOf(
                fionaApi(operations, offerings, authorizationAdmin, currentPrincipal, serviceAuthentication, fionaVersion(), auth),
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
