package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.github.castab.fionas.commerce.testing.withUiKey
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
        var revision = 0
        beforeSpec {
            app = TestApplication.create()
            revision = app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }
        val tables =
            listOf(
                "fionas.customers",
                "fionas.inquiries",
                "fionas.inquiry_submissions",
                "fionas.inquiry_pricing",
                "fionas.inquiry_pricing_categories",
                "fionas.inquiry_pricing_selections",
                "commerce.financial_document_snapshots",
                "commerce.financial_document_lines",
                "fionas.inquiry_financial_documents",
            )

        fun counts() = tables.associateWith(app.database::count)

        fun body(
            pricing: String? = null,
            email: String = "retry-${UUID.randomUUID()}@example.com",
        ) =
            """{"name":"Jane","email":"$email","message":"Birthday","zipCode":"02108","eventDate":"2026-12-05","eventType":"BIRTHDAY"${pricing?.let {
                ",\"pricingInputs\":$it"
            } ?: ""}}"""

        fun request(
            value: String,
            key: String? = UUID.randomUUID().toString(),
        ): Request {
            val request = Request(Method.POST, "/inquiries").withUiKey().header("Content-Type", "application/json").body(value)
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

        listOf(false, true).forEach { priced ->
            test("sequential ${if (priced) "priced" else "plain"} replay preserves receipt and all row counts") {
                val key = UUID.randomUUID().toString()
                val request = request(body(if (priced) pricingBody(revision) else null), key)
                val before = counts()
                val first = app.http(request)
                val committed = counts()
                sameReceipt(first, app.http(request))
                counts() shouldBe committed
                committed.getValue("fionas.inquiries") shouldBe before.getValue("fionas.inquiries") + 1
                committed.getValue("fionas.inquiry_submissions") shouldBe before.getValue("fionas.inquiry_submissions") + 1
                committed.getValue("commerce.financial_document_snapshots") shouldBe
                    before.getValue("commerce.financial_document_snapshots") + if (priced) 1 else 0
                committed.getValue("fionas.inquiry_financial_documents") shouldBe
                    before.getValue("fionas.inquiry_financial_documents") + if (priced) 1 else 0
            }
        }

        test("replay precedes freshness and different current intent conflicts without reprocessing") {
            val key = UUID.randomUUID().toString()
            val submitted = body(pricingBody(revision))
            val first = app.http(request(submitted, key))
            val previous = revision
            revision = app.addOffering(revision, "mint", "soft-serve-flavor", "Mint")
            val committed = counts()
            sameReceipt(first, app.http(request(submitted, key)))
            val changed = app.http(request(submitted.replace("\"catalogRevision\":$previous", "\"catalogRevision\":$revision"), key))
            changed.status shouldBe Status.CONFLICT
            changed.error().code shouldBe "IDEMPOTENCY_KEY_REUSED"
            changed.header("Cache-Control") shouldBe "no-store"
            counts() shouldBe committed
        }

        test("same key with changed customer, event, pricing context or selections conflicts") {
            val key = UUID.randomUUID().toString()
            val value = body(pricingBody(revision), "changed-intent@example.com")
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
                value.replace("\"durationMinutes\":120", "\"durationMinutes\":90"),
                value.replace("waffle-cone", "cup"),
                value.replace("\"catalogRevision\":$revision", "\"catalogRevision\":999"),
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
            val plain = body(email = "plain-mismatch@example.com")
            val plainKey = UUID.randomUUID().toString()
            app.http(request(plain, plainKey)).status shouldBe Status.CREATED
            app.http(request(plain.replace("Birthday", "Something else"), plainKey)).error().code shouldBe "IDEMPOTENCY_KEY_REUSED"
        }

        test("transport differences, normalization, absent optional defaults and untrusted totals do not change intent") {
            val key = UUID.randomUUID().toString()
            val value = body(pricingBody(revision), "canonical@example.com")
            val first = app.http(request(value, key))
            val committed = counts()
            val equivalent =
                value
                    .replace("\"Jane\"", "\" Jane \"")
                    .replace("canonical@example.com", " CANONICAL@EXAMPLE.COM ")
                    .replace("\"Birthday\"", "\" Birthday \"")
                    .replace("\"02108\"", "\" 02108 \"")
                    .replace("\"guestCount\":75", "\"guestCount\":75,\"guestCountIsMinimum\":false")
            val reordered = CommerceJson.parse(equivalent).toString()
            sameReceipt(first, app.http(request("  $reordered  ", key)))
            sameReceipt(first, app.http(request(value.dropLast(1) + ",\"total\":\"0.01\"}", key)))
            counts() shouldBe committed
        }

        test("stale, missing revision and ordinary validation failures roll back claims and allow corrected same-key retries") {
            val old = revision
            revision = app.addOffering(revision, "espresso", "soft-serve-flavor", "Espresso")
            listOf(pricingBody(old), pricingBody(999), pricingBody(revision, guests = 0)).forEach { invalid ->
                val key = UUID.randomUUID().toString()
                val before = counts()
                val failed = app.http(request(body(invalid), key))
                failed.status.successful shouldBe false
                counts() shouldBe before
                app.http(request(body(pricingBody(revision)), key)).status shouldBe Status.CREATED
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
