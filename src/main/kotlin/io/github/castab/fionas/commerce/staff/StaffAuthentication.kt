package io.github.castab.fionas.commerce.staff

import de.mkammerer.argon2.Argon2Factory
import io.github.castab.commerce.runtime.authorization.AuthorizationDirectory
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PermissionDefinition
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A mutable secret confined to credential handling. Close it to erase its character buffer. */
class SecretPassword private constructor(
    private val characters: CharArray,
) : AutoCloseable {
    internal fun <T> withCharacters(block: (CharArray) -> T): T = block(characters)

    override fun close() = characters.fill('\u0000')

    companion object {
        fun of(text: String): SecretPassword = SecretPassword(text.toCharArray())
    }
}

/** The only application component that encodes or verifies human passwords. */
class PasswordHasher {
    fun hash(password: SecretPassword): String =
        password.withCharacters { characters ->
            val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
            argon2.hash(3, 65536, 1, characters)
        }

    fun verify(
        encoded: String,
        password: SecretPassword,
    ): Boolean =
        password.withCharacters { characters ->
            val argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
            argon2.verify(encoded, characters)
        }
}

object FionaPermissions {
    val CredentialsManage = PermissionKey("fionas.credentials.manage")
    val definitions =
        listOf(
            PermissionDefinition(
                CredentialsManage,
                "Manage staff credentials",
                "Set or reset password credentials for Fiona's staff users",
            ),
        )
}

/** Checks Fiona's human credentials; sessions remain wholly runtime-owned. */
interface PasswordAuthenticator {
    fun authenticate(
        username: String,
        password: SecretPassword,
    ): UserId?
}

class StaffPasswordAuthenticator(
    private val authorization: AuthorizationDirectory,
    private val transactor: Transactor,
    private val credentials: CredentialRepository,
    private val passwords: PasswordHasher,
) : PasswordAuthenticator {
    override fun authenticate(
        username: String,
        password: SecretPassword,
    ): UserId? {
        // Invalid usernames have the same public result as an unknown runtime user.
        val user =
            try {
                authorization.findUserByUsername(username)
            } catch (_: CommerceFailure.ValidationFailed) {
                null
            } ?: return null
        val hash = transactor.inTransaction { credentials.passwordHash(it, user.id) } ?: return null
        return user.id.takeIf { passwords.verify(hash, password) && user.status == PrincipalStatus.ACTIVE }
    }
}

/** Verifies Fiona credentials, then lets runtime issue a session for the active user. */
class Login(
    private val authenticator: PasswordAuthenticator,
    private val sessions: SessionManager,
) {
    fun invoke(
        username: String,
        password: SecretPassword,
    ): IssuedSession? =
        password.use {
            authenticator.authenticate(username, password)?.let { id ->
                // A concurrent disable after password verification has the same public result.
                try {
                    sessions.create(id)
                } catch (_: CommerceFailure.NotFound) {
                    null
                } catch (_: CommerceFailure.Conflict) {
                    null
                }
            }
        }
}

/** Explicit first-admin provisioning input. It is read once and never logged. */
class BootstrapAdmin(
    val username: String,
    val displayName: String,
    val firstName: String?,
    val lastName: String?,
    val password: SecretPassword,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BootstrapAdmin? {
            val username = environment["FIONAS_BOOTSTRAP_ADMIN_USERNAME"] ?: return null
            val password = environment["FIONAS_BOOTSTRAP_ADMIN_PASSWORD"] ?: return null
            val displayName = environment["FIONAS_BOOTSTRAP_ADMIN_DISPLAY_NAME"] ?: return null
            return BootstrapAdmin(
                username,
                displayName,
                environment["FIONAS_BOOTSTRAP_ADMIN_FIRST_NAME"],
                environment["FIONAS_BOOTSTRAP_ADMIN_LAST_NAME"],
                SecretPassword.of(password),
            )
        }
    }
}

/** Startup operation: only the first staff user can be provisioned this way. */
class BootstrapFirstAdmin(
    private val transactor: Transactor,
    private val authorization: AuthorizationDirectory,
    private val credentials: CredentialRepository,
    private val passwords: PasswordHasher,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun invoke(input: BootstrapAdmin?) {
        if (input == null) return
        input.password.use { password ->
            if (authorization.listUsers().isNotEmpty()) return
            try {
                transactor.inTransaction { transaction ->
                    transaction.handle
                        .createQuery("SELECT pg_advisory_xact_lock(1229955662)")
                        .map { _, _ -> Unit }
                        .one()
                    password.withCharacters { require(it.size >= 12) { "Bootstrap password must have at least 12 characters" } }
                    val username = input.username.trim()
                    val displayName = input.displayName.trim()
                    require(displayName.isNotEmpty() && displayName.length <= 200) { "Bootstrap display name is invalid" }
                    authorization.createRole(
                        transaction,
                        RoleDefinition(
                            CommerceRoles.Administrator,
                            "Administrator",
                            "May administer Fiona's staff access and offerings catalog",
                            setOf(
                                CommercePermissions.OfferingsManage,
                                CommercePermissions.PrincipalRead,
                                CommercePermissions.PrincipalManage,
                                CommercePermissions.RoleRead,
                                CommercePermissions.RoleManage,
                                CommercePermissions.RoleAssign,
                                FionaPermissions.CredentialsManage,
                            ),
                        ),
                    )
                    val user =
                        User(
                            UserId(newId()),
                            username,
                            input.firstName?.trim(),
                            input.lastName?.trim(),
                            displayName,
                            PrincipalStatus.ACTIVE,
                            emptySet(),
                        )
                    authorization.createUser(transaction, user)
                    credentials.setPassword(transaction, user.id, passwords.hash(password), clock.instant().truncatedTo(ChronoUnit.MICROS))
                    authorization.assignRole(transaction, user.id, CommerceRoles.Administrator)
                }
            } catch (e: CommerceFailure.Conflict) {
                // Another instance may have completed bootstrap after our initial read.
                if (authorization.listUsers().isEmpty()) throw e
            }
        }
    }
}

/** Administrator provisioning and reset. Existing sessions remain active. */
class SetStaffPassword(
    private val authorization: AuthorizationDirectory,
    private val transactor: Transactor,
    private val credentials: CredentialRepository,
    private val passwords: PasswordHasher,
    private val clock: Clock,
) {
    fun invoke(
        userId: UserId,
        password: SecretPassword,
    ) {
        password.use { secret ->
            if (authorization.getUser(userId) == null) throw CommerceFailure.NotFound("User does not exist")
            secret.withCharacters {
                if (it.size < 12) throw CommerceFailure.ValidationFailed("Password must have at least 12 characters")
            }
            val hash = passwords.hash(secret)
            transactor.inTransaction { credentials.setPassword(it, userId, hash, clock.instant().truncatedTo(ChronoUnit.MICROS)) }
        }
    }
}
