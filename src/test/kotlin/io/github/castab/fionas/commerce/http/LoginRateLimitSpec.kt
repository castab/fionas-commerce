package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestSource
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class LoginRateLimitSpec :
    FunSpec({
        test("five attempts per IP refill gradually and Retry-After rounds up") {
            var now = 0L
            val handler = LoginRateLimit({ now }).filter.then { Response(Status.NO_CONTENT) }
            val request = Request(Method.POST, "/auth/login").source(RequestSource("192.0.2.1", 1000))
            repeat(5) { handler(request).status shouldBe Status.NO_CONTENT }
            val limited = handler(request)
            limited.status shouldBe Status.TOO_MANY_REQUESTS
            limited.header("Retry-After") shouldBe "300"
            limited.header("Cache-Control") shouldBe "no-store"
            CommerceJson.asA(limited.bodyString(), ErrorResponse.serializer()) shouldBe LOGIN_RATE_LIMIT_ERROR
            // Ports and spoofed proxy headers cannot acquire another bucket.
            handler(
                request
                    .source(RequestSource("192.0.2.1", 2000))
                    .header("X-Forwarded-For", "192.0.2.2")
                    .header("Forwarded", "for=192.0.2.3")
                    .header("X-Real-IP", "192.0.2.4"),
            ).status shouldBe Status.TOO_MANY_REQUESTS
            handler(request.source(RequestSource("192.0.2.2"))).status shouldBe Status.NO_CONTENT
            now = Duration.ofSeconds(299).toNanos() + 1
            handler(request).header("Retry-After") shouldBe "1"
            now = Duration.ofMinutes(5).toNanos()
            handler(request).status shouldBe Status.NO_CONTENT
            handler(request).status shouldBe Status.TOO_MANY_REQUESTS
            now += Duration.ofMinutes(25).toNanos()
            repeat(5) { handler(request).status shouldBe Status.NO_CONTENT }
            handler(request).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("missing source addresses share a bucket and identity churn cannot evict depleted buckets") {
            var now = 0L
            val handler = LoginRateLimit({ now }, maxIdentities = 2).filter.then { Response(Status.NO_CONTENT) }
            val request = Request(Method.POST, "/auth/login")
            repeat(5) { handler(request).status shouldBe Status.NO_CONTENT }
            handler(request).status shouldBe Status.TOO_MANY_REQUESTS
            handler(request.source(RequestSource("192.0.2.1"))).status shouldBe Status.NO_CONTENT
            repeat(100) { index ->
                handler(request.source(RequestSource("198.51.100.$index"))).status shouldBe Status.TOO_MANY_REQUESTS
            }
            handler(request).status shouldBe Status.TOO_MANY_REQUESTS
            now = Duration.ofMinutes(25).toNanos()
            repeat(5) { handler(request.source(RequestSource("203.0.113.1"))).status shouldBe Status.NO_CONTENT }
        }

        test("concurrent attempts spend exactly the burst capacity") {
            val handler = LoginRateLimit({ 0L }).filter.then { Response(Status.NO_CONTENT) }
            val request = Request(Method.POST, "/auth/login").source(RequestSource("192.0.2.1"))
            Executors.newFixedThreadPool(8).use { pool ->
                val statuses = pool.invokeAll(List(40) { Callable { handler(request).status } }).map { it.get() }
                statuses.count { it == Status.NO_CONTENT } shouldBe 5
                statuses.count { it == Status.TOO_MANY_REQUESTS } shouldBe 35
            }
        }

        test("complete login handler counts successes malformed requests and invalid passwords without resetting on success") {
            var now = 0L
            TestApplication.create(loginRateLimit = LoginRateLimit({ now })).use { app ->
                fun login(
                    body: String,
                    ip: String = "192.0.2.1",
                ) = app.http(
                    Request(Method.POST, "/auth/login")
                        .source(RequestSource(ip))
                        .header("Origin", TEST_ORIGIN)
                        .header("Content-Type", "application/json")
                        .body(body),
                )
                val valid = """{"username":"admin","password":"test-admin-password"}"""
                val invalid = """{"username":"admin","password":"wrong-password"}"""
                login(valid).status shouldBe Status.NO_CONTENT
                login("bad").status shouldBe Status.BAD_REQUEST
                repeat(3) { login(invalid).status shouldBe Status.UNAUTHORIZED }
                val limited = login(valid)
                limited.status shouldBe Status.TOO_MANY_REQUESTS
                limited.header("Retry-After") shouldBe "300"
                CommerceJson.asA(limited.bodyString(), ErrorResponse.serializer()) shouldBe LOGIN_RATE_LIMIT_ERROR
                limited.header("Set-Cookie") shouldBe null
                app.database.count("commerce.principal_sessions") shouldBe 1
                login(valid, "192.0.2.2").status shouldBe Status.NO_CONTENT
                app.adminGet("/auth/me").status shouldBe Status.OK
                now = Duration.ofMinutes(5).toNanos()
                login(valid).status shouldBe Status.NO_CONTENT
                login(valid).status shouldBe Status.TOO_MANY_REQUESTS
            }
        }
    })
