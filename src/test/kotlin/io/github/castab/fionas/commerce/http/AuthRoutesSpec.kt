package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.authorization.CurrentPrincipalDto
import io.github.castab.commerce.runtime.authorization.PermissionsDto
import io.github.castab.commerce.runtime.authorization.RuntimePermissions
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.staff.BootstrapAdmin
import io.github.castab.fionas.commerce.staff.BootstrapFirstAdmin
import io.github.castab.fionas.commerce.staff.CredentialRepository
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.staff.JdbiCredentialRepository
import io.github.castab.fionas.commerce.staff.PasswordHasher
import io.github.castab.fionas.commerce.staff.SecretPassword
import io.github.castab.fionas.commerce.testing.FIONAS_WEB_PERMISSIONS
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.testClock
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.time.Instant
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

        fun request(
            app: TestApplication,
            method: Method,
            path: String,
            cookie: String? = app.adminCookie,
            origin: String? = TEST_ORIGIN,
            body: String = "",
        ) = app.http(
            Request(method, path)
                .header("Content-Type", "application/json")
                .body(body)
                .let { if (cookie == null) it else it.header("Cookie", cookie) }
                .let { if (origin == null) it else it.header("Origin", origin) },
        )

        fun newUser(
            app: TestApplication,
            name: String,
        ): UserId =
            UserId(
                UUID.randomUUID(),
            ).also { app.authorization.createUser(User(it, name, null, null, name, PrincipalStatus.ACTIVE, emptySet())) }

        test("fresh bootstrap owns one runtime user and role with explicit grants and Fiona credential") {
            TestApplication.create().use { app ->
                val admin = app.authorization.findUserByUsername("admin")!!
                app.database.count("commerce.users") shouldBe 1
                app.database.count("commerce.roles") shouldBe 1
                app.database.count("commerce.principal_roles") shouldBe 1
                app.database.count("fionas.user_credentials") shouldBe 1
                admin.roles.map { it.role }.toSet() shouldBe setOf(CommerceRoles.Administrator)
                app.authorization.getRole(CommerceRoles.Administrator)!!.permissions shouldBe
                    setOf(
                        CommercePermissions.FinancialDocumentRead,
                        CommercePermissions.FinancialDocumentCreate,
                        CommercePermissions.DepositRequirementManage,
                        CommercePermissions.PaymentRecord,
                        CommercePermissions.RefundRecord,
                        CommercePermissions.PrincipalRead,
                        CommercePermissions.PrincipalManage,
                        CommercePermissions.RoleRead,
                        CommercePermissions.RoleManage,
                        CommercePermissions.RoleAssign,
                        RuntimePermissions.ServiceCredentialManage,
                        FionaPermissions.CredentialsManage,
                        FionaPermissions.InquiriesRead,
                        FionaPermissions.InquiriesManage,
                        FionaPermissions.CommunicationsAcknowledge,
                        FionaPermissions.FinancialTermsManage,
                    )
                // Priced inquiry submission is a SERVICE capability: no human administrator is granted it.
                (FionaPermissions.InquiriesCreate in app.authorization.getRole(CommerceRoles.Administrator)!!.permissions) shouldBe false
                val hash = app.database.strings("SELECT password_hash FROM fionas.user_credentials").single()
                hash.startsWith("\$argon2id\$") shouldBe true
                hash.contains("test-admin-password") shouldBe false
                // A later bootstrap never changes the existing role or its grants.
                val grants = app.database.strings("SELECT permission_key FROM commerce.role_permissions ORDER BY 1")
                BootstrapFirstAdmin(app.transactor, app.authorization, JdbiCredentialRepository(), PasswordHasher(), testClock).invoke(
                    BootstrapAdmin("other", "Other", null, null, SecretPassword.of("another-test-password")),
                )
                app.database.strings("SELECT permission_key FROM commerce.role_permissions ORDER BY 1") shouldBe grants
                app.database.count("commerce.users") shouldBe 1
                app.database.strings("SELECT password_hash FROM fionas.user_credentials").single() shouldBe hash
            }
        }

        test(
            "later bootstrap does not add deposit, refund, inquiry-read, fulfillment, communication or financial-terms permissions to an existing Administrator role",
        ) {
            TestApplication.create().use { app ->
                val role = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator))
                val previous =
                    role.permissions - CommercePermissions.DepositRequirementManage - CommercePermissions.RefundRecord -
                        FionaPermissions.InquiriesRead - FionaPermissions.InquiriesManage - FionaPermissions.CommunicationsAcknowledge -
                        FionaPermissions.FinancialTermsManage
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, previous)
                BootstrapFirstAdmin(app.transactor, app.authorization, JdbiCredentialRepository(), PasswordHasher(), testClock).invoke(
                    BootstrapAdmin("other", "Other", null, null, SecretPassword.of("another-test-password")),
                )
                app.authorization.getRole(CommerceRoles.Administrator)?.permissions shouldBe previous
            }
        }

        test("bootstrap is opt-in and rolls back runtime identity if Fiona credential insertion fails") {
            TestApplication.create(bootstrap = null).use { app ->
                app.database.count("commerce.users") shouldBe 0
                app.database.count("commerce.roles") shouldBe 0
                login(app).status shouldBe Status.UNAUTHORIZED
                val failingCredentials =
                    object : CredentialRepository {
                        override fun passwordHash(
                            transaction: Transaction,
                            userId: UserId,
                        ): String? = null

                        override fun setPassword(
                            transaction: Transaction,
                            userId: UserId,
                            hash: String,
                            changedAt: Instant,
                        ) {
                            error("simulated credential failure")
                        }
                    }
                runCatching {
                    BootstrapFirstAdmin(app.transactor, app.authorization, failingCredentials, PasswordHasher(), testClock).invoke(
                        BootstrapAdmin("admin", "Administrator", null, null, SecretPassword.of("test-admin-password")),
                    )
                }.isFailure shouldBe true
                app.database.count("commerce.users") shouldBe 0
                app.database.count("commerce.roles") shouldBe 0
                app.database.count("commerce.principal_roles") shouldBe 0
            }
        }

        test("login failures are generic for unknown, wrong, missing credential, and disabled user") {
            TestApplication.create().use { app ->
                val missing = login(app, username = "missing")
                val wrong = login(app, password = "wrong-password")
                missing.status shouldBe Status.UNAUTHORIZED
                wrong.bodyString() shouldBe missing.bodyString()
                newUser(app, "new-staff")
                login(app, username = "new-staff").bodyString() shouldBe missing.bodyString()
                app.authorization.setStatus(app.authorization.findUserByUsername("admin")!!.id, PrincipalStatus.DISABLED)
                login(app).bodyString() shouldBe missing.bodyString()
                app.database.count("commerce.principal_sessions") shouldBe 0
            }
        }

        test("me reads runtime identity and logout revokes; disabling invalidates an existing session") {
            TestApplication.create().use { app ->
                val cookie = app.adminCookie
                val me = request(app, Method.GET, "/auth/me", cookie)
                me.status shouldBe Status.OK
                val identity = CommerceJson.asA(me.bodyString(), CurrentUserResponse.serializer())
                identity.username shouldBe "admin"
                identity.roles shouldBe listOf("commerce.administrator")
                me.bodyString().contains("password") shouldBe false
                val logout = request(app, Method.POST, "/auth/logout", cookie)
                logout.status shouldBe Status.NO_CONTENT
                checkNotNull(logout.header("Set-Cookie")) shouldContain "Max-Age=0"
                request(app, Method.GET, "/auth/me", cookie).status shouldBe Status.UNAUTHORIZED
                request(app, Method.POST, "/auth/logout", cookie).status shouldBe Status.NO_CONTENT
                val freshCookie = login(app).header("Set-Cookie")!!.substringBefore(';')
                app.authorization.setStatus(app.authorization.findUserByUsername("admin")!!.id, PrincipalStatus.DISABLED)
                request(app, Method.GET, "/auth/me", freshCookie).status shouldBe Status.UNAUTHORIZED
            }
        }

        test("me handles a known service principal explicitly; authorization/me describes it as a SERVICE") {
            TestApplication.create().use { app ->
                val serviceId = ServiceId(UUID.randomUUID())
                app.authorization.createService(ServiceIdentity(serviceId, "future-adapter", PrincipalStatus.ACTIVE, emptySet()))
                // A runtime session is enough to exercise principal semantics; no service token transport is mounted.
                val cookie = "$STAFF_SESSION_COOKIE=${app.sessions.create(serviceId).token.value}"
                request(app, Method.GET, "/auth/me", cookie).status shouldBe Status.FORBIDDEN

                fun principal() =
                    request(app, Method.GET, "/authorization/me", cookie).let {
                        it.status shouldBe Status.OK
                        CommerceJson.asA(it.bodyString(), CurrentPrincipalDto.serializer())
                    }
                principal().let {
                    it.principal.kind shouldBe "SERVICE"
                    it.principal.id shouldBe serviceId.value.toString()
                    it.principal.displayName shouldBe "future-adapter"
                    it.permissions shouldBe emptyList()
                    it.permissionCatalogRevision shouldBe app.authorization.permissionCatalog.revision
                }
                // Its effective permissions are whatever the service currently holds, resolved live.
                val role = RoleKey("fionas.test-inquiry-reader")
                app.authorization.createRole(
                    RoleDefinition(
                        role,
                        "Inquiry reader",
                        "Test role.",
                        setOf(FionaPermissions.InquiriesRead, CommercePermissions.RoleRead),
                    ),
                )
                app.authorization.assignRole(serviceId, role)
                principal().permissions shouldBe listOf(CommercePermissions.RoleRead.value, FionaPermissions.InquiriesRead.value).sorted()
                request(app, Method.GET, "/auth/me", cookie).status shouldBe Status.FORBIDDEN
            }
        }

        test("authorization/me describes the USER session with live permissions and requires only authentication") {
            TestApplication.create().use { app ->
                val cookie = app.adminCookie
                val admin = checkNotNull(app.authorization.findUserByUsername("admin"))

                fun principal() =
                    request(app, Method.GET, "/authorization/me", cookie).let {
                        it.status shouldBe Status.OK
                        CommerceJson.asA(it.bodyString(), CurrentPrincipalDto.serializer())
                    }
                request(app, Method.GET, "/authorization/me", cookie = null).status shouldBe Status.UNAUTHORIZED
                // The retired static UI key establishes no principal.
                app.http(Request(Method.GET, "/authorization/me").withBearer("deterministic-test-ui-key")).status shouldBe
                    Status.UNAUTHORIZED
                // A service access token establishes the SERVICE principal, with its role's live permissions.
                app.http(Request(Method.GET, "/authorization/me").asFionasWeb(app)).let {
                    it.status shouldBe Status.OK
                    val service = CommerceJson.asA(it.bodyString(), CurrentPrincipalDto.serializer())
                    service.principal.kind shouldBe "SERVICE"
                    service.principal.id shouldBe
                        app.web.id.value
                            .toString()
                    service.permissions shouldBe FIONAS_WEB_PERMISSIONS.map { key -> key.value }.sorted()
                }
                val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
                principal().let {
                    it.principal.kind shouldBe "USER"
                    it.principal.id shouldBe admin.id.value.toString()
                    it.principal.displayName shouldBe "Test Administrator"
                    it.permissions.shouldBeSorted()
                    it.permissions shouldBe grants.map { key -> key.value }.sorted()
                    it.permissionCatalogRevision shouldMatch Regex("sha256:[0-9a-f]{64}")
                }
                // Grant changes appear on the next call of the same session, and RoleRead is never required.
                val limited = setOf(CommercePermissions.PaymentRecord, FionaPermissions.InquiriesRead)
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, limited)
                principal().permissions shouldBe limited.map { it.value }.sorted()
                app.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
                principal().permissions shouldBe emptyList()
                // A safe GET needs no Origin, like Fiona's other authenticated reads.
                request(app, Method.GET, "/authorization/me", cookie, origin = null).status shouldBe Status.OK
                request(app, Method.GET, "/auth/me", cookie, origin = null).status shouldBe Status.OK
                // Undeclared methods are derived from the contract's routes, this one included.
                request(app, Method.OPTIONS, "/authorization/me", cookie).status shouldBe Status.METHOD_NOT_ALLOWED
            }
        }

        test("the administration permission catalog is the running runtime and Fiona catalog, with the principal's revision") {
            TestApplication.create().use { app ->
                val response = request(app, Method.GET, "/admin/access/permissions")
                response.status shouldBe Status.OK
                val catalog = CommerceJson.asA(response.bodyString(), PermissionsDto.serializer())
                val keys = catalog.permissions.map { it.key }
                keys shouldContainAll
                    listOf(
                        FionaPermissions.CredentialsManage.value,
                        FionaPermissions.InquiriesRead.value,
                        CommercePermissions.RoleRead.value,
                        CommercePermissions.PrincipalRead.value,
                        // Runtime-owned since 0.0.20: the catalog is the composed one, not a hand-kept subset.
                        RuntimePermissions.ServiceCredentialManage.value,
                    )
                keys.shouldBeSorted()
                keys shouldBe
                    app.authorization.permissionCatalog.definitions
                        .map { it.key.value }
                catalog.permissions.single { it.key == FionaPermissions.InquiriesRead.value }.group shouldBe "fionas.inquiries"
                catalog.permissions.single { it.key == FionaPermissions.CredentialsManage.value }.group shouldBe "fionas.credentials"
                catalog.revision shouldBe app.authorization.permissionCatalog.revision
                // The catalog revision the current principal reports is the served catalog's own.
                CommerceJson
                    .asA(
                        request(app, Method.GET, "/authorization/me").bodyString(),
                        CurrentPrincipalDto.serializer(),
                    ).permissionCatalogRevision shouldBe catalog.revision
                // Catalog membership grants nothing: bootstrap never expands the Administrator role to match it.
                checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions shouldNotContain
                    CommercePermissions.BookingRead
            }
        }

        test("the permission catalog requires live RoleRead on the existing session") {
            TestApplication.create().use { app ->
                val cookie = app.adminCookie
                request(app, Method.GET, "/admin/access/permissions", cookie = null).status shouldBe Status.UNAUTHORIZED
                request(app, Method.GET, "/admin/access/permissions", cookie).status shouldBe Status.OK
                val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants - CommercePermissions.RoleRead)
                request(app, Method.GET, "/admin/access/permissions", cookie).status shouldBe Status.FORBIDDEN
                request(app, Method.GET, "/authorization/me", cookie).status shouldBe Status.OK
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
                request(app, Method.GET, "/admin/access/permissions", cookie).status shouldBe Status.OK
            }
        }

        test("me resolves sorted live effective permissions without role-read permission or a new login") {
            TestApplication.create().use { app ->
                val cookie = app.adminCookie

                fun me() =
                    CommerceJson.asA(
                        request(app, Method.GET, "/auth/me", cookie)
                            .also {
                                it.status shouldBe Status.OK
                            }.bodyString(),
                        CurrentUserResponse.serializer(),
                    )
                val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
                me().permissions shouldBe grants.map { it.value }.sorted()
                val limited = setOf(CommercePermissions.PaymentRecord, FionaPermissions.InquiriesRead)
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, limited)
                request(app, Method.GET, "/admin/access/roles", cookie).status shouldBe Status.FORBIDDEN
                me().permissions shouldBe limited.map { it.value }.sorted()
                val admin = checkNotNull(app.authorization.findUserByUsername("admin"))
                app.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
                me().permissions shouldBe emptyList()
                me().roles shouldBe emptyList()
                app.authorization.assignRole(admin.id, CommerceRoles.Administrator)
                me().permissions shouldBe limited.map { it.value }.sorted()
            }
        }

        test("removed catalog and pricing routes do not exist, and no offerings permission is defined or granted") {
            TestApplication.create().use { app ->
                listOf(
                    Method.GET to "/offering-catalog",
                    Method.POST to "/offering-catalog",
                    Method.GET to "/inquiry-form",
                    Method.POST to "/estimate-preview",
                ).forEach { (method, path) ->
                    request(app, method, path, cookie = null).status shouldBe Status.NOT_FOUND
                    request(app, method, path).status shouldBe Status.NOT_FOUND
                }
                val catalog = request(app, Method.GET, "/admin/access/permissions").bodyString()
                listOf("commerce.offerings.manage", "fionas.inquiry-form.read", "fionas.estimate-preview.create").forEach {
                    catalog.contains(it) shouldBe false
                }
                app.database.strings("SELECT permission_key FROM commerce.role_permissions").none { "offering" in it } shouldBe true
            }
        }

        test("mounted authorization administration checks permissions and manages runtime users") {
            TestApplication.create().use { app ->
                request(app, Method.GET, "/admin/access/users", cookie = null).status shouldBe Status.UNAUTHORIZED
                val admin = app.authorization.findUserByUsername("admin")!!
                app.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
                request(app, Method.GET, "/admin/access/users").status shouldBe Status.FORBIDDEN
                app.authorization.assignRole(admin.id, CommerceRoles.Administrator)
                request(app, Method.GET, "/admin/access/users").status shouldBe Status.OK
                request(app, Method.GET, "/admin/access/roles").status shouldBe Status.OK
                request(app, Method.GET, "/admin/access/permissions").bodyString().let { catalog ->
                    catalog shouldContain FionaPermissions.CredentialsManage.value
                    catalog shouldContain FionaPermissions.InquiriesRead.value
                    catalog shouldContain FionaPermissions.InquiriesCreate.value
                    catalog shouldContain FionaPermissions.FinancialTermsManage.value
                    catalog shouldContain RuntimePermissions.ServiceCredentialManage.value
                }
                val created =
                    request(app, Method.POST, "/admin/access/users", body = """{"username":"new-staff","displayName":"New Staff"}""")
                created.status shouldBe Status.CREATED
                val id = app.authorization.findUserByUsername("new-staff")!!.id
                request(app, Method.PUT, "/admin/access/users/${id.value}/roles/commerce.administrator").status shouldBe Status.NO_CONTENT
                app.authorization
                    .assignedRoles(id)
                    .map { it.role }
                    .toSet() shouldBe setOf(CommerceRoles.Administrator)
            }
        }

        test("credential administration provisions login and returns no secret material") {
            TestApplication.create().use { app ->
                val id = newUser(app, "new-staff")
                val path = "/admin/users/${id.value}/credentials/password"
                val body = """{"password":"new-staff-password"}"""
                request(app, Method.PUT, path, cookie = null, body = body).status shouldBe Status.UNAUTHORIZED
                val admin = app.authorization.findUserByUsername("admin")!!
                app.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
                request(app, Method.PUT, path, body = body).status shouldBe Status.FORBIDDEN
                app.authorization.assignRole(admin.id, CommerceRoles.Administrator)
                val set = request(app, Method.PUT, path, body = body)
                set.status shouldBe Status.NO_CONTENT
                set.bodyString() shouldBe ""
                login(app, "new-staff", "new-staff-password").status shouldBe Status.NO_CONTENT
                request(app, Method.PUT, path, body = """{"password":"short"}""").status shouldBe Status.UNPROCESSABLE_ENTITY
                request(app, Method.PUT, "/admin/users/not-a-uuid/credentials/password", body = body).status shouldBe Status.BAD_REQUEST
                request(app, Method.PUT, "/admin/users/${UUID.randomUUID()}/credentials/password", body = body).status shouldBe
                    Status.NOT_FOUND
                app.database.count("fionas.user_credentials") shouldBe 2
            }
        }

        test("Origin protection applies to unsafe administration methods") {
            TestApplication.create().use { app ->
                val id =
                    app.authorization
                        .findUserByUsername("admin")!!
                        .id.value
                val path = "/admin/users/$id/credentials/password"
                val body = """{"password":"another-test-password"}"""
                request(app, Method.PUT, path, origin = null, body = body).status shouldBe Status.FORBIDDEN
                request(app, Method.PUT, path, origin = "https://evil.example", body = body).status shouldBe Status.FORBIDDEN
                request(app, Method.PUT, path, body = body).status shouldBe Status.NO_CONTENT
                request(app, Method.GET, "/auth/me").status shouldBe Status.OK
                request(app, Method.POST, "/admin/access/users", origin = null).status shouldBe Status.FORBIDDEN
                request(app, Method.PATCH, "/admin/access/users/$id", origin = null).status shouldBe Status.FORBIDDEN
                request(app, Method.DELETE, "/admin/access/users/$id/roles/commerce.administrator", origin = null).status shouldBe
                    Status.FORBIDDEN
                login(app, origin = null).status shouldBe Status.FORBIDDEN
            }
        }
    })
