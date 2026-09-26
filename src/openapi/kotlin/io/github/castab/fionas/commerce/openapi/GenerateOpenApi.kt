package io.github.castab.fionas.commerce.openapi

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.fionas.commerce.fionaVersion
import io.github.castab.fionas.commerce.http.FionaOperations
import io.github.castab.fionas.commerce.http.OPENAPI_PATH
import io.github.castab.fionas.commerce.http.fionaApi
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
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
 * The OpenAPI document of the Fiona API, exactly as the running application serves it at
 * [OPENAPI_PATH]: the same [fionaApi] contract answers the same request, with no database,
 * server, or network.
 */
fun fionaOpenApiDocument(version: String = fionaVersion()): String {
    val response = fionaApi(notInvoked, version)(Request(Method.GET, OPENAPI_PATH))
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
