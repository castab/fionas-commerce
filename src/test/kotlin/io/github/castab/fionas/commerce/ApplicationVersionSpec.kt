package io.github.castab.fionas.commerce

import io.github.castab.fionas.commerce.openapi.fionaOpenApiDocument
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The application version is the Gradle project version and nothing else: a release build
 * (`-Pversion=<version>`) reaches the jar's `fionas-commerce.properties`, `fionaVersion()`, and
 * the OpenAPI document's `info.version`. The expected value comes from the build itself, so the
 * spec holds for the development default and for any release version it is run with.
 */
class ApplicationVersionSpec :
    FunSpec({
        val buildVersion =
            requireNotNull(System.getProperty("fionas.build.version")) { "The build passes the project version to the tests" }

        test("the application reports the version the build was given") {
            fionaVersion() shouldBe buildVersion
        }

        test("the generated OpenAPI document carries that version") {
            val info =
                Json
                    .parseToJsonElement(fionaOpenApiDocument())
                    .jsonObject
                    .getValue("info")
                    .jsonObject

            info.getValue("version").jsonPrimitive.content shouldBe buildVersion
        }
    })
