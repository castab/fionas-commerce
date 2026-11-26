package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authenticatedPrincipal
import io.github.castab.commerce.runtime.http.errorResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.session.IssuedSession
import io.github.castab.commerce.runtime.session.SessionCookie
import io.github.castab.commerce.runtime.session.SessionManager
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.passkey.AssertionCredential
import io.github.castab.fionas.commerce.passkey.CeremonyId
import io.github.castab.fionas.commerce.passkey.InvalidPasskeyName
import io.github.castab.fionas.commerce.passkey.OwnPasskeys
import io.github.castab.fionas.commerce.passkey.Passkey
import io.github.castab.fionas.commerce.passkey.PasskeyAccount
import io.github.castab.fionas.commerce.passkey.PasskeyId
import io.github.castab.fionas.commerce.passkey.PasskeyName
import io.github.castab.fionas.commerce.passkey.PasskeySignInFailed
import io.github.castab.fionas.commerce.passkey.RegistrationCredential
import io.github.castab.fionas.commerce.passkey.RegistrationOptions
import io.github.castab.fionas.commerce.passkey.SignInOptions
import io.github.castab.fionas.commerce.passkey.StaffPasskeys
import io.github.castab.fionas.commerce.passkey.UnknownPasskey
import io.github.castab.fionas.commerce.staff.CurrentPasswordIncorrect
import io.github.castab.fionas.commerce.staff.MAX_PASSWORD_LENGTH
import io.github.castab.fionas.commerce.staff.MIN_PASSWORD_LENGTH
import io.github.castab.fionas.commerce.staff.SecretPassword
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.PreFlightExtraction
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.cookie.cookie
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.Invalid
import org.http4k.lens.LensFailure
import org.http4k.lens.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Where the browser fetches options for a passkey sign-in (including autofill). Public. */
const val PASSKEY_SIGN_IN_OPTIONS_PATH = "/auth/passkey/options"

/** Where the browser's passkey answer is verified and a staff session issued. Public. */
const val PASSKEY_SIGN_IN_PATH = "/auth/passkey"

internal const val PASSKEYS_DISABLED = "passkeys_disabled"
internal const val REAUTHENTICATION_REQUIRED = "reauthentication_required"
internal const val CURRENT_PASSWORD_INCORRECT = "current_password_incorrect"
internal const val STAFF_ACCOUNT_REQUIRED = "Account settings belong to an authenticated staff user"

// ---------------------------------------------------------------------------------------------
// WebAuthn JSON, as the browser's `PublicKeyCredential.parse*OptionsFromJSON()` and `toJSON()`
// define it (WebAuthn Level 3). Every binary value is base64url without padding.
// ---------------------------------------------------------------------------------------------

@Serializable
data class PasskeyRelyingPartyJson(
    val id: String,
    val name: String,
)

@Serializable
data class PasskeyUserJson(
    @ApiProperty(description = "The staff member's WebAuthn user handle: 64 random bytes, base64url. Never the account id.")
    val id: String,
    @ApiProperty(description = "The staff username, which passkey managers show.")
    val name: String,
    val displayName: String,
)

@Serializable
data class PasskeyAlgorithmJson(
    val type: String = PUBLIC_KEY,
    @ApiProperty(description = "COSE algorithm: -8 (Ed25519), -7 (ES256) or -257 (RS256), in that order of preference.")
    val alg: Int,
)

@Serializable
data class PasskeyDescriptorJson(
    val type: String = PUBLIC_KEY,
    @ApiProperty(description = "A WebAuthn credential id, base64url.")
    val id: String,
    val transports: List<String> = emptyList(),
)

@Serializable
data class PasskeyAuthenticatorSelectionJson(
    @ApiProperty(description = "Always `required`: only discoverable passkeys, which sign in without a username.")
    val residentKey: String = "required",
    val requireResidentKey: Boolean = true,
    @ApiProperty(description = "Always `required`: the authenticator verifies the person (biometric, PIN or screen lock).")
    val userVerification: String = "required",
)

@Serializable
data class PasskeyCreationExtensionsJson(
    @ApiProperty(description = "Asks the browser to report whether the passkey is discoverable (`credProps.rk`).")
    val credProps: Boolean = true,
)

