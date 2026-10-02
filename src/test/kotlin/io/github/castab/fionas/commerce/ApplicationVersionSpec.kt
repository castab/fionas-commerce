package io.github.castab.fionas.commerce

import io.github.castab.fionas.commerce.openapi.fionaOpenApiDocument
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The build embeds no version: the version the application reports as the OpenAPI document's
 * `info.version` is the optional `APP_VERSION` environment variable, else a fixed placeholder,
 * so one image serves every release tag.
 */
class ApplicationVersionSpec :
    FunSpec({
        test("without APP_VERSION the application reports the placeholder") {
            fionaVersion(emptyMap()) shouldBe UNVERSIONED
        }

        test("a blank APP_VERSION is ignored") {
            fionaVersion(mapOf("APP_VERSION" to "  ")) shouldBe UNVERSIONED
        }

        test("APP_VERSION is reported, trimmed") {
            fionaVersion(mapOf("APP_VERSION" to " 0.0.21 ")) shouldBe "0.0.21"
        }

        test("the OpenAPI document carries the version it is rendered for") {
            val info =
                Json
                    .parseToJsonElement(fionaOpenApiDocument("0.0.21"))
                    .jsonObject
                    .getValue("info")
                    .jsonObject

            info.getValue("version").jsonPrimitive.content shouldBe "0.0.21"
        }
    })
