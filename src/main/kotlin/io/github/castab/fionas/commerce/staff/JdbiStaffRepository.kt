package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.RoleAssignment
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import java.time.Instant
import java.util.UUID

class JdbiStaffRepository : StaffRepository {
    override fun countUsers(transaction: Transaction): Long =
        transaction.handle
            .createQuery("SELECT count(*) FROM fionas.users")
            .mapTo(Long::class.java)
            .one()

    override fun insertUser(
        transaction: Transaction,
        user: User,
    ) {
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.users (id, username, first_name, last_name, display_name, status)
               VALUES (:id, :username, :firstName, :lastName, :displayName, :status)""",
            ).bind("id", user.id.value)
            .bind("username", user.username)
            .bind("firstName", user.firstName)
            .bind("lastName", user.lastName)
            .bind("displayName", user.displayName)
            .bind("status", user.status.name)
            .execute()
    }

    override fun insertCredential(
        transaction: Transaction,
        userId: UserId,
        passwordHash: String,
        changedAt: Instant,
    ) {
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.user_credentials (user_id, password_hash, password_changed_at)
               VALUES (:userId, :hash, :changedAt)""",
            ).bind("userId", userId.value)
            .bind("hash", passwordHash)
            .bind("changedAt", changedAt)
            .execute()
    }

    override fun findUser(
        transaction: Transaction,
        id: UserId,
    ): User? =
        transaction.handle
            .createQuery(
                "SELECT id, username, first_name, last_name, display_name, status FROM fionas.users WHERE id = :id",
            ).bind("id", id.value)
            .map { row, _ -> user(transaction, row) }
            .findOne()
            .orElse(null)

    override fun findUserByUsername(
        transaction: Transaction,
        username: String,
    ): User? =
        transaction.handle
            .createQuery(
                "SELECT id, username, first_name, last_name, display_name, status FROM fionas.users WHERE username = :username",
            ).bind("username", username)
            .map { row, _ -> user(transaction, row) }
            .findOne()
            .orElse(null)

    override fun passwordHash(
        transaction: Transaction,
        id: UserId,
    ): String? =
        transaction.handle
            .createQuery("SELECT password_hash FROM fionas.user_credentials WHERE user_id = :id")
            .bind("id", id.value)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)

    override fun findService(
        transaction: Transaction,
        id: ServiceId,
    ): ServiceIdentity? =
        transaction.handle
            .createQuery("SELECT id, name, status FROM fionas.service_identities WHERE id = :id")
            .bind("id", id.value)
            .map { row, _ ->
                ServiceIdentity(
                    ServiceId(row.getObject("id", UUID::class.java)),
                    row.getString("name"),
                    PrincipalStatus.valueOf(row.getString("status")),
                    roles(transaction, id),
                )
            }.findOne()
            .orElse(null)

    override fun assignRole(
        transaction: Transaction,
        principalId: PrincipalId,
        role: RoleKey,
    ) {
        val (kind, id) = principalColumns(principalId)
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.principal_role_assignments (principal_kind, principal_id, role_key)
               VALUES (:kind, :id, :role) ON CONFLICT DO NOTHING""",
            ).bind("kind", kind)
            .bind("id", id)
            .bind("role", role.value)
            .execute()
    }

    private fun user(
        transaction: Transaction,
        row: java.sql.ResultSet,
    ): User {
        val id = UserId(row.getObject("id", UUID::class.java))
        return User(
            id,
            row.getString("username"),
            row.getString("first_name"),
            row.getString("last_name"),
            row.getString("display_name"),
            PrincipalStatus.valueOf(row.getString("status")),
            roles(transaction, id),
        )
    }

    private fun roles(
        transaction: Transaction,
        principalId: PrincipalId,
    ): Set<RoleAssignment> {
        val (kind, id) = principalColumns(principalId)
        return transaction.handle
            .createQuery(
                """SELECT role_key FROM fionas.principal_role_assignments
               WHERE principal_kind = :kind AND principal_id = :id ORDER BY role_key""",
            ).bind("kind", kind)
            .bind("id", id)
            .map { row, _ -> RoleAssignment(RoleKey(row.getString("role_key"))) }
            .list()
            .toSet()
    }

    private fun principalColumns(id: PrincipalId): Pair<String, UUID> =
        when (id) {
            is UserId -> "USER" to id.value
            is ServiceId -> "SERVICE" to id.value
        }
}