@Serializable
data class PasskeyCreationOptionsJson(
    val rp: PasskeyRelyingPartyJson,
    val user: PasskeyUserJson,
    @ApiProperty(description = "32 random bytes, base64url; valid once, for five minutes.")
    val challenge: String,
    val pubKeyCredParams: List<PasskeyAlgorithmJson>,
    @ApiProperty(description = "Milliseconds.")
    val timeout: Long,
    @ApiProperty(description = "The staff member's existing passkeys, so an authenticator never registers twice.")
    val excludeCredentials: List<PasskeyDescriptorJson>,
    val authenticatorSelection: PasskeyAuthenticatorSelectionJson = PasskeyAuthenticatorSelectionJson(),
    @ApiProperty(description = "Always `none`: no attestation is requested, so no device information is collected.")
    val attestation: String = "none",
    val extensions: PasskeyCreationExtensionsJson = PasskeyCreationExtensionsJson(),
)

@Serializable
data class PasskeyRequestOptionsJson(
    @ApiProperty(description = "32 random bytes, base64url; valid once, for five minutes.")
    val challenge: String,
    @ApiProperty(description = "Milliseconds.")
    val timeout: Long,
    val rpId: String,
    @ApiProperty(description = "Always empty: any of the person's discoverable passkeys for this site may answer.")
    val allowCredentials: List<PasskeyDescriptorJson> = emptyList(),
    @ApiProperty(description = "Always `required`.")
    val userVerification: String = "required",
)

@Serializable
data class PasskeyRegistrationOptionsResponse(
    @ApiProperty(format = "uuid", description = "Send back with the created credential. Not a secret.")
    val ceremonyId: String,
    @ApiProperty(description = "For `PublicKeyCredential.parseCreationOptionsFromJSON()`.")
    val publicKey: PasskeyCreationOptionsJson,
)

@Serializable
data class PasskeySignInOptionsResponse(
    @ApiProperty(format = "uuid", description = "Send back with the passkey's answer. Not a secret.")
    val ceremonyId: String,
    @ApiProperty(description = "For `PublicKeyCredential.parseRequestOptionsFromJSON()`; also suits conditional (autofill) mediation.")
    val publicKey: PasskeyRequestOptionsJson,
)

@Serializable
data class PasskeyAttestationResponseJson(
    @SerialName("clientDataJSON")
    val clientDataJson: String,
    val attestationObject: String,
    val transports: List<String> = emptyList(),
)

@Serializable
data class PasskeyCredPropsJson(
    val rk: Boolean? = null,
)

@Serializable
data class PasskeyRegistrationExtensionResultsJson(
    val credProps: PasskeyCredPropsJson? = null,
)

@Serializable
data class PasskeyRegistrationCredentialJson(
    val id: String,
    val rawId: String,
    @ApiProperty(description = "Always `public-key`.")
    val type: String,
    val response: PasskeyAttestationResponseJson,
    val authenticatorAttachment: String? = null,
    val clientExtensionResults: PasskeyRegistrationExtensionResultsJson = PasskeyRegistrationExtensionResultsJson(),
)

@Serializable
data class PasskeyAssertionResponseJson(
    @SerialName("clientDataJSON")
    val clientDataJson: String,
    val authenticatorData: String,
    val signature: String,
    @ApiProperty(description = "The staff member's user handle; discoverable passkeys always return it.")
    val userHandle: String? = null,
)

@Serializable
data class PasskeyAssertionCredentialJson(
    val id: String,
    val rawId: String,
    @ApiProperty(description = "Always `public-key`.")
    val type: String,
    val response: PasskeyAssertionResponseJson,
    val authenticatorAttachment: String? = null,
)

@Serializable
data class PasskeySignInRequest(
    @ApiProperty(format = "uuid")
    val ceremonyId: String,
    @ApiProperty(description = "The browser's `PublicKeyCredential.toJSON()`, unchanged.")
    val credential: PasskeyAssertionCredentialJson,
)

@Serializable
data class RegisterPasskeyRequest(
    @ApiProperty(format = "uuid")
    val ceremonyId: String,
    @ApiProperty(description = "The browser's `PublicKeyCredential.toJSON()`, unchanged.")
    val credential: PasskeyRegistrationCredentialJson,
    @ApiProperty(description = "Optional name. Trimmed; blank means none.", maxLength = PasskeyName.MAX_LENGTH)
    val name: String? = null,
)

@Serializable
data class RenamePasskeyRequest(
    @ApiProperty(description = "The new name, trimmed; blank removes it.", maxLength = PasskeyName.MAX_LENGTH)
    val name: String,
)

