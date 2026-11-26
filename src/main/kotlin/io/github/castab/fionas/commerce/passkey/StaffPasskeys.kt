package io.github.castab.fionas.commerce.passkey

import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.data.AttestationConveyancePreference
import com.yubico.webauthn.data.AuthenticatorAssertionResponse
import com.yubico.webauthn.data.AuthenticatorAttestationResponse
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.AuthenticatorTransport
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.PublicKeyCredentialParameters
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.persistence.Transactor
import io.github.castab.commerce.staff.UserId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID
import com.yubico.webauthn.data.ByteArray as WebAuthnBytes

private val logger = KotlinLogging.logger {}

/** The staff member a registration is for, as the passkey manager will show them. */
class PasskeyAccount(
    val userId: UserId,
    val username: String,
    val displayName: String,
)

/** What the browser needs for `navigator.credentials.create()`; rendered to JSON by the HTTP layer. */
class RegistrationOptions(
    val ceremony: WebAuthnCeremony,
    val rpId: String,
    val rpName: String,
    internal val userHandle: ByteArray,
    val username: String,
    val displayName: String,
    internal val existing: List<Passkey>,
    val timeout: Duration,
)

/** What the browser needs for `navigator.credentials.get()`, including conditional (autofill) mediation. */
class SignInOptions(
    val ceremony: WebAuthnCeremony,
    val rpId: String,
    val timeout: Duration,
)

/** The browser's `RegistrationResponseJSON`, decoded to bytes. */
class RegistrationCredential(
    val id: ByteArray,
    val clientDataJson: ByteArray,
    val attestationObject: ByteArray,
    val transports: List<String>,
    /** The `credProps.rk` client extension result, when the browser reported it. */
    val discoverable: Boolean?,
)

/** The browser's `AuthenticationResponseJSON`, decoded to bytes. */
class AssertionCredential(
    val id: ByteArray,
    val clientDataJson: ByteArray,
    val authenticatorData: ByteArray,
    val signature: ByteArray,
    val userHandle: ByteArray?,
)

/** A passkey sign-in that did not verify. The cause is logged, never returned. */
class PasskeySignInFailed(
    reason: String,
) : RuntimeException(reason)

/** The credential is not (or no longer) registered here; the browser may tell its passkey manager to forget it. */
class UnknownPasskey : RuntimeException("The passkey is not registered")

/**
 * Yubico marks the WebAuthn backup flags (BE/BS) `@Deprecated` only to label them experimental
 * API; they are standard WebAuthn Level 3 flags, read and stored here deliberately.
 *
 * Staff passkeys: registration for the signed-in staff member, discoverable sign-in, and
 * management of one's own passkeys. Verification is the Yubico library's; this class owns the
 * policy around it (user verification and discoverable credentials required, attestation not
 * requested, one-time five-minute challenges, at most [MAX_PASSKEYS] per user) and Fiona's
 * persistence. It never issues sessions: sign-in answers the verified user, and the caller asks
 * the runtime for a session exactly as password login does.
 */
