package io.github.castab.fionas.commerce.openapi

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.authorization.AuthorizationDirectory
import io.github.castab.commerce.runtime.authorization.PermissionCatalog
import io.github.castab.commerce.runtime.authorization.authorizationAdministrationHttpCapability
import io.github.castab.commerce.runtime.authorization.commercePermissionDefinitions
import io.github.castab.commerce.runtime.authorization.currentPrincipalHttpCapability
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.persistence.FinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.PaymentRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.serviceauth.IssuedServiceAccessToken
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessToken
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokens
import io.github.castab.commerce.runtime.serviceauth.ServiceCredentialSecret
import io.github.castab.commerce.runtime.serviceauth.ServiceCredentials
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.runtime.session.SessionToken
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.fionas.commerce.fionaVersion
import io.github.castab.fionas.commerce.http.FionaAuthRoutes
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.OPENAPI_PATH
import io.github.castab.fionas.commerce.http.STAFF_SESSION_COOKIE
import io.github.castab.fionas.commerce.http.authorizationTag
import io.github.castab.fionas.commerce.http.fionaApi
import io.github.castab.fionas.commerce.http.fionaServiceAuthentication
import io.github.castab.fionas.commerce.http.staffAdministrationTag
import io.github.castab.fionas.commerce.staff.FionaPermissions
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.Status
import org.jdbi.v3.core.Jdbi
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Operations for rendering the contract only. Rendering never calls an operation, so none
 * needs persistence, a database, or a server.
 */
private val notInvoked =
    FionaOperations(
        acknowledgeInquiryCommunication = { error("not called while rendering") },
        readStaffRequest = { error("not called while rendering") },
        issueInquiryProposal = { error("not called while rendering") },
        previewInquiryQuote = { error("not called while rendering") },
        reviseInquiryQuoteProposal = { error("not called while rendering") },
        reviseInquiryProposalDeposit = { error("not called while rendering") },
        readStaffDashboard = { error("not called while rendering") },
        markInquiryServed = { error("Rendering never serves an inquiry") },
        closeInquiry = { error("Rendering never closes an inquiry") },
        createInquiry = { error("Rendering the OpenAPI document never creates an inquiry") },
        listInquiries = { error("Rendering the OpenAPI document never lists inquiries") },
        getInquiry = { error("Rendering the OpenAPI document never reads an inquiry") },
        createInquiryEstimate = { error("Rendering the OpenAPI document never persists an estimate") },
        createInquiryFinancialDocument = { error("Rendering the OpenAPI document never persists a financial document") },
        listInquiryFinancialDocuments = { error("Rendering the OpenAPI document never reads a financial document") },
        getFinancialDocument = { error("Rendering the OpenAPI document never reads a financial document") },
        getFinancialDocumentHistory = { error("Rendering the OpenAPI document never reads a financial document") },
        getDepositRequirement = { error("not called while rendering") },
        getDepositRequirementHistory = { error("not called while rendering") },
        setDepositRequirement = { error("not called while rendering") },
        withdrawDepositRequirement = { error("not called while rendering") },
        queryFinancialLineages = { error("not called while rendering") },
        issueQuote = { _, _ -> error("Rendering the OpenAPI document never issues a quote") },
        issueInvoice = { _, _ -> error("Rendering the OpenAPI document never issues an invoice") },
        createChangeOrder = { error("Rendering the OpenAPI document never applies a change order") },
        recordPayment = { error("Rendering the OpenAPI document never records a payment") },
        listFinancialDocumentPayments = { error("Rendering the OpenAPI document never reads payments") },
        listUnappliedPayments = { error("Rendering the OpenAPI document never discovers payments") },
        recordStandalonePayment = { error("Rendering the OpenAPI document never records a standalone payment") },
        allocatePayment = { error("Rendering the OpenAPI document never allocates a payment") },
        recordRefund = { error("Rendering the OpenAPI document never records a refund") },
        login = { _, _ -> error("Rendering the OpenAPI document never logs in") },
        currentUser = { error("Rendering the OpenAPI document never reads a user") },
        currentPermissions = { error("Rendering the OpenAPI document never resolves permissions") },
        setStaffPassword = { _, _ -> error("Rendering the OpenAPI document never sets a password") },
    )

private val notInvokedSessions =
    object : SessionManager {
        override fun create(principalId: PrincipalId): IssuedSession = error("Rendering never creates a session")

        override fun create(
            transaction: Transaction,
            principalId: PrincipalId,
        ): IssuedSession = error("Rendering never creates a session")

        override fun resolve(token: SessionToken): PrincipalId? = error("Rendering never resolves a session")

        override fun revoke(token: SessionToken): Unit = error("Rendering never revokes a session")

        override fun revoke(
            transaction: Transaction,
            token: SessionToken,
        ): Unit = error("Rendering never revokes a session")

        override fun revokeAll(principalId: PrincipalId): Unit = error("Rendering never revokes sessions")

        override fun revokeAll(
            transaction: Transaction,
            principalId: PrincipalId,
        ): Unit = error("Rendering never revokes sessions")
    }

/**
 * Service access tokens for rendering only: the service token endpoint's contract documents
 * the token lifetime, so this carries the runtime's default (15 minutes) and nothing else. It
 * holds no signing key, and it never issues, signs, or resolves a token.
 */
private val renderingOnlyServiceAccessTokens =
    object : ServiceAccessTokens {
        override val lifetime: Duration = Duration.ofMinutes(15)

        override fun issue(
            serviceId: ServiceId,
            secret: ServiceCredentialSecret,
        ): IssuedServiceAccessToken? = error("Rendering the OpenAPI document never issues or resolves a service token")

        override fun resolve(token: ServiceAccessToken): ServiceId? =
            error("Rendering the OpenAPI document never issues or resolves a service token")
    }

