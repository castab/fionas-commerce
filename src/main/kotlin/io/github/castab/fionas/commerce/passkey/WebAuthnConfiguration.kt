package io.github.castab.fionas.commerce.passkey

import java.net.URI

/**
 * The WebAuthn relying party staff passkeys belong to. Null configuration means passkeys are
 * disabled: no ceremony starts and no passkey signs in, while password sign-in is unaffected.
 *
 * [rpId] is the admin host itself (for example `admin.fionasicecream.com`), never a parent
 * domain: each environment's passkeys stay with that environment's database. [origins] are
 * the exact browser origins a ceremony may come from (the signed `clientDataJSON.origin`).
 * Each must be https (or http on localhost), on [rpId] or a subdomain of it, and also one of
 * Fiona's trusted browser origins.
 */
class WebAuthnConfiguration(
    val rpId: String,
    val rpName: String,
    val origins: Set<String>,
) {
    init {
        require(RP_ID.matches(rpId)) { "FIONAS_WEBAUTHN_RP_ID must be a lowercase host name without scheme or port" }
        require(rpName.isNotBlank() && rpName.length <= 64) { "FIONAS_WEBAUTHN_RP_NAME must be 1-64 characters" }
        require(origins.isNotEmpty()) { "FIONAS_WEBAUTHN_ORIGINS must name at least one origin" }
        origins.forEach { origin ->
            val uri = runCatching { URI(origin) }.getOrNull()
            val host = uri?.host
            require(uri != null && host != null && uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null) {
                "FIONAS_WEBAUTHN_ORIGINS entries must be bare origins: $origin"
            }
            require(uri.scheme == "https" || uri.scheme == "http" && host == "localhost") {
                "FIONAS_WEBAUTHN_ORIGINS entries must be https (or http://localhost): $origin"
            }
            require(host == rpId || host.endsWith(".$rpId")) {
                "FIONAS_WEBAUTHN_ORIGINS entry $origin is not on FIONAS_WEBAUTHN_RP_ID"
            }
        }
    }

    /** Fails unless every passkey origin is also a trusted browser origin (the Origin policy admits it). */
    fun requireTrusted(trustedOrigins: Set<String>) {
        val untrusted = origins - trustedOrigins
        require(untrusted.isEmpty()) { "FIONAS_WEBAUTHN_ORIGINS must also be in FIONAS_TRUSTED_ORIGINS: ${untrusted.sorted()}" }
    }

    override fun toString() = "WebAuthnConfiguration(rpId=$rpId, origins=$origins)"

    companion object {
        private val RP_ID = Regex("^(localhost|([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63})$")
        private val NAMES = listOf("FIONAS_WEBAUTHN_RP_ID", "FIONAS_WEBAUTHN_RP_NAME", "FIONAS_WEBAUTHN_ORIGINS")

        /** All three variables, or none (passkeys disabled). A partial configuration fails startup. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): WebAuthnConfiguration? {
            val present = NAMES.filter { !environment[it].isNullOrBlank() }
            if (present.isEmpty()) return null
            require(present.size == NAMES.size) { "Staff passkeys need all of ${NAMES.joinToString()}; missing ${NAMES - present.toSet()}" }
            return WebAuthnConfiguration(
                environment.getValue("FIONAS_WEBAUTHN_RP_ID").trim(),
                environment.getValue("FIONAS_WEBAUTHN_RP_NAME").trim(),
                environment
                    .getValue("FIONAS_WEBAUTHN_ORIGINS")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toSet(),
            )
        }
    }
}
