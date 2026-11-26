package io.github.castab.fionas.commerce.http

import io.github.castab.fionas.commerce.passkey.WebAuthnConfiguration
import io.github.castab.fionas.commerce.testing.SoftwareAuthenticator
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.staffMember
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.Clock
import java.time.Duration

private val webAuthn = WebAuthnConfiguration("fionas.test", "Fiona's Admin", setOf(TEST_ORIGIN))

class StaffPasskeyRoutesSpec :
    FunSpec({
        fun TestApplication.call(
            method: Method,
            path: String,
            cookie: String? = null,
            body: String = "",
            origin: String? = TEST_ORIGIN,
        ): Response =
            http(
                Request(method, path)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .let { if (cookie == null) it else it.header("Cookie", cookie) }
                    .let { if (origin == null) it else it.header("Origin", origin) },
            )

        fun Response.json(): JsonObject = Json.parseToJsonElement(bodyString()).jsonObject

        fun JsonObject.text(vararg path: String): String =
            path
                .dropLast(1)
                .fold(this) { node, key -> node.getValue(key).jsonObject }
                .getValue(path.last())
                .jsonPrimitive.content

        fun Response.sessionCookie(): String = checkNotNull(header("Set-Cookie")).substringBefore(';')

        /** Starts and finishes a registration for [cookie]'s user with [authenticator]. */
        fun TestApplication.register(
            cookie: String,
            authenticator: SoftwareAuthenticator,
            name: String? = null,
            tamper: (challenge: String, rpId: String, handle: String) -> String? = { _, _, _ -> null },
        ): Response {
            val options = call(Method.POST, "/staff/passkeys/registration/options", cookie).also { it.status shouldBe Status.OK }.json()
            val challenge = options.text("publicKey", "challenge")
            val rpId = options.text("publicKey", "rp", "id")
            val handle = options.text("publicKey", "user", "id")
            val credential = tamper(challenge, rpId, handle) ?: authenticator.register(challenge, rpId, handle)
            val named = name?.let { ""","name":"$it"""" }.orEmpty()
            return call(
                Method.POST,
                "/staff/passkeys",
                cookie,
                """{"ceremonyId":"${options.text("ceremonyId")}","credential":$credential$named}""",
            )
        }

        /** A full passkey sign-in; [answer] may replace the authenticator's honest answer. */
        fun TestApplication.signIn(
            authenticator: SoftwareAuthenticator,
            cookie: String? = null,
            answer: (challenge: String, rpId: String) -> String = { challenge, rpId -> authenticator.assert(challenge, rpId) },
        ): Response {
            val options = call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH).also { it.status shouldBe Status.OK }.json()
            val credential = answer(options.text("publicKey", "challenge"), options.text("publicKey", "rpId"))
            return call(
                Method.POST,
                PASSKEY_SIGN_IN_PATH,
                cookie,
                """{"ceremonyId":"${options.text("ceremonyId")}","credential":$credential}""",
            )
        }

        lateinit var app: TestApplication

        // Many sign-ins share one test connection identity; the limit itself has its own test below.
        beforeSpec { app = TestApplication.create(webAuthn = webAuthn, passkeyRateLimit = LoginRateLimit(capacity = 10_000)) }
        afterSpec { app.close() }

        test("a staff member adds a passkey and signs in with it; the new session works and none of the key is returned") {
            val (userId, cookie) = app.staffMember()
            val authenticator = SoftwareAuthenticator()

            val created = app.register(cookie, authenticator, name = "  Work laptop  ")
            created.status shouldBe Status.CREATED
            created.json().let {
                it.text("name") shouldBe "Work laptop"
                it.text("aaguid") shouldBe authenticator.aaguid.toString()
                it.text("backupEligible") shouldBe "true"
                it.text("backedUp") shouldBe "true"
                it.text("credentialId") shouldBe SoftwareAuthenticator.encode(authenticator.credentialId)
                it.keys shouldNotBe setOf("publicKey")
            }

            val signedIn = app.signIn(authenticator)
            signedIn.status shouldBe Status.NO_CONTENT
            signedIn.header("Set-Cookie") shouldContain "HttpOnly"
            val me = app.call(Method.GET, "/auth/me", signedIn.sessionCookie()).json()
            me.text("id") shouldBe userId.value.toString()

            val listed = app.call(Method.GET, "/staff/passkeys", cookie)
            listed.header("Cache-Control") shouldBe "no-store"
            listed.json().let {
                it.text("rpId") shouldBe "fionas.test"
                it.text("userHandle") shouldBe SoftwareAuthenticator.encode(checkNotNull(authenticator.userHandle))
                it.getValue("passkeys").jsonArray shouldHaveSize 1
                it
                    .getValue("passkeys")
                    .jsonArray[0]
                    .jsonObject
                    .let { passkey -> passkey.keys shouldNotBe emptySet<String>() }
            }
            listed.bodyString() shouldNotContain "publicKeyCose"
            app.call(Method.GET, "/staff/account", cookie).json().text("passkeyCount") shouldBe "1"
        }

        test("registration options require discoverable, user-verified passkeys without attestation and exclude existing ones") {
            val (_, cookie) = app.staffMember()
            val authenticator = SoftwareAuthenticator()
            app.register(cookie, authenticator).status shouldBe Status.CREATED

            val options =
                app
                    .call(Method.POST, "/staff/passkeys/registration/options", cookie)
                    .json()
                    .getValue("publicKey")
                    .jsonObject
            options.text("authenticatorSelection", "residentKey") shouldBe "required"
            options.text("authenticatorSelection", "userVerification") shouldBe "required"
            options.text("attestation") shouldBe "none"
            options.text("extensions", "credProps") shouldBe "true"
            options.getValue("pubKeyCredParams").jsonArray.map { it.jsonObject.text("alg") } shouldBe listOf("-8", "-7", "-257")
            options.getValue("excludeCredentials").jsonArray.map { it.jsonObject.text("id") } shouldBe
                listOf(SoftwareAuthenticator.encode(authenticator.credentialId))
            SoftwareAuthenticator.decode(options.text("user", "id")).size shouldBe 64
            SoftwareAuthenticator.decode(options.text("challenge")).size shouldBe 32
        }

        test("sign-in options name no account and require user verification") {
            val options = app.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH)
            options.header("Cache-Control") shouldBe "no-store"
            options.json().getValue("publicKey").jsonObject.let {
                it.getValue("allowCredentials").jsonArray.shouldBeEmpty()
                it.text("userVerification") shouldBe "required"
                it.text("rpId") shouldBe "fionas.test"
            }
        }

        test("a ceremony is spent once, whether or not the answer verified") {
            val (_, cookie) = app.staffMember()
            val authenticator = SoftwareAuthenticator().also { app.register(cookie, it).status shouldBe Status.CREATED }
            val options = app.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH).json()
            val challenge = options.text("publicKey", "challenge")

            fun send(credential: String) =
                app.call(
                    Method.POST,
                    PASSKEY_SIGN_IN_PATH,
                    body = """{"ceremonyId":"${options.text("ceremonyId")}","credential":$credential}""",
                )

            send(authenticator.assert(challenge, "fionas.test")).status shouldBe Status.NO_CONTENT
            send(authenticator.assert(challenge, "fionas.test")).status shouldBe Status.UNAUTHORIZED

            val failed = app.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH).json()
            val failedChallenge = failed.text("publicKey", "challenge")

            fun sendFailed(credential: String) =
                app.call(
                    Method.POST,
                    PASSKEY_SIGN_IN_PATH,
                    body = """{"ceremonyId":"${failed.text("ceremonyId")}","credential":$credential}""",
                )
            sendFailed(authenticator.assert(failedChallenge, "fionas.test", userVerified = false)).status shouldBe Status.UNAUTHORIZED
            sendFailed(authenticator.assert(failedChallenge, "fionas.test")).status shouldBe Status.UNAUTHORIZED
        }

        test("sign-in rejects every broken rule with the same generic 401") {
            val (_, cookie) = app.staffMember()
            val authenticator = SoftwareAuthenticator().also { app.register(cookie, it).status shouldBe Status.CREATED }
            mapOf<String, (String, String) -> String>(
                "another origin" to { c, rp -> authenticator.assert(c, rp, origin = "https://evil.test") },
                "another relying party" to { c, _ -> authenticator.assert(c, "evil.test") },
                "no user verification" to { c, rp -> authenticator.assert(c, rp, userVerified = false) },
                "another challenge" to { _, rp -> authenticator.assert(SoftwareAuthenticator.encode(ByteArray(32)), rp) },
                "a registration answer" to { c, rp -> authenticator.assert(c, rp, type = "webauthn.create") },
            ).forEach { (rule, answer) ->
                withClue(rule) {
                    val response = app.signIn(authenticator, answer = answer)
                    response.status shouldBe Status.UNAUTHORIZED
                    response.header("Set-Cookie") shouldBe null
                }
            }
            app.signIn(authenticator).status shouldBe Status.NO_CONTENT
        }

        test("a signature counter that goes backwards is rejected; a zero counter is accepted") {
            val (_, cookie) = app.staffMember()
            val counting = SoftwareAuthenticator().also { app.register(cookie, it).status shouldBe Status.CREATED }
            app.signIn(counting) { c, rp -> counting.assert(c, rp, count = 7) }.status shouldBe Status.NO_CONTENT
            app.signIn(counting) { c, rp -> counting.assert(c, rp, count = 3) }.status shouldBe Status.UNAUTHORIZED

            val synced = SoftwareAuthenticator().also { app.register(cookie, it).status shouldBe Status.CREATED }
            repeat(2) { app.signIn(synced) { c, rp -> synced.assert(c, rp, count = 0) }.status shouldBe Status.NO_CONTENT }
        }

        test("an unregistered passkey is 404, so the browser can tell its passkey manager to forget it") {
            val stranger = SoftwareAuthenticator().apply { userHandle = ByteArray(64) }
            val response = app.signIn(stranger)
            response.status shouldBe Status.NOT_FOUND
            response.json().text("code") shouldBe "not_found"
        }

        test("registration rejects a passkey from another origin, without user verification, or not discoverable") {
            val (_, cookie) = app.staffMember()
            val authenticator = SoftwareAuthenticator()
            mapOf<String, (String, String, String) -> String>(
                "another origin" to { c, rp, h -> authenticator.register(c, rp, h, origin = "https://evil.test") },
                "no user verification" to { c, rp, h -> authenticator.register(c, rp, h, userVerified = false) },
                "not discoverable" to { c, rp, h -> authenticator.register(c, rp, h, discoverable = false) },
                "another relying party" to { c, _, h -> authenticator.register(c, "evil.test", h) },
            ).forEach { (rule, answer) ->
                withClue(rule) { app.register(cookie, authenticator, tamper = answer).status shouldBe Status.UNPROCESSABLE_ENTITY }
            }
            app
                .call(Method.GET, "/staff/passkeys", cookie)
                .json()
                .getValue("passkeys")
                .jsonArray
                .shouldBeEmpty()
        }

        test("a registration ceremony belongs to the user who started it") {
            val (_, owner) = app.staffMember()
            val (_, other) = app.staffMember()
            val options = app.call(Method.POST, "/staff/passkeys/registration/options", owner).json()
            val credential =
                SoftwareAuthenticator().register(
                    options.text("publicKey", "challenge"),
                    options.text("publicKey", "rp", "id"),
                    options.text("publicKey", "user", "id"),
                )
            app
                .call(Method.POST, "/staff/passkeys", other, """{"ceremonyId":"${options.text("ceremonyId")}","credential":$credential}""")
                .status shouldBe Status.UNPROCESSABLE_ENTITY
        }

        test("passkeys are private: another member's id is 404 for rename and removal") {
            val (_, owner) = app.staffMember()
            val (_, other) = app.staffMember()
            val id = app.register(owner, SoftwareAuthenticator()).json().text("id")
            app.call(Method.PATCH, "/staff/passkeys/$id", other, """{"name":"Mine now"}""").status shouldBe Status.NOT_FOUND
            app.call(Method.DELETE, "/staff/passkeys/$id", other).status shouldBe Status.NOT_FOUND
            app.call(Method.PATCH, "/staff/passkeys/not-a-uuid", owner, """{"name":"x"}""").status shouldBe Status.BAD_REQUEST
        }

        test("renaming trims, clears on blank and rejects control characters") {
            val (_, cookie) = app.staffMember()
            val id = app.register(cookie, SoftwareAuthenticator()).json().text("id")
            app.call(Method.PATCH, "/staff/passkeys/$id", cookie, """{"name":"  Phone "}""").json().text("name") shouldBe "Phone"
            app.call(Method.PATCH, "/staff/passkeys/$id", cookie, """{"name":"  "}""").json().containsKey("name") shouldBe false
            app.call(Method.PATCH, "/staff/passkeys/$id", cookie, """{"name":"a\u0007b"}""").status shouldBe Status.UNPROCESSABLE_ENTITY
            app.call(Method.PATCH, "/staff/passkeys/$id", cookie, """{"name":"${"x".repeat(65)}"}""").status shouldBe
                Status.UNPROCESSABLE_ENTITY
        }

        test("a removed passkey never signs in again, and removal answers the remaining passkeys") {
            val (_, cookie) = app.staffMember()
            val kept = SoftwareAuthenticator()
            val removed = SoftwareAuthenticator()
            app.register(cookie, kept).status shouldBe Status.CREATED
            val id = app.register(cookie, removed).json().text("id")

            val remaining = app.call(Method.DELETE, "/staff/passkeys/$id", cookie)
            remaining.status shouldBe Status.OK
            remaining
                .json()
                .getValue("passkeys")
                .jsonArray
                .map { it.jsonObject.text("credentialId") } shouldBe
                listOf(SoftwareAuthenticator.encode(kept.credentialId))
            app.signIn(removed).status shouldBe Status.NOT_FOUND
            app.signIn(kept).status shouldBe Status.NO_CONTENT
        }

        test("signing in again revokes the session the browser presented") {
            val (_, cookie) = app.staffMember()
            val authenticator = SoftwareAuthenticator().also { app.register(cookie, it).status shouldBe Status.CREATED }
            app.signIn(authenticator, cookie = cookie).status shouldBe Status.NO_CONTENT
            app.call(Method.GET, "/auth/me", cookie).status shouldBe Status.UNAUTHORIZED
        }

        test("password login also revokes the session the browser presented") {
            val first = app.call(Method.POST, "/auth/login", body = """{"username":"admin","password":"test-admin-password"}""")
            val second =
                app.call(Method.POST, "/auth/login", first.sessionCookie(), """{"username":"admin","password":"test-admin-password"}""")
            second.status shouldBe Status.NO_CONTENT
            app.call(Method.GET, "/auth/me", first.sessionCookie()).status shouldBe Status.UNAUTHORIZED
            app.call(Method.GET, "/auth/me", second.sessionCookie()).status shouldBe Status.OK
        }

        test("sign-in endpoints require a trusted Origin, and account routes refuse SERVICE principals") {
            app.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH, origin = null).status shouldBe Status.FORBIDDEN
            app.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH, origin = "https://evil.test").status shouldBe Status.FORBIDDEN
            app.call(Method.POST, PASSKEY_SIGN_IN_PATH, body = "{}", origin = null).status shouldBe Status.FORBIDDEN
            app.http(Request(Method.GET, "/staff/passkeys").asFionasWeb(app)).status shouldBe Status.FORBIDDEN
            app.http(Request(Method.GET, "/staff/account").asFionasWeb(app)).status shouldBe Status.FORBIDDEN
            app.call(Method.GET, "/staff/passkeys").status shouldBe Status.UNAUTHORIZED
        }

        test("the account reports when this session signed in and its ten-minute window") {
            val (_, cookie) = app.staffMember()
            val account = app.call(Method.GET, "/staff/account", cookie).json()
            Duration.between(
                java.time.Instant.parse(account.text("authenticatedAt")),
                java.time.Instant.parse(account.text("recentAuthenticationUntil")),
            ) shouldBe Duration.ofMinutes(10)
            account.text("passkeysEnabled") shouldBe "true"
        }

        test("adding and removing a passkey need a sign-in within ten minutes; renaming does not") {
            val later = ShiftableClock()
            TestApplication.create(webAuthn = webAuthn, sessionClock = later).use { app ->
                val (_, cookie) = app.staffMember()
                val authenticator = SoftwareAuthenticator()
                val id = app.register(cookie, authenticator).json().text("id")

                later.shift = Duration.ofMinutes(11)
                app.call(Method.POST, "/staff/passkeys/registration/options", cookie).let {
                    it.status shouldBe Status.FORBIDDEN
                    it.json().text("code") shouldBe "reauthentication_required"
                }
                app.call(Method.DELETE, "/staff/passkeys/$id", cookie).json().text("code") shouldBe "reauthentication_required"
                app.call(Method.PATCH, "/staff/passkeys/$id", cookie, """{"name":"Laptop"}""").status shouldBe Status.OK

                // Signing in again (here with the passkey) replaces the session; the new one is recent.
                later.shift = Duration.ZERO
                val renewed = app.signIn(authenticator, cookie = cookie).sessionCookie()
                app.call(Method.DELETE, "/staff/passkeys/$id", renewed).status shouldBe Status.OK
            }
        }

        test("passkeys can be disabled: their routes answer 503 and the account says so") {
            TestApplication.create().use { disabled ->
                val (_, cookie) = disabled.staffMember()
                disabled.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH).let {
                    it.status shouldBe Status.SERVICE_UNAVAILABLE
                    it.json().text("code") shouldBe "passkeys_disabled"
                }
                disabled.call(Method.GET, "/staff/passkeys", cookie).status shouldBe Status.SERVICE_UNAVAILABLE
                disabled.call(Method.GET, "/staff/account", cookie).json().text("passkeysEnabled") shouldBe "false"
            }
        }

        test("passkey sign-in has its own rate limit") {
            TestApplication.create(webAuthn = webAuthn, passkeyRateLimit = LoginRateLimit(capacity = 1)).use { limited ->
                limited.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH).status shouldBe Status.OK
                limited.call(Method.POST, PASSKEY_SIGN_IN_OPTIONS_PATH).let {
                    it.status shouldBe Status.TOO_MANY_REQUESTS
                    it.header("Retry-After") shouldNotBe null
                }
                // Password login keeps its separate bucket.
                limited.adminCookie shouldNotBe null
            }
        }
    })

/** The system clock, shifted by [shift]: "now" as seen [shift] later. */
private class ShiftableClock(
    var shift: Duration = Duration.ZERO,
) : Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun instant(): java.time.Instant =
        java.time.Instant
            .now()
            .plus(shift)
}
