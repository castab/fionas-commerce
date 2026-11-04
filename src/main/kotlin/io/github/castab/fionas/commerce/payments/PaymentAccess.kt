package io.github.castab.fionas.commerce.payments

import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PaymentAccess(
    val inquiryId: InquiryId,
    val publicId: UUID,
    val generation: String,
    val tokenHash: String,
    val revoked: Boolean,
    val issuedAt: Instant,
    val issuedBy: UserId,
)

/** Only staff transport may render a usable link. Never put one in a public response. */
class StaffPaymentAccess(
    val publicId: UUID?,
    val revoked: Boolean,
    val url: String?,
) {
    override fun toString() = "StaffPaymentAccess([redacted])"
}

class PaymentBearer(
    val value: String,
) {
    override fun toString() = "PaymentBearer([redacted])"
}

internal class PaymentAccessSecrets(
    private val configuration: StripeConfiguration,
) {
    fun generation(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    fun bearer(
        publicId: UUID,
        generation: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(configuration.accessKey, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal("fionas.payment-access.v1:$publicId:$generation".toByteArray(UTF_8)),
        )
    }

    fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).joinToString("") {
            "%02x".format(it)
        }

    fun matches(
        access: PaymentAccess,
        bearer: PaymentBearer,
    ): Boolean =
        !access.revoked &&
            bearer.value.matches(Regex("[A-Za-z0-9_-]{43}")) &&
            MessageDigest.isEqual(hash(bearer.value).toByteArray(UTF_8), access.tokenHash.toByteArray(UTF_8))

    fun describe(access: PaymentAccess?): StaffPaymentAccess =
        StaffPaymentAccess(
            access?.publicId,
            access?.revoked ?: false,
            access?.takeUnless { it.revoked }?.let {
                val secret = bearer(it.publicId, it.generation)
                check(matches(it, PaymentBearer(secret))) { "Payment access derivation key differs from the issuing key" }
                "${configuration.publicOrigin}/pay/${it.publicId}#$secret"
            },
        )
}
