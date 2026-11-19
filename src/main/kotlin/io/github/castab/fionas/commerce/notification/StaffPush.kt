package io.github.castab.fionas.commerce.notification

import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

@JvmInline
value class StaffPushSubscriptionId(
    val value: UUID,
)

@JvmInline
value class StaffNotificationId(
    val value: UUID,
)

@JvmInline
value class StaffDeliveryId(
    val value: UUID,
)

/** The push service that issued an endpoint: safe device metadata, derived from the host only. */
enum class PushService { FCM, MOZILLA, WINDOWS, APPLE, OTHER }

/**
 * Which push services Fiona will call. Entries are exact hosts (`fcm.googleapis.com`) or suffix
 * wildcards (`*.notify.windows.com`, matching subdomains only). Requests never follow redirects,
 * so the allow-list is the complete set of hosts a send can reach.
 */
class PushHostAllowList(
    entries: Collection<String>,
) {
    private val exact: Set<String>
    private val suffixes: Set<String>

    init {
        require(entries.isNotEmpty()) { "The push host allow-list must not be empty" }
        val normalized = entries.map { it.trim().lowercase() }
        normalized.forEach {
            require(HOST_ENTRY.matches(it)) { "Push host allow-list entries must be DNS hosts or *.suffix wildcards" }
        }
        exact = normalized.filterNot { it.startsWith("*.") }.toSet()
        suffixes = normalized.filter { it.startsWith("*.") }.map { it.removePrefix("*") }.toSet()
    }

    fun allows(host: String): Boolean {
        val candidate = host.lowercase()
        return candidate in exact || suffixes.any { candidate.endsWith(it) && candidate.length > it.length }
    }

    companion object {
        private val HOST_ENTRY = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

        /** The browser push services Fiona's staff browsers use (Chrome/Edge via FCM and WNS, Firefox, Safari). */
        val DEFAULT =
            PushHostAllowList(
                listOf(
                    "fcm.googleapis.com",
                    "*.push.services.mozilla.com",
                    "updates.push.services.mozilla.com",
                    "*.notify.windows.com",
                    "web.push.apple.com",
                    "*.push.apple.com",
                ),
            )
    }
}

/**
 * A browser push endpoint: a bearer capability URL issued by a public push service. It is
 * validated, compared only by [sha256], and never rendered: [toString] is redacted.
 */
class PushEndpoint private constructor(
    val value: String,
    val host: String,
) {
    val sha256: String = sha256Hex(value)

    val service: PushService
        get() =
            when {
                host == "fcm.googleapis.com" -> PushService.FCM
                host.endsWith("push.services.mozilla.com") -> PushService.MOZILLA
                host.endsWith(".notify.windows.com") -> PushService.WINDOWS
                host.endsWith("push.apple.com") -> PushService.APPLE
                else -> PushService.OTHER
            }

    override fun equals(other: Any?) = other is PushEndpoint && other.value == value

    override fun hashCode() = value.hashCode()

    override fun toString() = "PushEndpoint([redacted])"

    companion object {
        const val MAX_LENGTH = 2048

        /** Validates the URL shape and that its host is an allowed public push service. */
        fun of(
            text: String,
            allowed: PushHostAllowList,
        ): PushEndpoint {
            require(text.length in 1..MAX_LENGTH && text.none(Char::isWhitespace)) { "Push endpoint is invalid" }
            val uri =
                try {
                    URI(text)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Push endpoint is invalid")
                }
            val host = uri.host?.lowercase()
            require(
                uri.scheme == "https" &&
                    host != null &&
                    uri.rawUserInfo == null &&
                    uri.rawFragment == null &&
                    (uri.port == -1 || uri.port == 443) &&
                    !uri.rawPath.isNullOrEmpty(),
            ) { "Push endpoint must be an https URL without credentials, fragment or a non-default port" }
            require(allowed.allows(host)) { "Push endpoint is not a supported push service" }
            return PushEndpoint(text, host)
        }

        internal fun restore(text: String): PushEndpoint = PushEndpoint(text, checkNotNull(URI(text).host).lowercase())
    }
}

