package io.github.castab.fionas.commerce.events

import java.net.InetAddress
import java.net.Socket
import java.security.KeyManagementException
import java.security.SecureRandom
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager

/**
 * The JVM default TLS context (trust and any client certificate come from the standard
 * `javax.net.ssl.*` properties) with server hostname verification switched on.
 *
 * jnats 2.26 upgrades `tls://` connections with `SSLSocketFactory.createSocket(socket, host,
 * port, autoClose)` and starts the handshake without an endpoint identification algorithm, so
 * a raw JSSE socket would accept any certificate the trust store chains, for any host. Every
 * socket and engine this context creates therefore uses `HTTPS` identification against the
 * configured server host (DNS name or IP address SAN).
 */
internal object HostnameVerifyingTls {
    fun context(base: SSLContext = SSLContext.getDefault()): SSLContext = object : SSLContext(Spi(base), base.provider, base.protocol) {}

    private fun verify(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
        return socket
    }

    private fun verify(engine: SSLEngine): SSLEngine {
        engine.sslParameters = engine.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        return engine
    }

    private class Spi(
        private val base: SSLContext,
    ) : SSLContextSpi() {
        override fun engineInit(
            keyManagers: Array<out KeyManager>?,
            trustManagers: Array<out TrustManager>?,
            random: SecureRandom?,
        ): Unit = throw KeyManagementException("The JVM default TLS context is already initialized")

        override fun engineGetSocketFactory(): SSLSocketFactory = Factory(base.socketFactory)

        override fun engineGetServerSocketFactory(): SSLServerSocketFactory = base.serverSocketFactory

        override fun engineCreateSSLEngine(): SSLEngine = verify(base.createSSLEngine())

        override fun engineCreateSSLEngine(
            host: String?,
            port: Int,
        ): SSLEngine = verify(base.createSSLEngine(host, port))

        override fun engineGetServerSessionContext(): SSLSessionContext = base.serverSessionContext

        override fun engineGetClientSessionContext(): SSLSessionContext = base.clientSessionContext
    }

    private class Factory(
        private val base: SSLSocketFactory,
    ) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

        override fun createSocket(): Socket = verify(base.createSocket())

        override fun createSocket(
            socket: Socket?,
            host: String?,
            port: Int,
            autoClose: Boolean,
        ): Socket = verify(base.createSocket(socket, host, port, autoClose))

        override fun createSocket(
            host: String?,
            port: Int,
        ): Socket = verify(base.createSocket(host, port))

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int,
        ): Socket = verify(base.createSocket(host, port, localHost, localPort))

        override fun createSocket(
            host: InetAddress?,
            port: Int,
        ): Socket = verify(base.createSocket(host, port))

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = verify(base.createSocket(address, port, localAddress, localPort))
    }
}