@Serializable
data class PasskeyResponse(
    @ApiProperty(format = "uuid") val id: String,
    @ApiProperty(description = "The staff member's own name for it, if any.")
    val name: String? = null,
    @ApiProperty(description = "The WebAuthn credential id, base64url, for the browser's passkey signals. Not a secret.")
    val credentialId: String,
    @ApiProperty(
        format = "uuid",
        description = "The authenticator model, from which the admin derives a provider name; all zeros when not reported.",
    )
    val aaguid: String,
    @ApiProperty(description = "Whether the passkey can be synced across devices.")
    val backupEligible: Boolean,
    @ApiProperty(description = "Whether it was synced, as of its latest use.")
    val backedUp: Boolean,
    val transports: List<String>,
    @ApiProperty(format = "date-time") val createdAt: String,
    @ApiProperty(format = "date-time") val lastUsedAt: String? = null,
)

@Serializable
data class PasskeyListResponse(
    @ApiProperty(description = "The relying party id the passkeys belong to.")
    val rpId: String,
    @ApiProperty(description = "The caller's WebAuthn user handle, base64url; absent before their first passkey.")
    val userHandle: String? = null,
    val passkeys: List<PasskeyResponse>,
)

@Serializable
data class StaffAccountResponse(
    @ApiProperty(format = "date-time", description = "When this session's sign-in happened (sessions never extend).")
    val authenticatedAt: String,
    @ApiProperty(
        format = "date-time",
        description = "Until when adding or removing a passkey needs no new sign-in; afterwards they answer `reauthentication_required`.",
    )
    val recentAuthenticationUntil: String,
    @ApiProperty(description = "False when passkeys are disabled in this deployment.")
    val passkeysEnabled: Boolean,
    val passkeyCount: Int,
)

@Serializable
data class ChangeOwnPasswordRequest(
    val currentPassword: String,
    @ApiProperty(description = "$MIN_PASSWORD_LENGTH to $MAX_PASSWORD_LENGTH characters; must differ from the current one.")
    val newPassword: String,
)

private const val PUBLIC_KEY = "public-key"

private val accountTag = Tag("Staff account", "The authenticated staff member's own sign-in methods: passkeys and password.")
private val accountErrors = jsonBody(ErrorResponse.serializer())
private val signInOptionsBody = jsonBody(PasskeySignInOptionsResponse.serializer())
private val signInBody = jsonBody(PasskeySignInRequest.serializer())
private val registrationOptionsBody = jsonBody(PasskeyRegistrationOptionsResponse.serializer())
private val registerBody = jsonBody(RegisterPasskeyRequest.serializer())
private val renameBody = jsonBody(RenamePasskeyRequest.serializer())
private val passkeyBody = jsonBody(PasskeyResponse.serializer())
private val passkeyListBody = jsonBody(PasskeyListResponse.serializer())
private val accountBody = jsonBody(StaffAccountResponse.serializer())
private val changePasswordBody = jsonBody(ChangeOwnPasswordRequest.serializer())
private val passkeyIdPath = Path.of("passkeyId", "The passkey's id.", mapOf("schema" to mapOf("format" to "uuid")))
private val passkeysDisabled = ErrorResponse(PASSKEYS_DISABLED, "Passkeys are disabled")
private val reauthenticationRequired = ErrorResponse(REAUTHENTICATION_REQUIRED, "Sign in again to continue")
private val base64Url = Base64.getUrlEncoder().withoutPadding()

/**
 * What the staff account routes need. [passkeys] is null when passkeys are disabled in this
 * deployment; password and account reads still work.
 */
class StaffAccountOperations(
    val passkeys: StaffPasskeys?,
    /** The active staff user as a passkey manager will show them, or null when they are not an active user. */
    val account: (UserId) -> PasskeyAccount?,
    val changePassword: (UserId, SecretPassword, SecretPassword) -> IssuedSession,
) {
    companion object {
        /** For contract rendering and specs that never call account operations: passkeys disabled, nothing invocable. */
        val UNCONFIGURED =
            StaffAccountOperations(
                passkeys = null,
                account = { error("Staff account operations are not configured") },
                changePassword = { _, _, _ -> error("Staff account operations are not configured") },
            )
    }
}

/**
 * "Sudo mode": a request passes only when its staff session was signed in within [window].
 * A session's creation time is its authentication time (commerce-runtime sessions never
 * extend), read with `SessionManager.describe`. [clock] must be the clock the runtime's sessions
 * use. Otherwise the request is `403 reauthentication_required`, and the browser signs in again
 * (password or passkey) to obtain a fresh session.
 */
