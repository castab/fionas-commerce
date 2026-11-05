package io.github.castab.fionas.commerce.payments

import java.net.URI
import java.util.Base64

/** Fiona deployment secrets. Deliberately no data-class-generated toString. */
class StripeConfiguration(
    internal val secretKey: String,
    internal val webhookSecret: String,
    internal val accessKey: ByteArray,
    val publicOrigin: String,
    val accountId: String,
    val liveMode: Boolean,
    val checkoutMode: CheckoutMode = CheckoutMode.HOSTED,
) {
    init {
        require(
            secretKey.startsWith(if (liveMode) "sk_live_" else "sk_test_") &&
                secretKey.length > 16 &&
                secretKey.matches(Regex("[A-Za-z0-9_]+")),
        ) {
            "STRIPE_SECRET_KEY must match FIONAS_STRIPE_LIVE_MODE"
        }
        require(webhookSecret.startsWith("whsec_") && webhookSecret.length > 12 && webhookSecret.matches(Regex("[A-Za-z0-9_]+"))) {
            "STRIPE_WEBHOOK_SECRET is invalid"
        }
        require(accountId.matches(Regex("acct_[A-Za-z0-9]+"))) { "FIONAS_STRIPE_ACCOUNT_ID is invalid" }
        require(accessKey.size == 32 && accessKey.toSet().size >= 16) { "FIONAS_PAYMENT_ACCESS_KEY must encode 32 random bytes" }
        val uri = runCatching { URI(publicOrigin) }.getOrNull()
        require(
            uri != null &&
                (uri.port == -1 || uri.port in 1..65535) &&
                uri.host != null &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.rawPath.isNullOrEmpty() &&
                (uri.scheme == "https" || (!liveMode && uri.scheme == "http" && uri.host == "localhost")),
        ) { "FIONAS_PUBLIC_BASE_URL must be an HTTPS origin (localhost HTTP allowed only in test mode), without a trailing slash" }
    }

    override fun toString() = "StripeConfiguration([redacted])"

    companion object {
        // Pinned together with stripe-java 34.1.0. Configure a SNAPSHOT destination with this version.
        const val API_VERSION = "2026-10-28.endive"

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): StripeConfiguration? {
            fun flag(name: String): Boolean =
                when (environment[name] ?: "false") {
                    "true" -> true
                    "false" -> false
                    else -> error("$name must be true or false")
                }
            if (!flag("FIONAS_STRIPE_ENABLED")) return null

            fun required(name: String) = environment[name]?.takeIf { it.isNotBlank() } ?: error("$name is required when Stripe is enabled")
            val key =
                runCatching { Base64.getDecoder().decode(required("FIONAS_PAYMENT_ACCESS_KEY")) }.getOrNull()
                    ?: error("FIONAS_PAYMENT_ACCESS_KEY must be base64")
            return StripeConfiguration(
                required("STRIPE_SECRET_KEY"),
                required("STRIPE_WEBHOOK_SECRET"),
                key,
                required("FIONAS_PUBLIC_BASE_URL"),
                required("FIONAS_STRIPE_ACCOUNT_ID"),
                flag("FIONAS_STRIPE_LIVE_MODE"),
                when (environment["FIONAS_STRIPE_CHECKOUT_MODE"] ?: "hosted") {
                    "hosted" -> CheckoutMode.HOSTED
                    "embedded" -> CheckoutMode.EMBEDDED
                    else -> error("FIONAS_STRIPE_CHECKOUT_MODE must be hosted or embedded")
                },
            )
        }
    }
}
