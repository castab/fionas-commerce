package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.ValidationErrorResponse
import io.github.castab.commerce.runtime.offering.CategoryDto
import io.github.castab.commerce.runtime.offering.OfferingAvailabilityDto
import io.github.castab.commerce.runtime.offering.OfferingSelectionStateDto
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.setOfferingState
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.github.castab.fionas.commerce.testing.withUiKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.util.UUID

/** Public submission through the full runtime handler, observing all committed state externally. */
class PublicInquirySubmissionSpec :
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
                "fionas.inquiry_submissions",
                "fionas.inquiries",
                "fionas.inquiry_pricing",
                "fionas.inquiry_pricing_categories",
                "fionas.inquiry_pricing_selections",
                "commerce.financial_document_snapshots",
                "commerce.financial_document_lines",
                "fionas.inquiry_financial_documents",
                "fionas.financial_document_pricing",
                "fionas.financial_document_pricing_categories",
                "fionas.financial_document_pricing_selections",
            )

        fun counts() = tables.associateWith(app.database::count)

        fun form() =
            CommerceJson.asA(
                app.http(Request(Method.GET, "/inquiry-form").withUiKey()).bodyString(),
                InquiryFormResponse.serializer(),
            )

        fun submit(inputs: String) =
            app.http(
                Request(Method.POST, "/inquiries").withSubmissionKey().withUiKey().header("Content-Type", "application/json").body(
                    """{"name":"Jane Doe","email":"public-${UUID.randomUUID()}@example.com","zipCode":"92626","eventDate":"2026-12-05","eventType":"BIRTHDAY","pricingInputs":$inputs}""",
                ),
            )

        test("current form revision materializes exactly one canonical Estimate with expected concrete pricing") {
            form().catalogRevision shouldBe revision
            val before = counts()
            val response = submit(pricingBody(revision))
            response.status shouldBe Status.CREATED
            val after = counts()
            // One inquiry, its requested inputs, one Estimate v1 and its association; no legacy staff pricing metadata.
            listOf(
                "fionas.customers",
                "fionas.inquiry_submissions",
                "fionas.inquiries",
                "fionas.inquiry_pricing",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
            ).forEach { after.getValue(it) shouldBe before.getValue(it) + 1 }
            after.getValue("fionas.inquiry_pricing_categories") shouldBe before.getValue("fionas.inquiry_pricing_categories") + 3
            after.getValue("fionas.financial_document_pricing") shouldBe before.getValue("fionas.financial_document_pricing")
            val receipt = CommerceJson.asA(response.bodyString(), InquiryReceiptResponse.serializer())
            val ids =
                app.database.strings(
                    "SELECT document_id FROM fionas.inquiry_financial_documents WHERE inquiry_id = '${receipt.id}' AND purpose = 'INITIAL_ESTIMATE'",
                )
            ids.size shouldBe 1
            app.database.strings("SELECT document_id FROM fionas.inquiry_financial_documents WHERE inquiry_id = '${receipt.id}'") shouldBe
                ids
            val document =
                CommerceJson.asA(
                    app.adminGet("/financial-documents/${ids.single()}").bodyString(),
                    FinancialDocumentResponse.serializer(),
                )
            document.stage shouldBe "ESTIMATE"
            document.version shouldBe 1
            document.total shouldBe "681.25"
            document.lines.size shouldBe
                after.getValue("commerce.financial_document_lines") - before.getValue("commerce.financial_document_lines")
            document.pricing shouldBe null
            app.database.strings("SELECT catalog_revision FROM fionas.inquiry_pricing WHERE inquiry_id = '${receipt.id}'") shouldBe
                listOf(revision.toString())
        }

        test("stale captured form is a machine-readable conflict with zero writes; refreshed revision succeeds") {
            val captured = form().catalogRevision
            revision = app.addOffering(revision, "mint", "soft-serve-flavor", "Mint")
            val before = counts()
            val response = submit(pricingBody(captured))
            response.status shouldBe Status.CONFLICT
            response.header("Cache-Control") shouldBe "no-store"
            val error = CommerceJson.asA(response.bodyString(), ErrorResponse.serializer())
            error.code shouldBe "CATALOG_REVISION_STALE"
            error.message shouldContain "review"
            counts() shouldBe before
            val refreshed = form()
            refreshed.catalogRevision shouldBe captured + 1
            submit(pricingBody(refreshed.catalogRevision)).status shouldBe Status.CREATED
            app.database.count("fionas.inquiry_financial_documents") shouldBe before.getValue("fionas.inquiry_financial_documents") + 1
        }

        test("every advertised available option is accepted in a structurally valid selection for every advertised duration") {
            val form = form()
            val choices = form.sections.flatMap { it.fields }.mapNotNull { it.input as? InquiryFormInputResponse.OfferingChoice }
            choices.map { it.category } shouldBe listOf("soft-serve-flavor", "topping", "cone-option")
            choices.forEach { chosen ->
                chosen.options.forEach { option ->
                    form.pricingPreview.durationOptions.forEach { duration ->
                        val selections =
                            choices.map { category ->
                                val keys =
                                    if (category == chosen) {
                                        listOf(option.key) + category.options.map { it.key }.filter { it != option.key }
                                    } else {
                                        category.options.map { it.key }
                                    }
                                PricingSelection(category.category, keys.take(category.minSelections.coerceAtLeast(1)))
                            }
                        val inputs =
                            InquiryPricingInputs(
                                form.catalogRevision,
                                75,
                                durationMinutes = duration.durationMinutes,
                                selections = selections,
                            )
                        submit(CommerceJson.json.encodeToString(InquiryPricingInputs.serializer(), inputs)).status shouldBe Status.CREATED
                    }
                }
            }
        }

        test("engine validation still rejects unknown, mismatched, cardinality and context violations with zero writes") {
            listOf(
                pricingBody(revision, cones = listOf("unknown")),
                pricingBody(revision, cones = listOf("vanilla")),
                pricingBody(revision, toppings = TOPPINGS.take(2)),
                pricingBody(revision, softServe = listOf("vanilla", "horchata", "chocolate")),
                pricingBody(revision, guests = 0),
                pricingBody(revision, minutes = 45),
                pricingBody(999),
            ).forEach { input ->
                val before = counts()
                val response = submit(input)
                response.status shouldBe if (input == pricingBody(999)) Status.NOT_FOUND else Status.UNPROCESSABLE_ENTITY
                counts() shouldBe before
            }
        }

        test("active hidden category and its offering are forbidden publicly but remain usable by staff, including historical revisions") {
            val category =
                app.adminPost(
                    "/offering-catalog/categories",
                    """{"expectedRevision":$revision,"key":"staff-adjustment","displayName":"Staff adjustment","maximumSelections":1}""",
                )
            category.status shouldBe Status.CREATED
            revision = CommerceJson.asA(category.bodyString(), CategoryDto.serializer()).revision
            revision =
                app.addOffering(
                    revision,
                    "travel-fee",
                    "staff-adjustment",
                    "Travel fee",
                    """{"kind":"FIXED","amount":"25.00","currency":"USD"}""",
                )
            form()
                .sections
                .flatMap { it.fields }
                .mapNotNull { it.input as? InquiryFormInputResponse.OfferingChoice }
                .any { it.category == "staff-adjustment" } shouldBe false
            val hidden =
                pricingBody(revision).replace(
                    "\"selections\":[",
                    "\"selections\":[{\"category\":\"staff-adjustment\",\"offerings\":[\"travel-fee\"]},",
                )
            val before = counts()
            val response = submit(hidden)
            response.status shouldBe Status.UNPROCESSABLE_ENTITY
            CommerceJson.asA(response.bodyString(), ValidationErrorResponse.serializer()).violations!!.map { it.code } shouldBe
                listOf("PUBLIC_INQUIRY_CATEGORY_NOT_ALLOWED")
            counts() shouldBe before
            // Putting a hidden offering into a public category cannot bypass catalog membership checks.
            val disguised = submit(pricingBody(revision, cones = listOf("travel-fee")))
            disguised.status shouldBe Status.UNPROCESSABLE_ENTITY
            counts() shouldBe before
            val historical = revision
            revision = app.addOffering(revision, "espresso", "soft-serve-flavor", "Espresso")
            val inquiryId = app.createInquiry()
            val staff = app.adminPost("/inquiries/$inquiryId/estimates", hidden)
            staff.status shouldBe Status.CREATED
            val document = CommerceJson.asA(staff.bodyString(), FinancialDocumentResponse.serializer())
            document.total shouldBe "706.25"
            document.pricing!!.catalogRevision shouldBe historical
        }

        test("retired public offerings and categories are absent from form and rejected at the accepted current revision") {
            app.adminRequest(Method.DELETE, "/offering-catalog/offerings/horchata?expectedRevision=$revision").status shouldBe Status.OK
            revision++
            val before = counts()
            submit(pricingBody(revision)).status shouldBe Status.UNPROCESSABLE_ENTITY
            counts() shouldBe before
            form()
                .sections
                .flatMap { it.fields }
                .mapNotNull { it.input as? InquiryFormInputResponse.OfferingChoice }
                .flatMap { it.options }
                .any { it.key == "horchata" } shouldBe false
            listOf("cup", "waffle-cone").forEach { key ->
                app.adminRequest(Method.DELETE, "/offering-catalog/offerings/$key?expectedRevision=$revision").status shouldBe Status.OK
                revision++
            }
            app.adminRequest(Method.DELETE, "/offering-catalog/categories/cone-option?expectedRevision=$revision").status shouldBe Status.OK
            revision++
            submit(pricingBody(revision, softServe = listOf("vanilla"))).status shouldBe Status.UNPROCESSABLE_ENTITY
            counts() shouldBe before
        }

        test("missing or null pricing inputs are a malformed request with zero writes") {
            val contact =
                """"name":"Jane Doe","email":"unconfigured-${UUID.randomUUID()}@example.com","zipCode":"92626",""" +
                    """"eventDate":"2026-12-05","eventType":"BIRTHDAY","message":"Just a question.""""
            listOf("{$contact}", """{$contact,"pricingInputs":null}""").forEach { body ->
                val before = counts()
                val response =
                    app.http(
                        Request(Method.POST, "/inquiries")
                            .withSubmissionKey()
                            .withUiKey()
                            .header("Content-Type", "application/json")
                            .body(body),
                    )
                response.status shouldBe Status.BAD_REQUEST
                CommerceJson.asA(response.bodyString(), ErrorResponse.serializer()).code shouldBe "malformed_request"
                counts() shouldBe before
            }
        }

        test("without an initialized catalog no inquiry can be accepted, and nothing is recorded") {
            TestApplication.create().use { empty ->
                val response =
                    empty.http(
                        Request(Method.POST, "/inquiries")
                            .withSubmissionKey()
                            .withUiKey()
                            .header("Content-Type", "application/json")
                            .body(
                                """{"name":"Jane","email":"jane@example.com","zipCode":"92626","eventDate":"2026-12-05",""" +
                                    """"eventType":"BIRTHDAY","pricingInputs":${pricingBody(1)}}""",
                            ),
                    )
                response.status shouldBe Status.NOT_FOUND
                tables.forEach { empty.database.count(it) shouldBe 0 }
            }
        }

        test("full snapshot rejects tampered state selections, stale forms win, and successful replay survives state changes") {
            TestApplication.create().use { fresh ->
                var latest = fresh.createAcceptanceCatalog()

                fun allCounts() = tables.associateWith(fresh.database::count)

                fun request(
                    inputs: String,
                    key: String = UUID.randomUUID().toString(),
                ) = Request(Method.POST, "/inquiries")
                    .withSubmissionKey(key)
                    .withUiKey()
                    .header("Content-Type", "application/json")
                    .body(
                        """{"name":"Jane","email":"states@example.com","zipCode":"92626","eventDate":"2026-12-05","eventType":"OTHER","pricingInputs":$inputs}""",
                    )
                val acceptedRequest = request(pricingBody(latest, softServe = listOf("vanilla")))
                val accepted = fresh.http(acceptedRequest)
                accepted.status shouldBe Status.CREATED
                val captured = latest
                latest = fresh.setOfferingState(latest, "vanilla", availability = OfferingAvailabilityDto.UNAVAILABLE)
                val before = allCounts()
                val stale = fresh.http(request(pricingBody(captured, softServe = listOf("vanilla"))))
                stale.status shouldBe Status.CONFLICT
                CommerceJson.asA(stale.bodyString(), ErrorResponse.serializer()).code shouldBe "CATALOG_REVISION_STALE"
                allCounts() shouldBe before
                latest = fresh.setOfferingState(latest, "vanilla", availability = OfferingAvailabilityDto.AVAILABLE)
                latest = fresh.setOfferingState(latest, "chocolate", availability = OfferingAvailabilityDto.UNAVAILABLE)
                latest = fresh.setOfferingState(latest, "horchata", selectionState = OfferingSelectionStateDto.DISABLED)
                latest =
                    fresh.addOffering(
                        latest,
                        "secret",
                        "soft-serve-flavor",
                        "Secret",
                        selectionState = OfferingSelectionStateDto.DISABLED,
                        availability = OfferingAvailabilityDto.UNAVAILABLE,
                    )

                listOf("chocolate" to "OFFERING_UNAVAILABLE", "horchata" to "OFFERING_DISABLED", "secret" to "OFFERING_DISABLED")
                    .forEach { (key, code) ->
                        val inputs = pricingBody(latest, softServe = listOf(key))
                        val submission = request(inputs)
                        val unchanged = allCounts()
                        val failed = fresh.http(submission)
                        failed.status shouldBe Status.UNPROCESSABLE_ENTITY
                        CommerceJson.asA(failed.bodyString(), ValidationErrorResponse.serializer()).violations!!.map { it.code } shouldBe
                            listOf(code)
                        allCounts() shouldBe unchanged
                        val preview =
                            fresh.http(
                                Request(Method.POST, "/estimate-preview")
                                    .withUiKey()
                                    .header("Content-Type", "application/json")
                                    .body(inputs),
                            )
                        preview.status shouldBe Status.UNPROCESSABLE_ENTITY
                        CommerceJson.asA(preview.bodyString(), ValidationErrorResponse.serializer()).violations!!.map { it.code } shouldBe
                            listOf(code)
                        allCounts() shouldBe unchanged
                        // A failed key is released: the corrected current command can use it.
                        val corrected = submission.body(submission.bodyString().replace("\"$key\"", "\"vanilla\""))
                        fresh.http(corrected).status shouldBe Status.CREATED
                        fresh.database.count("fionas.inquiry_submissions") shouldBe unchanged.getValue("fionas.inquiry_submissions") + 1
                    }

                fun replay() {
                    val unchanged = allCounts()
                    val replay = fresh.http(acceptedRequest)
                    replay.status shouldBe Status.CREATED
                    replay.bodyString() shouldBe accepted.bodyString()
                    replay.header("Location") shouldBe accepted.header("Location")
                    allCounts() shouldBe unchanged
                }
                latest = fresh.setOfferingState(latest, "vanilla", availability = OfferingAvailabilityDto.UNAVAILABLE)
                replay()
                val beforeDisable = latest
                latest = fresh.setOfferingState(latest, "vanilla", selectionState = OfferingSelectionStateDto.DISABLED)
                val staleDisabled = fresh.http(request(pricingBody(beforeDisable, softServe = listOf("vanilla"))))
                staleDisabled.status shouldBe Status.CONFLICT
                CommerceJson.asA(staleDisabled.bodyString(), ErrorResponse.serializer()).code shouldBe "CATALOG_REVISION_STALE"
                replay()
                fresh.adminRequest(Method.DELETE, "/offering-catalog/offerings/vanilla?expectedRevision=$latest").status shouldBe Status.OK
                replay()
            }
        }
    })
