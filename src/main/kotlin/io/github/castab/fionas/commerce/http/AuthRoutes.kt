package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.errorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.serviceauth.ServiceAuthenticationHttpCapability
import io.github.castab.commerce.runtime.serviceauth.serviceAuthenticationHttpCapability
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.runtime.session.sessionAuthentication
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.SecretPassword
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.cookie.cookie
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import java.util.UUID

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class CurrentUserResponse(
    @ApiProperty(format = "uuid") val id: String,
    val username: String,
    val displayName: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val roles: List<String>,
    @ApiProperty(description = "The staff user's effective live permissions, sorted by key; no role-administration permission is required.")
    val permissions: List<String>,
)

@Serializable
data class SetStaffPasswordRequest(
    val password: String,
)

private val loginBody = jsonBody(LoginRequest.serializer())
private val currentUserBody = jsonBody(CurrentUserResponse.serializer())
private val setPasswordBody = jsonBody(SetStaffPasswordRequest.serializer())
private val authTag = Tag("Authentication", "Staff browser sessions and service access tokens.")

/**
 * The name of Fiona's staff browser session cookie: the one `SessionCookie` Fiona composes and
 * the cookie its OpenAPI `staffSession` scheme names. Fiona's policy, not commerce-runtime's.
 */
const val STAFF_SESSION_COOKIE = "__Host-fionas_session"

/** Where SERVICE principals exchange a credential for a short-lived access token. */
const val SERVICE_TOKEN_PATH = "/auth/service/token"

/**
 * commerce-runtime's service token endpoint, mounted at [SERVICE_TOKEN_PATH] in Fiona's
 * contract. Fiona owns only where it is served; issuing and verifying tokens is the runtime's.
 * The endpoint is public by design (a credential obtains the token), so production protects
 * it with edge rate limiting and/or private reachability.
 */
fun fionaServiceAuthentication(context: CommerceRuntimeContext): ServiceAuthenticationHttpCapability =
    serviceAuthenticationHttpCapability(context, SERVICE_TOKEN_PATH, setOf(authTag))

/** Shared by Fiona's password route and the runtime's principal and role administration routes. */
val staffAdministrationTag = Tag("Staff administration", "Staff accounts, credentials, roles, and permissions.")

/**
 * The runtime's authorization read for whichever principal authenticated the request,
 * `/authorization/me` (a USER or a SERVICE). It administers nothing, so it is not staff
 * administration, and it is not Fiona's staff profile at `/auth/me`.
 */
val authorizationTag =
    Tag("Authorization", "The principal that authenticated the request, USER or SERVICE, and its effective permissions.")

/**
 * Route metadata for a route behind Fiona's `AccessControl.authenticated()`: either transport
 * may authenticate it (a staff session or a SERVICE access token, [principalSecurity]), and no
 * valid authentication is `401`. Documentation only; the route still states its enforcement.
 */
internal fun RouteMetaDsl.principalAuthentication() {
    security = principalSecurity
    returningError(
        ErrorCategory.UNAUTHENTICATED,
        "no valid authentication: neither an active staff session nor a valid service access token.",
        "Authentication is required",
    )
}

/**
 * Route metadata for a route behind Fiona's `AccessControl.requirePermission(permission)`:
 * [principalAuthentication], and `403` when the authenticated principal, USER or SERVICE,
 * does not currently hold [permission]. Which kind of principal holds it does not matter.
 * [alsoForbidden] adds the route's other `403` causes, such as [UNTRUSTED_ORIGIN].
 * Documentation only; enforcement stays explicit on the route.
 */
internal fun RouteMetaDsl.principalAccess(
    permission: PermissionKey,
    alsoForbidden: String? = null,
) {
    principalAuthentication()
    returningError(
        ErrorCategory.FORBIDDEN,
        "the authenticated principal lacks `${permission.value}`" + (alsoForbidden?.let { ", $it" } ?: "") + ".",
        "The authenticated principal is not permitted to perform this request",
    )
}

/** The `403` an unsafe request carrying the staff session cookie answers without a trusted browser Origin. */
internal const val UNTRUSTED_ORIGIN = "or the request carries the staff session cookie from an untrusted browser origin"

