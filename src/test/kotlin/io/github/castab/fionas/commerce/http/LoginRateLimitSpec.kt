package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.assertions.throwables.shouldThrow
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

private const val CF = ClientAddressSource.CF_CONNECTING_IP

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

        test("by default the connection address decides; a CF-Connecting-IP header is ignored") {
            val handler = LoginRateLimit({ 0L }).filter.then { Response(Status.NO_CONTENT) }
            val request = Request(Method.POST, "/auth/login").source(RequestSource("192.0.2.1"))
            repeat(5) { index -> handler(request.header(CF, "198.51.100.$index")).status shouldBe Status.NO_CONTENT }
            handler(request.header(CF, "198.51.100.99")).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("behind Cloudflare each client gets its own bucket, whatever proxy connection carries it") {
            val handler = LoginRateLimit({ 0L }, clientAddress = ClientAddressSource.CLOUDFLARE).filter.then { Response(Status.NO_CONTENT) }
            val viaProxy = Request(Method.POST, "/auth/login").source(RequestSource("172.18.0.5"))
            repeat(5) { handler(viaProxy.header(CF, "203.0.113.7")).status shouldBe Status.NO_CONTENT }
            handler(viaProxy.header(CF, "203.0.113.7")).status shouldBe Status.TOO_MANY_REQUESTS
            // Another proxy connection does not refill the same client.
            handler(viaProxy.source(RequestSource("172.18.0.6")).header(CF, "203.0.113.7")).status shouldBe Status.TOO_MANY_REQUESTS
            // Other clients behind the same proxy are unaffected.
            handler(viaProxy.header(CF, "203.0.113.8")).status shouldBe Status.NO_CONTENT
        }

        test("a missing, repeated or malformed CF-Connecting-IP spends the connection's bucket, never a new one") {
            val handler = LoginRateLimit({ 0L }, clientAddress = ClientAddressSource.CLOUDFLARE).filter.then { Response(Status.NO_CONTENT) }
            val viaProxy = Request(Method.POST, "/auth/login").source(RequestSource("172.18.0.5"))
            listOf(
                viaProxy,
                viaProxy.header(CF, "not-an-ip"),
                viaProxy.header(CF, "example.com"),
                viaProxy.header(CF, "203.0.113.7, 203.0.113.8"),
                viaProxy.header(CF, "203.0.113.9").header(CF, "203.0.113.10"),
            ).forEach { handler(it).status shouldBe Status.NO_CONTENT }
            handler(viaProxy.header(CF, "")).status shouldBe Status.TOO_MANY_REQUESTS
            handler(viaProxy.header(CF, "203.0.113.11")).status shouldBe Status.NO_CONTENT
        }

        test("IPv6 clients share their /64; IPv4-mapped addresses are the IPv4 client") {
            val handler = LoginRateLimit({ 0L }, clientAddress = ClientAddressSource.CLOUDFLARE).filter.then { Response(Status.NO_CONTENT) }
            val viaProxy = Request(Method.POST, "/auth/login").source(RequestSource("172.18.0.5"))
            repeat(5) { index -> handler(viaProxy.header(CF, "2001:db8:1:2::${index + 1}")).status shouldBe Status.NO_CONTENT }
            handler(viaProxy.header(CF, "2001:db8:1:2:ffff:ffff:ffff:ffff")).status shouldBe Status.TOO_MANY_REQUESTS
            handler(viaProxy.header(CF, "2001:db8:1:3::1")).status shouldBe Status.NO_CONTENT
            repeat(4) { handler(viaProxy.header(CF, "198.51.100.1")).status shouldBe Status.NO_CONTENT }
            handler(viaProxy.header(CF, "::ffff:198.51.100.1")).status shouldBe Status.NO_CONTENT
            handler(viaProxy.header(CF, "198.51.100.1")).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("FIONAS_CLIENT_IP_SOURCE is connection by default or cloudflare; anything else fails startup") {
            ClientAddressSource.fromEnvironment(emptyMap()) shouldBe ClientAddressSource.CONNECTION
            ClientAddressSource.fromEnvironment(mapOf("FIONAS_CLIENT_IP_SOURCE" to "connection")) shouldBe ClientAddressSource.CONNECTION
            ClientAddressSource.fromEnvironment(mapOf("FIONAS_CLIENT_IP_SOURCE" to " Cloudflare ")) shouldBe ClientAddressSource.CLOUDFLARE
            shouldThrow<IllegalStateException> {
                ClientAddressSource.fromEnvironment(
                    mapOf("FIONAS_CLIENT_IP_SOURCE" to "x-forwarded-for"),
                )
            }
        }

        test("the configured source keys every sign-in limit of the running application") {
            TestApplication.create(clientAddressSource = ClientAddressSource.CLOUDFLARE).use { app ->
                fun login(client: String) =
                    app.http(
                        Request(Method.POST, "/auth/login")
                            .source(RequestSource("172.18.0.5"))
                            .header(CF, client)
                            .header("Origin", TEST_ORIGIN)
                            .header("Content-Type", "application/json")
                            .body("""{"username":"admin","password":"wrong-password"}"""),
                    )
                repeat(5) { login("203.0.113.7").status shouldBe Status.UNAUTHORIZED }
                login("203.0.113.7").status shouldBe Status.TOO_MANY_REQUESTS
                login("203.0.113.8").status shouldBe Status.UNAUTHORIZED
            }
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
