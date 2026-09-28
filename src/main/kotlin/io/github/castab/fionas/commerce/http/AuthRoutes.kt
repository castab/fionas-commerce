package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.errorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.staff.SecretPassword
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.cookie.cookie
import org.http4k.core.then
import org.http4k.core.with

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
)

private val loginBody = jsonBody(LoginRequest.serializer())
private val currentUserBody = jsonBody(CurrentUserResponse.serializer())
private val authTag = Tag("Authentication", "Fiona staff browser sessions.")

fun loginRoute(
    login: (String, SecretPassword) -> IssuedSession?,
    cookie: SessionCookie,
    access: AccessControl,
    origin: Filter,
): ContractRoute =
    "/auth/login" meta {
        operationId = "login"
        summary = "Log in as a Fiona staff user"
        description = "Verifies a staff password and sets a Secure, HttpOnly, host-only session cookie. Requires a trusted Origin."
        tags += authTag
        receiving(loginBody to LoginRequest("brayan", "password"))
        returning(Status.NO_CONTENT to "The session cookie is set.")
        returningError(ErrorCategory.MALFORMED_REQUEST, "the credentials body is malformed.", "Malformed request")
        returningError(ErrorCategory.UNAUTHENTICATED, "the credentials are invalid or the user is disabled.", "Invalid credentials")
        returningError(ErrorCategory.FORBIDDEN, "the browser origin is not trusted.", "The browser origin is not trusted")
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
    } bindContract Method.POST to
        origin.then(access.public()).then { request: Request ->
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
    access: AccessControl,
): ContractRoute =
    "/auth/me" meta {
        operationId = "getCurrentUser"
        summary = "Read the authenticated staff identity"
        description = "Returns the current human staff profile and role keys, without credential or session material."
        tags += authTag
        returning(
            Status.OK,
            currentUserBody to
                CurrentUserResponse("00000000-0000-0000-0000-000000000001", "brayan", "Brayan", roles = listOf("commerce.administrator")),
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
                Response(Status.OK).with(currentUserBody of user.toResponse())
            }
        }

private fun User.toResponse() =
    CurrentUserResponse(
        id.value.toString(),
        username,
        displayName,
        firstName,
        lastName,
        roles.map { it.role.value }.sorted(),
    )
