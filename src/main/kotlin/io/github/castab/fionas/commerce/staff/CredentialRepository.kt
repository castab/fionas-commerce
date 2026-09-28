package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import java.time.Instant

/** Fiona-owned password material, keyed by a runtime human user. */
interface CredentialRepository {
    fun passwordHash(
        transaction: Transaction,
        userId: UserId,
    ): String?

    fun setPassword(
        transaction: Transaction,
        userId: UserId,
        hash: String,
        changedAt: Instant,
    )
}

class JdbiCredentialRepository : CredentialRepository {
    override fun passwordHash(
        transaction: Transaction,
        userId: UserId,
    ): String? =
        transaction.handle
            .createQuery("SELECT password_hash FROM fionas.user_credentials WHERE user_id = :userId")
            .bind("userId", userId.value)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)

    override fun setPassword(
        transaction: Transaction,
        userId: UserId,
        hash: String,
        changedAt: Instant,
    ) {
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.user_credentials (user_id, password_hash, password_changed_at)
                   VALUES (:userId, :hash, :changedAt)
                   ON CONFLICT (user_id) DO UPDATE SET
                     password_hash = EXCLUDED.password_hash,
                     password_changed_at = EXCLUDED.password_changed_at""",
            ).bind("userId", userId.value)
            .bind("hash", hash)
            .bind("changedAt", changedAt)
            .execute()
    }
}