fun loginRoute(
    login: (String, SecretPassword) -> IssuedSession?,
    cookie: SessionCookie,
    access: AccessControl,
    origin: Filter,
    rateLimit: LoginRateLimit,
): ContractRoute =
    "/auth/login" meta {
        operationId = "login"
        summary = "Log in as a Fiona staff user"
        description = "Verifies a staff password and sets a Secure, HttpOnly, host-only session cookie. Requires a trusted Origin. " +
            "Limited per connection IP to a burst of five attempts, refilling one attempt every five minutes. " +
            "All attempts count, including successful and malformed requests. 429 includes Retry-After in seconds."
        tags += authTag
        receiving(loginBody to LoginRequest("brayan", "password"))
        returning(Status.NO_CONTENT to "The session cookie is set.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "the credentials body is malformed.", "Malformed request")
        returningError(ErrorCategory.UNAUTHENTICATED, "the credentials are invalid or the user is disabled.", "Invalid credentials")
        returningError(ErrorCategory.FORBIDDEN, "the browser origin is not trusted.", "The browser origin is not trusted")
        returningLoginRateLimit()
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to
        rateLimit.filter.then(origin).then(access.public()).then { request: Request ->
            val body = loginBody(request)
            val issued =
                login(body.username, SecretPassword.of(body.password))
                    ?: return@then errorResponse(ErrorCategory.UNAUTHENTICATED, "Invalid credentials")
            Response(Status.NO_CONTENT).cookie(cookie.issue(issued))
        }

/**
 * `POST /auth/logout`: ends a Fiona staff browser session. It authenticates with the runtime's
 * session authentication only, not the general `AccessControl`, because a session is all it
 * can revoke:
 *
 * - an active session is revoked and the cookie cleared (`204`);
 * - a session cookie the runtime no longer accepts (revoked, expired, unknown) still has its
 *   cookie cleared (`204`), so browser logout is idempotent;
 * - a request without a session cookie that authenticates otherwise, a SERVICE access token,
 *   is `403`: there is no session to end, and access tokens are not revoked here (they expire,
 *   and disabling the service suspends them);
 * - a request with neither has nothing to end and is answered `204` with the cookie cleared.
 *
 * [origin] (Fiona's browser Origin policy) applies first, so a request carrying the session
 * cookie needs a trusted Origin whatever else it carries.
 */
fun logoutRoute(
    sessions: SessionManager,
    cookie: SessionCookie,
    access: AccessControl,
    origin: Filter,
): ContractRoute {
    val cleared = { Response(Status.NO_CONTENT).cookie(cookie.clear()) }
    val endSession =
        sessionAuthentication(sessions, cookie).then { request: Request ->
            cookie.extract(request)?.let(sessions::revoke)
            cleared()
        }
    // Reached only without a session the runtime accepts: another principal has no session to end.
    val notASession =
        access.authenticated().then { _: Request ->
            errorResponse(ErrorCategory.FORBIDDEN, LOGOUT_NOT_A_SESSION)
        }
    return "/auth/logout" meta {
        operationId = "logout"
        summary = "Log out of the current staff browser session"
        description =
            "Revokes the staff browser session named by the session cookie and clears the cookie. A cookie whose session " +
            "is already revoked or expired is still cleared, so repeated logout is safe. Requires a trusted Origin. Only " +
            "browser sessions are revoked: a SERVICE access token is not revoked by this route (it expires, and disabling " +
            "the service suspends it), and a request authenticated only by one is answered 403."
        tags += authTag
        security = optionalStaffSessionSecurity
        returning(Status.NO_CONTENT to "The session, if any, is revoked and the browser cookie is cleared.")
        returningError(
            ErrorCategory.FORBIDDEN,
            "the browser origin is not trusted, or the request has no staff session cookie and is authenticated as another " +
                "principal, such as a SERVICE access token, which logout does not revoke.",
            LOGOUT_NOT_A_SESSION,
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to
        origin.then { request: Request ->
            val ended = endSession(request)
            when {
                ended.status != Status.UNAUTHORIZED -> ended
                cookie.extract(request) != null -> cleared()
                else -> notASession(request).takeUnless { it.status == Status.UNAUTHORIZED } ?: cleared()
            }
        }
}

private const val LOGOUT_NOT_A_SESSION = "Logout ends a staff browser session; this request has none"

/**
 * `GET /auth/me`: who the current Fiona human staff user is, with Fiona's staff profile
 * (username, names, role keys) and effective live permissions. Only an active USER is
 * described; any other authenticated principal, a SERVICE included, is `403`.
 *
 * Not the runtime's `GET /authorization/me`, which describes whichever principal authenticated
 * the request, USER or SERVICE, with its effective permissions and the permission catalog
 * revision, and no Fiona profile.
 */
fun currentUserRoute(
    currentUser: (UserId) -> User?,
    currentPermissions: (UserId) -> Set<PermissionKey>,
    access: AccessControl,
): ContractRoute =
    "/auth/me" meta {
        operationId = "getCurrentUser"
        summary = "Read the authenticated staff identity"
        description =
            "Returns the current human staff profile, role keys, and effective live permissions. Requires only an active staff " +
            "session; a SERVICE principal's access token authenticates but is answered with 403, never a staff profile."
        tags += authTag
        returning(
            Status.OK,
            currentUserBody to
                CurrentUserResponse(
                    "00000000-0000-0000-0000-000000000001",
                    "brayan",
                    "Brayan",
                    roles = listOf("commerce.administrator"),
                    permissions = listOf("commerce.payment.record"),
                ),
        )
        principalAuthentication()
        returningError(
            ErrorCategory.FORBIDDEN,
            "the authenticated principal is not an active human staff user: a SERVICE access token authenticates, " +
                "but this route describes staff users only (see `GET /authorization/me` for any principal).",
            "The current principal is not an active staff user",
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.GET to
        access.authenticated().then { request: Request ->
            val id = authenticatedPrincipal(request) as? UserId
            val user = id?.let(currentUser)
            if (user == null || user.status != PrincipalStatus.ACTIVE) {
                errorResponse(ErrorCategory.FORBIDDEN, "The current principal is not an active staff user")
            } else {
                Response(Status.OK).with(currentUserBody of user.toResponse(currentPermissions(user.id)))
            }
        }

private fun User.toResponse(permissions: Set<PermissionKey>) =
    CurrentUserResponse(
        id.value.toString(),
        username,
        displayName,
        firstName,
        lastName,
        roles.map { it.role.value }.sorted(),
        permissions.map { it.value }.sorted(),
    )

fun setStaffPasswordRoute(
    setPassword: (UserId, SecretPassword) -> Unit,
    access: AccessControl,
): ContractRoute {
    val userId = Path.of("userId", "The runtime user's id.", mapOf("schema" to mapOf("format" to "uuid")))
    return "/admin/users" / userId / "credentials" / "password" meta {
        operationId = "setStaffPassword"
        summary = "Set a staff user's password"
        description =
            "Sets or resets a runtime user's Fiona password credential. Existing sessions remain active. Requires a trusted Origin."
        tags += staffAdministrationTag
        preFlightExtraction = PreFlightExtraction.IgnoreBody
        receiving(setPasswordBody to SetStaffPasswordRequest("new-password"))
        returning(Status.NO_CONTENT to "The password credential was stored.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "the request or user ID is malformed.", "Malformed request")
        principalAccess(FionaPermissions.CredentialsManage, UNTRUSTED_ORIGIN)
        returningError(ErrorCategory.NOT_FOUND, "the target user does not exist.", "User does not exist")
        returningError(ErrorCategory.VALIDATION_FAILED, "the password is too short.", "Password must have at least 12 characters")
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.PUT to { id: String, _: String, _: String ->
        access.requirePermission(FionaPermissions.CredentialsManage).then { request: Request ->
            val target =
                try {
                    UserId(UUID.fromString(id))
                } catch (e: IllegalArgumentException) {
                    throw LensFailure(Invalid(userId.meta), cause = e)
                }
            setPassword(target, SecretPassword.of(setPasswordBody(request).password))
            Response(Status.NO_CONTENT)
        }
    }
}
