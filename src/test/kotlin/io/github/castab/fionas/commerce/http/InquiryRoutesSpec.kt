package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.withUiKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** The inquiry API through the complete fionas-commerce HTTP handler, runtime error handling included. */
class InquiryRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        fun TestApplication.post(body: String) =
            http(Request(Method.POST, "/inquiries").withUiKey().header("Content-Type", "application/json").body(body))

        fun post(body: String) = application.post(body)

        fun anonymousGet(path: String) = application.http(Request(Method.GET, path))

        fun Response.receipt() = CommerceJson.asA(bodyString(), InquiryReceiptResponse.serializer())

        fun Response.inquiry() = CommerceJson.asA(bodyString(), InquiryResponse.serializer())

        fun Response.list() = CommerceJson.asA(bodyString(), InquiryListResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun Response.keys() = Json.parseToJsonElement(bodyString()).jsonObject.keys

        fun rows() =
            listOf(
                "fionas.customers",
                "fionas.inquiries",
                "fionas.inquiry_pricing",
                "fionas.inquiry_pricing_selections",
            ).map(application.database::count)

        /** Runs [block] while the bootstrap administrator holds only [permissions], then restores the Administrator role. */
        fun <T> TestApplication.asStaffWith(
            permissions: Set<PermissionKey>,
            block: () -> T,
        ): T {
            val admin = checkNotNull(authorization.findUserByUsername("admin"))
            val role = RoleKey("fionas.test.restricted-${UUID.randomUUID()}")
            authorization.createRole(RoleDefinition(role, "Restricted staff", null, permissions))
            authorization.unassignRole(admin.id, CommerceRoles.Administrator)
            authorization.assignRole(admin.id, role)
            try {
                return block()
            } finally {
                authorization.unassignRole(admin.id, role)
                authorization.assignRole(admin.id, CommerceRoles.Administrator)
            }
        }

        fun inquiryBody(
            email: String,
            name: String = "Jane Doe",
            extra: String = "",
            zipCode: String = "92626",
            eventDate: String = "2026-12-05",
            eventType: String = "BIRTHDAY",
        ) = """{"name":"$name","email":"$email","zipCode":"$zipCode","eventDate":"$eventDate","eventType":"$eventType"$extra}"""

        // POST /inquiries: public, and a receipt of the new inquiry only.

        test("every event type and a leap-day date survive detail and inbox reads without entering customer data") {
            val email = "event-${UUID.randomUUID()}@example.com"
            var customerId: String? = null
            InquiryEventType.entries.forEach { type ->
                val response = post(inquiryBody(email, eventDate = "2028-02-29", eventType = type.name))
                response.status shouldBe Status.CREATED
                response.keys() shouldBe setOf("id", "createdAt")
                val read = application.adminGet("/inquiries/${response.receipt().id}").inquiry()
                read.eventDate shouldBe "2028-02-29"
                read.eventType shouldBe type
                customerId?.let { read.customerId shouldBe it }
                customerId = read.customerId
                val item =
                    application
                        .adminGet("/inquiries")
                        .list()
                        .inquiries
                        .single { it.id == read.id }
                item.eventDate shouldBe read.eventDate
                item.eventType shouldBe type
            }
        }

        test("invalid calendar dates reject submission without recording customer or inquiry") {
            listOf(
                "",
                "2026-02-29",
                "2026-04-31",
                "0000-01-01",
                "10000-01-01",
                "2026-1-2",
                " 2026-12-05 ",
                "2026-12-05T12:00:00Z",
                "12/05/2026",
            ).forEach { date ->
                val before = rows()
                val response = post(inquiryBody("invalid-event-${UUID.randomUUID()}@example.com", eventDate = date))
                response.status shouldBe Status.UNPROCESSABLE_ENTITY
                response.error().code shouldBe "validation_failed"
                rows() shouldBe before
            }
        }

        test("required event fields reject missing, null, wrong-type and unknown selection values") {
            val body = inquiryBody("missing-event@example.com")
            val before = rows()
            listOf(
                body.replace(",\"eventDate\":\"2026-12-05\"", ""),
                body.replace("\"eventDate\":\"2026-12-05\"", "\"eventDate\":null"),
                body.replace("\"eventDate\":\"2026-12-05\"", "\"eventDate\":20261205"),
                body.replace(",\"eventType\":\"BIRTHDAY\"", ""),
                body.replace("\"eventType\":\"BIRTHDAY\"", "\"eventType\":null"),
                body.replace("\"eventType\":\"BIRTHDAY\"", "\"eventType\":1"),
                body.replace("BIRTHDAY", "UNKNOWN"),
                body.replace("BIRTHDAY", "Birthday"),
            ).forEach { submitted ->
                post(submitted).let {
                    it.status shouldBe Status.BAD_REQUEST
                    it.error().code shouldBe "malformed_request"
                }
                rows() shouldBe before
            }
        }

        test("event ZIP codes belong to each inquiry, survive staff reads and never expose a customer to public callers") {
            val email = "zip-${UUID.randomUUID()}@example.com"
            val first = post(inquiryBody(email, zipCode = " 02108 "))
            first.status shouldBe Status.CREATED
            first.keys() shouldBe setOf("id", "createdAt")
            val second = post(inquiryBody(email, zipCode = "92626"))
            second.status shouldBe Status.CREATED
            val firstRead = application.adminGet("/inquiries/${first.receipt().id}").inquiry()
            val secondRead = application.adminGet("/inquiries/${second.receipt().id}").inquiry()
            firstRead.zipCode shouldBe "02108"
            secondRead.zipCode shouldBe "92626"
            firstRead.customerId shouldBe secondRead.customerId
            val inbox =
                application
                    .adminGet("/inquiries")
                    .list()
                    .inquiries
                    .associateBy { it.id }
            inbox.getValue(firstRead.id).zipCode shouldBe "02108"
            inbox.getValue(secondRead.id).zipCode shouldBe "92626"
            anonymousGet("/inquiries/${firstRead.id}").status shouldBe Status.UNAUTHORIZED
        }

        test("blank or invalid ZIP rejects submission without writing anything") {
            listOf("", "   ", "1234", "123456", "12a45", "12345-6789", "１２３４５").forEach { invalid ->
                val before = rows()
                val response = post(inquiryBody("bad-zip-${UUID.randomUUID()}@example.com", zipCode = invalid))
                response.status shouldBe Status.UNPROCESSABLE_ENTITY
                response.error() shouldBe ErrorResponse("validation_failed", "ZIP code must contain exactly five digits")
                rows() shouldBe before
            }
        }

        test("missing, null or numeric ZIP is a malformed request and writes nothing") {
            val before = rows()
            listOf(
                """{"name":"Jane","email":"missing-zip@example.com","eventDate":"2026-12-05","eventType":"BIRTHDAY"}""",
                """{"name":"Jane","email":"null-zip@example.com","eventDate":"2026-12-05","eventType":"BIRTHDAY","zipCode":null}""",
                """{"name":"Jane","email":"numeric-zip@example.com","eventDate":"2026-12-05","eventType":"BIRTHDAY","zipCode":2108}""",
            ).forEach { body ->
                post(body).let {
                    it.status shouldBe Status.BAD_REQUEST
                    it.error().code shouldBe "malformed_request"
                }
            }
            rows() shouldBe before
        }

        test("POST /inquiries records the inquiry and answers a receipt of it, with its location") {
            val email = "jane-${UUID.randomUUID()}@example.com"

            val response =
                post(
                    inquiryBody(
                        email.uppercase(),
                        name = " Jane Doe ",
                        extra = ""","message":"I'm interested in ice cream service for a birthday."""",
                    ),
                )

            response.status shouldBe Status.CREATED
            response.keys() shouldBe setOf("id", "createdAt")
            val receipt = response.receipt()
            receipt.createdAt shouldBe STORED_INSTANT.toString()
            response.header("Location") shouldBe "/inquiries/${receipt.id}"
            application.adminGet("/inquiries/${receipt.id}").inquiry().let {
                it.id shouldBe receipt.id
                it.name shouldBe "Jane Doe"
                it.email shouldBe email
                it.message shouldBe "I'm interested in ice cream service for a birthday."
                it.createdAt shouldBe receipt.createdAt
            }
        }

        test("a public submission with an existing customer's email never reveals that customer's stored record") {
            val email = "known-${UUID.randomUUID()}@example.com"
            val stored = "Alice Stored-Name"
            val first = post(inquiryBody(email, name = stored)).receipt()
            val storedCustomerId = application.adminGet("/inquiries/${first.id}").inquiry().customerId

            val probe = post(inquiryBody(" ${email.uppercase()} ", name = "Bob Probe"))

            probe.status shouldBe Status.CREATED
            probe.keys() shouldBe setOf("id", "createdAt")
            probe.bodyString() shouldNotContain stored
            probe.bodyString() shouldNotContain storedCustomerId
            probe.bodyString() shouldNotContain email
            probe.header("Location") shouldBe "/inquiries/${probe.receipt().id}"
            // The customer-matching policy is unchanged: the inquiry is the stored customer's, whose name is kept.
            application.adminGet("/inquiries/${probe.receipt().id}").inquiry().let {
                it.customerId shouldBe storedCustomerId
                it.name shouldBe stored
            }
        }

        test("invalid values are a validation failure, and nothing is recorded") {
            val before = rows()

            post(inquiryBody("not-an-email", name = "Jane")).let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error() shouldBe ErrorResponse("validation_failed", "Email must contain exactly one @ after a non-empty local part")
            }
            post(inquiryBody("jane@example.com", name = "   ")).let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error() shouldBe ErrorResponse("validation_failed", "Name must not be blank")
            }
            post(inquiryBody("jane@example.com", extra = ""","message":"${"x".repeat(4001)}"""")).error().code shouldBe
                "validation_failed"

            rows() shouldBe before
        }

        test("an unreadable body is a malformed request, and nothing is recorded") {
            val before = rows()

            post("""{"email":"jane@example.com"}""").let {
                it.status shouldBe Status.BAD_REQUEST
                it.error().code shouldBe "malformed_request"
            }
            post("""{not json""").error().code shouldBe "malformed_request"
            post(inquiryBody("jane@example.com", extra = ""","pricingInputs":{"catalogRevision":$revision}""")).let {
                it.status shouldBe Status.BAD_REQUEST
                it.error().code shouldBe "malformed_request"
            }

            rows() shouldBe before
        }

        // GET /inquiries/{inquiryId}: staff only.

        test("reading an inquiry requires a staff session holding fionas.inquiries.read") {
            val id = post(inquiryBody("guarded-${UUID.randomUUID()}@example.com")).receipt().id
            val path = "/inquiries/$id"

            anonymousGet(path).let {
                it.status shouldBe Status.UNAUTHORIZED
                it.error().code shouldBe "unauthenticated"
                it.bodyString() shouldNotContain "Jane"
            }
            // An anonymous caller learns nothing, not even whether the id is well formed or known.
            anonymousGet("/inquiries/not-a-uuid").status shouldBe Status.UNAUTHORIZED
            anonymousGet("/inquiries/${UUID.randomUUID()}").status shouldBe Status.UNAUTHORIZED
            application.asStaffWith(setOf(CommercePermissions.FinancialDocumentRead, CommercePermissions.FinancialDocumentCreate)) {
                application.adminGet(path).let {
                    it.status shouldBe Status.FORBIDDEN
                    it.error().code shouldBe "forbidden"
                }
            }
            application.asStaffWith(setOf(FionaPermissions.InquiriesRead)) {
                application.adminGet(path).status shouldBe Status.OK
            }
            application.adminGet(path).let {
                it.status shouldBe Status.OK
                it.inquiry().id shouldBe id
            }
        }

        test("GET /inquiries/{id} returns the persisted inquiry, without absent properties") {
            val id = post(inquiryBody("ada-${UUID.randomUUID()}@example.com", name = "Ada")).receipt().id

            val response = application.adminGet("/inquiries/$id")

            response.status shouldBe Status.OK
            response.inquiry().let {
                it.id shouldBe id
                it.name shouldBe "Ada"
                it.message.shouldBeNull()
                it.pricingInputs.shouldBeNull()
            }
            response.keys() shouldBe setOf("id", "customerId", "name", "email", "createdAt", "zipCode", "eventDate", "eventType")
        }

        test("an authorized read of an unknown inquiry is not found, and a malformed id a malformed request") {
            val missing = UUID.randomUUID()

            application.adminGet("/inquiries/$missing").let {
                it.status shouldBe Status.NOT_FOUND
                it.error() shouldBe ErrorResponse("not_found", "Inquiry $missing was not found")
            }
            application.adminGet("/inquiries/not-a-uuid").let {
                it.status shouldBe Status.BAD_REQUEST
                it.error() shouldBe ErrorResponse("malformed_request", "Malformed request: path 'inquiryId'")
            }
        }

        // The configured request survives the handoff to staff.

        test("a plain contact inquiry without pricing inputs is still recorded") {
            val before = application.database.count("fionas.inquiry_pricing")

            val response = post(inquiryBody("plain-${UUID.randomUUID()}@example.com", extra = ""","message":"Just a question.""""))

            response.status shouldBe Status.CREATED
            application
                .adminGet("/inquiries/${response.receipt().id}")
                .inquiry()
                .pricingInputs
                .shouldBeNull()
            application.database.count("fionas.inquiry_pricing") shouldBe before
        }

        test("the configured pricing inputs survive persistence and are returned to staff exactly as submitted") {
            val inputs =
                pricingBody(revision, guests = 120, minutes = 180, toppings = TOPPINGS.reversed())
                    .replace("\"guestCount\":120", "\"guestCount\":120,\"guestCountIsMinimum\":true")

            val response = post(inquiryBody("configured-${UUID.randomUUID()}@example.com", extra = ""","pricingInputs":$inputs"""))

            response.status shouldBe Status.CREATED
            response.keys() shouldBe setOf("id", "createdAt")
            application.adminGet("/inquiries/${response.receipt().id}").inquiry().pricingInputs shouldBe
                InquiryRequestedPricing(
                    catalogRevision = revision,
                    guestCount = 120,
                    guestCountIsMinimum = true,
                    durationMinutes = 180,
                    selections =
                        listOf(
                            PricingSelection("soft-serve-flavor", listOf("vanilla", "horchata")),
                            PricingSelection("topping", TOPPINGS.reversed()),
                            PricingSelection("cone-option", listOf("waffle-cone")),
                        ),
                )
        }

        test("the requested catalog revision is pinned: a later catalog revision never replaces it") {
            val response =
                post(inquiryBody("pinned-${UUID.randomUUID()}@example.com", extra = ""","pricingInputs":${pricingBody(revision)}"""))

            val later = application.addOffering(revision, "pistachio", "soft-serve-flavor", "Pistachio")

            later shouldBe revision + 1
            application
                .adminGet("/inquiries/${response.receipt().id}")
                .inquiry()
                .pricingInputs
                ?.catalogRevision shouldBe revision
            revision = later
        }

        test("pricing inputs the pricing rejects fail exactly as an estimate preview fails, and nothing is recorded") {
            val before = rows()
            listOf(
                pricingBody(revision, guests = 0),
                pricingBody(revision, minutes = 45),
                pricingBody(revision, toppings = TOPPINGS + "sprinkles"),
                pricingBody(revision, cones = listOf("no-such-cone")),
                pricingBody(999),
            ).forEach { inputs ->
                val preview =
                    application.http(
                        Request(Method.POST, "/estimate-preview").withUiKey().header("Content-Type", "application/json").body(inputs),
                    )
                val submitted = post(inquiryBody("rejected-${UUID.randomUUID()}@example.com", extra = ""","pricingInputs":$inputs"""))

                preview.status.successful shouldBe false
                submitted.status shouldBe preview.status
                submitted.error() shouldBe preview.error()
            }
            post(inquiryBody("rejected@example.com", extra = ""","pricingInputs":${pricingBody(999)}""")).error().code shouldBe "not_found"

            rows() shouldBe before
        }

        test("client-calculated amounts are never accepted: they are ignored, and only the inputs are recorded") {
            val forged = ""","lines":[{"description":"Everything","unitPrice":"1.00"}],"subtotal":"1.00","total":"1.00""""
            val inputs = pricingBody(revision, extra = forged)

            val response =
                post(inquiryBody("forged-${UUID.randomUUID()}@example.com", extra = ""","total":"1.00","pricingInputs":$inputs"""))

            response.status shouldBe Status.CREATED
            val read = application.adminGet("/inquiries/${response.receipt().id}")
            Json
                .parseToJsonElement(read.bodyString())
                .jsonObject
                .getValue("pricingInputs")
                .jsonObject.keys shouldBe
                setOf("catalogRevision", "guestCount", "guestCountIsMinimum", "durationMinutes", "selections")
            read.bodyString() shouldNotContain "1.00"
        }

        test("a returning customer's new request keeps its own inputs, and the earlier one is unchanged") {
            val email = "repeat-${UUID.randomUUID()}@example.com"
            val first = post(inquiryBody(email, extra = ""","pricingInputs":${pricingBody(revision, guests = 50)}""")).receipt()
            val firstInputs = application.adminGet("/inquiries/${first.id}").inquiry().pricingInputs

            val second =
                post(
                    inquiryBody(
                        email,
                        name = "Someone Else",
                        extra = ""","pricingInputs":${pricingBody(revision, guests = 200, minutes = 90)}""",
                    ),
                ).receipt()

            val secondRead = application.adminGet("/inquiries/${second.id}").inquiry()
            val firstRead = application.adminGet("/inquiries/${first.id}").inquiry()
            secondRead.customerId shouldBe firstRead.customerId
            firstRead.pricingInputs shouldBe firstInputs
            firstRead.pricingInputs?.guestCount shouldBe 50
            secondRead.pricingInputs?.guestCount shouldBe 200
            secondRead.pricingInputs?.durationMinutes shouldBe 90
        }

        test("preview → public inquiry with the same inputs → staff read → an estimate priced as the preview, with nothing re-entered") {
            val inputs = pricingBody(revision)
            val preview =
                application.http(
                    Request(Method.POST, "/estimate-preview").withUiKey().header("Content-Type", "application/json").body(inputs),
                )
            preview.status shouldBe Status.OK
            val previewTotal = CommerceJson.asA(preview.bodyString(), EstimatePreviewResponse.serializer()).total
            val documents = application.database.count("commerce.financial_document_snapshots")

            val receipt =
                post(inquiryBody("handoff-${UUID.randomUUID()}@example.com", extra = ""","message":"A birthday","pricingInputs":$inputs"""))
                    .receipt()

            // Recording an inquiry creates no financial document.
            application.database.count("commerce.financial_document_snapshots") shouldBe documents
            // Staff start the estimate from the recorded inputs exactly as read, without reconstructing them.
            val requested =
                Json
                    .parseToJsonElement(application.adminGet("/inquiries/${receipt.id}").bodyString())
                    .jsonObject
                    .getValue("pricingInputs")
                    .toString()
            val estimate = application.adminPost("/inquiries/${receipt.id}/estimates", requested)
            estimate.status shouldBe Status.CREATED
            val document = CommerceJson.asA(estimate.bodyString(), FinancialDocumentResponse.serializer())
            document.total shouldBe previewTotal
            document.pricing.catalogRevision shouldBe revision
            CommerceJson.asA(requested, InquiryRequestedPricing.serializer()).let {
                document.pricing shouldBe
                    DocumentPricing(it.catalogRevision, it.guestCount, it.guestCountIsMinimum, it.durationMinutes, it.selections)
            }
        }

        // GET /inquiries: the staff inbox.

        test("listing inquiries requires a staff session holding fionas.inquiries.read") {
            listOf("/inquiries", "/inquiries?limit=5", "/inquiries?limit=abc", "/inquiries?cursor=garbage").forEach { path ->
                anonymousGet(path).let {
                    it.status shouldBe Status.UNAUTHORIZED
                    it.error().code shouldBe "unauthenticated"
                }
            }
            application.asStaffWith(setOf(CommercePermissions.FinancialDocumentRead, CommercePermissions.PaymentRecord)) {
                application.adminGet("/inquiries").let {
                    it.status shouldBe Status.FORBIDDEN
                    it.error().code shouldBe "forbidden"
                }
            }
            application.asStaffWith(setOf(FionaPermissions.InquiriesRead)) {
                application.adminGet("/inquiries").status shouldBe Status.OK
            }
            application.adminGet("/inquiries").status shouldBe Status.OK
        }

        test("an Administrator role from an earlier release gains inquiry reads through the complete-set grant") {
            TestApplication.create().use { app ->
                val upgraded = checkNotNull(app.authorization.getRole(CommerceRoles.Administrator)).permissions
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, upgraded - FionaPermissions.InquiriesRead)
                app.adminGet("/inquiries").status shouldBe Status.FORBIDDEN

                // The documented upgrade: read the current grants, add fionas.inquiries.read, submit the complete set.
                val current =
                    Json
                        .parseToJsonElement(app.adminGet("/admin/access/roles/commerce.administrator").bodyString())
                        .jsonObject
                        .getValue("permissions")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                val desired = JsonArray((current + FionaPermissions.InquiriesRead.value).map(::JsonPrimitive))
                app
                    .adminRequest(Method.PUT, "/admin/access/roles/commerce.administrator/permissions", """{"permissions":$desired}""")
                    .status.successful shouldBe true

                app.adminGet("/inquiries").status shouldBe Status.OK
                app.authorization.getRole(CommerceRoles.Administrator)?.permissions shouldBe upgraded
            }
        }

        test("with no inquiries the list is empty and has no next page") {
            TestApplication.create().use { app ->
                val response = app.adminGet("/inquiries")

                response.status shouldBe Status.OK
                response.list().inquiries.shouldBeEmpty()
                response.list().nextCursor.shouldBeNull()
                response.keys() shouldBe setOf("inquiries")
            }
        }

        test("inquiries are listed newest first, a bounded page at a time, with the default and maximum page sizes") {
            val clock = SteppingClock(Instant.parse("2026-10-01T12:00:00Z"))
            TestApplication.create(clock = clock).use { app ->
                val ids =
                    List(105) { index ->
                        clock.now = Instant.parse("2026-10-01T12:00:00Z").plus(Duration.ofMinutes(index.toLong()))
                        app.post(inquiryBody("inbox-$index@example.com", name = "Customer $index")).receipt().id
                    }
                val newestFirst = ids.reversed()

                val first = app.adminGet("/inquiries")
                first.status shouldBe Status.OK
                first.list().inquiries.map { it.id } shouldContainExactly newestFirst.take(25)
                first.list().nextCursor.shouldNotBeNull()
                first.list().inquiries.first().let {
                    it.name shouldBe "Customer 104"
                    it.email shouldBe "inbox-104@example.com"
                    it.createdAt shouldBe "2026-10-01T13:44:00Z"
                }

                app.adminGet("/inquiries?limit=100").list().let {
                    it.inquiries.map { item -> item.id } shouldContainExactly newestFirst.take(100)
                    it.nextCursor.shouldNotBeNull()
                }
                app
                    .adminGet("/inquiries?limit=1")
                    .list()
                    .inquiries
                    .map { it.id } shouldContainExactly newestFirst.take(1)

                // Walking every page visits every inquiry once, newest first.
                val walked = mutableListOf<String>()
                var cursor: String? = null
                var pages = 0
                do {
                    val page = app.adminGet("/inquiries?limit=7" + (cursor?.let { "&cursor=$it" } ?: "")).list()
                    page.inquiries shouldHaveSize 7
                    walked += page.inquiries.map { it.id }
                    cursor = page.nextCursor
                    pages++
                } while (cursor != null)
                pages shouldBe 15
                walked shouldContainExactly newestFirst

                // A page exactly at the end has no next page.
                app.adminGet("/inquiries?limit=100&cursor=${app.adminGet("/inquiries?limit=5").list().nextCursor}").list().let {
                    it.inquiries.map { item -> item.id } shouldContainExactly newestFirst.subList(5, 105)
                    it.nextCursor.shouldBeNull()
                }
            }
        }

        test("inquiries recorded at the same instant are neither repeated nor skipped across pages") {
            val clock = SteppingClock(Instant.parse("2026-10-02T09:00:00Z"))
            TestApplication.create(clock = clock).use { app ->
                val older = app.post(inquiryBody("older@example.com")).receipt().id
                clock.now = Instant.parse("2026-10-02T09:30:00.123456Z")
                val tied = List(9) { app.post(inquiryBody("tied-$it@example.com")).receipt().id }
                clock.now = Instant.parse("2026-10-02T10:00:00Z")
                val newer = app.post(inquiryBody("newer@example.com")).receipt().id

                val walked = mutableListOf<String>()
                var cursor: String? = null
                do {
                    val page = app.adminGet("/inquiries?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")).list()
                    walked += page.inquiries.map { it.id }
                    cursor = page.nextCursor
                } while (cursor != null)

                walked.shouldBeUnique()
                walked shouldHaveSize 11
                walked.first() shouldBe newer
                walked.last() shouldBe older
                // Ties follow PostgreSQL's uuid order, descending: the canonical text, descending.
                walked.subList(1, 10) shouldContainExactly tied.sortedDescending()
            }
        }

        test("an inquiry recorded while staff page through the list never shifts a later page") {
            val clock = SteppingClock(Instant.parse("2026-10-03T09:00:00Z"))
            TestApplication.create(clock = clock).use { app ->
                val ids =
                    List(6) { index ->
                        clock.now = Instant.parse("2026-10-03T09:00:00Z").plusSeconds(index.toLong())
                        app.post(inquiryBody("stable-$index@example.com")).receipt().id
                    }.reversed()
                val first = app.adminGet("/inquiries?limit=3").list()

                clock.now = Instant.parse("2026-10-03T10:00:00Z")
                app.post(inquiryBody("arrived-later@example.com"))

                app
                    .adminGet("/inquiries?limit=3&cursor=${first.nextCursor}")
                    .list()
                    .inquiries
                    .map { it.id } shouldContainExactly
                    ids.subList(3, 6)
            }
        }

        test("a limit outside 1 to 100 is a validation failure, and one that is not an integer a malformed request") {
            listOf("0", "-1", "101").forEach { limit ->
                application.adminGet("/inquiries?limit=$limit").let {
                    it.status shouldBe Status.UNPROCESSABLE_ENTITY
                    it.error() shouldBe ErrorResponse("validation_failed", "limit must be between 1 and 100")
                }
            }
            listOf("abc", "2.5").forEach { limit ->
                application.adminGet("/inquiries?limit=$limit").let {
                    it.status shouldBe Status.BAD_REQUEST
                    it.error() shouldBe ErrorResponse("malformed_request", "Malformed request: query 'limit'")
                }
            }
        }

        test("a cursor the API did not issue is a malformed request") {
            fun encoded(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
            listOf(
                "garbage!",
                encoded("not a position"),
                encoded("2026-10-01T12:00:00Z|not-a-uuid"),
                encoded("yesterday|${UUID.randomUUID()}"),
                encoded("2026-10-01T12:00:00Z|${UUID.randomUUID()}|extra"),
            ).forEach { cursor ->
                application.adminGet("/inquiries?cursor=$cursor").let {
                    it.status shouldBe Status.BAD_REQUEST
                    it.error() shouldBe ErrorResponse("malformed_request", "Malformed request: query 'cursor'")
                }
            }
        }

        // The contract leaves every error body to commerce-runtime.

        test("inputs the contract cannot read keep commerce-runtime's error body") {
            listOf(post(""), post("""{not json"""), post("""{"name":1,"email":"jane@example.com"}""")).forEach {
                it.status shouldBe Status.BAD_REQUEST
                it.header("Content-Type") shouldBe "application/json; charset=utf-8"
                it.error() shouldBe ErrorResponse("malformed_request", "Malformed request: body 'body'")
            }
            application.adminGet("/inquiries/not-a-uuid").error() shouldBe
                ErrorResponse("malformed_request", "Malformed request: path 'inquiryId'")
            application.adminGet("/inquiries/${UUID.randomUUID()}/more").error().code shouldBe "not_found"
        }

        test("GET /inquiries is now the staff list, and every other undeclared method is still not allowed") {
            application.adminGet("/inquiries").status shouldBe Status.OK
            val id = UUID.randomUUID()
            listOf(
                Request(Method.PUT, "/inquiries"),
                Request(Method.DELETE, "/inquiries"),
                Request(Method.OPTIONS, "/inquiries"),
                Request(Method.POST, "/inquiries/$id"),
                Request(Method.DELETE, "/inquiries/$id"),
                Request(Method.HEAD, "/inquiries/$id"),
                Request(Method.OPTIONS, "/inquiries/$id"),
            ).forEach { request ->
                val response = application.http(request)
                response.status shouldBe Status.METHOD_NOT_ALLOWED
                response.bodyString() shouldBe ""
            }
        }
    })

/** A clock whose time a spec sets, so inquiries can be recorded at chosen instants. */
private class SteppingClock(
    @Volatile var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
