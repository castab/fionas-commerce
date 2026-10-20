package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.ApplicationContributions
import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration.Migrations.OnStartup
import io.github.castab.commerce.runtime.persistence.ApplicationMigrations
import io.github.castab.fionas.commerce.FIONA_MIGRATION_LOCATION
import io.github.castab.fionas.commerce.FIONA_MIGRATION_SCHEMA
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.nats.client.Connection
import io.nats.client.ErrorListener
import io.nats.client.JetStreamOptions
import io.nats.client.Nats
import io.nats.client.Options
import io.nats.client.api.MessageInfo
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.system.exitProcess

/** Read-only exact retained-message lookup. Never creates a consumer or touches the primary ACK state. */
internal fun recoverInquiry(
    sequence: Long,
    stored: MessageInfo,
    handle: (ByteArray) -> Unit,
    publish: (String, ByteArray) -> Unit,
): IntakeResult {
    require(sequence > 0 && stored.seq == sequence && stored.subject == INQUIRY_INPUT_SUBJECT && stored.stream == INQUIRY_INPUT_STREAM) {
        "Exact retained inquiry source is unavailable"
    }
    val delivery =
        object : IntakeDelivery {
            override val bytes: ByteArray = checkNotNull(stored.data)
            override val sequence: Long = sequence
            override val attempt = 1L
            override val storedAt: Instant = checkNotNull(stored.time).toInstant()

            override fun acknowledge() = Unit

            override fun retry(delay: Duration) = Unit

            override fun terminate() = Unit
        }
    return IntakeProcessor(handle, publish).process(delivery, sourceDisposition = false)
}

/** Packaged operator entry point: java -cp <same executable jar> ...events.InquiryRecovery <sequence>. */
object InquiryRecovery {
    @JvmStatic
    fun main(args: Array<String>) {
        val result =
            try {
                require(args.size == 1) { "Supply exactly one positive input stream sequence" }
                val sequence = args.single().toLong().also { require(it > 0) }
                val configuration =
                    checkNotNull(NatsIntakeConfiguration.fromEnvironment()) { "Enable intake configuration for this operator process" }
                val runtimeConfiguration =
                    CommerceRuntimeConfiguration.load(
                        environment = System.getenv().filterKeys { !it.startsWith("SERVICE_TOKENS_") },
                        resource = "/inquiry-recovery.conf",
                    )
                require(
                    runtimeConfiguration.migrations.onStartup == OnStartup.VALIDATE,
                ) { "Recovery requires MIGRATIONS_ON_STARTUP=validate" }
                requireIntakeDatabaseBounds(runtimeConfiguration.database)
                val options =
                    Options
                        .builder()
                        .server(configuration.connection.url)
                        .connectionName("fionas-commerce-inquiry-recovery")
                        .connectionTimeout(Duration.ofSeconds(1))
                        .maxReconnects(0)
                        .reconnectBufferSize(0)
                        .socketWriteTimeout(1_000)
                        .errorListener(
                            object : ErrorListener {
                                override fun errorOccurred(
                                    connection: Connection,
                                    error: String,
                                ) = Unit

                                override fun exceptionOccurred(
                                    connection: Connection,
                                    exception: Exception,
                                ) = Unit
                            },
                        ).also(configuration.connection::authenticate)
                        .build()
                Nats.connect(options).use { connection ->
                    val stored =
                        connection
                            .jetStreamManagement(JetStreamOptions.builder().requestTimeout(Duration.ofSeconds(5)).build())
                            .getMessage(INQUIRY_INPUT_STREAM, sequence)
                    var handle: ((ByteArray) -> Unit)? = null
                    NatsIntakeTransport(configuration).use { diagnosticTransport ->
                        // Compose the operation without starting HTTP, bootstrap or background services.
                        commerceRuntime(
                            runtimeConfiguration,
                            ApplicationContributions(
                                migrations = ApplicationMigrations(FIONA_MIGRATION_SCHEMA, listOf(FIONA_MIGRATION_LOCATION)),
                                permissionDefinitions = FionaPermissions.definitions,
                                routes = { context ->
                                    handle = inquiryIntakeHandler(context, configuration, Clock.systemUTC())
                                    emptyList()
                                },
                            ),
                        ).use {
                            recoverInquiry(sequence, stored, checkNotNull(handle), diagnosticTransport::publishDiagnostic)
                        }
                    }
                }
            } catch (_: Exception) {
                // Driver/client/SQL errors can contain secrets and customer data. Operator repairs then retries deliberately.
                System.err.println(
                    "event=intake_recovery_failed code=check_configuration_authority_connectivity_and_exact_retained_sequence",
                )
                exitProcess(1)
            }
        println("event=intake_recovery_completed result=$result primaryAckState=unchanged outboxDelivery=not_verified")
        if (result != IntakeResult.PERSISTED) exitProcess(1)
    }
}