class RecentAuthentication(
    private val sessions: SessionManager,
    private val cookie: SessionCookie,
    private val clock: Clock = Clock.systemUTC(),
    val window: Duration = DEFAULT_WINDOW,
) {
    /** When the request's own staff session signed in, or null without one. */
    fun authenticatedAt(request: Request): Instant? =
        cookie
            .extract(request)
            ?.let(sessions::describe)
            ?.takeIf { it.principalId == authenticatedPrincipal(request) }
            ?.createdAt

    fun recentUntil(authenticatedAt: Instant): Instant = authenticatedAt.plus(window)

    val filter: Filter =
        Filter { next ->
            { request ->
                val at = authenticatedAt(request)
                if (at != null && clock.instant().isBefore(recentUntil(at))) {
                    next(request)
                } else {
                    Response(Status.FORBIDDEN).with(accountErrors of reauthenticationRequired)
                }
            }
        }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofMinutes(10)
    }
}

private val noStore = Filter { next -> { request -> next(request).header("Cache-Control", "no-store") } }

private fun enabled(passkeys: StaffPasskeys?) =
    Filter { next ->
        { request ->
            if (passkeys == null) Response(Status.SERVICE_UNAVAILABLE).with(accountErrors of passkeysDisabled) else next(request)
        }
    }

/** Account settings are personal: any authenticated staff USER manages only their own; a SERVICE is `403`. */
private val requireStaffAccount: Filter =
    Filter { next ->
        { request ->
            if (authenticatedPrincipal(request) is UserId) next(request) else errorResponse(ErrorCategory.FORBIDDEN, STAFF_ACCOUNT_REQUIRED)
        }
    }

private fun RouteMetaDsl.disabledPasskeys() =
    returning(
        Status.SERVICE_UNAVAILABLE,
        accountErrors to passkeysDisabled,
        "`$PASSKEYS_DISABLED`: passkeys are not enabled in this deployment.",
    )

private fun RouteMetaDsl.rateLimited(limit: LoginRateLimit) {
    description = (description ?: "") + " " + limit.described
    returningLoginRateLimit()
}

private fun RouteMetaDsl.accountMetadata(
    id: String,
    title: String,
    details: String,
    unsafe: Boolean = false,
    reauthenticate: Boolean = false,
    forbiddenCode: Pair<String, String>? = null,
) {
    operationId = id
    summary = title
    tags += accountTag
    description =
        "$details Requires an authenticated staff USER (any permissions; a SERVICE is 403). Passkeys are private to their " +
        "owner: another staff member's ids are indistinguishable from unknown ids. Cache-Control: no-store."
    principalAuthentication()
    security = staffSessionSecurity
    val otherForbidden = "the authenticated principal is a SERVICE, not a staff user" + if (unsafe) ", $UNTRUSTED_ORIGIN" else ""
    when {
        reauthenticate ->
            returning(
                Status.FORBIDDEN,
                accountErrors to reauthenticationRequired,
                "`$REAUTHENTICATION_REQUIRED`: the session signed in more than ten minutes ago; sign in again (password or " +
                    "passkey) and retry. The same status with code `forbidden` means $otherForbidden.",
            )
        forbiddenCode != null ->
            returning(
                Status.FORBIDDEN,
                accountErrors to ErrorResponse(forbiddenCode.first, forbiddenCode.second),
                "`${forbiddenCode.first}`: ${forbiddenCode.second.lowercase()}. The same status with code `forbidden` means $otherForbidden.",
            )
        else -> returningError(ErrorCategory.FORBIDDEN, "$otherForbidden.", STAFF_ACCOUNT_REQUIRED)
    }
    returningError(ErrorCategory.INTERNAL_FAILURE, "An unexpected failure; its cause is never described.", INTERNAL_FAILURE)
}

private val exampleCreationOptions =
    PasskeyCreationOptionsJson(
        PasskeyRelyingPartyJson("admin.fionasicecream.com", "Fiona's Admin"),
        PasskeyUserJson("kQ3y…64-random-bytes-base64url", "brayan", "Brayan"),
        "c2lnbi1tZS1vbmNlLXdpdGhpbi1maXZlLW1pbnV0ZXM",
        listOf(PasskeyAlgorithmJson(alg = -8), PasskeyAlgorithmJson(alg = -7), PasskeyAlgorithmJson(alg = -257)),
        300_000,
        emptyList(),
    )

private val examplePasskey =
    PasskeyResponse(
        "0b8c7f0e-49c4-4d2e-a0f3-6d3a2f5b1c90",
        "Work laptop",
        "AQIDBAUGBwgJCgsMDQ4PEA",
        "fbfc3007-154e-4ecc-8c0b-6e020557d7bd",
        backupEligible = true,
        backedUp = true,
        transports = listOf("hybrid", "internal"),
        createdAt = "2026-11-25T17:02:11.204Z",
        lastUsedAt = "2026-11-25T18:40:03.118Z",
    )

