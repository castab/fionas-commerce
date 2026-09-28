package io.github.castab.fionas.commerce.staff

import de.mkammerer.argon2.Argon2Factory
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleAssignment
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Locale
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

fun normalizeUsername(submitted: String): String = submitted.trim().lowercase(Locale.ROOT)

fun requireUsername(username: String) {
    require(username.matches(Regex("[a-z0-9._-]{1,100}"))) { "Username must be 1 to 100 letters, digits, dots, underscores, or hyphens" }
}

/** Checks Fiona's human credentials; sessions remain wholly runtime-owned. */
interface PasswordAuthenticator {
    fun authenticate(
        transaction: Transaction,
        username: String,
        password: SecretPassword,
    ): UserId?
}

class StaffPasswordAuthenticator(
    private val users: StaffRepository,
    private val passwords: PasswordHasher,
) : PasswordAuthenticator {
    override fun authenticate(
        transaction: Transaction,
        username: String,
        password: SecretPassword,
    ): UserId? {
        val normalized = normalizeUsername(username)
        val user = users.findUserByUsername(transaction, normalized) ?: return null
        val hash = users.passwordHash(transaction, user.id) ?: return null
        return user.id.takeIf { passwords.verify(hash, password) && user.status == PrincipalStatus.ACTIVE }
    }
}

/** Verifies credentials and issues a runtime session in one shared transaction. */
class Login(
    private val transactor: Transactor,
    private val authenticator: PasswordAuthenticator,
    private val sessions: SessionManager,
) {
    fun invoke(
        username: String,
        password: SecretPassword,
    ): IssuedSession? =
        password.use {
            transactor.inTransaction { transaction ->
                authenticator.authenticate(transaction, username, password)?.let { sessions.create(transaction, it) }
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
    private val users: StaffRepository,
    private val passwords: PasswordHasher,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun invoke(input: BootstrapAdmin?) {
        if (input == null) return
        input.password.use { password ->
            transactor.inTransaction { transaction ->
                transaction.handle
                    .createQuery("SELECT pg_advisory_xact_lock(1229955662)")
                    .map { _, _ -> Unit }
                    .one()
                if (users.countUsers(transaction) != 0L) return@inTransaction
                password.withCharacters { require(it.size >= 12) { "Bootstrap password must have at least 12 characters" } }
                val username = normalizeUsername(input.username)
                requireUsername(username)
                val displayName = input.displayName.trim()
                require(displayName.isNotEmpty() && displayName.length <= 200) { "Bootstrap display name is invalid" }
                val user =
                    User(
                        UserId(newId()),
                        username,
                        input.firstName?.trim(),
                        input.lastName?.trim(),
                        displayName,
                        PrincipalStatus.ACTIVE,
                        setOf(RoleAssignment(CommerceRoles.Administrator)),
                    )
                users.insertUser(transaction, user)
                users.insertCredential(transaction, user.id, passwords.hash(password), clock.instant().truncatedTo(ChronoUnit.MICROS))
                users.assignRole(transaction, user.id, CommerceRoles.Administrator)
            }
        }
    }
}
