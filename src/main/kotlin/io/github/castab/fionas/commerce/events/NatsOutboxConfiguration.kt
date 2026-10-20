package io.github.castab.fionas.commerce.events

import io.nats.client.Options

/** Independent outbox credentials, with no generated secret-bearing toString. */
class NatsOutboxConfiguration private constructor(
    private val connection: NatsConnectionConfiguration,
) {
    val url get() = connection.url
    val credentialsFile get() = connection.credentialsFile

    internal fun authenticate(builder: Options.Builder) = connection.authenticate(builder)

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): NatsOutboxConfiguration? {
            when (environment["FIONAS_OUTBOX_PUBLISHER_ENABLED"]) {
                null, "false" -> return null
                "true" -> Unit
                else -> error("FIONAS_OUTBOX_PUBLISHER_ENABLED must be true or false")
            }
            return NatsOutboxConfiguration(NatsConnectionConfiguration.fromEnvironment(environment, "FIONAS_NATS"))
        }
    }
}
