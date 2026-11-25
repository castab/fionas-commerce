package io.github.castab.fionas.commerce.notification

import java.math.BigInteger
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId
import java.util.Base64

/**
 * The scheduled unanswered-inquiry reminder: when it runs ([schedule], a cron expression in its own
 * time zone) and where a click on it leads in the admin ([path], relative).
 */
class UnansweredReminderSettings(
    val schedule: CronSchedule,
    val path: String = DEFAULT_PATH,
) {
    init {
        require(PATH.matches(path)) { "FIONAS_PUSH_UNANSWERED_REMINDER_PATH must be a relative path starting with /" }
    }

    companion object {
        const val DEFAULT_SCHEDULE = "0 10,14 * * MON-FRI"
        val DEFAULT_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
        const val DEFAULT_PATH = "/"
        private val PATH = Regex("^/([A-Za-z0-9_.-][A-Za-z0-9/_.-]{0,199})?$")

        val DEFAULT = UnansweredReminderSettings(CronSchedule.parse(DEFAULT_SCHEDULE, DEFAULT_ZONE))
    }
}

/**
 * Staff Web Push settings. Null means the feature is disabled: no routes enroll or send, no
 * dispatcher runs and no provider is ever called. [unansweredReminders] separately switches the
 * scheduled unanswered-inquiry reminder (null is off), so test sends can be verified first.
 * Secrets never render.
 */