private val exampleRegistrationCredential =
    PasskeyRegistrationCredentialJson(
        "AQIDBAUGBwgJCgsMDQ4PEA",
        "AQIDBAUGBwgJCgsMDQ4PEA",
        PUBLIC_KEY,
        PasskeyAttestationResponseJson(
            "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIi…",
            "o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YV…",
            listOf("internal"),
        ),
        "platform",
        PasskeyRegistrationExtensionResultsJson(PasskeyCredPropsJson(true)),
    )

/**
 * The public passkey sign-in endpoints. They issue a staff session exactly like password login
 * ([issue] is the runtime's `sessions.create`), behind Fiona's Origin policy and their own
 * [rateLimit]. Any session cookie the browser already presented is revoked once the new one is
 * issued, so signing in again (for example to re-authenticate) never leaves the old session alive.
 */
internal fun passkeySignInRoutes(
    passkeys: StaffPasskeys?,
    issue: (UserId) -> IssuedSession?,
    sessions: SessionManager,
    cookie: SessionCookie,
    access: AccessControl,
    origin: Filter,
    rateLimit: LoginRateLimit,
): List<ContractRoute> =
    listOf(
        PASSKEY_SIGN_IN_OPTIONS_PATH meta {
            operationId = "startPasskeySignIn"
            summary = "Start a passkey sign-in"
            description =
                "Issues a one-time, five-minute challenge for `navigator.credentials.get()`, naming no account, so it also " +
                "suits passkey autofill (conditional mediation). Requires a trusted Origin. Cache-Control: no-store."
            rateLimited(rateLimit)
            tags += authTag
            returning(
                Status.OK,
                signInOptionsBody to
                    PasskeySignInOptionsResponse(
                        "3a7c1a52-8d0f-4c55-9a6e-2c1b7e9f0d14",
                        PasskeyRequestOptionsJson("c2lnbi1tZS1vbmNlLXdpdGhpbi1maXZlLW1pbnV0ZXM", 300_000, "admin.fionasicecream.com"),
                    ),
            )
            returningError(ErrorCategory.FORBIDDEN, "the browser origin is not trusted.", "The browser origin is not trusted")
            disabledPasskeys()
            returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
        } bindContract Method.POST to
            rateLimit.filter.then(origin).then(access.public()).then(noStore).then(enabled(passkeys)).then { _: Request ->
                Response(Status.OK).with(signInOptionsBody of checkNotNull(passkeys).startSignIn().response())
            },
        PASSKEY_SIGN_IN_PATH meta {
            operationId = "finishPasskeySignIn"
            summary = "Sign in with a passkey"
            description =
                "Verifies the passkey's answer to the ceremony's challenge (origin, relying party, user verification and " +
                "signature) and sets the same Secure, HttpOnly, host-only session cookie as password login. The ceremony is " +
                "spent either way. Requires a trusted Origin."
            rateLimited(rateLimit)
            tags += authTag
            preFlightExtraction = PreFlightExtraction.None
            receiving(
                signInBody to
                    PasskeySignInRequest(
                        "3a7c1a52-8d0f-4c55-9a6e-2c1b7e9f0d14",
                        PasskeyAssertionCredentialJson(
                            "AQIDBAUGBwgJCgsMDQ4PEA",
                            "AQIDBAUGBwgJCgsMDQ4PEA",
                            PUBLIC_KEY,
                            PasskeyAssertionResponseJson("eyJ0eXBlIjoid2ViYXV0aG4uZ2V0Ii…", "SZYN5YgOjGh0NBcPZHZgW4…", "MEUCIQ…", "kQ3y…"),
                            "platform",
                        ),
                    ),
            )
            returning(Status.NO_CONTENT to "The session cookie is set.")
            returningError(
                ErrorCategory.MALFORMED_REQUEST,
                "the body is not the documented JSON or a value is not base64url.",
                "Malformed request",
            )
            returningError(
                ErrorCategory.UNAUTHENTICATED,
                "the passkey did not verify, the ceremony is unknown, expired or spent, or the user is disabled.",
                "Passkey sign-in failed",
            )
            returningError(ErrorCategory.FORBIDDEN, "the browser origin is not trusted.", "The browser origin is not trusted")
            returningError(
                ErrorCategory.NOT_FOUND,
                "the passkey is not registered here (it was removed, or belongs elsewhere). The browser may signal its " +
                    "passkey manager to forget it.",
                "Passkey is not registered",
            )
            disabledPasskeys()
            returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure.", INTERNAL_FAILURE)
        } bindContract Method.POST to
            rateLimit.filter.then(origin).then(access.public()).then(noStore).then(enabled(passkeys)).then { request: Request ->
                val input = signInBody(request)
                val credential = input.credential.assertion()
                val userId =
                    try {
                        checkNotNull(passkeys).finishSignIn(CeremonyId(uuid(input.ceremonyId)), credential)
                    } catch (_: UnknownPasskey) {
                        return@then errorResponse(ErrorCategory.NOT_FOUND, "Passkey is not registered")
                    } catch (_: PasskeySignInFailed) {
                        return@then errorResponse(ErrorCategory.UNAUTHENTICATED, "Passkey sign-in failed")
                    }
                val issued = issue(userId) ?: return@then errorResponse(ErrorCategory.UNAUTHENTICATED, "Passkey sign-in failed")
                retirePresentedSession(request, cookie, sessions)
                Response(Status.NO_CONTENT).cookie(cookie.issue(issued))
            },
    )

