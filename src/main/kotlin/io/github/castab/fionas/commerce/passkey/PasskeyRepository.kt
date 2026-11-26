package io.github.castab.fionas.commerce.passkey

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Fiona-owned passkey material and WebAuthn user ids ("user handles"), keyed by runtime human users. */
interface PasskeyRepository {
    fun webAuthnUserId(
        transaction: Transaction,
        userId: UserId,
    ): ByteArray?

    /** Stores [id] as [userId]'s WebAuthn user id (the "user handle") unless one exists, and returns the stored one either way. */
    fun ensureWebAuthnUserId(
        transaction: Transaction,
        userId: UserId,
        id: ByteArray,
        createdAt: Instant,
    ): ByteArray

    fun userForWebAuthnUserId(
        transaction: Transaction,
        handle: ByteArray,
    ): UserId?

    fun insert(
        transaction: Transaction,
        passkey: Passkey,
    )

    fun byCredentialId(
        transaction: Transaction,
        credentialId: ByteArray,
    ): Passkey?

    /** [userId]'s passkey [id], or null for an unknown id and for another user's passkey alike. */
    fun find(
        transaction: Transaction,
        userId: UserId,
        id: PasskeyId,
    ): Passkey?

    /** [userId]'s passkeys, oldest first. With [lock], serializes concurrent registrations for that user. */
    fun list(
        transaction: Transaction,
        userId: UserId,
        lock: Boolean = false,
    ): List<Passkey>

    fun rename(
        transaction: Transaction,
        id: PasskeyId,
        name: PasskeyName?,
    )

    fun recordUse(
        transaction: Transaction,
        id: PasskeyId,
        signatureCount: Long,
        backedUp: Boolean,
        usedAt: Instant,
    )

    fun delete(
        transaction: Transaction,
        id: PasskeyId,
    )
}

class JdbiPasskeyRepository : PasskeyRepository {
    override fun webAuthnUserId(
        transaction: Transaction,
        userId: UserId,
    ): ByteArray? =
        transaction.handle
            .createQuery("SELECT user_handle FROM fionas.staff_webauthn_user_handles WHERE user_id = :userId")
            .bind("userId", userId.value)
            .mapTo(ByteArray::class.java)
            .findOne()
            .orElse(null)

    override fun ensureWebAuthnUserId(
        transaction: Transaction,
        userId: UserId,
        id: ByteArray,
        createdAt: Instant,
    ): ByteArray {
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.staff_webauthn_user_handles (user_id, user_handle, created_at)
                   VALUES (:userId, :handle, :createdAt)
                   ON CONFLICT (user_id) DO NOTHING""",
            ).bind("userId", userId.value)
            .bind("handle", id)
            .bind("createdAt", createdAt)
            .execute()
        return checkNotNull(webAuthnUserId(transaction, userId))
    }

    override fun userForWebAuthnUserId(
        transaction: Transaction,
        id: ByteArray,
    ): UserId? =
        transaction.handle
            .createQuery("SELECT user_id FROM fionas.staff_webauthn_user_handles WHERE user_handle = :handle")
            .bind("handle", id)
            .mapTo(UUID::class.java)
            .findOne()
            .map(::UserId)
            .orElse(null)

    override fun insert(
        transaction: Transaction,
        passkey: Passkey,
    ) {
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.staff_passkeys
                     (id, user_id, credential_id, public_key_cose, signature_count, aaguid, transports,
                      backup_eligible, backed_up, name, created_at, last_used_at)
                   VALUES
                     (:id, :userId, :credentialId, :publicKeyCose, :signatureCount, :aaguid, :transports,
                      :backupEligible, :backedUp, :name, :createdAt, CAST(:lastUsedAt AS timestamptz))""",
            ).bind("id", passkey.id.value)
            .bind("userId", passkey.userId.value)
            .bind("credentialId", passkey.credentialId)
            .bind("publicKeyCose", passkey.publicKeyCose)
            .bind("signatureCount", passkey.signatureCount)
            .bind("aaguid", passkey.aaguid)
            .bindArray("transports", String::class.java, passkey.transports)
            .bind("backupEligible", passkey.backupEligible)
            .bind("backedUp", passkey.backedUp)
            .bindByType("name", passkey.name?.value, String::class.java)
            .bind("createdAt", passkey.createdAt)
            .bind("lastUsedAt", passkey.lastUsedAt?.toString())
            .execute()
    }

    override fun byCredentialId(
        transaction: Transaction,
        credentialId: ByteArray,
    ): Passkey? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_passkeys WHERE credential_id = :credentialId")
            .bind("credentialId", credentialId)
            .map { row, _ -> passkey(row) }
            .findOne()
            .orElse(null)

    override fun find(
        transaction: Transaction,
        userId: UserId,
        id: PasskeyId,
    ): Passkey? =
        transaction.handle
            .createQuery("SELECT * FROM fionas.staff_passkeys WHERE id = :id AND user_id = :userId")
            .bind("id", id.value)
            .bind("userId", userId.value)
            .map { row, _ -> passkey(row) }
            .findOne()
            .orElse(null)

    override fun list(
        transaction: Transaction,
        userId: UserId,
        lock: Boolean,
    ): List<Passkey> {
        if (lock) {
            // Every registration first ensures the user's WebAuthn user id row, so locking it serializes them.
            transaction.handle
                .createQuery("SELECT user_id FROM fionas.staff_webauthn_user_handles WHERE user_id = :userId FOR UPDATE")
                .bind("userId", userId.value)
                .mapTo(UUID::class.java)
                .findOne()
        }
        return transaction.handle
            .createQuery("SELECT * FROM fionas.staff_passkeys WHERE user_id = :userId ORDER BY created_at, id")
            .bind("userId", userId.value)
            .map { row, _ -> passkey(row) }
            .list()
    }

    override fun rename(
        transaction: Transaction,
        id: PasskeyId,
        name: PasskeyName?,
    ) {
        transaction.handle
            .createUpdate("UPDATE fionas.staff_passkeys SET name = :name WHERE id = :id")
            .bind("id", id.value)
            .bindByType("name", name?.value, String::class.java)
            .execute()
    }

    override fun recordUse(
        transaction: Transaction,
        id: PasskeyId,
        signatureCount: Long,
        backedUp: Boolean,
        usedAt: Instant,
    ) {
        transaction.handle
            .createUpdate(
                """UPDATE fionas.staff_passkeys
                   SET signature_count = :signatureCount,
                       backed_up = backup_eligible AND :backedUp,
                       last_used_at = :usedAt
                   WHERE id = :id""",
            ).bind("id", id.value)
            .bind("signatureCount", signatureCount)
            .bind("backedUp", backedUp)
            .bind("usedAt", usedAt)
            .execute()
    }

    override fun delete(
        transaction: Transaction,
        id: PasskeyId,
    ) {
        transaction.handle
            .createUpdate("DELETE FROM fionas.staff_passkeys WHERE id = :id")
            .bind("id", id.value)
            .execute()
    }

    @Suppress("UNCHECKED_CAST")
    private fun passkey(row: ResultSet) =
        Passkey(
            id = PasskeyId(row.getObject("id", UUID::class.java)),
            userId = UserId(row.getObject("user_id", UUID::class.java)),
            credentialId = row.getBytes("credential_id"),
            publicKeyCose = row.getBytes("public_key_cose"),
            signatureCount = row.getLong("signature_count"),
            aaguid = row.getObject("aaguid", UUID::class.java),
            transports = (row.getArray("transports").array as Array<String>).toList(),
            backupEligible = row.getBoolean("backup_eligible"),
            backedUp = row.getBoolean("backed_up"),
            name = PasskeyName.of(row.getString("name")),
            createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            lastUsedAt = row.getObject("last_used_at", OffsetDateTime::class.java)?.toInstant(),
        )
}

