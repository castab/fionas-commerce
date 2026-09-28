package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PrincipalStatus
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
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
                        CommercePermissions.OfferingsManage,
                        CommercePermissions.FinancialDocumentRead,
                        CommercePermissions.FinancialDocumentCreate,
                        CommercePermissions.PaymentRecord,
                        CommercePermissions.PrincipalRead,
                        CommercePermissions.PrincipalManage,
                        CommercePermissions.RoleRead,
                        CommercePermissions.RoleManage,
                        CommercePermissions.RoleAssign,
                        FionaPermissions.CredentialsManage,
                    )
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

        test("me handles a known service principal explicitly") {
            TestApplication.create().use { app ->
                val serviceId = ServiceId(UUID.randomUUID())
                app.authorization.createService(ServiceIdentity(serviceId, "future-adapter", PrincipalStatus.ACTIVE, emptySet()))
                val cookie = "__Host-fionas_session=${app.sessions.create(serviceId).token.value}"
                request(app, Method.GET, "/auth/me", cookie).status shouldBe Status.FORBIDDEN
            }
        }

        test("Offerings public reads and protected writes use live runtime permissions") {
            TestApplication.create().use { app ->
                request(app, Method.POST, "/offering-catalog", cookie = null).status shouldBe Status.UNAUTHORIZED
                request(app, Method.GET, "/offering-catalog", cookie = null).status shouldBe Status.NOT_FOUND
                val admin = app.authorization.findUserByUsername("admin")!!
                app.authorization.unassignRole(admin.id, CommerceRoles.Administrator)
                request(app, Method.POST, "/offering-catalog").status shouldBe Status.FORBIDDEN
                app.authorization.assignRole(admin.id, CommerceRoles.Administrator)
                request(app, Method.POST, "/offering-catalog").status shouldBe Status.CREATED
                request(app, Method.GET, "/offering-catalog", cookie = null).status shouldBe Status.OK
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
                request(app, Method.GET, "/admin/access/permissions").bodyString() shouldContain FionaPermissions.CredentialsManage.value
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
