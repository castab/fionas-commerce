package io.github.castab.fionas.commerce.http

import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The real Node payment smoke script against Fiona's started runtime and migrated throwaway
 * PostgreSQL. The script plays the public pricing authority with its own SERVICE credential: it
 * submits already-priced lines, then walks staff Invoice payments, a refund and final settlement.
 */
class PaymentSmokeScriptSpec :
    FunSpec({
        lateinit var app: TestApplication
        beforeSpec {
            app = TestApplication.create()
            app.runtime.start()
        }
        afterSpec { app.close() }

        fun runScript(
            service: TestService?,
            expectedExit: Int = 0,
        ): String {
            val log = Files.createTempFile("fiona-script-", ".log")
            val builder =
                ProcessBuilder("node", Path.of("scripts", "spoof-payment.mjs").toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
            builder.environment().putAll(
                mapOf(
                    "FIONAS_BASE_URL" to "http://127.0.0.1:${app.runtime.port()}",
                    "FIONAS_ORIGIN" to TEST_ORIGIN,
                    "FIONAS_ADMIN_USERNAME" to "admin",
                    "FIONAS_ADMIN_PASSWORD" to "test-admin-password",
                ) +
                    (
                        service?.let { mapOf("FIONAS_WEB_SERVICE_ID" to it.id.value.toString(), "FIONAS_WEB_SERVICE_SECRET" to it.secret) }
                            ?: emptyMap()
                    ),
            )
            val process = builder.start()
            try {
                process.outputStream.close()
                check(process.waitFor(45, TimeUnit.SECONDS)) { "Node script timed out: ${Files.readString(log)}" }
                val output = Files.readString(log)
                check(process.exitValue() == expectedExit) { "Node script exited ${process.exitValue()}: $output" }
                return output
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
                Files.deleteIfExists(log)
            }
        }

        test("the payment smoke completes with service-priced lines and no catalog") {
            val output = runScript(app.provisionService("smoke-pricing-authority", setOf(FionaPermissions.InquiriesCreate)))
            output shouldContain "Created inquiry"
            output shouldContain "Result: PAID IN FULL"
        }

        test("without a pricing-authority credential the script stops before writing anything") {
            val before = app.database.count("fionas.inquiries")
            runScript(null, expectedExit = 1) shouldContain "FIONAS_WEB_SERVICE_ID"
            app.database.count("fionas.inquiries") shouldBe before
        }

        test("a service without priced inquiry submission is refused without writing anything") {
            val before = app.database.count("fionas.inquiries")
            runScript(app.provisionService("smoke-unprivileged", emptySet()), expectedExit = 1) shouldContain "403"
            app.database.count("fionas.inquiries") shouldBe before
        }
    })
