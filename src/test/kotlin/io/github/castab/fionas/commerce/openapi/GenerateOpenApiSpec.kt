package io.github.castab.fionas.commerce.openapi

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.io.File

/** The `generateOpenApi` entry point: the build artifact other applications consume. */
class GenerateOpenApiSpec :
    FunSpec({
        test("writes the contract's document as UTF-8 JSON, creating the directory") {
            val output = File(tempdir(), "openapi/fionas-commerce-openapi.json")

            main(arrayOf(output.path))

            Json.parseToJsonElement(output.readText(Charsets.UTF_8)) shouldBe Json.parseToJsonElement(fionaOpenApiDocument())
        }

        test("generates identical bytes every time") {
            val directory = tempdir()
            val first = File(directory, "first.json").also { main(arrayOf(it.path)) }
            val second = File(directory, "second.json").also { main(arrayOf(it.path)) }

            second.readBytes().contentEquals(first.readBytes()) shouldBe true
        }
    })
