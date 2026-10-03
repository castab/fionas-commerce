package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ValidationErrorResponse
import io.github.castab.commerce.runtime.offering.OfferingAvailabilityDto
import io.github.castab.commerce.runtime.offering.OfferingPriceDto
import io.github.castab.commerce.runtime.offering.OfferingSelectionStateDto
import io.github.castab.commerce.runtime.offering.OfferingsBatchDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.commerce.runtime.offering.RetiredCategoriesDto
import io.github.castab.commerce.runtime.offering.RetiredOfferingsDto
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.inquiry.InquiryFormControl
import io.github.castab.fionas.commerce.testing.TEST_ORIGIN
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.withSubmissionKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The real Node scripts against Fiona's started runtime and migrated throwaway PostgreSQL. */
class ReplaceCatalogSpec :
    FunSpec({
        lateinit var app: TestApplication
        val loginTime = AtomicLong()
        val trustedOrigins = mutableSetOf(TEST_ORIGIN)
        beforeSpec {
            app = TestApplication.create(loginRateLimit = LoginRateLimit(nanoTime = loginTime::get), trustedOrigins = trustedOrigins)
            app.runtime.start()
            // Configure the script's same-origin URL once the runtime's ephemeral port is known.
            trustedOrigins += "http://127.0.0.1:${app.runtime.port()}"
        }
        afterSpec { app.close() }

        fun runScript(
            script: String = "replace-catalog.mjs",
            expectedExit: Int = 0,
            source: String? = null,
            input: String = "http://127.0.0.1:${app.runtime.port()}\nadmin\ntest-admin-password\n",
        ): String {
            // Model separate developer runs without waiting for real login-bucket replenishment.
            loginTime.addAndGet(Duration.ofMinutes(30).toNanos())
            val log = Files.createTempFile("fiona-script-", ".log")
            val edited = source?.let { Files.createTempFile("fiona-edited-catalog-", ".mjs").also { file -> Files.writeString(file, it) } }
            val builder =
                ProcessBuilder("node", (edited ?: Path.of("scripts", script)).toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
            if (script == "spoof-payment.mjs") {
                builder.environment().putAll(
                    mapOf(
                        "FIONAS_BASE_URL" to "http://127.0.0.1:${app.runtime.port()}",
                        "FIONAS_ORIGIN" to TEST_ORIGIN,
                        "FIONAS_ADMIN_USERNAME" to "admin",
                        "FIONAS_ADMIN_PASSWORD" to "test-admin-password",
                    ),
                )
            }
            val process = builder.start()
            try {
                process.outputStream.bufferedWriter().use { it.write(if (script == "spoof-payment.mjs") "" else input) }
                check(process.waitFor(45, TimeUnit.SECONDS)) { "Node script timed out: ${Files.readString(log)}" }
                val output = Files.readString(log)
                check(process.exitValue() == expectedExit) { "Node script exited ${process.exitValue()}: $output" }
                return output
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
                Files.deleteIfExists(log)
                edited?.let(Files::deleteIfExists)
            }
        }

        fun editedScript(vararg replacements: Pair<String, String>): String {
            var source = Files.readString(Path.of("scripts", "replace-catalog.mjs"))
            replacements.forEach { (original, replacement) ->
                check(source.contains(original)) { "Script fixture no longer contains $original" }
                source = source.replace(original, replacement)
            }
            return source
        }

        fun catalog() = CommerceJson.asA(app.adminGet("/offering-catalog").bodyString(), OfferingsCatalogDto.serializer())

        fun form(): InquiryFormResponse {
            val response = app.http(Request(Method.GET, "/inquiry-form").asFionasWeb(app))
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), InquiryFormResponse.serializer())
        }

        fun revision(response: Response): Int =
            CommerceJson.json
                .parseToJsonElement(response.bodyString())
                .jsonObject
                .getValue("revision")
                .jsonPrimitive.int

        val handKeys =
            listOf("chocolate-chip", "chocolate", "vanilla-bean", "strawberry", "butter-pecan", "mint-chip", "new-york-cheesecake")
                .map { "hand-scooped-$it" }
        val firstFour = handKeys.take(4)
        val businessTables =
            listOf(
                "fionas.customers",
                "fionas.inquiry_submissions",
                "fionas.inquiries",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
                "fionas.financial_document_pricing",
            )

        fun counts() = businessTables.associateWith(app.database::count)

        fun inputs(
            hand: List<String>? = firstFour,
            toppings: List<String> = TOPPINGS,
            softServe: List<String> = listOf("vanilla", "horchata"),
        ): String =
            CommerceJson.json.encodeToString(
                InquiryPricingInputs.serializer(),
                InquiryPricingInputs(
                    catalogRevision = catalog().revision,
                    guestCount = 75,
                    durationMinutes = 120,
                    selections =
                        listOf(PricingSelection("soft-serve-flavor", softServe)) +
                            (hand?.let { listOf(PricingSelection("hand-scooped-flavor", it)) } ?: emptyList()) +
                            listOf(PricingSelection("topping", toppings), PricingSelection("cone-option", listOf("waffle-cone"))),
                ),
            )

        fun preview(body: String) =
            app.http(
                Request(Method.POST, "/estimate-preview").asFionasWeb(app).header("Content-Type", "application/json").body(body),
            )

        fun submit(body: String) =
            app.http(
                Request(Method.POST, "/inquiries")
                    .asFionasWeb(app)
                    .withSubmissionKey()
                    .header("Content-Type", "application/json")
                    .body(
                        """{"name":"Jane","email":"hand-${UUID.randomUUID()}@example.com","zipCode":"92626",""" +
                            """"eventDate":"2026-12-05","eventType":"OTHER","pricingInputs":$body}""",
                    ),
            )

        // Browser arithmetic from wire metadata only; unpriced hand-scooped choices contribute zero.
        fun browserTotal(body: String): BigDecimal {
            val answers = CommerceJson.asA(body, InquiryPricingInputs.serializer())
            val definition = form()
            val facts = definition.pricingPreview
            val duration = facts.durationOptions.single { it.durationMinutes == answers.durationMinutes }
            val guests = answers.guestCount.toBigDecimal()
            val selected = answers.selections.flatMap { it.offerings }.toSet()
            val options =
                definition.sections
                    .flatMap { it.fields }
                    .mapNotNull { it.input as? InquiryFormInputResponse.OfferingChoice }
                    .flatMap { it.options }
            var total = BigDecimal(duration.baseServiceAmount) + BigDecimal(facts.perGuestAmount) * guests
            options.filter { it.key in selected }.forEach { offering ->
                offering.price?.let { price ->
                    total +=
                        when (price.kind) {
                            "FIXED" -> BigDecimal(price.amount)
                            "PER_QUANTITY" -> BigDecimal(price.amount) * guests
                            "PER_DURATION" -> BigDecimal(duration.offeringContributions.single { it.offeringKey == offering.key }.amount)
                            else -> error("Unknown price kind")
                        }
                }
            }
            val adjustment = facts.toppingAdjustment
            val toppingCount =
                answers.selections
                    .single { it.category == adjustment.category }
                    .offerings.size
            return total + (toppingCount - adjustment.includedSelections).coerceAtLeast(0).toBigDecimal() *
                guests * BigDecimal(adjustment.additionalSelectionPerGuestAmount)
        }

        test("actual bootstrap seeds one 19-item batch at revision 6 and projects all five CHIPS questions with option text") {
            val output = runScript()
            output shouldContain "Added 19 offerings in one batch: revision 6"
            output shouldContain "Catalog verified at revision 6"
            output shouldContain "Result: OK"
            output shouldContain "Base URL [http://localhost:8080]: "
            output shouldContain "Admin username: "
            output shouldContain "Admin password: "
            output shouldNotContain "test-admin-password"
            val seeded = catalog()
            seeded.revision shouldBe 6
            seeded.categories.map { it.key } shouldContainExactly
                listOf("soft-serve-flavor", "hand-scooped-flavor", "topping", "cone-option")
            seeded.categories.sumOf { it.offerings.size } shouldBe 19
            seeded.categories
                .flatMap { it.offerings }
                .map { it.key }
                .distinct()
                .size shouldBe 19

            val definition = form()
            definition.definitionVersion shouldBe 11
            definition.catalogRevision shouldBe 6
            val service = definition.sections.single { it.key == "service" }
            service.optional shouldBe false
            service.fields.map { it.key } shouldContainExactly
                listOf(
                    "guestCount",
                    "durationMinutes",
                    "offering:soft-serve-flavor",
                    "offering:hand-scooped-flavor",
                    "offering:topping",
                    "offering:cone-option",
                )
            service.fields.drop(1).map { it.presentation.control } shouldBe List(5) { InquiryFormControl.CHIPS }
            (service.fields[1].input is InquiryFormInputResponse.IntegerChoice) shouldBe true
            definition.sections
                .flatMap { it.fields }
                .single { it.key == "eventType" }
                .presentation.control shouldBe
                InquiryFormControl.SELECT
            val field = service.fields.single { it.key == "offering:hand-scooped-flavor" }
            field.label shouldBe "Choose your hand-scooped flavors"
            field.submissionPointer shouldBe "/pricingInputs/selections"
            field.required shouldBe true
            val hand = field.input as InquiryFormInputResponse.OfferingChoice
            hand.category shouldBe "hand-scooped-flavor"
            hand.minSelections shouldBe 4
            hand.maxSelections shouldBe 4
            hand.options.map { it.key } shouldContainExactly handKeys
            hand.options.map { it.displayName } shouldContainExactly
                listOf("Chocolate Chip", "Chocolate", "Vanilla Bean", "Strawberry", "Butter Pecan", "Mint Chip", "New York Cheesecake")
            hand.options.map { it.selectionState } shouldBe List(7) { OfferingSelectionStateDto.ENABLED }
            hand.options.map { it.availability } shouldContainExactly
                listOf("AVAILABLE", "AVAILABLE", "AVAILABLE", "AVAILABLE", "UNAVAILABLE", "AVAILABLE", "UNAVAILABLE")
                    .map(OfferingAvailabilityDto::valueOf)
            hand.options.map { it.price } shouldBe List(7) { null }
            hand.options[4].infoNote shouldBe "Contains tree nuts"
            hand.options[6].statusNote shouldBe "Back on the menu this fall!"
            hand.options[6].badge shouldBe "Returning soon"
            hand.options.map { it.badge } shouldBe List(6) { null } + "Returning soon"
            hand.options.map { it.statusNote } shouldBe List(6) { null } + "Back on the menu this fall!"
            hand.options.map { it.infoNote } shouldBe listOf(null, null, null, null, "Contains tree nuts", null, null)
            hand.options shouldBe seeded.categories.single { it.key == "hand-scooped-flavor" }.offerings
            val toppings = service.fields.single { it.key == "offering:topping" }.input as InquiryFormInputResponse.OfferingChoice
            toppings.minSelections shouldBe 4
            toppings.maxSelections shouldBe 6
            toppings.options.map { it.key } shouldContainExactly TOPPINGS + "chopped-peanuts"
            toppings.options.last().let {
                it.displayName shouldBe "Chopped Peanuts"
                it.infoNote shouldBe "Contains peanuts"
                it.selectionState shouldBe OfferingSelectionStateDto.ENABLED
                it.availability shouldBe OfferingAvailabilityDto.AVAILABLE
                it.price shouldBe null
            }
            counts().values.toSet() shouldBe setOf(0)
        }

        test("four available flavors and peanuts price and materialize without a surcharge; alternate fourth flavor works") {
            listOf(
                inputs(),
                inputs(hand = firstFour.take(3) + handKeys[5]),
                inputs(toppings = TOPPINGS.dropLast(1) + "chopped-peanuts"),
            ).forEach { body ->
                browserTotal(body).compareTo(BigDecimal("681.25")) shouldBe 0
                val result = preview(body)
                result.status shouldBe Status.OK
                CommerceJson.json
                    .parseToJsonElement(result.bodyString())
                    .jsonObject
                    .getValue("total")
                    .jsonPrimitive.content shouldBe
                    "681.25"
                val response = submit(body)
                response.status shouldBe Status.CREATED
                val inquiry = CommerceJson.asA(response.bodyString(), InquiryReceiptResponse.serializer())
                val document =
                    CommerceJson.asA(
                        app.adminGet("/financial-documents/${app.initialEstimateOf(inquiry.id)}").bodyString(),
                        FinancialDocumentResponse.serializer(),
                    )
                document.total shouldBe "681.25"
                document.version shouldBe 1
            }
        }

        test("wrong flavor counts, unavailable flavors, missing selections, and seven toppings reject with zero business writes") {
            listOf(
                inputs(hand = firstFour.take(3)) to "TOO_FEW_SELECTIONS",
                inputs(hand = firstFour + handKeys[5]) to "TOO_MANY_SELECTIONS",
                inputs(hand = null) to "TOO_FEW_SELECTIONS",
                inputs(hand = firstFour.take(3) + handKeys[4]) to "OFFERING_UNAVAILABLE",
                inputs(hand = firstFour.take(3) + handKeys[6]) to "OFFERING_UNAVAILABLE",
                inputs(softServe = emptyList()) to "TOO_FEW_SELECTIONS",
                inputs(toppings = TOPPINGS + "chopped-peanuts") to "TOO_MANY_SELECTIONS",
            ).forEach { (body, code) ->
                val before = counts()
                listOf(preview(body), submit(body)).forEach { response ->
                    response.status shouldBe Status.UNPROCESSABLE_ENTITY
                    CommerceJson.asA(response.bodyString(), ValidationErrorResponse.serializer()).violations!!.map { it.code } shouldBe
                        listOf(code)
                }
                counts() shouldBe before
            }
        }

        test("replacement needs only catalog permissions and the payment smoke still completes") {
            val before = catalog()
            val beforeCounts = counts()
            val grants = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, setOf(CommercePermissions.OfferingsManage))
            try {
                runScript() shouldContain "Restored 19 offerings in one batch"
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
            }
            val replaced = catalog()
            replaced.catalogId shouldBe before.catalogId
            replaced.revision shouldBe before.revision + 10
            replaced.categories shouldBe before.categories
            counts() shouldBe beforeCounts
            runScript() shouldContain "Catalog verified at revision ${replaced.revision + 10}"
            catalog().categories shouldBe before.categories
            runScript("spoof-payment.mjs") shouldContain "Result: PAID IN FULL"
        }

        test("invalid or incomplete prompted input and incorrect passwords leave the catalog unchanged") {
            val before = catalog()
            val beforeCounts = counts()
            listOf(
                "not-a-url\n" to "Base URL must be a valid http:// or https:// URL",
                "ftp://localhost\n" to "Base URL must use http:// or https://",
                "http://127.0.0.1:${app.runtime.port()}\n   \n" to "Admin username must not be blank",
                "http://127.0.0.1:${app.runtime.port()}\nadmin\n   \n" to "Admin password must not be blank",
                "http://127.0.0.1:${app.runtime.port()}\nadmin\n" to "Input ended before all connection details were supplied",
                "http://127.0.0.1:${app.runtime.port()}\nadmin\ntest-admin-password \n" to "POST /auth/login returned 401",
            ).forEach { (input, message) ->
                val output = runScript(input = input, expectedExit = 1)
                output shouldContain message
                output shouldNotContain "test-admin-password"
                catalog() shouldBe before
                counts() shouldBe beforeCounts
            }
        }

        test("retired hand-scooped category omits its question and restoring the category and offerings restores its CHIPS and notes") {
            val before = catalog()
            val original = before.categories.single { it.key == "hand-scooped-flavor" }.offerings
            val retired =
                app.adminPost(
                    "/offering-catalog/offerings/retire",
                    """{"expectedRevision":${before.revision},"keys":[${handKeys.joinToString(",") { "\"$it\"" }}]}""",
                )
            retired.status shouldBe Status.OK
            val removed =
                app.adminRequest(Method.DELETE, "/offering-catalog/categories/hand-scooped-flavor?expectedRevision=${revision(retired)}")
            removed.status shouldBe Status.OK
            form().sections.flatMap { it.fields }.any { it.key == "offering:hand-scooped-flavor" } shouldBe false
            val restored =
                app.adminPost(
                    "/offering-catalog/categories/hand-scooped-flavor/restore",
                    """{"expectedRevision":${revision(
                        removed,
                    )},"displayName":"Hand-Scooped flavors","minimumSelections":4,"maximumSelections":4}""",
                )
            restored.status shouldBe Status.OK
            app
                .adminPost(
                    "/offering-catalog/offerings/restore",
                    CommerceJson.json.encodeToString(OfferingsBatchDto.serializer(), OfferingsBatchDto(revision(restored), original)),
                ).status shouldBe Status.OK
            val field = form().sections.flatMap { it.fields }.single { it.key == "offering:hand-scooped-flavor" }
            field.presentation.control shouldBe InquiryFormControl.CHIPS
            field.required shouldBe true
            (field.input as InquiryFormInputResponse.OfferingChoice).options shouldBe original
        }

        test("edited definitions replace properties and order, add mixed new keys, and retire entries omitted on the next run") {
            runScript()
            val original = catalog()
            val beforeCounts = counts()
            val changed =
                editedScript(
                    "{ key: \"soft-serve-flavor\", displayName: \"Soft Serve\"" to
                        "{ key: \"seasonal\", displayName: \"Seasonal\", maximumSelections: 2 },\n" +
                        "    { key: \"soft-serve-flavor\", description: \"Development menu\", displayName: \"New Soft Serve\"",
                    "{ key: \"vanilla\", category: \"soft-serve-flavor\", displayName: \"Vanilla\" }," to
                        "{ key: \"vanilla\", category: \"soft-serve-flavor\", displayName: \"Dev Vanilla\", " +
                        "description: \"Updated flavor\", price: { kind: \"FIXED\", amount: \"12.50\", currency: \"USD\" }, " +
                        "selectionState: \"DISABLED\", availability: \"UNAVAILABLE\", " +
                        "badge: \"Special\", statusNote: \"Ask staff\", infoNote: \"New recipe\" },\n" +
                        "    { key: \"new-flavor\", category: \"soft-serve-flavor\", displayName: \"New Flavor\" },",
                    "key: \"horchata\", category: \"soft-serve-flavor\"" to
                        "key: \"seasonal-special\", category: \"seasonal\"",
                    "{ key: \"cone-option\", displayName: \"Cones\", minimumSelections: 1, maximumSelections: 1 }" to
                        "{ key: \"cone-option\", displayName: \"Containers\", description: \"Pick a container\", " +
                        "minimumSelections: 0, maximumSelections: 2 }",
                )
            runScript(source = changed) shouldContain "Result: OK"
            val edited = catalog()
            edited.catalogId shouldBe original.catalogId
            edited.categories.map { it.key } shouldContainExactly
                listOf("seasonal", "soft-serve-flavor", "hand-scooped-flavor", "topping", "cone-option")
            val soft = edited.categories.single { it.key == "soft-serve-flavor" }
            soft.displayName shouldBe "New Soft Serve"
            soft.description shouldBe "Development menu"
            soft.offerings.map { it.key } shouldContainExactly listOf("vanilla", "new-flavor", "chocolate")
            soft.offerings.first() shouldBe
                original.categories.first().offerings.first().copy(
                    displayName = "Dev Vanilla",
                    description = "Updated flavor",
                    price = OfferingPriceDto("FIXED", "12.50", "USD"),
                    selectionState = OfferingSelectionStateDto.DISABLED,
                    availability = OfferingAvailabilityDto.UNAVAILABLE,
                    badge = "Special",
                    statusNote = "Ask staff",
                    infoNote = "New recipe",
                )
            edited.categories.single { it.key == "cone-option" }.let {
                it.displayName shouldBe "Containers"
                it.description shouldBe "Pick a container"
                it.minimumSelections shouldBe 0
                it.maximumSelections shouldBe 2
            }
            val retired =
                CommerceJson.asA(
                    app.adminGet("/offering-catalog/retired/offerings").bodyString(),
                    RetiredOfferingsDto.serializer(),
                )
            retired.offerings.map { it.offering.key } shouldContainExactly listOf("horchata")

            // Reverting the source restores horchata, clears all vanilla metadata and removes the extra category/options.
            runScript() shouldContain "Result: OK"
            catalog().categories shouldBe original.categories
            CommerceJson
                .asA(app.adminGet("/offering-catalog/retired/categories").bodyString(), RetiredCategoriesDto.serializer())
                .categories
                .map { it.category.key } shouldContainExactly listOf("seasonal")
            CommerceJson
                .asA(app.adminGet("/offering-catalog/retired/offerings").bodyString(), RetiredOfferingsDto.serializer())
                .offerings
                .map { it.offering.key } shouldContainExactly listOf("new-flavor", "seasonal-special")

            // Keys omitted on one run can be restored with a later edit, in exactly the same mixed order.
            runScript(source = changed) shouldContain "Restored 20 offerings in one batch"
            catalog().categories shouldBe edited.categories
            runScript() shouldContain "Result: OK"
            counts() shouldBe beforeCounts
        }

        test("a rerun recovers a partially written catalog after a later offering batch fails") {
            val original = catalog()
            val beforeCounts = counts()
            val invalid =
                editedScript(
                    "{ key: \"chocolate\", category: \"soft-serve-flavor\", displayName: \"Chocolate\" }," to
                        "{ key: \"bad-flavor\", category: \"missing-category\", displayName: \"Bad Flavor\" },\n" +
                        "    { key: \"chocolate\", category: \"soft-serve-flavor\", displayName: \"Chocolate\" },",
                )
            runScript(source = invalid, expectedExit = 1) shouldContain "POST /offering-catalog/offerings returned 404"
            val partial = catalog()
            partial.categories.map { it.key } shouldBe original.categories.map { it.key }
            partial.categories.flatMap { it.offerings }.map { it.key } shouldBe listOf("vanilla")
            runScript() shouldContain "Result: OK"
            catalog().catalogId shouldBe original.catalogId
            catalog().categories shouldBe original.categories
            counts() shouldBe beforeCounts
        }
    })
