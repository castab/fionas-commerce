package io.github.castab.fionas.commerce.http

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.fionas.commerce.staff.BootstrapAdmin
import io.github.castab.fionas.commerce.staff.BootstrapFirstAdmin
import io.github.castab.fionas.commerce.staff.JdbiStaffRepository
import io.github.castab.fionas.commerce.staff.PasswordHasher
import io.github.castab.fionas.commerce.staff.SecretPassword
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.slf4j.LoggerFactory
import java.util.UUID

class AuthRoutesSpec :
    FunSpec({
        fun login(
            app: TestApplication,
            username: String = "admin",
            password: String = "test-admin-password",
            origin: String? = TEST_ORIGIN,
        ) = app.http(
            Request(Method.POST, "/auth/login")
                .header("Content-Type", "application/json")
                .body("""{"username":"$username","password":"$password"}""")
                .let { if (origin == null) it else it.header("Origin", origin) },
        )

        fun error(body: String) = CommerceJson.asA(body, ErrorResponse.serializer())

        test("bootstrap stores only a salted Argon2id hash, normalizes the username, and repeats safely") {
            TestApplication.create().use { app ->
                app.database.count("fionas.users") shouldBe 1
                app.database.count("fionas.user_credentials") shouldBe 1
                app.database.count("fionas.principal_role_assignments") shouldBe 1
                val hash = app.database.strings("SELECT password_hash FROM fionas.user_credentials").single()
                hash.startsWith("\$argon2id\$") shouldBe true
                hash.contains("test-admin-password") shouldBe false
                val first = SecretPassword.of("another-test-password").use(PasswordHasher()::hash)
                val second = SecretPassword.of("another-test-password").use(PasswordHasher()::hash)
                (first != second) shouldBe true
                app.database.strings("SELECT username FROM fionas.users") shouldBe listOf("admin")

                BootstrapFirstAdmin(app.transactor, JdbiStaffRepository(), PasswordHasher(), testClock).invoke(
                    BootstrapAdmin("other", "Other", null, null, SecretPassword.of("another-test-password")),
                )
                app.database.count("fionas.users") shouldBe 1
            }
        }

        test("login sets a secure host-only cookie and keeps the token out of its response body") {
            TestApplication.create().use { app ->
                val response = login(app, username = " ADMIN ")
                response.status shouldBe Status.NO_CONTENT
                val cookie = checkNotNull(response.header("Set-Cookie"))
                cookie shouldContain "__Host-fionas_session="
                cookie shouldContain "secure"
                cookie shouldContain "HttpOnly"
                cookie shouldContain "SameSite=Lax"
                cookie shouldContain "Path=/"
                cookie.contains("Domain=") shouldBe false
                response.bodyString() shouldBe ""
                app.database.count("commerce.principal_sessions") shouldBe 1
            }
        }

        test("bad credentials share one 401 response, and disabled staff cannot log in") {
            TestApplication.create().use { app ->
                val wrongName = login(app, username = "missing")
                val wrongPassword = login(app, password = "bad-password")
                wrongName.status shouldBe Status.UNAUTHORIZED
                wrongPassword.status shouldBe Status.UNAUTHORIZED
                wrongName.bodyString() shouldBe wrongPassword.bodyString()
                error(wrongName.bodyString()).code shouldBe "unauthenticated"
                app.database.execute("UPDATE fionas.users SET status = 'DISABLED' WHERE username = 'admin'")
                login(app).status shouldBe Status.UNAUTHORIZED
                app.database.count("commerce.principal_sessions") shouldBe 0
            }
        }

        test("me exposes the safe current user and logout revokes and clears even on a repeated call") {
            TestApplication.create().use { app ->
                app.http(Request(Method.GET, "/auth/me")).status shouldBe Status.UNAUTHORIZED
                val cookie = app.adminCookie
                val me = app.http(Request(Method.GET, "/auth/me").header("Cookie", cookie))
                me.status shouldBe Status.OK
                val user = CommerceJson.asA(me.bodyString(), CurrentUserResponse.serializer())
                user.username shouldBe "admin"
                user.roles shouldBe listOf("commerce.administrator")
                me.bodyString().contains("password") shouldBe false
                me.bodyString().contains("session") shouldBe false

                val logout = Request(Method.POST, "/auth/logout").header("Cookie", cookie).header("Origin", TEST_ORIGIN)
                val first = app.http(logout)
                first.status shouldBe Status.NO_CONTENT
                checkNotNull(first.header("Set-Cookie")) shouldContain "Max-Age=0"
                app.http(Request(Method.GET, "/auth/me").header("Cookie", cookie)).status shouldBe Status.UNAUTHORIZED
                val second = app.http(logout)
                second.status shouldBe Status.NO_CONTENT
                checkNotNull(second.header("Set-Cookie")) shouldContain "Max-Age=0"
            }
        }

        test("Offerings writes require a live permission while reads and customer routes stay public") {
            TestApplication.create().use { app ->
                app.http(Request(Method.POST, "/offering-catalog")).status shouldBe Status.UNAUTHORIZED
                app.http(Request(Method.GET, "/offering-catalog")).status shouldBe Status.NOT_FOUND
                app.http(Request(Method.GET, "/health")).status shouldBe Status.OK
                app.http(Request(Method.GET, "/ready")).status shouldBe Status.OK
                app
                    .http(
                        Request(Method.POST, "/inquiries")
                            .header("Content-Type", "application/json")
                            .body("""{"name":"Jane","email":"jane@example.com"}"""),
                    ).status shouldBe Status.CREATED

                val cookie = app.adminCookie
                app.database.execute("DELETE FROM fionas.principal_role_assignments WHERE role_key = 'commerce.administrator'")
                app.adminPost("/offering-catalog").status shouldBe Status.FORBIDDEN
                app.database.execute(
                    """INSERT INTO fionas.principal_role_assignments (principal_kind, principal_id, role_key)
                   SELECT 'USER', id, 'commerce.administrator' FROM fionas.users""",
                )
                app.adminPost("/offering-catalog").status shouldBe Status.CREATED
                app.http(Request(Method.GET, "/offering-catalog")).status shouldBe Status.OK
                app.http(Request(Method.GET, "/auth/me").header("Cookie", cookie)).status shouldBe Status.OK
            }
        }

        test("login and cookie-authenticated unsafe requests reject missing or foreign browser origins") {
            TestApplication.create().use { app ->
                login(app, origin = null).status shouldBe Status.FORBIDDEN
                login(app, origin = "https://evil.example").status shouldBe Status.FORBIDDEN
                val cookie = app.adminCookie
                app.http(Request(Method.POST, "/offering-catalog").header("Cookie", cookie)).status shouldBe Status.FORBIDDEN
                app
                    .http(
                        Request(Method.POST, "/offering-catalog").header("Cookie", cookie).header("Origin", "https://evil.example"),
                    ).status shouldBe Status.FORBIDDEN
                app.adminPost("/offering-catalog").status shouldBe Status.CREATED
            }
        }

        test("a service PrincipalId uses the same live roles but is handled explicitly by me") {
            TestApplication.create().use { app ->
                val serviceId = ServiceId(UUID.randomUUID())
                app.database.execute(
                    """INSERT INTO fionas.service_identities (id, name, status)
                   VALUES ('${serviceId.value}', 'future-adapter', 'ACTIVE')""",
                )
                val token =
                    app.sessions
                        .create(serviceId)
                        .token.value
                val request =
                    Request(Method.POST, "/offering-catalog")
                        .header("Origin", TEST_ORIGIN)
                        .header("Cookie", "__Host-fionas_session=$token")
                app.http(request).status shouldBe Status.FORBIDDEN
                app.database.execute(
                    """INSERT INTO fionas.principal_role_assignments (principal_kind, principal_id, role_key)
                   VALUES ('SERVICE', '${serviceId.value}', 'commerce.administrator')""",
                )
                app.http(Request(Method.GET, "/auth/me").header("Cookie", "__Host-fionas_session=$token")).status shouldBe Status.FORBIDDEN
                app.http(request).status shouldBe Status.CREATED
            }
        }

        test("credential hashes and session tokens are absent from authentication logs") {
            val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                TestApplication.create().use { app ->
                    val hash = app.database.strings("SELECT password_hash FROM fionas.user_credentials").single()
                    val response = login(app)
                    response.status shouldBe Status.NO_CONTENT
                    val token = checkNotNull(response.header("Set-Cookie")).substringAfter('=').substringBefore(';').trim('"')
                    login(app, password = "bad-password").status shouldBe Status.UNAUTHORIZED
                    val messages = appender.list.joinToString("\n") { it.formattedMessage }
                    messages.contains("test-admin-password") shouldBe false
                    messages.contains("bad-password") shouldBe false
                    messages.contains(hash) shouldBe false
                    messages.contains(token) shouldBe false
                }
            } finally {
                root.detachAppender(appender)
                appender.stop()
            }
        }
    })
