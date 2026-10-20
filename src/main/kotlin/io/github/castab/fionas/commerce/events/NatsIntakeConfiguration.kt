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
    requireIntakeSetting(database.connectionTimeoutMs in 1..5_000) {
        "DATABASE_CONNECTION_TIMEOUT_MS must be 1..5000; the runtime's effective value is ${database.connectionTimeoutMs}ms"
    }
    val parameters =
        try {
            database.jdbcUrl.substringAfter('?', "").split('&').filter(String::isNotEmpty).map {
                URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter('=', ""), StandardCharsets.UTF_8)
            }
        } catch (_: IllegalArgumentException) {
            // Decoder errors and URL/query values can contain credentials. Never retain their cause.
            throw IllegalArgumentException("Intake database configuration: DATABASE_JDBC_URL has malformed query encoding")
        }

    fun parameter(name: String): String {
        val matches = parameters.filter { it.first == name }
        requireIntakeSetting(matches.size == 1) {
            "DATABASE_JDBC_URL must contain exactly one $name query parameter; found ${matches.size}"
        }
        return matches.single().second
    }
    val socket = parameter("socketTimeout").toIntOrNull()
    requireIntakeSetting(socket != null && socket in 1..5) { "DATABASE_JDBC_URL socketTimeout must be 1..5 seconds" }
    val tokens = parameter("options").trim().split(Regex("\\s+"))
    requireIntakeSetting(tokens.size == 4 && tokens[0] == "-c" && tokens[2] == "-c") {
        "DATABASE_JDBC_URL options must contain two -c timeout assignments; check URL encoding and avoid surrounding quotes"
    }
    val assignments = listOf(tokens[1], tokens[3]).map { it.substringBefore('=') to it.substringAfter('=', "") }
    val settings = assignments.toMap()
    requireIntakeSetting(settings.keys == setOf("transaction_timeout", "statement_timeout")) {
        "DATABASE_JDBC_URL options must set transaction_timeout and statement_timeout once each, without additional assignments"
    }
    requireIntakeSetting(timeoutMillis(settings.getValue("transaction_timeout")) == 50_000L) {
        "DATABASE_JDBC_URL options transaction_timeout must be 50000ms (50s)"
    }
    requireIntakeSetting(timeoutMillis(settings.getValue("statement_timeout")) == 5_000L) {
        "DATABASE_JDBC_URL options statement_timeout must be 5000ms (5s)"
    }
}

private inline fun requireIntakeSetting(
    condition: Boolean,
    message: () -> String,
) {
    require(condition) { "Intake database configuration: ${message()}. See docs/inquiry-intake.md" }
}

/** The supported integer PostgreSQL timeout forms; overflow and unsupported/quoted values fail closed. */
private fun timeoutMillis(value: String): Long? {
    val match = Regex("([0-9]+)(ms|s|min)?").matchEntire(value) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    val multiplier =
        when (match.groupValues[2]) {
            "s" -> 1_000L
            "min" -> 60_000L
            else -> 1L
        }
    return try {
        Math.multiplyExact(amount, multiplier)
    } catch (_: ArithmeticException) {
        null
    }
}