class WebPushConfiguration(
    internal val vapidPrivateKey: ECPrivateKey,
    internal val vapidPublicKey: ECPublicKey,
    /** The VAPID `sub` claim: a `mailto:` or `https:` contact for push services. */
    val subject: String,
    val allowedHosts: PushHostAllowList = PushHostAllowList.DEFAULT,
    val sendTimeout: Duration = Duration.ofMillis(DEFAULT_SEND_TIMEOUT_MS),
    val unansweredReminders: UnansweredReminderSettings? = null,
) {
    init {
        require(subject.startsWith("mailto:") && subject.length > 7 || isHttpsUrl(subject)) {
            "FIONAS_PUSH_VAPID_SUBJECT must be a mailto: or https: contact"
        }
        require(!sendTimeout.isNegative && sendTimeout.toMillis() in 1_000..10_000) {
            "FIONAS_PUSH_SEND_TIMEOUT_MS must be between 1000 and 10000"
        }
        require(pairMatches(vapidPrivateKey, vapidPublicKey)) { "The VAPID private and public keys are not a pair" }
    }

    /** The browser's `applicationServerKey`: the uncompressed public key, base64url without padding. */
    val applicationServerKey: String = base64Url(uncompressed(vapidPublicKey))

    override fun toString() = "WebPushConfiguration([redacted])"

    companion object {
        const val DEFAULT_SEND_TIMEOUT_MS = 8_000L

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): WebPushConfiguration? {
            // The per-inquiry trigger was replaced by the scheduled reminder; never ignore a request for it.
            require(!flag(environment, "FIONAS_PUSH_INQUIRY_NOTIFICATIONS_ENABLED")) {
                "FIONAS_PUSH_INQUIRY_NOTIFICATIONS_ENABLED was removed; use FIONAS_PUSH_UNANSWERED_REMINDERS_ENABLED"
            }
            val enabled = flag(environment, "FIONAS_PUSH_ENABLED")
            val reminders = flag(environment, "FIONAS_PUSH_UNANSWERED_REMINDERS_ENABLED")
            if (!enabled) {
                require(!reminders) { "FIONAS_PUSH_UNANSWERED_REMINDERS_ENABLED requires FIONAS_PUSH_ENABLED=true" }
                return null
            }
            val private = secret(environment, "FIONAS_PUSH_VAPID_PRIVATE_KEY")
            val public = secret(environment, "FIONAS_PUSH_VAPID_PUBLIC_KEY")
            val subject = environment["FIONAS_PUSH_VAPID_SUBJECT"]?.trim()
            require(!subject.isNullOrEmpty()) { "FIONAS_PUSH_VAPID_SUBJECT is required when FIONAS_PUSH_ENABLED=true" }
            val hosts =
                environment["FIONAS_PUSH_ALLOWED_HOSTS"]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotEmpty)
                    ?.let(::PushHostAllowList)
                    ?: PushHostAllowList.DEFAULT
            val timeout =
                environment["FIONAS_PUSH_SEND_TIMEOUT_MS"]?.let {
                    requireNotNull(it.toLongOrNull()) { "FIONAS_PUSH_SEND_TIMEOUT_MS must be an integer" }
                } ?: DEFAULT_SEND_TIMEOUT_MS
            return WebPushConfiguration(
                vapidPrivateKey(private),
                vapidPublicKey(public),
                subject,
                hosts,
                Duration.ofMillis(timeout),
                if (reminders) reminderSettings(environment) else null,
            )
        }

        private fun reminderSettings(environment: Map<String, String>): UnansweredReminderSettings {
            val zone =
                environment["FIONAS_PUSH_UNANSWERED_REMINDER_TIME_ZONE"]?.let {
                    try {
                        ZoneId.of(it.trim())
                    } catch (_: DateTimeException) {
                        throw IllegalArgumentException(
                            "FIONAS_PUSH_UNANSWERED_REMINDER_TIME_ZONE must be a time zone such as America/Los_Angeles",
                        )
                    }
                } ?: UnansweredReminderSettings.DEFAULT_ZONE
            val expression = environment["FIONAS_PUSH_UNANSWERED_REMINDER_SCHEDULE"] ?: UnansweredReminderSettings.DEFAULT_SCHEDULE
            val schedule =
                try {
                    CronSchedule.parse(expression, zone)
                } catch (failure: IllegalArgumentException) {
                    throw IllegalArgumentException("FIONAS_PUSH_UNANSWERED_REMINDER_SCHEDULE: ${failure.message}")
                }
            return UnansweredReminderSettings(
                schedule,
                environment["FIONAS_PUSH_UNANSWERED_REMINDER_PATH"]?.trim() ?: UnansweredReminderSettings.DEFAULT_PATH,
            )
        }

        /** A raw 32-byte P-256 private scalar, base64url (as `web-push generate-vapid-keys` prints it). */
        fun vapidPrivateKey(text: String): ECPrivateKey {
            val bytes = decode(text, "FIONAS_PUSH_VAPID_PRIVATE_KEY")
            require(bytes.size == 32) { "FIONAS_PUSH_VAPID_PRIVATE_KEY must be a 32-byte P-256 key in base64url" }
            return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes), P256)) as ECPrivateKey
        }

        /** A raw 65-byte uncompressed P-256 public key, base64url. */
        fun vapidPublicKey(text: String): ECPublicKey {
            val bytes = decode(text, "FIONAS_PUSH_VAPID_PUBLIC_KEY")
            require(bytes.size == 65 && bytes[0] == 4.toByte()) {
                "FIONAS_PUSH_VAPID_PUBLIC_KEY must be a 65-byte uncompressed P-256 key in base64url"
            }
            val point = ECPoint(BigInteger(1, bytes.copyOfRange(1, 33)), BigInteger(1, bytes.copyOfRange(33, 65)))
            return try {
                KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, P256)) as ECPublicKey
            } catch (_: Exception) {
                throw IllegalArgumentException("FIONAS_PUSH_VAPID_PUBLIC_KEY is not a valid P-256 point")
            }
        }

        private val P256: ECParameterSpec =
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)

        private fun flag(
            environment: Map<String, String>,
            name: String,
        ): Boolean =
            when (environment[name]) {
                null, "false" -> false
                "true" -> true
                else -> error("$name must be true or false")
            }

        /** A value given directly or through a mounted `_FILE`, never both. */
        private fun secret(
            environment: Map<String, String>,
            name: String,
        ): String {
            val direct = environment[name]
            val file = environment["${name}_FILE"]
            require((direct == null) != (file == null)) { "Configure exactly one of $name or ${name}_FILE" }
            val text =
                direct ?: try {
                    Files.readString(Path.of(checkNotNull(file)))
                } catch (_: Exception) {
                    throw IllegalArgumentException("${name}_FILE must identify a readable mounted file")
                }
            return text.trim()
        }

        private fun decode(
            text: String,
            name: String,
        ): ByteArray =
            try {
                require(Regex("^[A-Za-z0-9_-]+={0,2}$").matches(text))
                Base64.getUrlDecoder().decode(text.trimEnd('='))
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("$name must be base64url text")
            }

        private fun isHttpsUrl(text: String) =
            try {
                URI(text).let { it.scheme == "https" && !it.host.isNullOrEmpty() && it.rawUserInfo == null }
            } catch (_: Exception) {
                false
            }

        private fun pairMatches(
            private: ECPrivateKey,
            public: ECPublicKey,
        ): Boolean {
            val probe = "fionas-vapid-pair".toByteArray()
            val signature =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(private)
                    update(probe)
                    sign()
                }
            return Signature.getInstance("SHA256withECDSA").run {
                initVerify(public)
                update(probe)
                verify(signature)
            }
        }

        internal fun uncompressed(key: ECPublicKey): ByteArray {
            fun coordinate(value: BigInteger): ByteArray {
                val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
                return ByteArray(32 - bytes.size) + bytes
            }
            return byteArrayOf(4) + coordinate(key.w.affineX) + coordinate(key.w.affineY)
        }

        internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