@Suppress("DEPRECATION")
class StaffPasskeys(
    private val configuration: WebAuthnConfiguration,
    private val transactor: Transactor,
    private val passkeys: PasskeyRepository,
    private val ceremonies: WebAuthnCeremonyRepository,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    /** The relying party id this deployment's passkeys belong to. */
    val rpId: String get() = configuration.rpId

    /** Starts adding a passkey for [account]. Fails `Conflict` when the account already has [MAX_PASSKEYS]. */
    fun startRegistration(account: PasskeyAccount): RegistrationOptions =
        transactor.inTransaction { transaction ->
            val now = now()
            val handle = passkeys.ensureWebAuthnUserId(transaction, account.userId, randomBytes(USER_HANDLE_BYTES), now)
            val existing = passkeys.list(transaction, account.userId, lock = true)
            if (existing.size >= MAX_PASSKEYS) throw CommerceFailure.Conflict(TOO_MANY_PASSKEYS)
            val ceremony = newCeremony(CeremonyKind.REGISTRATION, account.userId, now)
            ceremonies.insert(transaction, ceremony, now)
            RegistrationOptions(
                ceremony,
                configuration.rpId,
                configuration.rpName,
                handle,
                account.username,
                account.displayName,
                existing,
                TIMEOUT,
            )
        }

    /**
     * Verifies and stores the passkey [credential] created for [account]'s registration
     * [ceremonyId]. The ceremony is spent whether or not verification succeeds.
     */
    fun finishRegistration(
        account: PasskeyAccount,
        ceremonyId: CeremonyId,
        credential: RegistrationCredential,
        name: PasskeyName?,
    ): Passkey {
        val ceremony =
            transactor
                .inTransaction { ceremonies.consume(it, ceremonyId, CeremonyKind.REGISTRATION, now()) }
                ?.takeIf { it.userId == account.userId }
                ?: throw CommerceFailure.ValidationFailed(CEREMONY_EXPIRED)
        if (credential.discoverable == false) throw CommerceFailure.ValidationFailed(NOT_DISCOVERABLE)
        return transactor.inTransaction { transaction ->
            val handle = checkNotNull(passkeys.webAuthnUserId(transaction, account.userId)) { "Registration started without a user handle" }
            val existing = passkeys.list(transaction, account.userId, lock = true)
            if (existing.size >= MAX_PASSKEYS) throw CommerceFailure.Conflict(TOO_MANY_PASSKEYS)
            val request = creationOptions(ceremony.challenge, handle, account.username, account.displayName, existing)
            val result =
                try {
                    val response =
                        AuthenticatorAttestationResponse
                            .builder()
                            .attestationObject(WebAuthnBytes(credential.attestationObject))
                            .clientDataJSON(WebAuthnBytes(credential.clientDataJson))
                            .transports(transports(credential.transports))
                            .build()
                    val publicKeyCredential =
                        PublicKeyCredential
                            .builder<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs>()
                            .id(WebAuthnBytes(credential.id))
                            .response(response)
                            .clientExtensionResults(ClientRegistrationExtensionOutputs.builder().build())
                            .build()
                    relyingParty(transaction).finishRegistration(
                        FinishRegistrationOptions
                            .builder()
                            .request(request)
                            .response(publicKeyCredential)
                            .build(),
                    )
                } catch (failure: RegistrationFailedException) {
                    logger.info { "event=passkey_registration_rejected user=${account.userId.value} reason=${failure.message}" }
                    throw CommerceFailure.ValidationFailed(NOT_VERIFIED)
                } catch (failure: Exception) {
                    // Undecodable attestation objects or client data.
                    logger.info {
                        "event=passkey_registration_rejected user=${account.userId.value} reason=${failure.javaClass.simpleName}"
                    }
                    throw CommerceFailure.ValidationFailed(NOT_VERIFIED)
                }
            if (!result.isUserVerified) throw CommerceFailure.ValidationFailed(NOT_VERIFIED)
            val credentialId = result.keyId.id.bytes
            if (passkeys.byCredentialId(transaction, credentialId) != null) throw CommerceFailure.Conflict(ALREADY_REGISTERED)
            val passkey =
                Passkey(
                    id = PasskeyId(UUID.randomUUID()),
                    userId = account.userId,
                    credentialId = credentialId,
                    publicKeyCose = result.publicKeyCose.bytes,
                    signatureCount = result.signatureCount,
                    aaguid = uuid(result.aaguid.bytes),
                    transports =
                        credential.transports
                            .filter { it in TRANSPORTS }
                            .distinct()
                            .sorted(),
                    backupEligible = result.isBackupEligible,
                    backedUp = result.isBackedUp,
                    name = name,
                    createdAt = now(),
                    lastUsedAt = null,
                )
            passkeys.insert(transaction, passkey)
            logger.info { "event=passkey_registered user=${account.userId.value} passkey=${passkey.id.value} synced=${passkey.backedUp}" }
            passkey
        }
    }

    /** Starts a sign-in with any discoverable passkey: no account is named, so nothing about accounts is revealed. */
    fun startSignIn(): SignInOptions =
        transactor.inTransaction { transaction ->
            val now = now()
            val ceremony = newCeremony(CeremonyKind.AUTHENTICATION, null, now)
            ceremonies.insert(transaction, ceremony, now)
            SignInOptions(ceremony, configuration.rpId, TIMEOUT)
        }

    /**
     * The staff user [credential] proves, for sign-in ceremony [ceremonyId]. The ceremony is
     * spent whether or not verification succeeds. Throws [UnknownPasskey] for a credential that
     * is not registered here and [PasskeySignInFailed] for every other failure.
     */
    fun finishSignIn(
        ceremonyId: CeremonyId,
        credential: AssertionCredential,
    ): UserId {
        val ceremony =
            transactor.inTransaction { ceremonies.consume(it, ceremonyId, CeremonyKind.AUTHENTICATION, now()) }
                ?: throw PasskeySignInFailed("ceremony_unavailable")
        return transactor.inTransaction { transaction ->
            val registered = passkeys.byCredentialId(transaction, credential.id) ?: throw UnknownPasskey()
            val result =
                try {
                    val response =
                        AuthenticatorAssertionResponse
                            .builder()
                            .authenticatorData(WebAuthnBytes(credential.authenticatorData))
                            .clientDataJSON(WebAuthnBytes(credential.clientDataJson))
                            .signature(WebAuthnBytes(credential.signature))
                            .userHandle(Optional.ofNullable(credential.userHandle?.let(::WebAuthnBytes)))
                            .build()
                    val publicKeyCredential =
                        PublicKeyCredential
                            .builder<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs>()
                            .id(WebAuthnBytes(credential.id))
                            .response(response)
                            .clientExtensionResults(ClientAssertionExtensionOutputs.builder().build())
                            .build()
                    relyingParty(transaction).finishAssertion(
                        FinishAssertionOptions
                            .builder()
                            .request(
                                AssertionRequest
                                    .builder()
                                    .publicKeyCredentialRequestOptions(requestOptions(ceremony.challenge))
                                    .build(),
                            ).response(publicKeyCredential)
                            .build(),
                    )
                } catch (failure: AssertionFailedException) {
                    logger.info { "event=passkey_sign_in_rejected passkey=${registered.id.value} reason=${failure.message}" }
                    throw PasskeySignInFailed("not_verified")
                } catch (failure: Exception) {
                    logger.info { "event=passkey_sign_in_rejected passkey=${registered.id.value} reason=${failure.javaClass.simpleName}" }
                    throw PasskeySignInFailed("malformed")
                }
            if (!result.isSuccess ||
                !result.isUserVerified ||
                !result.credential.credentialId.bytes
                    .contentEquals(registered.credentialId)
            ) {
                throw PasskeySignInFailed("not_verified")
            }
            passkeys.recordUse(transaction, registered.id, result.signatureCount, result.isBackedUp, now())
            logger.info { "event=passkey_authenticated user=${registered.userId.value} passkey=${registered.id.value}" }
            registered.userId
        }
    }

    /** [userId]'s own passkeys, oldest first, with the WebAuthn user handle the browser's passkey signals need. */
    fun list(userId: UserId): OwnPasskeys =
        transactor.inTransaction { transaction ->
            OwnPasskeys(passkeys.webAuthnUserId(transaction, userId), passkeys.list(transaction, userId))
        }

    fun rename(
        userId: UserId,
        id: PasskeyId,
        name: PasskeyName?,
    ): Passkey =
        transactor.inTransaction { transaction ->
            passkeys.find(transaction, userId, id) ?: throw CommerceFailure.NotFound(PASSKEY_NOT_FOUND)
            passkeys.rename(transaction, id, name)
            checkNotNull(passkeys.find(transaction, userId, id))
        }

    /** Removes [userId]'s passkey [id] immediately; it can never sign in again. Another user's id is `NotFound`. */
    fun remove(
        userId: UserId,
        id: PasskeyId,
    ): OwnPasskeys =
        transactor.inTransaction { transaction ->
            passkeys.find(transaction, userId, id) ?: throw CommerceFailure.NotFound(PASSKEY_NOT_FOUND)
            passkeys.delete(transaction, id)
            logger.info { "event=passkey_removed user=${userId.value} passkey=${id.value}" }
            OwnPasskeys(passkeys.webAuthnUserId(transaction, userId), passkeys.list(transaction, userId))
        }

    private fun relyingParty(transaction: Transaction): RelyingParty =
        RelyingParty
            .builder()
            .identity(identity())
            .credentialRepository(TransactionCredentials(transaction, passkeys))
            .origins(configuration.origins)
            .allowOriginPort(false)
            .allowOriginSubdomain(false)
            .attestationConveyancePreference(AttestationConveyancePreference.NONE)
            .preferredPubkeyParams(ALGORITHMS)
            .validateSignatureCounter(true)
            .build()

    private fun identity() =
        RelyingPartyIdentity
            .builder()
            .id(configuration.rpId)
            .name(configuration.rpName)
            .build()

    /** The exact creation options a registration was started with; [RegistrationOptions] renders the same values. */
    private fun creationOptions(
        challenge: ByteArray,
        handle: ByteArray,
        username: String,
        displayName: String,
        existing: List<Passkey>,
    ): PublicKeyCredentialCreationOptions =
        PublicKeyCredentialCreationOptions
            .builder()
            .rp(identity())
            .user(
                UserIdentity
                    .builder()
                    .name(username)
                    .displayName(displayName)
                    .id(WebAuthnBytes(handle))
                    .build(),
            ).challenge(WebAuthnBytes(challenge))
            .pubKeyCredParams(ALGORITHMS)
            .authenticatorSelection(
                AuthenticatorSelectionCriteria
                    .builder()
                    .residentKey(ResidentKeyRequirement.REQUIRED)
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .build(),
            ).excludeCredentials(existing.map(::descriptor).toSet())
            .attestation(AttestationConveyancePreference.NONE)
            .timeout(TIMEOUT.toMillis())
            .build()

    private fun requestOptions(challenge: ByteArray): PublicKeyCredentialRequestOptions =
        PublicKeyCredentialRequestOptions
            .builder()
            .challenge(WebAuthnBytes(challenge))
            .rpId(configuration.rpId)
            .userVerification(UserVerificationRequirement.REQUIRED)
            .timeout(TIMEOUT.toMillis())
            .build()

    private fun newCeremony(
        kind: CeremonyKind,
        userId: UserId?,
        now: java.time.Instant,
    ) = WebAuthnCeremony(CeremonyId(UUID.randomUUID()), kind, randomBytes(WebAuthnCeremony.CHALLENGE_BYTES), userId, now, now.plus(TIMEOUT))

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val MAX_PASSKEYS = 20
        val TIMEOUT: Duration = Duration.ofMinutes(5)
        private const val USER_HANDLE_BYTES = 64

        /** Ed25519, then ES256 (P-256), then RS256: every algorithm passkey providers use. */
        internal val ALGORITHMS =
            listOf(PublicKeyCredentialParameters.EdDSA, PublicKeyCredentialParameters.ES256, PublicKeyCredentialParameters.RS256)
        internal val TRANSPORTS = setOf("usb", "nfc", "ble", "smart-card", "hybrid", "internal")

        const val TOO_MANY_PASSKEYS = "A staff account can have at most $MAX_PASSKEYS passkeys"
        const val CEREMONY_EXPIRED = "The passkey request expired; start again"
        const val NOT_DISCOVERABLE = "This passkey can't be used to sign in without a username; try another passkey provider"
        const val NOT_VERIFIED = "The passkey could not be verified"
        const val ALREADY_REGISTERED = "This passkey is already registered"
        const val PASSKEY_NOT_FOUND = "Passkey was not found"

        private fun descriptor(passkey: Passkey) =
            PublicKeyCredentialDescriptor
                .builder()
                .id(WebAuthnBytes(passkey.credentialId))
                .transports(transports(passkey.transports))
                .build()

        private fun transports(values: List<String>): Set<AuthenticatorTransport> =
            values.filter { it in TRANSPORTS }.map(AuthenticatorTransport::of).toSet()

        private fun uuid(bytes: ByteArray): UUID =
            if (bytes.size == 16) {
                ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
            } else {
                UUID(0, 0)
            }
    }
}

