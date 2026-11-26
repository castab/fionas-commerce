package io.github.castab.fionas.commerce.passkey

import io.github.castab.commerce.staff.UserId
import java.time.Instant
import java.util.UUID

/** Fiona's stable id of one registered passkey; not the WebAuthn credential id, which is never returned. */
@JvmInline
value class PasskeyId(
    val value: UUID,
)

/** A staff member's own name for a passkey: trimmed, 1–[MAX_LENGTH] characters, no control characters. */
@JvmInline
value class PasskeyName private constructor(
    val value: String,
) {
    companion object {
        const val MAX_LENGTH = 64

        /** The validated name, or null for a blank one (meaning "no name"). */
        fun of(text: String?): PasskeyName? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.length > MAX_LENGTH || trimmed.any(Char::isISOControl)) throw InvalidPasskeyName()
            return PasskeyName(trimmed)
        }
    }
}

class InvalidPasskeyName :
    IllegalArgumentException("Passkey name must be 1-${PasskeyName.MAX_LENGTH} characters without control characters")

/**
 * One registered staff passkey. [publicKeyCose] is verification material that never leaves the
 * application; routes describe a passkey by [id], [name], [aaguid], its WebAuthn [credentialId]
 * (shown only to its owner, for the browser's passkey signals), backup flags and timestamps.
 */
class Passkey(
    val id: PasskeyId,
    val userId: UserId,
    internal val credentialId: ByteArray,
    internal val publicKeyCose: ByteArray,
    val signatureCount: Long,
    /** The authenticator model, which the admin maps to a provider name such as "iCloud Keychain". All zeros when unknown. */
    val aaguid: UUID,
    val transports: List<String>,
    /** Whether the passkey can be synced (a multi-device credential). */
    val backupEligible: Boolean,
    /** Whether it was synced (backed up) as of its latest use. */
    val backedUp: Boolean,
    val name: PasskeyName?,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)

/** A WebAuthn ceremony's purpose: adding a passkey (bound to one user) or signing in with one. */
enum class CeremonyKind {
    REGISTRATION,
    AUTHENTICATION,
}

@JvmInline
value class CeremonyId(
    val value: UUID,
)

/** One issued challenge. Consuming it is atomic and happens at most once, before it expires. */
class WebAuthnCeremony(
    val id: CeremonyId,
    val kind: CeremonyKind,
    internal val challenge: ByteArray,
    val userId: UserId?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    init {
        require((kind == CeremonyKind.REGISTRATION) == (userId != null)) { "Only a registration is bound to a user" }
        require(challenge.size == CHALLENGE_BYTES) { "A challenge has $CHALLENGE_BYTES bytes" }
    }

    companion object {
        const val CHALLENGE_BYTES = 32
    }
}