/** The subscription's message-encryption keys (RFC 8291), as unpadded base64url text. Redacted. */
class PushKeys private constructor(
    val p256dh: String,
    val auth: String,
) {
    override fun equals(other: Any?) = other is PushKeys && other.p256dh == p256dh && other.auth == auth

    override fun hashCode() = 31 * p256dh.hashCode() + auth.hashCode()

    override fun toString() = "PushKeys([redacted])"

    companion object {
        fun of(
            p256dh: String,
            auth: String,
        ): PushKeys {
            val key = p256dh.trimEnd('=')
            val secret = auth.trimEnd('=')
            val keyBytes = decode(key)
            require(keyBytes != null && keyBytes.size == 65 && keyBytes[0] == 4.toByte()) {
                "keys.p256dh must be an uncompressed P-256 public key in base64url"
            }
            require(decode(secret)?.size == 16) { "keys.auth must be a 16-byte secret in base64url" }
            return PushKeys(key, secret)
        }

        internal fun restore(
            p256dh: String,
            auth: String,
        ) = PushKeys(p256dh, auth)

        private fun decode(text: String): ByteArray? =
            if (!Regex("^[A-Za-z0-9_-]+$").matches(text)) {
                null
            } else {
                try {
                    Base64.getUrlDecoder().decode(text)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
    }
}

/** A staff member's own name for a device ("Front desk iPad"). */
@JvmInline
value class DeviceLabel(
    val value: String,
) {
    init {
        require(value == value.trim() && value.length in 1..MAX_LENGTH && value.none(Char::isISOControl)) {
            "Device label must be 1–$MAX_LENGTH characters without control characters"
        }
    }

    companion object {
        const val MAX_LENGTH = 64

        fun ofOptional(text: String?): DeviceLabel? = text?.trim()?.takeIf(String::isNotEmpty)?.let(::DeviceLabel)
    }
}

/** The staff caller's opaque identity for one test send; same shape as other Fiona idempotency keys. */
@JvmInline
value class TestNotificationKey(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH && FORMAT.matches(value)) { "Invalid test notification key" }
    }

    companion object {
        const val MAX_LENGTH = 128
        const val PATTERN = "^[A-Za-z0-9_-]{1,128}$"
        private val FORMAT = Regex(PATTERN)
    }
}

enum class StaffNotificationKind(
    val wire: String,
) {
    TEST("test"),
    NEW_INQUIRY("new_inquiry"),
}

enum class DeliveryState(
    val wire: String,
    val terminal: Boolean,
) {
    QUEUED("queued", false),
    RETRYING("retrying", false),
    PUSH_ACCEPTED("push_accepted", true),
    PUSH_FAILED("push_failed", true),
    SKIPPED("skipped", true),
}

/** Machine-readable delivery reasons; stable codes clients may match on. */
enum class DeliveryReason(
    val code: String,
) {
    SUBSCRIPTION_EXPIRED("subscription_expired"),
    SUBSCRIPTION_REVOKED("subscription_revoked"),
    RECIPIENT_NOT_ELIGIBLE("recipient_not_eligible"),
    PROVIDER_REJECTED("provider_rejected"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    PROVIDER_THROTTLED("provider_throttled"),
    TRANSPORT_FAILURE("transport_failure"),
    ENDPOINT_NOT_ALLOWED("endpoint_not_allowed"),
    PROVIDER_OUTCOME_UNKNOWN("provider_outcome_unknown"),

    /** The delivery window elapsed before any provider answer (for example while no dispatcher ran). */
    DELIVERY_EXPIRED("delivery_expired"),
    ;

    companion object {
        fun of(code: String): DeliveryReason = entries.single { it.code == code }
    }
}

enum class SubscriptionRetirement { REVOKED, PROVIDER_EXPIRED }

/** One device registration. [endpoint] and [keys] are null once retired; they are cleared, never kept. */
data class StaffPushSubscription(
    val id: StaffPushSubscriptionId,
    val owner: UserId,
    val endpoint: PushEndpoint?,
    val endpointSha256: String,
    val service: PushService,
    val keys: PushKeys?,
    val expirationTime: Instant?,
    val label: DeviceLabel?,
    val registrationVersion: Long,
    val enrolledAt: Instant,
    val refreshedAt: Instant,
    val retiredAt: Instant?,
    val retirement: SubscriptionRetirement?,
) {
    val active: Boolean get() = retiredAt == null
}

data class StaffNotification(
    val id: StaffNotificationId,
    val kind: StaffNotificationKind,
    val requestedBy: UserId?,
    val key: TestNotificationKey?,
    val target: StaffPushSubscriptionId?,
    val inquiryId: InquiryId?,
    val createdAt: Instant,
    val giveUpAt: Instant,
    val fannedOutAt: Instant?,
)

data class StaffDelivery(
    val id: StaffDeliveryId,
    val notificationId: StaffNotificationId,
    val subscriptionId: StaffPushSubscriptionId,
    val state: DeliveryState,
    val reason: DeliveryReason?,
    val attempts: Int,
    val everUncertain: Boolean,
    val providerCode: Int?,
    val attemptToken: UUID?,
    val attemptStartedAt: Instant?,
    val notBefore: Instant?,
    val giveUpAt: Instant,
    val createdAt: Instant,
    val lastAttemptAt: Instant?,
    val completedAt: Instant?,
)

internal fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
