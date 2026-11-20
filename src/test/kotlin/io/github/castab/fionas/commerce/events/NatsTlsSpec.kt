package io.github.castab.fionas.commerce.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.nats.client.Nats
import io.nats.client.Options
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess

/**
 * TLS to NATS uses the JVM-wide `javax.net.ssl.*` trust settings, with hostname verification.
 * Each probe runs in a fresh JVM because the default SSLContext is fixed once per process.
 */
class NatsTlsSpec :
    FunSpec({
        val directory = Files.createDirectories(Path.of("build", "nats-tls-${UUID.randomUUID()}").toAbsolutePath())
        val containers = mutableListOf<String>()
        val password = "changeit"
        val javaBin = Path.of(System.getProperty("java.home"), "bin")

        fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).directory(directory.toFile()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(90, TimeUnit.SECONDS) && process.exitValue() == 0) { "Command failed: ${command.first()} $output" }
            return output
        }

        fun keytool(vararg arguments: String) =
            run(
                javaBin.resolve(if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool").toString(),
                *arguments,
            )

        fun pem(
            type: String,
            der: ByteArray,
        ) = "-----BEGIN $type-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $type-----\n"

        /** A server certificate for [san], signed by the test CA, in its own directory. */
        fun server(
            name: String,
            san: String,
        ): Path {
            val dir = Files.createDirectories(directory.resolve(name))
            keytool(
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=$name",
                "-validity",
                "2",
                "-keystore",
                "$name/server.p12",
                "-storetype",
                "PKCS12",
                "-storepass",
                password,
            )
            keytool("-certreq", "-alias", "server", "-keystore", "$name/server.p12", "-storepass", password, "-file", "$name/server.csr")
            keytool(
                "-gencert",
                "-alias",
                "ca",
                "-keystore",
                "ca.p12",
                "-storepass",
                password,
                "-infile",
                "$name/server.csr",
                "-outfile",
                "$name/server.pem",
                "-rfc",
                "-ext",
                "SAN=$san",
                "-validity",
                "2",
            )
            val store =
                KeyStore.getInstance("PKCS12").apply {
                    Files.newInputStream(dir.resolve("server.p12")).use { load(it, password.toCharArray()) }
                }
            Files.writeString(
                dir.resolve("server-key.pem"),
                pem("PRIVATE KEY", (store.getKey("server", password.toCharArray()) as PrivateKey).encoded),
            )
            Files.writeString(
                dir.resolve("nats.conf"),
                "tls { cert_file: \"/etc/nats/server.pem\", key_file: \"/etc/nats/server-key.pem\" }\n",
            )
            return dir
        }

        fun nats(
            dir: Path,
            host: String = "127.0.0.1",
        ): String {
            val port = ServerSocket(0).use { it.localPort }
            val name = "fionas-test-nats-tls-${UUID.randomUUID()}"
            run(
                "docker",
                "run",
                "--detach",
                "--name",
                name,
                "--publish",
                "127.0.0.1:$port:4222",
                "--mount",
                "type=bind,src=${dir.absolutePathString()},dst=/etc/nats,readonly",
                "nats:2.14.7",
                "--config",
                "/etc/nats/nats.conf",
            )
            containers += name
            Thread.sleep(1_500)
            return "tls://$host:$port"
        }

        fun probe(
            mode: String,
            url: String,
        ): Int {
            val process =
                ProcessBuilder(
                    javaBin.resolve("java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "-Djavax.net.ssl.trustStore=${directory.resolve("trust.p12")}",
                    "-Djavax.net.ssl.trustStorePassword=$password",
                    "-Djavax.net.ssl.trustStoreType=PKCS12",
                    NatsTlsProbe::class.java.name,
                    mode,
                    url,
                ).redirectErrorStream(true)
                    .start()
            process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS))
            return process.exitValue()
        }

        lateinit var matching: String
        lateinit var matchingDnsName: String
        lateinit var mismatched: String
        beforeSpec {
            keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=Fiona Test CA",
                "-ext",
                "bc:c",
                "-validity",
                "2",
                "-keystore",
                "ca.p12",
                "-storetype",
                "PKCS12",
                "-storepass",
                password,
            )
            keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", password, "-rfc", "-file", "ca.pem")
            keytool(
                "-importcert",
                "-alias",
                "ca",
                "-file",
                "ca.pem",
                "-keystore",
                "trust.p12",
                "-storetype",
                "PKCS12",
                "-storepass",
                password,
                "-noprompt",
            )
            matching = nats(server("matching", "ip:127.0.0.1"))
            // Only a DNS SAN, dialed by that name: the shape of a broker behind a hosted TCP proxy.
            matchingDnsName = nats(server("matching-dns", "dns:localhost"), host = "localhost")
            mismatched = nats(server("mismatched", "dns:nats.elsewhere.test"))
        }
        afterSpec {
            containers.forEach { runCatching { run("docker", "rm", "--force", "--volumes", it) } }
            directory.toFile().deleteRecursively()
        }

        test("a certificate chaining to the JVM trust store and naming the server host connects") {
            probe("fiona", matching) shouldBe 0
        }

        test("a hostname URL is verified against the hostname, not against the IP jnats would resolve it to") {
            // jnats resolves hostnames to IPs by default and passes the IP to the TLS upgrade;
            // verifying that IP against a DNS-only certificate refused every hostname broker.
            probe("fiona", matchingDnsName) shouldBe 0
        }

        test("a trusted certificate for another host is rejected; plain jnats would have accepted it") {
            probe("fiona", mismatched) shouldBe 2
            // The reason Fiona sets endpoint identification itself: jnats' default TLS upgrade skips it.
            probe("plain", mismatched) shouldBe 0
        }
    })

/** Runs in a forked JVM: exit 0 when the TLS connection succeeds, 2 when it is refused. */
object NatsTlsProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val (mode, url) = args
        val code =
            try {
                if (mode == "fiona") {
                    val configuration = NatsConnectionConfiguration.fromEnvironment(mapOf("PROBE_URL" to url), "PROBE")
                    val connection = SharedNatsConnection(configuration, "tls-probe")
                    connection.connected().flush(Duration.ofSeconds(2))
                    connection.release()
                } else {
                    Nats
                        .connect(
                            Options
                                .builder()
                                .server(url)
                                .connectionTimeout(Duration.ofSeconds(2))
                                .maxReconnects(0)
                                .build(),
                        ).use { it.flush(Duration.ofSeconds(2)) }
                }
                0
            } catch (_: Exception) {
                2
            }
        exitProcess(code)
    }
}