/** Revokes the session cookie a sign-in request presented, after a new session was issued for it. */
internal fun retirePresentedSession(
    request: Request,
    cookie: SessionCookie,
    sessions: SessionManager,
) {
    cookie.extract(request)?.let(sessions::revoke)
}

/** The authenticated staff member's account: sign-in recency, passkeys, and password. */
internal fun staffAccountRoutes(
    operations: StaffAccountOperations,
    access: AccessControl,
    recent: RecentAuthentication,
    cookie: SessionCookie,
    passwordRateLimit: LoginRateLimit,
): List<ContractRoute> {
    val passkeys = operations.passkeys
    val staff = noStore.then(access.authenticated()).then(requireStaffAccount)
    return listOf(
        "/staff/account" meta {
            accountMetadata(
                "getStaffAccount",
                "Read my account's sign-in state",
                "When the current session signed in, until when sensitive changes need no new sign-in, and whether passkeys " +
                    "are enabled and how many the caller has.",
            )
            returning(
                Status.OK,
                accountBody to StaffAccountResponse("2026-11-25T17:00:00Z", "2026-11-25T17:10:00Z", true, 1),
            )
        } bindContract Method.GET to
            staff.then { request: Request ->
                val user = staffUser(request)
                val at = recent.authenticatedAt(request) ?: return@then errorResponse(ErrorCategory.FORBIDDEN, STAFF_ACCOUNT_REQUIRED)
                Response(Status.OK).with(
                    accountBody of
                        StaffAccountResponse(
                            at.toString(),
                            recent.recentUntil(at).toString(),
                            passkeys != null,
                            passkeys?.list(user)?.passkeys?.size ?: 0,
                        ),
                )
            },
        "/staff/passkeys" meta {
            accountMetadata("listStaffPasskeys", "List my passkeys", "The caller's passkeys, oldest first. Public keys are never returned.")
            returning(Status.OK, passkeyListBody to PasskeyListResponse("admin.fionasicecream.com", "kQ3y…", listOf(examplePasskey)))
            disabledPasskeys()
        } bindContract Method.GET to
            staff.then(enabled(passkeys)).then { request: Request ->
                Response(Status.OK).with(passkeyListBody of checkNotNull(passkeys).list(staffUser(request)).response(passkeys))
            },
        "/staff/passkeys/registration/options" meta {
            accountMetadata(
                "startPasskeyRegistration",
                "Start adding a passkey",
                "Issues a one-time, five-minute challenge for `navigator.credentials.create()`: discoverable credential and " +
                    "user verification required, no attestation, the caller's existing passkeys excluded. Needs a sign-in " +
                    "within the last ten minutes.",
                unsafe = true,
                reauthenticate = true,
            )
            returning(
                Status.OK,
                registrationOptionsBody to
                    PasskeyRegistrationOptionsResponse("3a7c1a52-8d0f-4c55-9a6e-2c1b7e9f0d14", exampleCreationOptions),
            )
            returningError(
                ErrorCategory.CONFLICT,
                "the caller already has ${StaffPasskeys.MAX_PASSKEYS} passkeys.",
                StaffPasskeys.TOO_MANY_PASSKEYS,
            )
            disabledPasskeys()
        } bindContract Method.POST to
            staff.then(enabled(passkeys)).then(recent.filter).then { request: Request ->
                val account =
                    operations.account(staffUser(request)) ?: return@then errorResponse(ErrorCategory.FORBIDDEN, STAFF_ACCOUNT_REQUIRED)
                Response(Status.OK).with(registrationOptionsBody of checkNotNull(passkeys).startRegistration(account).response())
            },
        "/staff/passkeys" meta {
            accountMetadata(
                "registerStaffPasskey",
                "Add a passkey",
                "Verifies the passkey the browser created for the caller's registration ceremony (origin, relying party, " +
                    "challenge, user verification) and stores it. The ceremony is spent either way.",
                unsafe = true,
            )
            preFlightExtraction = PreFlightExtraction.None
            receiving(
                registerBody to
                    RegisterPasskeyRequest(
                        "3a7c1a52-8d0f-4c55-9a6e-2c1b7e9f0d14",
                        exampleRegistrationCredential,
                        "Work laptop",
                    ),
            )
            returning(Status.CREATED, passkeyBody to examplePasskey, "The passkey is registered.")
            returningError(
                ErrorCategory.MALFORMED_REQUEST,
                "the body is not the documented JSON or a value is not base64url.",
                "Malformed request",
            )
            returningError(
                ErrorCategory.CONFLICT,
                "the caller already has ${StaffPasskeys.MAX_PASSKEYS} passkeys, or this passkey is already registered.",
                StaffPasskeys.ALREADY_REGISTERED,
            )
            returningError(
                ErrorCategory.VALIDATION_FAILED,
                "the ceremony is unknown, expired, spent or another user's; the passkey did not verify or is not discoverable; " +
                    "or the name is invalid.",
                StaffPasskeys.NOT_VERIFIED,
            )
            disabledPasskeys()
        } bindContract Method.POST to
            staff.then(enabled(passkeys)).then { request: Request ->
                val input = registerBody(request)
                val account =
                    operations.account(staffUser(request)) ?: return@then errorResponse(ErrorCategory.FORBIDDEN, STAFF_ACCOUNT_REQUIRED)
                val passkey =
                    checkNotNull(passkeys).finishRegistration(
                        account,
                        CeremonyId(uuid(input.ceremonyId)),
                        input.credential.registration(),
                        passkeyName(input.name),
                    )
                Response(Status.CREATED).with(passkeyBody of passkey.response())
            },
        "/staff/passkeys" / passkeyIdPath meta {
            accountMetadata(
                "renameStaffPasskey",
                "Rename a passkey",
                "Sets or clears the caller's own name for one of their passkeys.",
                unsafe = true,
            )
            preFlightExtraction = PreFlightExtraction.None
            receiving(renameBody to RenamePasskeyRequest("Work laptop"))
            returning(Status.OK, passkeyBody to examplePasskey, "The renamed passkey.")
            returningError(ErrorCategory.MALFORMED_REQUEST, "passkeyId is not a UUID, or the body is not JSON.", "Malformed request")
            returningError(ErrorCategory.NOT_FOUND, "No such passkey of the caller.", StaffPasskeys.PASSKEY_NOT_FOUND)
            returningError(ErrorCategory.VALIDATION_FAILED, "the name is too long or has control characters.", "Passkey name is invalid")
            disabledPasskeys()
        } bindContract Method.PATCH to { id: String ->
            staff.then(enabled(passkeys)).then { request: Request ->
                val passkey =
                    checkNotNull(
                        passkeys,
                    ).rename(staffUser(request), PasskeyId(uuid(id, passkeyIdPath)), passkeyName(renameBody(request).name))
                Response(Status.OK).with(passkeyBody of passkey.response())
            }
        },
        "/staff/passkeys" / passkeyIdPath meta {
            accountMetadata(
                "removeStaffPasskey",
                "Remove a passkey",
                "Deletes one of the caller's passkeys; it can never sign in again. Answers the caller's remaining passkeys, " +
                    "so the browser can tell its passkey manager which ones are still accepted. Needs a sign-in within the " +
                    "last ten minutes.",
                unsafe = true,
                reauthenticate = true,
            )
            returning(
                Status.OK,
                passkeyListBody to PasskeyListResponse("admin.fionasicecream.com", "kQ3y…", emptyList()),
                "The remaining passkeys.",
            )
            returningError(ErrorCategory.MALFORMED_REQUEST, "passkeyId is not a UUID.", "Malformed request")
            returningError(ErrorCategory.NOT_FOUND, "No such passkey of the caller.", StaffPasskeys.PASSKEY_NOT_FOUND)
            disabledPasskeys()
        } bindContract (Method.DELETE) to { id: String ->
            staff.then(enabled(passkeys)).then(recent.filter).then { request: Request ->
                val remaining = checkNotNull(passkeys).remove(staffUser(request), PasskeyId(uuid(id, passkeyIdPath)))
                Response(Status.OK).with(passkeyListBody of remaining.response(passkeys))
            }
        },
        "/staff/credentials/password" meta {
            accountMetadata(
                "changeOwnPassword",
                "Change my password",
                "Verifies the current password, stores the new one ($MIN_PASSWORD_LENGTH to $MAX_PASSWORD_LENGTH characters, " +
                    "different from the current one), signs out every session of the caller on every device, and sets a " +
                    "fresh session cookie for this browser.",
                unsafe = true,
                forbiddenCode = CURRENT_PASSWORD_INCORRECT to "The current password is incorrect",
            )
            rateLimited(passwordRateLimit)
            preFlightExtraction = PreFlightExtraction.None
            receiving(changePasswordBody to ChangeOwnPasswordRequest("current-password", "a-new-long-passphrase"))
            returning(Status.NO_CONTENT to "The password changed; the new session cookie is set and every other session ended.")
            returningError(ErrorCategory.MALFORMED_REQUEST, "the body is not the documented JSON.", "Malformed request")
            returningError(
                ErrorCategory.VALIDATION_FAILED,
                "the new password is too short, too long, or the same as the current one.",
                "Password must have at least $MIN_PASSWORD_LENGTH characters",
            )
        } bindContract Method.PUT to
            passwordRateLimit.filter.then(staff).then { request: Request ->
                val input = changePasswordBody(request)
                val issued =
                    try {
                        operations.changePassword(
                            staffUser(request),
                            SecretPassword.of(input.currentPassword),
                            SecretPassword.of(input.newPassword),
                        )
                    } catch (_: CurrentPasswordIncorrect) {
                        return@then Response(Status.FORBIDDEN).with(
                            accountErrors of ErrorResponse(CURRENT_PASSWORD_INCORRECT, "The current password is incorrect"),
                        )
                    }
                Response(Status.NO_CONTENT).cookie(cookie.issue(issued))
            },
    )
}

