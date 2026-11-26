package io.github.castab.fionas.commerce.testing

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

/**
 * A software WebAuthn authenticator for specs: one discoverable ES256 passkey whose answers
 * are built byte for byte as a browser and platform authenticator would send them
 * (`none` attestation, CBOR attestation object, COSE key, ECDSA signature over
 * `authenticatorData || SHA-256(clientDataJSON)`). Each knob lets a spec break exactly one rule.
 */
class SoftwareAuthenticator(
    val aaguid: UUID = UUID.fromString("fbfc3007-154e-4ecc-8c0b-6e020557d7bd"),
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair(),
    val credentialId: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes),
) {
    /** The user handle the passkey stores at registration and returns with every assertion. */
    var userHandle: ByteArray? = null
    var signatureCount: Long = 0

    /** A `navigator.credentials.create()` answer, as the browser's `toJSON()` renders it. */
    fun register(
        challenge: String,
        rpId: String,
        userHandle: String,
        origin: String = TEST_ORIGIN,
        userVerified: Boolean = true,
        discoverable: Boolean? = true,
    ): String {
        this.userHandle = decode(userHandle)
        val clientData = clientData("webauthn.create", challenge, origin)
        val authData = authenticatorData(rpId, userVerified, attestedCredential = true)
        val attestationObject =
            Cbor.map(
                listOf(
                    Cbor.text("fmt") to Cbor.text("none"),
                    Cbor.text("attStmt") to Cbor.map(emptyList()),
                    Cbor.text("authData") to Cbor.bytes(authData),
                ),
            )
        val credProps = discoverable?.let { """"credProps":{"rk":$it}""" }.orEmpty()
        return """{"id":"${encode(credentialId)}","rawId":"${encode(credentialId)}","type":"public-key",""" +
            """"authenticatorAttachment":"platform","response":{"clientDataJSON":"${encode(clientData)}",""" +
            """"attestationObject":"${encode(attestationObject)}","transports":["internal","hybrid"]},""" +
            """"clientExtensionResults":{$credProps}}"""
    }

    /** A `navigator.credentials.get()` answer. */
    fun assert(
        challenge: String,
        rpId: String,
        origin: String = TEST_ORIGIN,
        userVerified: Boolean = true,
        type: String = "webauthn.get",
        count: Long = ++signatureCount,
    ): String {
        val clientData = clientData(type, challenge, origin)
        val authData = authenticatorData(rpId, userVerified, attestedCredential = false, count = count)
        val signature =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(authData + sha256(clientData))
                sign()
            }
        val handle = userHandle?.let { ""","userHandle":"${encode(it)}"""" }.orEmpty()
        return """{"id":"${encode(credentialId)}","rawId":"${encode(credentialId)}","type":"public-key",""" +
            """"authenticatorAttachment":"platform","response":{"clientDataJSON":"${encode(clientData)}",""" +
            """"authenticatorData":"${encode(authData)}","signature":"${encode(signature)}"$handle},""" +
            """"clientExtensionResults":{}}"""
    }

    private fun clientData(
        type: String,
        challenge: String,
        origin: String,
    ) = """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

    private fun authenticatorData(
        rpId: String,
        userVerified: Boolean,
        attestedCredential: Boolean,
        count: Long = signatureCount,
    ): ByteArray {
        // UP, UV, BE and BS (a synced passkey), and AT when a credential is attached.
        var flags = 0x01 or 0x08 or 0x10
        if (userVerified) flags = flags or 0x04
        if (attestedCredential) flags = flags or 0x40
        val out = ByteArrayOutputStream()
        out.write(sha256(rpId.toByteArray()))
        out.write(flags)
        out.write(ByteBuffer.allocate(4).putInt(count.toInt()).array())
        if (attestedCredential) {
            out.write(
                ByteBuffer
                    .allocate(16)
                    .putLong(aaguid.mostSignificantBits)
                    .putLong(aaguid.leastSignificantBits)
                    .array(),
            )
            out.write(ByteBuffer.allocate(2).putShort(credentialId.size.toShort()).array())
            out.write(credentialId)
            out.write(coseKey())
        }
        return out.toByteArray()
    }

    /** The EC2 P-256 public key as a COSE_Key: kty 2, alg -7, crv 1, x, y. */
    private fun coseKey(): ByteArray {
        val point = (keys.public as ECPublicKey).w
        return Cbor.map(
            listOf(
                Cbor.int(1) to Cbor.int(2),
                Cbor.int(3) to Cbor.int(-7),
                Cbor.int(-1) to Cbor.int(1),
                Cbor.int(-2) to Cbor.bytes(coordinate(point.affineX)),
                Cbor.int(-3) to Cbor.bytes(coordinate(point.affineY)),
            ),
        )
    }

    companion object {
        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun coordinate(value: BigInteger): ByteArray {
            val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - raw.size) + raw
        }
    }
}

/** The few CBOR major types a WebAuthn attestation object needs (RFC 8949), canonical lengths only. */
private object Cbor {
    fun int(value: Long): ByteArray = if (value >= 0) head(0, value) else head(1, -1 - value)

    fun int(value: Int): ByteArray = int(value.toLong())

    fun bytes(value: ByteArray): ByteArray = head(2, value.size.toLong()) + value

    fun text(value: String): ByteArray = value.toByteArray().let { head(3, it.size.toLong()) + it }

    fun map(entries: List<Pair<ByteArray, ByteArray>>): ByteArray =
        entries.fold(head(5, entries.size.toLong())) { acc, (key, value) -> acc + key + value }

    private fun head(
        major: Int,
        length: Long,
    ): ByteArray {
        val type = major shl 5
        return when {
            length < 24 -> byteArrayOf((type or length.toInt()).toByte())
            length < 0x100 -> byteArrayOf((type or 24).toByte(), length.toByte())
            length < 0x10000 -> byteArrayOf((type or 25).toByte()) + ByteBuffer.allocate(2).putShort(length.toShort()).array()
            else -> byteArrayOf((type or 26).toByte()) + ByteBuffer.allocate(4).putInt(length.toInt()).array()
        }
    }
}
