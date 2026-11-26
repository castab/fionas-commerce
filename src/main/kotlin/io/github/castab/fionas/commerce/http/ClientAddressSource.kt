package io.github.castab.fionas.commerce.http

import org.http4k.core.Request
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Where Fiona learns a client's address for per-client limits ([LoginRateLimit]). It never
 * authenticates anyone; it only decides which bucket an attempt spends.
 *
 * - [CONNECTION] (default): the TCP peer. Behind a proxy every client shares the proxy's address.
 * - [CLOUDFLARE]: the `CF-Connecting-IP` header Cloudflare sets on every request it forwards.
 *   Trust it **only** where clients cannot reach the application except through Cloudflare (for
 *   example a Cloudflare Tunnel with no published port): anyone who can connect directly can send
 *   any value. A missing, repeated or malformed header falls back to the connection address, so
 *   it can never mint a bucket out of nothing.
 *
 * IPv6 clients are bucketed by their /64 network, the smallest block one subscriber normally
 * holds, so rotating addresses inside it gains nothing. IPv4 clients are bucketed per address.
 */
enum class ClientAddressSource {
    CONNECTION,
    CLOUDFLARE,
    ;

    /** The bucket identity of [request]'s client under this source. */
    fun of(request: Request): String {
        val connection = request.source?.address?.takeIf(String::isNotBlank)
        val forwarded =
            if (this == CLOUDFLARE) {
                request.headerValues(CF_CONNECTING_IP).singleOrNull()?.let(::literal)
            } else {
                null
            }
        return forwarded?.let(::bucket) ?: connection?.let { literal(it)?.let(::bucket) ?: it } ?: UNKNOWN
    }

    companion object {
        const val CF_CONNECTING_IP = "CF-Connecting-IP"
        private const val UNKNOWN = "unknown"
        private const val VARIABLE = "FIONAS_CLIENT_IP_SOURCE"

        /** `FIONAS_CLIENT_IP_SOURCE`: `connection` (default when absent) or `cloudflare`; anything else fails startup. */
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ClientAddressSource =
            when (environment[VARIABLE]?.trim()?.lowercase()) {
                null, "", "connection" -> CONNECTION
                "cloudflare" -> CLOUDFLARE
                else -> error("$VARIABLE must be connection or cloudflare")
            }

        /** An IP literal, never a host name: `InetAddress.ofLiteral` performs no DNS lookup. */
        private fun literal(text: String): InetAddress? =
            text
                .trim()
                .takeIf { it.isNotEmpty() && it.length <= 45 && it.none(Char::isWhitespace) }
                ?.let { runCatching { InetAddress.ofLiteral(it) }.getOrNull() }

        private fun bucket(address: InetAddress): String =
            if (address is Inet6Address) {
                val network = address.address.copyOf(8) + ByteArray(8)
                InetAddress.getByAddress(network).hostAddress + "/64"
            } else {
                address.hostAddress
            }
    }
}