private fun passkeyName(text: String?): PasskeyName? =
    try {
        PasskeyName.of(text)
    } catch (_: InvalidPasskeyName) {
        throw CommerceFailure.ValidationFailed("Passkey name is invalid")
    }

private fun SignInOptions.response() =
    PasskeySignInOptionsResponse(
        ceremony.id.value.toString(),
        PasskeyRequestOptionsJson(base64Url.encodeToString(ceremony.challenge), timeout.toMillis(), rpId),
    )

private fun RegistrationOptions.response() =
    PasskeyRegistrationOptionsResponse(
        ceremony.id.value.toString(),
        PasskeyCreationOptionsJson(
            PasskeyRelyingPartyJson(rpId, rpName),
            PasskeyUserJson(base64Url.encodeToString(userHandle), username, displayName),
            base64Url.encodeToString(ceremony.challenge),
            listOf(-8, -7, -257).map { PasskeyAlgorithmJson(alg = it) },
            timeout.toMillis(),
            existing.map { PasskeyDescriptorJson(id = base64Url.encodeToString(it.credentialId), transports = it.transports) },
        ),
    )

private fun Passkey.response() =
    PasskeyResponse(
        id.value.toString(),
        name?.value,
        base64Url.encodeToString(credentialId),
        aaguid.toString(),
        backupEligible,
        backedUp,
        transports,
        createdAt.toString(),
        lastUsedAt?.toString(),
    )

