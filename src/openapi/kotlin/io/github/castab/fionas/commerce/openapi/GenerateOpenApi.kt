package io.github.castab.fionas.commerce.openapi

import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.runtime.CommerceRuntimeContext
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.offering.offeringsHttpCapability
import io.github.castab.commerce.runtime.persistence.OfferingsSnapshotRepository
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.fionas.commerce.fionaVersion
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.OPENAPI_PATH
import io.github.castab.fionas.commerce.http.fionaApi
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_BINDING
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.jdbi.v3.core.Jdbi
import java.nio.file.Files
import java.nio.file.Path

/**
 * Operations for rendering the contract only. Rendering never calls an operation, so none
 * needs persistence, a database, or a server.
 */
private val notInvoked =
    FionaOperations(
        createInquiry = { error("Rendering the OpenAPI document never creates an inquiry") },
        getInquiry = { error("Rendering the OpenAPI document never reads an inquiry") },
    )

/**
 * A `CommerceRuntimeContext` for rendering only: its transactor opens no connection and its
 * repository refuses every call, and rendering calls neither.
 *
 * A workaround for a commerce-runtime 0.0.8 gap (AGENTS.md, "Known upstream gaps"): the
 * Offerings capability builds its contract routes only from a context, and only a composed
 * runtime, which needs a database, creates one; the constructor is `internal` to Kotlin
 * callers, so it is called reflectively. It lives in this source set, never in the
 * deployable jar, and goes away once the runtime can describe its routes without a database.
 */
private fun renderingOnlyContext(): CommerceRuntimeContext {
    val configuration =
        CommerceRuntimeConfiguration(
            database =
                CommerceRuntimeConfiguration.Database(
                    jdbcUrl = "jdbc:postgresql://rendering-only.invalid/none",
                    username = "",
                    password = "",
                ),
        )
    val transactor = Transactor(Jdbi.create { error("Rendering the OpenAPI document never opens a connection") })
    val snapshots =
        object : OfferingsSnapshotRepository {
            override fun insert(
                transaction: Transaction,
                snapshot: OfferingsSnapshot,
            ) = error("Rendering the OpenAPI document never writes a catalog")

            override fun retrieveVersion(
                transaction: Transaction,
                reference: OfferingsSnapshotReference,
            ) = error("Rendering the OpenAPI document never reads a catalog")

            override fun retrieveLatestVersion(
                transaction: Transaction,
                catalogId: OfferingsCatalogId,
            ) = error("Rendering the OpenAPI document never reads a catalog")
        }
    return CommerceRuntimeContext::class.java
        .getConstructor(CommerceRuntimeConfiguration::class.java, Transactor::class.java, OfferingsSnapshotRepository::class.java)
        .newInstance(configuration, transactor, snapshots)
}

/**
 * The OpenAPI document of the Fiona API, exactly as the running application serves it at
 * [OPENAPI_PATH]: the same [fionaApi] contract, with the same Offerings binding, answers the
 * same request, with no database, server, or network.
 */
fun fionaOpenApiDocument(version: String = fionaVersion()): String {
    val offerings = offeringsHttpCapability(renderingOnlyContext(), FIONA_OFFERINGS_BINDING)
    val response = fionaApi(notInvoked, offerings, version)(Request(Method.GET, OPENAPI_PATH))
    check(response.status == Status.OK) { "Rendering the OpenAPI document failed: ${response.status}" }
    return response.bodyString()
}

/**
 * Writes the OpenAPI document to the path in [args], pretty-printed UTF-8 JSON (the `generateOpenApi` Gradle task).
 */
fun main(args: Array<String>) {
    val output = Path.of(args.single())
    Files.createDirectories(output.toAbsolutePath().parent)
    Files.writeString(output, CommerceJson.pretty(CommerceJson.parse(fionaOpenApiDocument())) + "\n")
}
