package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.staff.ServiceId
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Disabled by default. Construction validates configuration without any broker or database I/O. */
class NatsIntakeConfiguration private constructor(
    internal val connection: NatsConnectionConfiguration,
    val author: ServiceId,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): NatsIntakeConfiguration? {
            when (environment["FIONAS_INTAKE_ENABLED"]) {
                null, "false" -> return null
                "true" -> Unit
                else -> error("FIONAS_INTAKE_ENABLED must be true or false")
            }
            require(listOf("CREDS_FILE", "USER", "TOKEN").count { environment.containsKey("FIONAS_INTAKE_NATS_$it") } == 1) {
                "Intake requires exactly one NATS authentication method: mounted credentials, user/password, or token"
            }
            val id = environment["FIONAS_INTAKE_AUTHOR_SERVICE_ID"]
            require(id != null && submittedUuid.matches(id)) { "FIONAS_INTAKE_AUTHOR_SERVICE_ID must be a SERVICE UUID" }
            return NatsIntakeConfiguration(
                NatsConnectionConfiguration.fromEnvironment(environment, "FIONAS_INTAKE_NATS"),
                ServiceId(UUID.fromString(id)),
            )
        }
    }
}

/**
 * Use the runtime's supported JDBC configuration, not an application transaction/cancellation manager.
 * PostgreSQL 18 bounds the whole transaction, including idle time; the driver bounds a lost socket.
 * These are explicit enablement prerequisites, never silently changed by Fiona.
 */
internal fun requireIntakeDatabaseBounds(database: CommerceRuntimeConfiguration.Database) {
    val parameters =
        database.jdbcUrl.substringAfter('?', "").split('&').filter(String::isNotEmpty).map {
            URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
        }

    fun parameter(name: String): String? = parameters.filter { it.first == name }.singleOrNull()?.second
    val socket = parameter("socketTimeout")?.toIntOrNull()
    val options = parameter("options")
    require(
        socket != null &&
            socket in 1..5 &&
            options == "-c transaction_timeout=50000 -c statement_timeout=5000" &&
            database.connectionTimeoutMs <= 5_000,
    ) {
        "Intake requires DATABASE_CONNECTION_TIMEOUT_MS <= 5000 and JDBC socketTimeout=1..5 plus options=-c transaction_timeout=50000 -c statement_timeout=5000; see docs/inquiry-intake.md"
    }
}