private fun OwnPasskeys.response(passkeys: StaffPasskeys) =
    PasskeyListResponse(passkeys.rpId, userHandle?.let(base64Url::encodeToString), this.passkeys.map { it.response() })

private fun PasskeyRegistrationCredentialJson.registration(): RegistrationCredential {
    if (type != PUBLIC_KEY || id != rawId) throw malformed()
    return RegistrationCredential(
        bytes(rawId),
        bytes(response.clientDataJson),
        bytes(response.attestationObject),
        response.transports,
        clientExtensionResults.credProps?.rk,
    )
}

private fun PasskeyAssertionCredentialJson.assertion(): AssertionCredential {
    if (type != PUBLIC_KEY || id != rawId) throw malformed()
    return AssertionCredential(
        bytes(rawId),
        bytes(response.clientDataJson),
        bytes(response.authenticatorData),
        bytes(response.signature),
        response.userHandle?.let(::bytes),
    )
}

private fun bytes(value: String): ByteArray =
    try {
        Base64.getUrlDecoder().decode(value).also { if (it.isEmpty()) throw malformed() }
    } catch (_: IllegalArgumentException) {
        throw malformed()
    }

private fun malformed() = LensFailure(message = "Malformed request")

private fun uuid(value: String): UUID =
    try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw malformed()
    }

private fun uuid(
    segment: String,
    lens: org.http4k.lens.PathLens<String>,
): UUID =
    try {
        UUID.fromString(segment)
    } catch (failure: IllegalArgumentException) {
        throw LensFailure(Invalid(lens.meta), cause = failure)
    }
