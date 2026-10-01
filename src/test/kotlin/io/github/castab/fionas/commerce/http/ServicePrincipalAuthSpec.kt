package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.authorization.RuntimePermissions
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokenDto
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.FIONAS_WEB_PERMISSIONS
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestService
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.withBearer
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.util.UUID

/**
 * SERVICE-principal authentication through the real application: commerce-runtime's token
 * endpoint mounted by Fiona, service access tokens accepted by Fiona's one `AccessControl`,
 * and Fiona's customer operations authorized by live role grants. The runtime's own suite
 * covers token cryptography and credential storage; these specs prove Fiona's wiring.
 */
class ServicePrincipalAuthSpec :
    FunSpec({
        val unauthenticated = ErrorResponse("unauthenticated", "Authentication is required")
        val forbidden = ErrorResponse("forbidden", "The authenticated principal is not permitted to perform this request")

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun Response.json() = CommerceJson.parse(bodyString()).jsonObject

        // Each route with a body its own validation rejects, so authorization success is observable
        // as the route's existing downstream answer without the application state changing.
        val customerRoutes =
            listOf(
                Triple(FionaPermissions.InquiryFormRead, Request(Method.GET, "/inquiry-form"), Status.NOT_FOUND),
                Triple(
                    FionaPermissions.EstimatePreviewCreate,
                    Request(Method.POST, "/estimate-preview").header("Content-Type", "application/json").body("not JSON"),
                    Status.BAD_REQUEST,
                ),
                Triple(
                    FionaPermissions.InquiriesCreate,
                    Request(Method.POST, "/inquiries").withSubmissionKey().header("Content-Type", "application/json").body("not JSON"),
                    Status.BAD_REQUEST,
                ),
            )

        fun tampered(token: String): String {
            val signature = token.substringAfterLast('.')
            // A middle character carries six whole signature bits, so any change invalidates it.
            val replacement = if (signature[5] == 'A') "B" else "A"
            return token.dropLast(signature.length) + signature.replaceRange(5, 6, replacement)
        }

        fun staffCookie(
            app: TestApplication,
            permissions: Set<PermissionKey>,
        ): String {
            val id = UserId(UUID.randomUUID())
            app.authorization.createUser(User(id, "staff-${id.value}", null, null, "Staff", PrincipalStatus.ACTIVE, emptySet()))
            if (permissions.isNotEmpty()) {
                val role = RoleKey("test.staff-${id.value}")
                app.authorization.createRole(RoleDefinition(role, "Staff", null, permissions))
                app.authorization.assignRole(id, role)
            }
            return "__Host-fionas_session=${app.sessions.create(id).token.value}"
        }

        test("a service exchanges its credential at the public token endpoint for a short-lived bearer token") {
            TestApplication.create().use { app ->
                val service = app.provisionService("token-client", emptySet())
                val response =
                    app.http(
                        Request(Method.POST, SERVICE_TOKEN_PATH)
                            .header("Content-Type", "application/json")
                            .body("""{"serviceId":"${service.id.value}","secret":"${service.secret}"}"""),
                    )
                response.status shouldBe Status.OK
                response.header("Cache-Control") shouldBe "no-store"
                val issued = CommerceJson.asA(response.bodyString(), ServiceAccessTokenDto.serializer())
                issued.tokenType shouldBe "Bearer"
                issued.expiresIn shouldBe 15 * 60L
                issued.accessToken shouldNotContain service.secret

                // Every authentication failure is one uniform answer; a malformed id is a malformed request.
                val failure = ErrorResponse("unauthenticated", "Service authentication failed")
                listOf(
                    """{"serviceId":"${service.id.value}","secret":"${service.secret.dropLast(2)}xx"}""",
                    """{"serviceId":"${UUID.randomUUID()}","secret":"${service.secret}"}""",
                    """{"serviceId":"${service.id.value}","secret":"not-a-credential"}""",
                ).forEach { body ->
                    val rejected = app.http(Request(Method.POST, SERVICE_TOKEN_PATH).header("Content-Type", "application/json").body(body))
                    rejected.status shouldBe Status.UNAUTHORIZED
                    rejected.error() shouldBe failure
                    rejected.header("Cache-Control") shouldBe "no-store"
                }
                app
                    .http(
                        Request(Method.POST, SERVICE_TOKEN_PATH)
                            .header("Content-Type", "application/json")
                            .body("""{"serviceId":"not-a-uuid","secret":"${service.secret}"}"""),
                    ).status shouldBe Status.BAD_REQUEST
                // Obtaining a token never requires a token, a session, or a browser Origin.
                app
                    .http(
                        Request(Method.POST, SERVICE_TOKEN_PATH)
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer ${issued.accessToken}")
                            .body("""{"serviceId":"${service.id.value}","secret":"${service.secret}"}"""),
                    ).status shouldBe Status.OK
            }
        }

        test(
            "each customer route answers 401 without valid authentication, 403 without its permission, and otherwise reaches its handler",
        ) {
            TestApplication.create().use { app ->
                val holder = app.provisionService("no-grants", emptySet())
                val unprivileged = app.serviceToken(holder)
                customerRoutes.forEach { (permission, request, downstream) ->
                    val invalid =
                        listOf(
                            request,
                            request.header("Authorization", "Bearer"),
                            request.header("Authorization", "Basic ${app.webToken}"),
                            request.withBearer("not-a-token"),
                            request.withBearer("aaaa.bbbb.cccc"),
                            request.withBearer(tampered(app.webToken)),
                            // The retired static UI key authenticates nothing.
                            request.withBearer("deterministic-test-ui-key"),
                            request.query("access_token", app.webToken),
                        )

                    // The form's failures are never cached.
                    fun Response.noStoreIfForm() =
                        if (request.uri.path ==
                            "/inquiry-form"
                        ) {
                            header("Cache-Control") shouldBe "no-store"
                        } else {
                            Unit
                        }
                    invalid.forEach { attempt ->
                        val response = app.http(attempt)
                        response.status shouldBe Status.UNAUTHORIZED
                        response.error() shouldBe unauthenticated
                        response.noStoreIfForm()
                    }
                    app.http(request.withBearer(unprivileged)).let {
                        it.status shouldBe Status.FORBIDDEN
                        it.error() shouldBe forbidden
                        it.noStoreIfForm()
                    }
                    // Exactly the one permission authorizes the route, through the web frontend's role.
                    (permission in FIONAS_WEB_PERMISSIONS) shouldBe true
                    app.http(request.asFionasWeb(app)).status shouldBe downstream
                }
                // Authorized submissions still require their Idempotency-Key, after authorization.
                app
                    .http(Request(Method.POST, "/inquiries").asFionasWeb(app).header("Content-Type", "application/json").body("{}"))
                    .status shouldBe Status.BAD_REQUEST
                app.database.count("fionas.inquiries") shouldBe 0
                app.database.count("fionas.inquiry_submissions") shouldBe 0
            }
        }

        test("permissions are independent and live: the same token changes with role grants, never with a new token") {
            TestApplication.create().use { app ->
                val service = app.provisionService("form-reader", setOf(FionaPermissions.InquiryFormRead))
                val token = app.serviceToken(service)
                val (_, form, formAuthorized) = customerRoutes[0]
                val (_, preview, previewAuthorized) = customerRoutes[1]
                val (_, submit, submitAuthorized) = customerRoutes[2]

                fun status(request: Request) = app.http(request.withBearer(token)).status

                fun grant(permissions: List<PermissionKey>) {
                    val keys = JsonArray(permissions.map { JsonPrimitive(it.value) })
                    app
                        .adminRequest(Method.PUT, "/admin/access/roles/${service.role.value}/permissions", """{"permissions":$keys}""")
                        .status shouldBe Status.OK
                }

                status(form) shouldBe formAuthorized
                status(preview) shouldBe Status.FORBIDDEN
                status(submit) shouldBe Status.FORBIDDEN

                grant(listOf(FionaPermissions.InquiryFormRead, FionaPermissions.EstimatePreviewCreate))
                status(preview) shouldBe previewAuthorized
                status(submit) shouldBe Status.FORBIDDEN

                grant(listOf(FionaPermissions.InquiryFormRead, FionaPermissions.EstimatePreviewCreate, FionaPermissions.InquiriesCreate))
                status(submit) shouldBe submitAuthorized

                grant(listOf(FionaPermissions.InquiryFormRead))
                status(form) shouldBe formAuthorized
                status(preview) shouldBe Status.FORBIDDEN
                status(submit) shouldBe Status.FORBIDDEN

                app.authorization.unassignRole(service.id, service.role)
                status(form) shouldBe Status.FORBIDDEN

                // Disabling the service suspends its tokens on the very next request.
                app.authorization.assignRole(service.id, service.role)
                app.authorization.setStatus(service.id, PrincipalStatus.DISABLED)
                status(form) shouldBe Status.UNAUTHORIZED
                app.authorization.setStatus(service.id, PrincipalStatus.ACTIVE)
                status(form) shouldBe formAuthorized
            }
        }

        test("a service's grants are real authorization on staff routes, and the web frontend holds none of them") {
            TestApplication.create().use { app ->
                listOf(
                    Method.GET to "/inquiries",
                    Method.GET to "/inquiries/00000000-0000-0000-0000-000000000001",
                    Method.GET to "/admin/access/users",
                    Method.GET to "/payments/unapplied",
                    Method.POST to "/inquiries/00000000-0000-0000-0000-000000000001/estimates",
                    Method.PUT to "/admin/users/00000000-0000-0000-0000-000000000001/credentials/password",
                    Method.POST to "/offering-catalog",
                ).forEach { (method, path) ->
                    val response = app.http(Request(method, path).asFionasWeb(app))
                    response.status shouldBe Status.FORBIDDEN
                    response.error() shouldBe forbidden
                }
                val reader = app.provisionService("inquiry-reader", setOf(FionaPermissions.InquiriesRead))
                app.http(Request(Method.GET, "/inquiries").withBearer(app.serviceToken(reader))).status shouldBe Status.OK
            }
        }

        test("/auth/me rejects a SERVICE principal safely") {
            TestApplication.create().use { app ->
                val me = app.http(Request(Method.GET, "/auth/me").asFionasWeb(app))
                me.status shouldBe Status.FORBIDDEN
                me.error() shouldBe ErrorResponse("forbidden", "The current principal is not an active staff user")
            }
        }

        test("logout is session-only: a SERVICE token is refused, never pretended revoked, and stays valid") {
            TestApplication.create().use { app ->
                app.adminGet("/auth/me").status shouldBe Status.OK

                fun sessions() =
                    app.database.strings("SELECT coalesce(revoked_at::text, 'active') FROM commerce.principal_sessions ORDER BY created_at")
                val before = sessions()
                val refused = app.http(Request(Method.POST, "/auth/logout").asFionasWeb(app))
                refused.status shouldBe Status.FORBIDDEN
                refused.error() shouldBe ErrorResponse("forbidden", "Logout ends a staff browser session; this request has none")
                refused.header("Set-Cookie") shouldBe null
                // Nothing was revoked: the token and every staff session still authenticate.
                sessions() shouldBe before
                app.http(Request(Method.GET, "/inquiry-form").asFionasWeb(app)).status shouldBe Status.NOT_FOUND
                app.adminGet("/auth/me").status shouldBe Status.OK
            }
        }

        test("logout revokes a valid staff session and clears its cookie, and a stale cookie is still cleared") {
            TestApplication.create().use { app ->
                val cookie = app.adminCookie

                fun logout(cookieHeader: String) =
                    app.http(Request(Method.POST, "/auth/logout").header("Origin", TEST_ORIGIN).header("Cookie", cookieHeader))

                fun me(cookieHeader: String) = app.http(Request(Method.GET, "/auth/me").header("Cookie", cookieHeader)).status

                me(cookie) shouldBe Status.OK
                logout(cookie).let {
                    it.status shouldBe Status.NO_CONTENT
                    checkNotNull(it.header("Set-Cookie")) shouldContain "Max-Age=0"
                }
                me(cookie) shouldBe Status.UNAUTHORIZED
                // The same, now revoked, cookie: browser cleanup stays idempotent rather than becoming 401.
                logout(cookie).let {
                    it.status shouldBe Status.NO_CONTENT
                    checkNotNull(it.header("Set-Cookie")) shouldContain "Max-Age=0"
                }
                // A session revoked elsewhere (for example by disabling the user) is cleared the same way.
                val admin = checkNotNull(app.authorization.findUserByUsername("admin"))
                val stale = app.sessions.create(admin.id).also { app.sessions.revoke(it.token) }
                logout("__Host-fionas_session=${stale.token.value}").let {
                    it.status shouldBe Status.NO_CONTENT
                    checkNotNull(it.header("Set-Cookie")) shouldContain "Max-Age=0"
                }
            }
        }

        test("a session cookie keeps logout under the browser Origin policy, even alongside a service token") {
            TestApplication.create().use { app ->
                val cookie = app.adminCookie
                val withCookie = Request(Method.POST, "/auth/logout").header("Cookie", cookie)
                app.http(withCookie).status shouldBe Status.FORBIDDEN
                app.http(withCookie.asFionasWeb(app)).let {
                    it.status shouldBe Status.FORBIDDEN
                    it.error().message shouldBe "The browser origin is not trusted"
                }
                app.http(withCookie.header("Origin", "https://evil.example").asFionasWeb(app)).status shouldBe Status.FORBIDDEN
                app.adminGet("/auth/me").status shouldBe Status.OK
                // With a trusted Origin, the session wins over the token: the session is the one revoked.
                app.http(withCookie.header("Origin", TEST_ORIGIN).asFionasWeb(app)).status shouldBe Status.NO_CONTENT
                app.adminGet("/auth/me").status shouldBe Status.UNAUTHORIZED
                app.http(Request(Method.GET, "/inquiry-form").asFionasWeb(app)).status shouldBe Status.NOT_FOUND
            }
        }

        test("browser Origin protects cookie requests only; a token-only request needs no Origin") {
            TestApplication.create().use { app ->
                val (_, submit, authorized) = customerRoutes[2]
                app.http(submit.asFionasWeb(app)).status shouldBe authorized
                app.http(submit.asFionasWeb(app).header("Origin", "https://evil.example")).status shouldBe authorized
                // A cookie-carrying unsafe request still needs a trusted Origin, whatever else it carries.
                val withCookie = submit.header("Cookie", app.adminCookie)
                app.http(withCookie).let {
                    it.status shouldBe Status.FORBIDDEN
                    it.error().message shouldBe "The browser origin is not trusted"
                }
                app.http(withCookie.asFionasWeb(app)).status shouldBe Status.FORBIDDEN
                app.http(withCookie.header("Origin", TEST_ORIGIN)).status shouldBe authorized
            }
        }

        test("a staff session takes precedence over a service token on the same request; identities never merge") {
            TestApplication.create().use { app ->
                val both = Request(Method.GET, "/auth/me").header("Cookie", app.adminCookie).asFionasWeb(app)
                app.http(both).let {
                    it.status shouldBe Status.OK
                    CommerceJson.asA(it.bodyString(), CurrentUserResponse.serializer()).username shouldBe "admin"
                }
                // A staff user without the permission stays that user: the token's grants are not borrowed.
                val unprivileged = staffCookie(app, emptySet())
                val form = Request(Method.GET, "/inquiry-form")
                app.http(form.header("Cookie", unprivileged).asFionasWeb(app)).status shouldBe Status.FORBIDDEN
                app.http(form.asFionasWeb(app)).status shouldBe Status.NOT_FOUND
                // A staff session holding the permission is authorized through the same AccessControl.
                app.http(form.header("Cookie", staffCookie(app, setOf(FionaPermissions.InquiryFormRead)))).status shouldBe
                    Status.NOT_FOUND
            }
        }

        test("the bootstrap administrator provisions and rotates the web frontend's service through the mounted administration API") {
            TestApplication.create().use { app ->
                val created = app.adminPost("/admin/access/services", """{"name":"fionas-web"}""")
                created.status shouldBe Status.CREATED
                val serviceId =
                    created
                        .json()
                        .getValue("id")
                        .jsonPrimitive.content
                val permissions = FIONAS_WEB_PERMISSIONS.map { "\"${it.value}\"" }.sorted().joinToString(",")
                app
                    .adminPost(
                        "/admin/access/roles",
                        """{"key":"fionas.web","displayName":"Fiona's web frontend","description":"Customer operations","permissions":[$permissions]}""",
                    ).status shouldBe Status.CREATED
                app.adminRequest(Method.PUT, "/admin/access/services/$serviceId/roles/fionas.web").status shouldBe Status.NO_CONTENT

                fun createCredential(label: String): Pair<String, String> {
                    val response = app.adminPost("/admin/access/services/$serviceId/credentials", """{"label":"$label"}""")
                    response.status shouldBe Status.CREATED
                    response.header("Cache-Control") shouldBe "no-store"
                    val body = response.json()
                    return body.getValue("credentialId").jsonPrimitive.content to body.getValue("secret").jsonPrimitive.content
                }
                val (credentialA, secretA) = createCredential("fionas-web A")
                val service = TestService(ServiceId(UUID.fromString(serviceId)), RoleKey("fionas.web"), secretA)
                val tokenA = app.serviceToken(service)
                app.http(Request(Method.GET, "/inquiry-form").withBearer(tokenA)).status shouldBe Status.NOT_FOUND

                // Listing returns metadata only, never secret material.
                val listing = app.adminGet("/admin/access/services/$serviceId/credentials")
                listing.status shouldBe Status.OK
                listing.bodyString() shouldNotContain secretA
                listing.bodyString() shouldNotContain secretA.substringAfter('.')
                listing
                    .json()
                    .getValue("credentials")
                    .jsonArray
                    .single()
                    .jsonObject.keys
                    .contains("secret") shouldBe false

                // Rotation without a restart: create B, use B, revoke A.
                val (_, secretB) = createCredential("fionas-web B")
                val tokenB = app.serviceToken(service.copy(secret = secretB))
                app.http(Request(Method.GET, "/inquiry-form").withBearer(tokenB)).status shouldBe Status.NOT_FOUND
                app
                    .adminRequest(Method.DELETE, "/admin/access/services/$serviceId/credentials/$credentialA")
                    .status shouldBe Status.NO_CONTENT
                app
                    .http(
                        Request(Method.POST, SERVICE_TOKEN_PATH)
                            .header("Content-Type", "application/json")
                            .body("""{"serviceId":"$serviceId","secret":"$secretA"}"""),
                    ).status shouldBe Status.UNAUTHORIZED
                app.serviceToken(service.copy(secret = secretB)).isNotBlank() shouldBe true
                // A token A already obtained stays valid until it expires.
                app.http(Request(Method.GET, "/inquiry-form").withBearer(tokenA)).status shouldBe Status.NOT_FOUND
            }
        }

        test("issuing service credentials needs the runtime's ServiceCredentialManage, which bootstrap grants explicitly") {
            TestApplication.create().use { app ->
                val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
                (RuntimePermissions.ServiceCredentialManage in grants) shouldBe true
                val serviceId = app.provisionService("rotating", emptySet()).id.value
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants - RuntimePermissions.ServiceCredentialManage)
                // The administrator can still create services and assign roles, but cannot issue their credentials.
                app.adminPost("/admin/access/services", """{"name":"another"}""").status shouldBe Status.CREATED
                app.adminPost("/admin/access/services/$serviceId/credentials", """{"label":"x"}""").status shouldBe Status.FORBIDDEN
                app.adminGet("/admin/access/services/$serviceId/credentials").status shouldBe Status.OK
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
                app.adminPost("/admin/access/services/$serviceId/credentials", """{"label":"x"}""").status shouldBe Status.CREATED
                (CommercePermissions.PrincipalManage in grants) shouldBe true
            }
        }
    })
