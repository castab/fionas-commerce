package io.github.castab.fionas.commerce.events

import io.nats.client.Nats
import io.nats.client.Options
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Secrets deliberately have no generated toString. Null configuration means disabled. */
class NatsOutboxConfiguration private constructor(
    val url: String,
    val credentialsFile: Path?,
    private val user: String?,
    private val password: String?,
    private val token: String?,
) {
    internal fun authenticate(builder: Options.Builder) {
        credentialsFile?.let { builder.authHandler(Nats.credentials(it.toString())) }
        user?.let { builder.userInfo(it.toCharArray(), checkNotNull(password).toCharArray()) }
        token?.let { builder.token(it.toCharArray()) }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): NatsOutboxConfiguration? {
            when (environment["FIONAS_OUTBOX_PUBLISHER_ENABLED"]) {
                null, "false" -> return null
                "true" -> Unit
                else -> error("FIONAS_OUTBOX_PUBLISHER_ENABLED must be true or false")
            }
            val url = environment["FIONAS_NATS_URL"]
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
            ) { "FIONAS_NATS_URL must be one nats:// or tls:// server URL without embedded credentials" }
            val file =
                environment["FIONAS_NATS_CREDS_FILE"]?.let {
                    val path =
                        try {
                            Path.of(it)
                        } catch (_: Exception) {
                            null
                        }
                    require(it.isNotBlank() && path != null && Files.isRegularFile(path) && Files.isReadable(path)) {
                        "FIONAS_NATS_CREDS_FILE must identify a readable mounted credentials file"
                    }
                    path
                }
            val user = environment["FIONAS_NATS_USER"]
            val password = environment["FIONAS_NATS_PASSWORD"]
            require((user == null) == (password == null) && (user == null || user.isNotBlank() && !password.isNullOrBlank())) {
                "FIONAS_NATS_USER and FIONAS_NATS_PASSWORD must both be nonblank when configured"
            }
            val token = environment["FIONAS_NATS_TOKEN"]
            require(token == null || token.isNotBlank()) { "FIONAS_NATS_TOKEN must be nonblank when configured" }
            require(listOfNotNull(file, user, token).size <= 1) {
                "Use one NATS authentication method: mounted credentials, user/password, or token"
            }
            return NatsOutboxConfiguration(checkNotNull(url), file, user, password, token)
        }
    }
}
