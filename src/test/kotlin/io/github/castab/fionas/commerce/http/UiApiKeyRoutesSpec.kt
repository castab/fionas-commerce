package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.fionas.commerce.testing.TEST_UI_API_KEY
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.withUiKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status

class UiApiKeyRoutesSpec :
    FunSpec({
        test("configuration rejects missing blank and malformed keys without disclosing them") {
            listOf(null, "", " ", "secret with spaces", "secret\nvalue").forEach { value ->
                val failure = shouldThrow<IllegalArgumentException> { UiApiKey(value) }
                failure.message shouldBe "FIONAS_UI_API_KEY must be configured with a nonblank Bearer-compatible key"
            }
            val key = UiApiKey(TEST_UI_API_KEY)
            key.toString() shouldNotContain TEST_UI_API_KEY
            shouldThrow<IllegalArgumentException> { UiApiKey.fromEnvironment(emptyMap()) }
            UiApiKey.fromEnvironment(mapOf("FIONAS_UI_API_KEY" to TEST_UI_API_KEY)).matches(TEST_UI_API_KEY) shouldBe true
        }

        test("all three customer routes reject every invalid credential before parsing bodies or invoking operations") {
            TestApplication.create().use { app ->
                val routes = listOf(Method.GET to "/inquiry-form", Method.POST to "/estimate-preview", Method.POST to "/inquiries")
                routes.forEach { (method, path) ->
                    val request = Request(method, path).header("Content-Type", "application/json").body("invalid JSON")
                    val failures =
                        listOf(
                            request,
                            request.header("Authorization", "Bearer wrong"),
                            request.header("Authorization", "Basic $TEST_UI_API_KEY"),
                            request.header("Authorization", "Bearer"),
                            request.header("Authorization", "Bearer $TEST_UI_API_KEY extra"),
                            request.withUiKey().header("Authorization", "Bearer wrong"),
                            request.query("api_key", TEST_UI_API_KEY),
                            request.header("Cookie", "api_key=$TEST_UI_API_KEY"),
                            request.body("""{"api_key":"$TEST_UI_API_KEY"}"""),
                            request.header("Cookie", app.adminCookie),
                        )
                    failures.forEach { invalid ->
                        val response = app.http(invalid)
                        response.status shouldBe Status.UNAUTHORIZED
                        CommerceJson.asA(response.bodyString(), ErrorResponse.serializer()) shouldBe
                            ErrorResponse("unauthenticated", "Authentication is required")
                        response.header("Cache-Control") shouldBe "no-store"
                        response.bodyString() shouldNotContain TEST_UI_API_KEY
                    }
                }
                app.database.count("fionas.inquiries") shouldBe 0
                // Valid UI authentication reaches existing handlers and their existing validation.
                app.http(Request(Method.GET, "/inquiry-form").withUiKey()).status shouldBe Status.NOT_FOUND
                listOf("/estimate-preview", "/inquiries").forEach { path ->
                    app.http(Request(Method.POST, path).withUiKey().header("Content-Type", "application/json").body("bad")).status shouldBe
                        Status.BAD_REQUEST
                }
            }
        }

        test("UI key grants no staff access while staff sessions still work") {
            TestApplication.create().use { app ->
                listOf(
                    Method.GET to "/inquiries",
                    Method.GET to "/inquiries/00000000-0000-0000-0000-000000000001",
                    Method.GET to "/auth/me",
                    Method.GET to "/admin/access/users",
                    Method.GET to "/payments/unapplied",
                    Method.POST to "/inquiries/00000000-0000-0000-0000-000000000001/estimates",
                    Method.PUT to "/admin/users/00000000-0000-0000-0000-000000000001/credentials/password",
                ).forEach { (method, path) ->
                    app.http(Request(method, path).withUiKey()).status shouldBe Status.UNAUTHORIZED
                }
                app.adminGet("/inquiries").status shouldBe Status.OK
                app.adminGet("/auth/me").status shouldBe Status.OK
                app.http(Request(Method.GET, "/health")).status shouldBe Status.OK
                app.http(Request(Method.GET, "/ready")).status shouldBe Status.OK
            }
        }
    })
