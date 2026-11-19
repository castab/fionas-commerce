package io.github.castab.fionas.commerce.events

import io.nats.client.Nats
import io.nats.client.Options
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Secrets deliberately have no generated toString. Null configuration means disabled.
 *
 * `${prefix}_URL` is one server URL or a comma-separated server list of the same scheme. TLS
 * (`tls://`) uses the JVM default `SSLContext`: trust and any client certificate come from the
 * standard `javax.net.ssl.*` properties, never from Fiona settings.
 */
internal class NatsConnectionConfiguration private constructor(
    val servers: List<String>,
    val credentialsFile: Path?,
    private val user: String?,
    private val password: String?,
    private val token: String?,
) {
    /** The configured server list as given (comma-separated), for diagnostics without secrets. */
    val url: String get() = servers.joinToString(",")

    /**
     * Applies the server list to a client options builder. `tls://` servers use the JVM default
     * TLS context with hostname verification switched on (see [HostnameVerifyingTls]).
     */
    internal fun servers(builder: Options.Builder): Options.Builder =
        builder.servers(servers.toTypedArray()).also {
            if (servers.first().startsWith("tls://")) it.sslContext(HostnameVerifyingTls.context())
        }

    /** Same servers and the same authentication: one long-lived connection can serve both. */
    internal fun sameDestination(other: NatsConnectionConfiguration): Boolean =
        servers == other.servers &&
            credentialsFile == other.credentialsFile &&
            user == other.user &&
            password == other.password &&
            token == other.token

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
            val servers =
                environment["${prefix}_URL"]
                    ?.split(',')
                    ?.map(String::trim)
                    .orEmpty()
            val uris =
                servers.map {
                    try {
                        URI(it)
                    } catch (_: Exception) {
                        null
                    }
                }
            require(
                servers.isNotEmpty() &&
                    servers.size <= 16 &&
                    uris.all { uri ->
                        uri != null &&
                            uri.scheme in setOf("nats", "tls") &&
                            !uri.host.isNullOrBlank() &&
                            uri.rawUserInfo == null &&
                            uri.rawQuery == null &&
                            uri.rawFragment == null &&
                            uri.path.isNullOrEmpty() &&
                            (uri.port == -1 || uri.port in 1..65535)
                    } &&
                    uris.map { it?.scheme }.toSet().size == 1,
            ) {
                "${prefix}_URL must be one nats:// or tls:// server URL, or a comma-separated list of the same scheme, " +
                    "without embedded credentials"
            }
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
            return NatsConnectionConfiguration(servers, file, user, password, token)
        }
    }
}
