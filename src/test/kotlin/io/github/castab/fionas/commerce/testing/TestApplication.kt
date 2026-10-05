package io.github.castab.fionas.commerce.testing

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.CommerceRuntime
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.authorization.AuthorizationDirectory
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokenDto
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.fionas.commerce.FIONA_DEFAULT_EVENT_CALENDAR_ZONE
import io.github.castab.fionas.commerce.fionaApplication
import io.github.castab.fionas.commerce.http.LoginRateLimit
import io.github.castab.fionas.commerce.http.SERVICE_TOKEN_PATH
import io.github.castab.fionas.commerce.staff.BootstrapAdmin
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.SecretPassword
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** A fixed test time with nanoseconds, which PostgreSQL cannot store. */
val TEST_INSTANT: Instant = Instant.parse("2026-09-26T18:30:00.123456789Z")

val testClock: Clock = Clock.fixed(TEST_INSTANT, ZoneOffset.UTC)

/**
 * fionas-commerce composed as `main()` composes it, `commerceRuntime(configuration,
 * fionaApplication())`, against a throwaway database, with commerce-runtime migrating on
 * startup: its own migrations, then Fiona's.
 *
 * [context] is the `CommerceRuntimeContext` the runtime hands to Fiona's route factory, and
 * [transactor] its own `Transactor`, so specs drive repositories and operations inside real
 * runtime transactions, with the runtime's own financial ledger, rather than test-built
 * stand-ins.
 */
class TestApplication private constructor(
    val database: TestDatabase,
    val runtime: CommerceRuntime,
    val context: CommerceRuntimeContext,
) : AutoCloseable {
    val transactor: Transactor get() = context.transactor
    val sessions: SessionManager get() = context.sessions
    val authorization: AuthorizationDirectory get() = context.authorization

    /** The complete HTTP handler, including the runtime's error handling, without a server. */
    val http: HttpHandler get() = runtime.http

    val adminCookie: String by lazy {
        val response =
            http(
                Request(Method.POST, "/auth/login")
                    .header("Origin", TEST_ORIGIN)
                    .header("Content-Type", "application/json")
                    .body("""{"username":"admin","password":"test-admin-password"}"""),
            )
        check(response.status == Status.NO_CONTENT) { "Test admin login failed: ${response.status} ${response.bodyString()}" }
        checkNotNull(response.header("Set-Cookie")).substringBefore(';')
    }

    /**
     * The server-side web frontend's SERVICE principal, provisioned as an administrator would:
     * service `fionas-web`, role `fionas.web` granting exactly [FIONAS_WEB_PERMISSIONS], and one
     * credential. Created on first use.
     */
    val web: TestService by lazy { provisionService("fionas-web", FIONAS_WEB_PERMISSIONS, RoleKey("fionas.web")) }

    /** An access token for [web], obtained like the frontend obtains one: its credential at the token endpoint. */
    val webToken: String by lazy { serviceToken(web) }

    /**
     * Creates an ACTIVE service named [name] holding [permissions] through [role] (no role when
     * [permissions] is empty), and one credential for it. Tests may change its role grants
     * afterwards through [authorization] to observe live authorization.
     */
    fun provisionService(
        name: String,
        permissions: Set<PermissionKey>,
        role: RoleKey = RoleKey("test.${name.lowercase()}-${UUID.randomUUID()}"),
    ): TestService {
        val id = ServiceId(UUID.randomUUID())
        authorization.createService(ServiceIdentity(id, name, PrincipalStatus.ACTIVE, emptySet()))
        if (permissions.isNotEmpty()) {
            authorization.createRole(RoleDefinition(role, name, null, permissions))
            authorization.assignRole(id, role)
        }
        val secret =
            context.serviceCredentials
                .create(id, "test credential")
                .secret.value
        return TestService(id, role, secret)
    }

    /** Exchanges [service]'s credential for an access token at the runtime's token endpoint. */
    fun serviceToken(service: TestService): String {
        val response =
            http(
                Request(Method.POST, SERVICE_TOKEN_PATH)
                    .header("Content-Type", "application/json")
                    .body("""{"serviceId":"${service.id.value}","secret":"${service.secret}"}"""),
            )
        check(response.status == Status.OK) { "Service token exchange failed: ${response.status} ${response.bodyString()}" }
        return CommerceJson.asA(response.bodyString(), ServiceAccessTokenDto.serializer()).accessToken
    }

    fun adminPost(
        path: String,
        body: String = "",
    ): Response = adminRequest(Method.POST, path, body)

    fun adminGet(path: String): Response = adminRequest(Method.GET, path)

    /** A request from the bootstrap administrator's browser session, from a trusted origin. */
    fun adminRequest(
        method: Method,
        path: String,
        body: String = "",
    ): Response =
        http(
            Request(method, path)
                .header("Origin", TEST_ORIGIN)
                .header("Cookie", adminCookie)
                .header("Content-Type", "application/json")
                .body(body),
        )

    override fun close() {
        runtime.close()
        database.close()
    }

    companion object {
        fun create(
            clock: Clock = testClock,
            bootstrap: BootstrapAdmin? =
                BootstrapAdmin("admin", "Test Administrator", null, null, SecretPassword.of("test-admin-password")),
            loginRateLimit: LoginRateLimit = LoginRateLimit(),
            trustedOrigins: Set<String> = setOf(TEST_ORIGIN),
            eventCalendarZone: ZoneId = FIONA_DEFAULT_EVENT_CALENDAR_ZONE,
        ): TestApplication {
            val database = TestDatabase.create()
            try {
                val fiona = fionaApplication(clock, bootstrap, trustedOrigins, loginRateLimit, eventCalendarZone)
                var context: CommerceRuntimeContext? = null
                val runtime =
                    commerceRuntime(
                        configuration =
                            CommerceRuntimeConfiguration(
                                server = CommerceRuntimeConfiguration.Server(port = 0),
                                database = database.configuration,
                                migrations = CommerceRuntimeConfiguration.Migrations(onStartup = OnStartup.MIGRATE),
                                serviceTokens = TEST_SERVICE_TOKENS,
                            ),
                        application =
                            ApplicationContributions(
                                migrations = fiona.migrations,
                                permissionDefinitions = fiona.permissionDefinitions,
                                routes = { runtimeContext ->
                                    context = runtimeContext
                                    fiona.routes(runtimeContext)
                                },
                            ),
                    )
                return TestApplication(database, runtime, checkNotNull(context))
            } catch (e: Exception) {
                database.close()
                throw e
            }
        }
    }
}