/** Issued WebAuthn challenges. */
interface WebAuthnCeremonyRepository {
    /** Inserts [ceremony] after deleting every ceremony that expired before [now], so the table stays bounded. */
    fun insert(
        transaction: Transaction,
        ceremony: WebAuthnCeremony,
        now: Instant,
    )

    /**
     * Atomically marks ceremony [id] of [kind] consumed and returns it, or null when it is
     * unknown, of another kind, already consumed or expired at [now]. At most one caller ever
     * receives a given ceremony.
     */
    fun consume(
        transaction: Transaction,
        id: CeremonyId,
        kind: CeremonyKind,
        now: Instant,
    ): WebAuthnCeremony?
}

class JdbiWebAuthnCeremonyRepository : WebAuthnCeremonyRepository {
    override fun insert(
        transaction: Transaction,
        ceremony: WebAuthnCeremony,
        now: Instant,
    ) {
        transaction.handle
            .createUpdate("DELETE FROM fionas.webauthn_ceremonies WHERE expires_at <= :now")
            .bind("now", now)
            .execute()
        transaction.handle
            .createUpdate(
                """INSERT INTO fionas.webauthn_ceremonies (id, kind, challenge, user_id, created_at, expires_at)
                   VALUES (:id, :kind, :challenge, CAST(:userId AS uuid), :createdAt, :expiresAt)""",
            ).bind("id", ceremony.id.value)
            .bind("kind", ceremony.kind.name)
            .bind("challenge", ceremony.challenge)
            .bind("userId", ceremony.userId?.value?.toString())
            .bind("createdAt", ceremony.createdAt)
            .bind("expiresAt", ceremony.expiresAt)
            .execute()
    }

    override fun consume(
        transaction: Transaction,
        id: CeremonyId,
        kind: CeremonyKind,
        now: Instant,
    ): WebAuthnCeremony? =
        transaction.handle
            .createQuery(
                """UPDATE fionas.webauthn_ceremonies SET consumed_at = :now
                   WHERE id = :id AND kind = :kind AND consumed_at IS NULL AND expires_at > :now
                   RETURNING id, kind, challenge, user_id, created_at, expires_at""",
            ).bind("id", id.value)
            .bind("kind", kind.name)
            .bind("now", now)
            .map { row, _ ->
                WebAuthnCeremony(
                    CeremonyId(row.getObject("id", UUID::class.java)),
                    CeremonyKind.valueOf(row.getString("kind")),
                    row.getBytes("challenge"),
                    row.getObject("user_id", UUID::class.java)?.let(::UserId),
                    row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                    row.getObject("expires_at", OffsetDateTime::class.java).toInstant(),
                )
            }.findOne()
            .orElse(null)
}