/** One staff member's passkeys and their WebAuthn user handle (null before their first registration). */
class OwnPasskeys(
    internal val userHandle: ByteArray?,
    val passkeys: List<Passkey>,
)

/**
 * The Yubico library's view of Fiona's passkeys, bound to one runtime transaction. Its
 * "username" is the staff user's id, never the login username, which can change.
 */
@Suppress("DEPRECATION") // Experimental backup flags; see StaffPasskeys.
private class TransactionCredentials(
    private val transaction: Transaction,
    private val passkeys: PasskeyRepository,
) : CredentialRepository {
    override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
        userId(username)
            ?.let { passkeys.list(transaction, it) }
            .orEmpty()
            .map { PublicKeyCredentialDescriptor.builder().id(WebAuthnBytes(it.credentialId)).build() }
            .toSet()

    override fun getUserHandleForUsername(username: String): Optional<WebAuthnBytes> =
        Optional.ofNullable(userId(username)?.let { passkeys.webAuthnUserId(transaction, it) }?.let(::WebAuthnBytes))

    override fun getUsernameForUserHandle(userHandle: WebAuthnBytes): Optional<String> =
        Optional.ofNullable(passkeys.userForWebAuthnUserId(transaction, userHandle.bytes)?.value?.toString())

    override fun lookup(
        credentialId: WebAuthnBytes,
        userHandle: WebAuthnBytes,
    ): Optional<RegisteredCredential> {
        val passkey = passkeys.byCredentialId(transaction, credentialId.bytes) ?: return Optional.empty()
        val ownerHandle = passkeys.webAuthnUserId(transaction, passkey.userId) ?: return Optional.empty()
        if (!ownerHandle.contentEquals(userHandle.bytes)) return Optional.empty()
        return Optional.of(registered(passkey, ownerHandle))
    }

    override fun lookupAll(credentialId: WebAuthnBytes): Set<RegisteredCredential> {
        val passkey = passkeys.byCredentialId(transaction, credentialId.bytes) ?: return emptySet()
        val ownerHandle = passkeys.webAuthnUserId(transaction, passkey.userId) ?: return emptySet()
        return setOf(registered(passkey, ownerHandle))
    }

    private fun registered(
        passkey: Passkey,
        ownerHandle: ByteArray,
    ) = RegisteredCredential
        .builder()
        .credentialId(WebAuthnBytes(passkey.credentialId))
        .userHandle(WebAuthnBytes(ownerHandle))
        .publicKeyCose(WebAuthnBytes(passkey.publicKeyCose))
        .signatureCount(passkey.signatureCount)
        .backupEligible(passkey.backupEligible)
        .backupState(passkey.backedUp)
        .build()

    private fun userId(username: String): UserId? = runCatching { UserId(UUID.fromString(username)) }.getOrNull()
}
