package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.linesJson
import io.github.castab.fionas.commerce.testing.requestedServiceJson
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.util.UUID

class InquiryIdempotencyRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }
        val tables =
            listOf(
                "fionas.customers",
                "fionas.inquiries",
                "fionas.inquiry_submissions",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
            )

        fun counts() = tables.associateWith(app.database::count)

        fun body(
            lines: List<TestLine> = acceptanceLines(),
            email: String = "retry-${UUID.randomUUID()}@example.com",
        ) = """{"name":"Jane","email":"$email","message":"Birthday","zipCode":"02108","eventDate":"2026-12-05",""" +
            """"eventType":"BIRTHDAY","requestedService":${requestedServiceJson()},"lines":${linesJson(lines)}}"""

        fun request(
            value: String,
            key: String? = UUID.randomUUID().toString(),
        ): Request {
            val request = Request(Method.POST, "/inquiries").asFionasWeb(app).header("Content-Type", "application/json").body(value)
            return key?.let { request.withSubmissionKey(it) } ?: request
        }

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun sameReceipt(
            first: Response,
            replay: Response,
        ) {
            first.status shouldBe Status.CREATED
            replay.status shouldBe first.status
            replay.bodyString() shouldBe first.bodyString()
            replay.header("Location") shouldBe first.header("Location")
        }

        test("sequential replay preserves the receipt and creates no second inquiry, Estimate or association") {
            val key = UUID.randomUUID().toString()
            val request = request(body(), key)
            val before = counts()
            val first = app.http(request)
            val committed = counts()
            sameReceipt(first, app.http(request))
            counts() shouldBe committed
            listOf(
                "fionas.inquiries",
                "fionas.inquiry_submissions",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
            ).forEach { committed.getValue(it) shouldBe before.getValue(it) + 1 }
        }

        test("a committed replay writes nothing, and changed line amounts or order under the key conflict without reprocessing") {
            val key = UUID.randomUUID().toString()
            val submitted = body()
            val first = app.http(request(submitted, key))
            val committed = counts()
            sameReceipt(first, app.http(request(submitted, key)))
            val lines = acceptanceLines()
            listOf(
                body(lines.map { if (it.description == "Horchata") it.copy(unitPrice = "0.49") else it }),
                body(lines.reversed()),
                body(lines.dropLast(1)),
                body(lines + COURTESY_DISCOUNT),
                body(lines.map { if (it.description == "Waffle cones") it.copy(taxAmount = "1.00") else it }),
                body(lines.map { if (it.description == "Base service") it.copy(subDescription = "Two hours") else it }),
                body(lines.map { if (it.description == "Ice cream service") it.copy(quantity = "76") else it }),
            ).forEach { changed ->
                app.http(request(changed, key)).let {
                    it.status shouldBe Status.CONFLICT
                    it.error().code shouldBe "IDEMPOTENCY_KEY_REUSED"
                    it.header("Cache-Control") shouldBe "no-store"
                }
            }
            counts() shouldBe committed
        }

        test("same key with changed customer, event or requested service conflicts") {
            val key = UUID.randomUUID().toString()
            val value = body(email = "changed-intent@example.com")
            app.http(request(value, key)).status shouldBe Status.CREATED
            val committed = counts()
            listOf(
                value.replace("Jane", "Janet"),
                value.replace("changed-intent@example.com", "other@example.com"),
                value.replace("Birthday", "Wedding party"),
                value.replace("02108", "92626"),
                value.replace("2026-12-05", "2026-12-06"),
                value.replace("BIRTHDAY", "WEDDING"),
                value.replace("\"guestCount\":75", "\"guestCount\":76"),
                value.replace("\"guestCountIsMinimum\":false", "\"guestCountIsMinimum\":true"),
                value.replace("\"durationMinutes\":120", "\"durationMinutes\":90"),
                value.replace("Waffle cones\"}]", "Cups\"}]"),
                value.replace("test-pricing@1", "test-pricing@2"),
            ).forEach { changed ->
                app.http(request(changed, key)).let {
                    it.status shouldBe Status.CONFLICT
                    it.error().code shouldBe "IDEMPOTENCY_KEY_REUSED"
                    it.header("Cache-Control") shouldBe "no-store"
                    it.bodyString() shouldNotContain "changed-intent@example.com"
                    it.bodyString() shouldNotContain "request_fingerprint"
                }
                counts() shouldBe committed
            }
        }

        test("transport differences, normalization, absent optional defaults and untrusted totals do not change intent") {
            val key = UUID.randomUUID().toString()
            val value = body(email = "canonical@example.com")
            val first = app.http(request(value, key))
            val committed = counts()
            val equivalent =
                value
                    .replace("\"Jane\"", "\" Jane \"")
                    .replace("canonical@example.com", " CANONICAL@EXAMPLE.COM ")
                    .replace("\"Birthday\"", "\" Birthday \"")
                    .replace("\"02108\"", "\" 02108 \"")
                    // Numerically equal amounts and quantities are the same intent, whatever their scale.
                    .replace("\"unitPrice\":\"4.00\"", "\"unitPrice\":\"4\"")
                    .replace("\"quantity\":\"75\"", "\"quantity\":\"75.000\"")
            val reordered = CommerceJson.parse(equivalent).toString()
            sameReceipt(first, app.http(request("  $reordered  ", key)))
            sameReceipt(first, app.http(request(value.dropLast(1) + ",\"total\":\"0.01\"}", key)))
            counts() shouldBe committed
        }

        test("validation failures roll back claims and allow corrected same-key retries") {
            listOf(
                body(listOf(TestLine("Service", unitPrice = "-1.00"))),
                body(listOf(TestLine("Service", unitPrice = "1.001"))),
                body(acceptanceLines()).replace("\"zipCode\":\"02108\"", "\"zipCode\":\"2108\""),
            ).forEach { invalid ->
                val key = UUID.randomUUID().toString()
                val before = counts()
                val failed = app.http(request(invalid, key))
                failed.status.successful shouldBe false
                counts() shouldBe before
                app.http(request(body(), key)).status shouldBe Status.CREATED
            }
        }

        test("invalid, missing and duplicate keys are malformed; authentication and malformed bodies create no claims") {
            val value = body()
            val before = counts()
            listOf(null, "", " ", "bad key", "a".repeat(129), "bad.key", "é", "a,b").forEach { key ->
                val response = app.http(request(value, key))
                response.status shouldBe Status.BAD_REQUEST
                response.error().code shouldBe "malformed_request"
                counts() shouldBe before
            }
            app.http(request(value, "A").header("Idempotency-Key", "A")).status shouldBe Status.BAD_REQUEST
            app.http(request("not json", "malformed")).status shouldBe Status.BAD_REQUEST
            listOf(
                request(value, "auth").removeHeader("Authorization"),
                request(value, "auth").replaceHeader("Authorization", "Bearer wrong"),
            ).forEach { app.http(it).status shouldBe Status.UNAUTHORIZED }
            counts() shouldBe before
            app.http(request(value, "a".repeat(128))).status shouldBe Status.CREATED
        }

        test("distinct keys remain distinct inquiries even when every semantic input and email is identical") {
            val value = body(email = "two-commands@example.com")
            val before = counts()
            val first = app.http(request(value, "distinct-A"))
            val second = app.http(request(value, "distinct-B"))
            first.status shouldBe Status.CREATED
            second.status shouldBe Status.CREATED
            (first.bodyString() != second.bodyString()) shouldBe true
            app.database.count("fionas.inquiries") shouldBe before.getValue("fionas.inquiries") + 2
            app.database.count("fionas.customers") shouldBe before.getValue("fionas.customers") + 1
        }
    })
