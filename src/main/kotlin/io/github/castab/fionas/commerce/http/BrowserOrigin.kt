package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.errorResponse
import io.github.castab.commerce.runtime.session.SessionCookie
import org.http4k.core.Filter
import org.http4k.core.Method

/**
 * Fiona's same-origin policy for cookie-authenticated browser mutations and for the public
 * browser sign-in endpoints ([PUBLIC_BROWSER_SIGN_IN_PATHS]), which carry no cookie yet.
 */
class BrowserOrigin(
    private val trustedOrigins: Set<String>,
    private val cookie: SessionCookie,
) {
    val filter: Filter =
        Filter { next ->
            { request ->
                val unsafe = request.method in setOf(Method.POST, Method.PUT, Method.PATCH, Method.DELETE)
                val browserAuthentication = request.uri.path in PUBLIC_BROWSER_SIGN_IN_PATHS || cookie.extract(request) != null
                if (unsafe && browserAuthentication && request.header("Origin") !in trustedOrigins) {
                    errorResponse(ErrorCategory.FORBIDDEN, "The browser origin is not trusted")
                } else {
                    next(request)
                }
            }
        }
}

/** Password login and both passkey sign-in steps: each needs a trusted Origin even without a session cookie. */
val PUBLIC_BROWSER_SIGN_IN_PATHS = setOf("/auth/login", PASSKEY_SIGN_IN_OPTIONS_PATH, PASSKEY_SIGN_IN_PATH)
