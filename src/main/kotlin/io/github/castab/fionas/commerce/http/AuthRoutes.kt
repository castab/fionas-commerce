package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.errorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.SecretPassword
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
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
private val authTag = Tag("Authentication", "Fiona staff browser sessions.")

/** Shared by Fiona's password route and the runtime's principal and role administration routes. */
val staffAdministrationTag = Tag("Staff administration", "Staff accounts, credentials, roles, and permissions.")

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

fun logoutRoute(
    sessions: SessionManager,
    cookie: SessionCookie,
    access: AccessControl,
): ContractRoute {
    // An already-revoked cookie still receives a clear cookie and a successful logout.
    val clearOnUnauthenticated =
        Filter { next ->
            { request ->
                val response = next(request)
                if (response.status == Status.UNAUTHORIZED) Response(Status.NO_CONTENT).cookie(cookie.clear()) else response
            }
        }
    return "/auth/logout" meta {
        operationId = "logout"
        summary = "Log out of the current browser session"
        description = "Revokes the runtime session and clears the browser cookie. Repeated logout is safe."
        tags += authTag
        returning(Status.NO_CONTENT to "The browser cookie is cleared.")
        returningError(ErrorCategory.FORBIDDEN, "the browser origin is not trusted.", "The browser origin is not trusted")
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to
        clearOnUnauthenticated.then(access.authenticated()).then { request: Request ->
            cookie.extract(request)?.let(sessions::revoke)
            Response(Status.NO_CONTENT).cookie(cookie.clear())
        }
}

fun currentUserRoute(
    currentUser: (UserId) -> User?,
    currentPermissions: (UserId) -> Set<PermissionKey>,
    access: AccessControl,
): ContractRoute =
    "/auth/me" meta {
        operationId = "getCurrentUser"
        summary = "Read the authenticated staff identity"
        description =
            "Returns the current human staff profile, role keys, and effective live permissions. Requires only an active staff session."
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
        returningError(ErrorCategory.UNAUTHENTICATED, "there is no active session.", "Authentication is required")
        returningError(
            ErrorCategory.FORBIDDEN,
            "the principal is not an active human staff user.",
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
        returningError(ErrorCategory.UNAUTHENTICATED, "authentication is required.", "Authentication is required")
        returningError(ErrorCategory.FORBIDDEN, "permission or browser origin is missing.", "Forbidden")
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
