package io.github.castab.fionas.commerce.events

import io.nats.client.Nats
import io.nats.client.Options
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Secrets deliberately have no generated toString. Null configuration means disabled. */
internal class NatsConnectionConfiguration private constructor(
    val url: String,
    val credentialsFile: Path?,
    private val user: String?,
    private val password: String?,
    private val token: String?,
) {
    internal fun authenticate(builder: Options.Builder) {
        credentialsFile?.let {
            try {
                builder.authHandler(Nats.credentials(it.toString()))
            } catch (_: Exception) {
                throw IllegalArgumentException("NATS credentials file could not be loaded")
            }
        }
        user?.let { builder.userInfo(it.toCharArray(), checkNotNull(password).toCharArray()) }
        token?.let { builder.token(it.toCharArray()) }
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String>,
            prefix: String,
        ): NatsConnectionConfiguration {
            val url = environment["${prefix}_URL"]
            val uri =
                try {
                    url?.let(::URI)
                } catch (_: Exception) {
                    null
                }
            require(
                uri != null &&
                    uri.scheme in setOf("nats", "tls") &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    uri.path.isNullOrEmpty() &&
                    (uri.port == -1 || uri.port in 1..65535),
            ) { "${prefix}_URL must be one nats:// or tls:// server URL without embedded credentials" }
            val file =
                environment["${prefix}_CREDS_FILE"]?.let {
                    val path =
                        try {
                            Path.of(it)
                        } catch (_: Exception) {
                            null
                        }
                    require(it.isNotBlank() && path != null && Files.isRegularFile(path) && Files.isReadable(path)) {
                        "${prefix}_CREDS_FILE must identify a readable mounted credentials file"
                    }
                    path
                }
            val user = environment["${prefix}_USER"]
            val password = environment["${prefix}_PASSWORD"]
            require((user == null) == (password == null) && (user == null || user.isNotBlank() && !password.isNullOrBlank())) {
                "${prefix}_USER and ${prefix}_PASSWORD must both be nonblank when configured"
            }
            val token = environment["${prefix}_TOKEN"]
            require(token == null || token.isNotBlank()) { "${prefix}_TOKEN must be nonblank when configured" }
            require(listOfNotNull(file, user, token).size <= 1) {
                "Use one NATS authentication method: mounted credentials, user/password, or token"
            }
            return NatsConnectionConfiguration(checkNotNull(url), file, user, password, token)
        }
    }
}
