package io.github.castab.fionas.commerce

import io.github.castab.commerce.runtime.commerceRuntime
import io.github.castab.commerce.runtime.config.CommerceRuntimeConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.system.exitProcess

/**
 * Runs fionas-commerce: loads `application.conf` and the environment, composes
 * commerce-runtime with Fiona's contributions, serves HTTP, and keeps the process alive
 * until the JVM is asked to stop. commerce-runtime manages its server and connection pool;
 * this function owns the process around them.
 */
fun main() {
    // Every log line goes through Logback; kotlin-logging would otherwise announce itself
    // on stdout, outside the configured format.
    KotlinLoggingConfiguration.logStartupMessage = false
    val logger = KotlinLogging.logger {}

    try {
        val runtime =
            commerceRuntime(
                configuration = CommerceRuntimeConfiguration.load(),
                application = fionaApplication(),
            )
        Runtime.getRuntime().addShutdownHook(Thread(runtime::close, "fionas-commerce-shutdown"))
        runtime.start()
    } catch (e: Exception) {
        logger.error(e) { "event=startup_failed" }
        exitProcess(1)
    }
    // Jetty serves on its own threads. The main thread waits until shutdown, when the hook
    // above stops the server and closes the connection pool.
    Thread.currentThread().join()
}
