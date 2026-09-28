package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.offering.GetOfferingsCatalogRevision
import io.github.castab.commerce.runtime.offering.offeringsHttpCapability
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.http.BrowserOrigin
import io.github.castab.fionas.commerce.http.FionaAuthRoutes
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.apiDocs
import io.github.castab.fionas.commerce.http.fionaApi
import io.github.castab.fionas.commerce.inquiry.CreateInquiry
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.PreviewEstimate
import io.github.castab.fionas.commerce.offering.fionaOfferingsBinding
import io.github.castab.fionas.commerce.staff.BootstrapAdmin
import io.github.castab.fionas.commerce.staff.BootstrapFirstAdmin
import io.github.castab.fionas.commerce.staff.GetCurrentUser
import io.github.castab.fionas.commerce.staff.JdbiStaffRepository
import io.github.castab.fionas.commerce.staff.Login
import io.github.castab.fionas.commerce.staff.PasswordHasher
import io.github.castab.fionas.commerce.staff.StaffPasswordAuthenticator
import io.github.castab.fionas.commerce.staff.fionaPermissionResolver
import org.http4k.core.then
import java.time.Clock
import java.util.Properties

/**
 * Where Fiona's own migrations live: Fiona's migration stream, applied by commerce-runtime
 * after its own. Never `db/commerce`; the runtime discovers and applies its migrations itself.
 */
const val FIONA_MIGRATION_LOCATION = "classpath:db/fionas"

/**
 * Everything Fiona's contributes to commerce-runtime: the location of its own migrations
 * (never the runtime's) and its routes: the Fiona API contract and its Swagger UI.
 *
 * This is the application's composition root. Repositories and operations are built here
 * with ordinary Kotlin from the runtime's `CommerceRuntimeContext`, so every Fiona write
 * goes through the runtime's single `Transactor`. Fiona's Offerings catalog is
 * commerce-runtime's Offerings capability bound to Fiona's catalog; its contract routes join
 * the same API contract as Fiona's own. Estimate previews read exact catalog revisions
 * through the runtime's own `GetOfferingsCatalogRevision` and price them with Fiona's
 * [FionasOfferingsEngine].
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
): ApplicationContributions =
    ApplicationContributions(
        migrationLocations = listOf(FIONA_MIGRATION_LOCATION),
        routes = { context ->
            val customers = JdbiCustomerRepository()
            val inquiries = JdbiInquiryRepository()
            val staff = JdbiStaffRepository()
            val hasher = PasswordHasher()
            BootstrapFirstAdmin(context.transactor, staff, hasher, clock).invoke(bootstrap)
            val cookie = SessionCookie("__Host-fionas_session")
            val origin = BrowserOrigin(trustedOrigins, cookie)
            val access =
                AccessControl(
                    origin.filter.then(sessionAuthentication(context.sessions, cookie)),
                    fionaPermissionResolver(context.transactor, staff),
                )
            val auth = FionaAuthRoutes(context.sessions, cookie, access, origin.filter)
            val operations =
                FionaOperations(
                    createInquiry = CreateInquiry(context.transactor, customers, inquiries, clock)::invoke,
                    getInquiry = GetInquiry(context.transactor, customers, inquiries)::invoke,
                    previewEstimate =
                        PreviewEstimate(
                            getRevision = GetOfferingsCatalogRevision(context.transactor, context.offeringsSnapshotRepository)::invoke,
                            engine = FionasOfferingsEngine(FIONAS_PRICING_POLICY),
                        )::invoke,
                    login = Login(context.transactor, StaffPasswordAuthenticator(staff, hasher), context.sessions)::invoke,
                    currentUser = GetCurrentUser(context.transactor, staff)::invoke,
                )
            val offerings = offeringsHttpCapability(context, fionaOfferingsBinding(access))
            listOf(fionaApi(operations, offerings, fionaVersion(), auth), apiDocs())
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
