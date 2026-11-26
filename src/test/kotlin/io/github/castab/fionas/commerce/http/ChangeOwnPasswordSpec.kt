package io.github.castab.fionas.commerce.http

import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

class ChangeOwnPasswordSpec :
    FunSpec({
        fun TestApplication.call(
            method: Method,
            path: String,
            cookie: String? = null,
            body: String = "",
        ): Response =
            http(
                Request(method, path)
                    .header("Origin", TEST_ORIGIN)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .let { if (cookie == null) it else it.header("Cookie", cookie) },
            )

        fun TestApplication.login(password: String) =
            call(Method.POST, "/auth/login", body = """{"username":"admin","password":"$password"}""")

        fun TestApplication.change(
            cookie: String,
            current: String,
            new: String,
        ) = call(Method.PUT, "/staff/credentials/password", cookie, """{"currentPassword":"$current","newPassword":"$new"}""")

        fun Response.cookie() = checkNotNull(header("Set-Cookie")).substringBefore(';')

        test("changing my password signs out every other session and keeps this browser signed in") {
            TestApplication.create().use { app ->
                val here = app.login("test-admin-password").cookie()
                val elsewhere = app.login("test-admin-password").cookie()

                val changed = app.change(here, "test-admin-password", "a much longer new passphrase")
                changed.status shouldBe Status.NO_CONTENT
                changed.header("Set-Cookie") shouldContain "HttpOnly"
                val renewed = changed.cookie()

                app.call(Method.GET, "/auth/me", here).status shouldBe Status.UNAUTHORIZED
                app.call(Method.GET, "/auth/me", elsewhere).status shouldBe Status.UNAUTHORIZED
                app.call(Method.GET, "/auth/me", renewed).status shouldBe Status.OK
                app.login("test-admin-password").status shouldBe Status.UNAUTHORIZED
                app.login("a much longer new passphrase").status shouldBe Status.NO_CONTENT
            }
        }

        test("a wrong current password is 403 current_password_incorrect and changes nothing") {
            TestApplication.create().use { app ->
                val response = app.change(app.adminCookie, "not-my-password", "a much longer new passphrase")
                response.status shouldBe Status.FORBIDDEN
                response.bodyString() shouldContain "current_password_incorrect"
                app.call(Method.GET, "/auth/me", app.adminCookie).status shouldBe Status.OK
                app.login("test-admin-password").status shouldBe Status.NO_CONTENT
            }
        }

        test("the new password must be long enough, not too long, and different") {
            TestApplication.create().use { app ->
                app.change(app.adminCookie, "test-admin-password", "short").status shouldBe Status.UNPROCESSABLE_ENTITY
                app.change(app.adminCookie, "test-admin-password", "x".repeat(257)).status shouldBe Status.UNPROCESSABLE_ENTITY
                app.change(app.adminCookie, "test-admin-password", "test-admin-password").status shouldBe Status.UNPROCESSABLE_ENTITY
                app.call(Method.GET, "/auth/me", app.adminCookie).status shouldBe Status.OK
            }
        }

        test("only a staff USER changes their own password, at a limited rate") {
            TestApplication.create(passwordRateLimit = LoginRateLimit(capacity = 2)).use { app ->
                app.http(Request(Method.PUT, "/staff/credentials/password").asFionasWeb(app).body("{}")).status shouldBe
                    Status.FORBIDDEN
                app.change(app.adminCookie, "wrong-1", "a much longer new passphrase").status shouldBe Status.FORBIDDEN
                app.change(app.adminCookie, "wrong-2", "a much longer new passphrase").let {
                    it.status shouldBe Status.TOO_MANY_REQUESTS
                    it.header("Retry-After") shouldNotBe null
                }
            }
        }
    })
