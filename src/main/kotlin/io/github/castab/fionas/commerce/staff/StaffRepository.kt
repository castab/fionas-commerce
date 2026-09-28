package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.commerce.staff.ServiceIdentity
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import java.time.Instant

/** Fiona-owned staff identity, credential, and assignment persistence. */
interface StaffRepository {
    fun countUsers(transaction: Transaction): Long

    fun insertUser(
        transaction: Transaction,
        user: User,
    )

    fun insertCredential(
        transaction: Transaction,
        userId: UserId,
        passwordHash: String,
        changedAt: Instant,
    )

    fun findUser(
        transaction: Transaction,
        id: UserId,
    ): User?

    fun findUserByUsername(
        transaction: Transaction,
        username: String,
    ): User?

    fun passwordHash(
        transaction: Transaction,
        id: UserId,
    ): String?

    fun findService(
        transaction: Transaction,
        id: io.github.castab.commerce.staff.ServiceId,
    ): ServiceIdentity?

    fun assignRole(
        transaction: Transaction,
        principalId: PrincipalId,
        role: RoleKey,
    )
}