const val TEST_ORIGIN = "https://fionas.test"

/**
 * Test-only service token signing: the base64 of the 32 distinct bytes 0..31, and an issuer
 * naming the test deployment. Not a deployment secret, and never given to test clients: they
 * obtain tokens through a service credential and the token endpoint, as the frontend does.
 */
val TEST_SERVICE_TOKENS =
    CommerceRuntimeConfiguration.ServiceTokens(
        signingKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }),
        issuer = "fionas-commerce-test",
    )

/** What the web frontend's role `fionas.web` grants: Fiona's three customer operations, nothing else. */
val FIONAS_WEB_PERMISSIONS =
    setOf(FionaPermissions.InquiryFormRead, FionaPermissions.EstimatePreviewCreate, FionaPermissions.InquiriesCreate)

/** A provisioned SERVICE principal, its role, and its credential secret. */
data class TestService(
    val id: ServiceId,
    val role: RoleKey,
    val secret: String,
)

/** Sends [token] as the request's `Authorization: Bearer` credential. */
fun Request.withBearer(token: String): Request = header("Authorization", "Bearer $token")

/** A request from the server-side web frontend, authenticated by its service access token. */
fun Request.asFionasWeb(app: TestApplication): Request = withBearer(app.webToken)

/** A new logical submission by default; replay tests supply and retain an explicit key. */
fun Request.withSubmissionKey(key: String = UUID.randomUUID().toString()): Request = header("Idempotency-Key", key)
