package io.github.castab.fionas.commerce.openapi

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.authorization.AuthorizationDirectory
import io.github.castab.commerce.runtime.authorization.PermissionCatalog
import io.github.castab.commerce.runtime.authorization.authorizationAdministrationHttpCapability
import io.github.castab.commerce.runtime.authorization.commercePermissionDefinitions
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.financial.FinancialLedger
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.offering.offeringsHttpCapability
import io.github.castab.commerce.runtime.persistence.FinancialDocumentRepository
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.PaymentRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.runtime.session.SessionToken
import io.github.castab.commerce.staff.PermissionResolver
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.fionas.commerce.fionaVersion
import io.github.castab.fionas.commerce.http.FionaAuthRoutes
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.OPENAPI_PATH
import io.github.castab.fionas.commerce.http.fionaApi
import io.github.castab.fionas.commerce.http.staffAdministrationTag
import io.github.castab.fionas.commerce.offering.fionaOfferingsBinding
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

/**
 * Operations for rendering the contract only. Rendering never calls an operation, so none
 * needs persistence, a database, or a server.
 */
private val notInvoked =
    FionaOperations(
        createInquiry = { error("Rendering the OpenAPI document never creates an inquiry") },
        listInquiries = { error("Rendering the OpenAPI document never lists inquiries") },
        getInquiry = { error("Rendering the OpenAPI document never reads an inquiry") },
        previewEstimate = { error("Rendering the OpenAPI document never prices an estimate") },
        createInquiryEstimate = { _, _ -> error("Rendering the OpenAPI document never persists an estimate") },
        createInquiryFinancialDocument = { error("Rendering the OpenAPI document never persists a financial document") },
        listInquiryFinancialDocuments = { error("Rendering the OpenAPI document never reads a financial document") },
        getFinancialDocument = { error("Rendering the OpenAPI document never reads a financial document") },
        getFinancialDocumentHistory = { error("Rendering the OpenAPI document never reads a financial document") },
        issueQuote = { _, _ -> error("Rendering the OpenAPI document never issues a quote") },
        issueInvoice = { _, _ -> error("Rendering the OpenAPI document never issues an invoice") },
        createChangeOrder = { _, _, _ -> error("Rendering the OpenAPI document never applies a change order") },
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

private val renderingAccess = AccessControl(Filter.NoOp, PermissionResolver { emptySet() })
private val renderingAuth = FionaAuthRoutes(notInvokedSessions, SessionCookie("__Host-fionas_session"), renderingAccess, Filter.NoOp)

/**
 * A `CommerceRuntimeContext` for rendering only: its transactor opens no connection, its
 * repositories refuse every call, and rendering calls none of them.
 *
 * The Offerings and authorization capabilities require a runtime context even to describe
 * their contract routes. A composed runtime needs a database, so this source set builds a
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
    val snapshots =
        object : OfferingsSnapshotRepository {
            override fun insert(
                transaction: Transaction,
                snapshot: OfferingsSnapshot,
            ) = error("Rendering the OpenAPI document never writes a catalog")

            override fun retrieveVersion(
                transaction: Transaction,
                reference: OfferingsSnapshotReference,
            ) = error("Rendering the OpenAPI document never reads a catalog")

            override fun retrieveLatestVersion(
                transaction: Transaction,
                catalogId: OfferingsCatalogId,
            ) = error("Rendering the OpenAPI document never reads a catalog")

            override fun offeringKeyExistsInHistory(
                transaction: Transaction,
                catalogId: OfferingsCatalogId,
                key: OfferingKey,
            ) = error("Rendering the OpenAPI document never checks offering history")

            override fun categoryKeyExistsInHistory(
                transaction: Transaction,
                catalogId: OfferingsCatalogId,
                key: OfferingCategoryKey,
            ) = error("Rendering the OpenAPI document never checks category history")

            override fun retrieveRetiredOfferings(
                transaction: Transaction,
                reference: OfferingsSnapshotReference,
            ) = error("Rendering the OpenAPI document never discovers retired offerings")

            override fun retrieveRetiredCategories(
                transaction: Transaction,
                reference: OfferingsSnapshotReference,
            ) = error("Rendering the OpenAPI document never discovers retired categories")
        }
    val documents = refusing<FinancialDocumentRepository>("Rendering the OpenAPI document never touches a financial document")
    val payments = refusing<PaymentRepository>("Rendering the OpenAPI document never touches a payment")
    // 0.0.17's internal ledger constructor takes the concrete PostgreSQL repositories.
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
            OfferingsSnapshotRepository::class.java,
            FinancialDocumentRepository::class.java,
            PaymentRepository::class.java,
            FinancialLedger::class.java,
            SessionManager::class.java,
            AuthorizationDirectory::class.java,
        ).newInstance(configuration, transactor, snapshots, documents, payments, ledger, notInvokedSessions, authorization)
}

/** A [T] whose every method fails with [reason]; rendering never calls one. */
private inline fun <reified T : Any> refusing(reason: String): T =
    T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf<Class<*>>(T::class.java)) { _, _, _ -> error(reason) })

/**
 * The OpenAPI document of the Fiona API, exactly as the running application serves it at
 * [OPENAPI_PATH]: the same [fionaApi] contract, with the same Offerings binding, answers the
 * same request, with no database, server, or network.
 */
fun fionaOpenApiDocument(version: String = fionaVersion()): String {
    val context = renderingOnlyContext()
    val offerings = offeringsHttpCapability(context, fionaOfferingsBinding(renderingAccess))
    val authorizationAdmin =
        authorizationAdministrationHttpCapability(context, renderingAccess, "/admin/access", setOf(staffAdministrationTag))
    val response = fionaApi(notInvoked, offerings, authorizationAdmin, version, renderingAuth)(Request(Method.GET, OPENAPI_PATH))
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