/**
 * A `CommerceRuntimeContext` for rendering only: its transactor opens no connection, its
 * repositories refuse every call, and rendering calls none of them.
 *
 * The authorization and service authentication capabilities require a runtime
 * context even to describe their contract routes. A composed runtime needs a database, so this source set builds a
 * rendering-only context reflectively, including the financial ledger the context carries.
 * The transactor opens no connection, and route rendering invokes no repository or
 * operation. This provisional workaround exists only in the OpenAPI source set and never
 * enters the deployable application. Commerce-runtime may eventually offer a first-class
 * contract composition seam without persistence.
 */
private fun renderingOnlyContext(): CommerceRuntimeContext {
    val configuration =
        CommerceRuntimeConfiguration(
            database =
                CommerceRuntimeConfiguration.Database(
                    jdbcUrl = "jdbc:postgresql://rendering-only.invalid/none",
                    username = "",
                    password = "",
                ),
        )
    val transactor = Transactor(Jdbi.create { error("Rendering the OpenAPI document never opens a connection") })
    val documents = refusing<FinancialDocumentRepository>("Rendering the OpenAPI document never touches a financial document")
    val payments = refusing<PaymentRepository>("Rendering the OpenAPI document never touches a payment")
    // The runtime's current internal ledger constructor takes the concrete PostgreSQL repositories.
    // Constructing these opens no connection; the refusing transactor guards every ledger call.
    val documentRepositoryType = Class.forName("io.github.castab.commerce.runtime.persistence.PostgresFinancialDocumentRepository")
    val paymentRepositoryType = Class.forName("io.github.castab.commerce.runtime.persistence.PostgresPaymentRepository")
    val ledgerDocuments = documentRepositoryType.getConstructor().newInstance()
    val ledgerPayments = paymentRepositoryType.getConstructor(FinancialDocumentRepository::class.java).newInstance(ledgerDocuments)
    val ledger =
        FinancialLedger::class.java
            .getConstructor(Transactor::class.java, documentRepositoryType, paymentRepositoryType)
            .newInstance(
                transactor,
                ledgerDocuments,
                ledgerPayments,
            )
    val catalog = PermissionCatalog(commercePermissionDefinitions + FionaPermissions.definitions)
    val repositoryType = Class.forName("io.github.castab.commerce.runtime.persistence.AuthorizationRepository")
    val repository = repositoryType.getConstructor().newInstance()
    val authorization =
        AuthorizationDirectory::class.java
            .getConstructor(Transactor::class.java, repositoryType, SessionManager::class.java, PermissionCatalog::class.java)
            .newInstance(transactor, repository, notInvokedSessions, catalog)
    return CommerceRuntimeContext::class.java
        .getConstructor(
            CommerceRuntimeConfiguration::class.java,
            Transactor::class.java,
            FinancialDocumentRepository::class.java,
            PaymentRepository::class.java,
            FinancialLedger::class.java,
            SessionManager::class.java,
            AuthorizationDirectory::class.java,
            ServiceCredentials::class.java,
            ServiceAccessTokens::class.java,
        ).newInstance(
            configuration,
            transactor,
            documents,
            payments,
            ledger,
            notInvokedSessions,
            authorization,
            refusing<ServiceCredentials>("Rendering the OpenAPI document never touches a service credential"),
            renderingOnlyServiceAccessTokens,
        )
}

/**
 * An [AccessControl] for rendering only, bound to the rendering-only [context]'s authorization
 * directory (the runtime binds every `AccessControl` to one directory); it authenticates
 * nothing, and rendering never invokes it.
 */
fun renderingOnlyAccess(context: CommerceRuntimeContext = renderingOnlyContext()): AccessControl =
    AccessControl(Filter.NoOp, context.authorization)

/** A [T] whose every method fails with [reason]; rendering never calls one. */
private inline fun <reified T : Any> refusing(reason: String): T =
    T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf<Class<*>>(T::class.java)) { _, _, _ -> error(reason) })

/**
 * The OpenAPI document of the Fiona API, exactly as the running application serves it at
 * [OPENAPI_PATH]: the same [fionaApi] contract answers the
 * same request, with no database, server, or network.
 */
fun fionaOpenApiDocument(version: String = fionaVersion()): String {
    val context = renderingOnlyContext()
    val renderingAccess = renderingOnlyAccess(context)
    val renderingAuth =
        FionaAuthRoutes(
            notInvokedSessions,
            SessionCookie(STAFF_SESSION_COOKIE),
            renderingAccess,
            Filter.NoOp,
        )
    val authorizationAdmin =
        authorizationAdministrationHttpCapability(context, renderingAccess, "/admin/access", setOf(staffAdministrationTag))
    val currentPrincipal = currentPrincipalHttpCapability(renderingAccess, "/authorization/me", setOf(authorizationTag))
    val serviceAuthentication = fionaServiceAuthentication(context)
    val response =
        fionaApi(
            notInvoked,
            authorizationAdmin,
            currentPrincipal,
            serviceAuthentication,
            version,
            renderingAuth,
        )(Request(Method.GET, OPENAPI_PATH))
    check(response.status == Status.OK) { "Rendering the OpenAPI document failed: ${response.status}" }
    return response.bodyString()
}

/**
 * Writes the OpenAPI document to the path in [args], pretty-printed UTF-8 JSON (the `generateOpenApi` Gradle task).
 */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output.toAbsolutePath().parent)
    Files.writeString(output, CommerceJson.pretty(CommerceJson.parse(fionaOpenApiDocument())) + "\n")
}
