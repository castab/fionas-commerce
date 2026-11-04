package io.github.castab.fionas.commerce.payments

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticates a server-created authorization during recovery before its Session id was saved. */
internal fun StripeConfiguration.authorizationProof(attempt: StripeAttempt): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(accessKey, "HmacSHA256"))
    val text = "fionas.stripe.v1:${attempt.id}:${attempt.authorizedMinor}:${attempt.expiresAt.epochSecond}:${attempt.providerKey}"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
}
