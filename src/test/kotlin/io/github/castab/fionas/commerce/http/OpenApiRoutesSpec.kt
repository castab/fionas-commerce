package io.github.castab.fionas.commerce.http

import io.github.castab.fionas.commerce.openapi.fionaOpenApiDocument
import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

/** `/openapi.json` and `/docs` as the complete fionas-commerce HTTP handler serves them. */
class OpenApiRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun get(path: String) = application.http(Request(Method.GET, path))

        test("GET /openapi.json serves the OpenAPI document as JSON") {
            val response = get("/openapi.json")

            response.status shouldBe Status.OK
            response.header("Content-Type") shouldBe "application/json; charset=utf-8"
            Json.parseToJsonElement(response.bodyString())
        }

        test("the served document is the one the build generates") {
            Json.parseToJsonElement(get("/openapi.json").bodyString()) shouldBe Json.parseToJsonElement(fionaOpenApiDocument())
        }

        test("GET /docs leads to Swagger UI") {
            get("/docs").let {
                it.status shouldBe Status.FOUND
                it.header("Location") shouldBe "/docs/index.html"
            }
            get("/docs/index.html").let {
                it.status shouldBe Status.OK
                it.header("Content-Type")!! shouldStartWith "text/html"
                it.bodyString() shouldContain """<div id="swagger-ui"></div>"""
            }
        }

        test("the document Swagger UI reads offers the bound runtime capabilities' operations alongside Fiona's") {
            val document = Json.parseToJsonElement(get("/openapi.json").bodyString()).jsonObject
            val operationIds =
                document
                    .getValue("paths")
                    .jsonObject.values
                    .flatMap { methods ->
                        methods.jsonObject.values.map {
                            it.jsonObject
                                .getValue("operationId")
                                .jsonPrimitive.content
                        }
                    }

            operationIds shouldContainAll
                listOf(
                    "createInquiry",
                    "getInquiry",
                    "fionasOfferingsCreateCatalog",
                    "fionasOfferingsAddCategory",
                    "fionasOfferingsAddOffering",
                    "fionasOfferingsGetCatalog",
                    "fionasOfferingsGetCatalogRevision",
                    "getCurrentUser",
                    "authorizationCurrentPrincipal",
                    "authorizationListPermissions",
                )
        }

        test("Swagger UI reads /openapi.json and loads nothing from outside the application") {
            get("/docs/swagger-initializer.js").bodyString().let {
                it shouldContain """url: "/openapi.json""""
                it shouldContain """document.title = "Fiona's Commerce API""""
            }
            val page = get("/docs/index.html").bodyString()
            page shouldNotContain "://"
            listOf("swagger-ui-bundle.js", "swagger-ui-standalone-preset.js", "swagger-ui.css").forEach { asset ->
                page shouldContain "./$asset"
                get("/docs/$asset").let {
                    it.status shouldBe Status.OK
                    it.bodyString().length shouldBeGreaterThan 10_000
                }
            }
        }
    })
