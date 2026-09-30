package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.errorResponse
import org.http4k.core.Filter
import org.http4k.security.BearerAuthSecurity
import java.security.MessageDigest

/** One server-side UI credential. Only its digest is retained; text forms reveal no secret. */
class UiApiKey(
    value: String?,
) {
    private val digest: ByteArray

    init {
        require(value != null && TOKEN.matches(value)) {
            "FIONAS_UI_API_KEY must be configured with a nonblank Bearer-compatible key"
        }
        digest = sha256(value)
    }

    internal fun matches(value: String): Boolean = MessageDigest.isEqual(digest, sha256(value))

    override fun toString(): String = "UiApiKey([redacted])"

    companion object {
        private val TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): UiApiKey = UiApiKey(environment["FIONAS_UI_API_KEY"])

        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}

private val bearerCredential = Regex("Bearer +([A-Za-z0-9._~+/-]+=*)", RegexOption.IGNORE_CASE)

fun uiApiKeySecurity(key: UiApiKey): BearerAuthSecurity = BearerAuthSecurity(uiApiKeyAuthentication(key), "fionasUiApiKey")

/** Fiona-local HTTP authentication; it never creates a staff principal or session. */
fun uiApiKeyAuthentication(key: UiApiKey): Filter =
    Filter { next ->
        { request ->
            val header = request.headerValues("Authorization").singleOrNull()
            val supplied = header?.let { bearerCredential.matchEntire(it)?.groupValues?.get(1) }
            if (supplied != null && key.matches(supplied)) {
                next(request)
            } else {
                errorResponse(ErrorCategory.UNAUTHENTICATED, "Authentication is required")
                    .header("Cache-Control", "no-store")
            }
        }
    }
