package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceErrorHandling
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.authentication
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.runtime.serviceauth.ServiceAccessTokenAuthenticator
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.commerce.staff.PermissionKey
import io.github.castab.commerce.staff.RoleDefinition
import io.github.castab.commerce.staff.RoleKey
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryLifecycle
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.asFionasWeb
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.linesJson
import io.github.castab.fionas.commerce.testing.requestedServiceJson
import io.github.castab.fionas.commerce.testing.withBearer
import io.github.castab.fionas.commerce.testing.withSubmissionKey
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
import org.http4k.contract.contract
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
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

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun TestApplication.post(body: String) =
            http(
                Request(Method.POST, "/inquiries")
                    .withSubmissionKey()
                    .asFionasWeb(this)
                    .header("Content-Type", "application/json")
                    .body(body),
            )

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
                "fionas.inquiry_submissions",
                "fionas.inquiries",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
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
            requested: String? = requestedServiceJson(),
            lines: String? = linesJson(acceptanceLines()),
        ) = """{"name":"$name","email":"$email","zipCode":"$zipCode","eventDate":"$eventDate","eventType":"$eventType"$extra""" +
            requested?.let { ""","requestedService":$it""" }.orEmpty() + lines?.let { ""","lines":$it""" }.orEmpty() + "}"

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
                """{"name":"Jane","email":"missing-zip@example.com","eventDate":"2026-12-05","eventType":"BIRTHDAY","requestedService":${requestedServiceJson()},"lines":${linesJson(
                    acceptanceLines(),
                )}}""",
                """{"name":"Jane","email":"null-zip@example.com","eventDate":"2026-12-05","eventType":"BIRTHDAY","requestedService":${requestedServiceJson()},"lines":${linesJson(
                    acceptanceLines(),
                )},"zipCode":null}""",
                """{"name":"Jane","email":"numeric-zip@example.com","eventDate":"2026-12-05","eventType":"BIRTHDAY","requestedService":${requestedServiceJson()},"lines":${linesJson(
                    acceptanceLines(),
                )},"zipCode":2108}""",
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

        test("an unreadable body, including missing or null requested service or lines, is a malformed request, and nothing is recorded") {
            val before = rows()

            post("""{"email":"jane@example.com"}""").let {
                it.status shouldBe Status.BAD_REQUEST
                it.error().code shouldBe "malformed_request"
            }
            post("""{not json""").error().code shouldBe "malformed_request"
            listOf(null, "null", "[]").forEach { requested ->
                post(inquiryBody("jane@example.com", requested = requested)).error().code shouldBe "malformed_request"
            }
            listOf(null, "null", "{}").forEach { lines ->
                post(inquiryBody("jane@example.com", lines = lines)).error().code shouldBe "malformed_request"
            }
            // Amounts are exact decimal strings, never JSON numbers.
            post(
                inquiryBody(
                    "jane@example.com",
                    lines = """[{"description":"Service","unitPrice":450.00,"taxAmount":"0.00","currency":"USD"}]""",
                ),
            ).error()
                .code shouldBe "malformed_request"

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
                it.requestedService.guestCount shouldBe 75
            }
            response.keys() shouldBe
                setOf(
                    "id",
                    "customerId",
                    "name",
                    "email",
                    "createdAt",
                    "requestedService",
                    "zipCode",
                    "eventDate",
                    "eventType",
                    "lifecycle",
                )
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

        test("a present inquiry with a missing runtime canonical lineage returns a generic internal failure") {
            val id = post(inquiryBody("missing-lineage-${UUID.randomUUID()}@example.com")).receipt().id
            application.adminGet("/inquiries/$id").status shouldBe Status.OK
            // Simulate a dangling canonical lookup at Fiona's repository seam, keeping the real FK intact.
            val absentDocument = UUID.randomUUID()
            val associations =
                object : InquiryFinancialDocumentRepository by JdbiInquiryFinancialDocumentRepository() {
                    override fun initialEstimateOf(
                        transaction: Transaction,
                        inquiryId: InquiryId,
                    ): UUID = absentDocument
                }
            val read =
                GetInquiry(
                    application.transactor,
                    JdbiCustomerRepository(),
                    JdbiInquiryRepository(),
                    ReadInquiryLifecycle(application.context.financialLedger, associations, JdbiInquiryFulfillmentRepository()),
                )
            val handler =
                CommerceErrorHandling.then(
                    contract {
                        renderer = fionaOpenApi("test")
                        routes +=
                            getInquiryRoute(
                                read::invoke,
                                AccessControl(
                                    authentication(ServiceAccessTokenAuthenticator(application.context.serviceAccessTokens)),
                                    application.authorization,
                                ),
                            )
                    },
                )
            val service = application.provisionService("inquiry-integrity-reader", setOf(FionaPermissions.InquiriesRead))
            val token = application.serviceToken(service)
            val response = handler(Request(Method.GET, "/inquiries/$id").withBearer(token))
            response.status shouldBe Status.INTERNAL_SERVER_ERROR
            Json.parseToJsonElement(response.bodyString()) shouldBe
                Json.parseToJsonElement("""{"code":"internal_failure","message":"The request could not be completed"}""")

            val unknown = UUID.randomUUID()
            handler(Request(Method.GET, "/inquiries/$unknown").withBearer(token)).let {
                it.status shouldBe Status.NOT_FOUND
                it.error() shouldBe ErrorResponse("not_found", "Inquiry $unknown was not found")
            }
        }

        // The configured request survives the handoff to staff.

        test("the requested service survives persistence and is returned to staff exactly as recorded") {
            val requested =
                """{"guestCount":120,"guestCountIsMinimum":true,"durationMinutes":180,""" +
                    """"items":[{"label":" Horchata soft serve ","group":"Soft serve","key":"horchata"},""" +
                    """{"label":"Churros, if possible"}],""" +
                    """"pricingReference":"fionas-web-pricing@2026-10-01"}"""

            val response = post(inquiryBody("configured-${UUID.randomUUID()}@example.com", requested = requested))

            response.status shouldBe Status.CREATED
            response.keys() shouldBe setOf("id", "createdAt")
            application.adminGet("/inquiries/${response.receipt().id}").inquiry().requestedService shouldBe
                RequestedServiceRequest(
                    guestCount = 120,
                    guestCountIsMinimum = true,
                    durationMinutes = 180,
                    items =
                        listOf(
                            RequestedServiceItemRequest("Horchata soft serve", "Soft serve", "horchata"),
                            RequestedServiceItemRequest("Churros, if possible"),
                        ),
                    pricingReference = "fionas-web-pricing@2026-10-01",
                )
        }

        test("the requested service is descriptive only: lines that disagree with it are recorded exactly as the authority priced them") {
            // A bespoke request: 40 guests and a churro service no catalog offers, at a flat negotiated price.
            val response =
                post(
                    inquiryBody(
                        "bespoke-${UUID.randomUUID()}@example.com",
                        requested = requestedServiceJson(guests = 40, minutes = null, items = listOf("Churro bar")),
                        lines = linesJson(listOf(CHURROS, COURTESY_DISCOUNT)),
                    ),
                )

            response.status shouldBe Status.CREATED
            val id = response.receipt().id
            val estimate =
                CommerceJson.asA(
                    application.adminGet("/financial-documents/${application.initialEstimateOf(id)}").bodyString(),
                    FinancialDocumentResponse.serializer(),
                )
            estimate.lines.map { Triple(it.description, it.quantity, it.unitPrice) } shouldContainExactly
                listOf(Triple("Churro catering service", "1", "450.00"), Triple("Courtesy discount", null, "-50.00"))
            estimate.total shouldBe "400.00"
        }

        test("invalid lines are a validation failure with nothing recorded, and their key stays usable") {
            val before = rows()
            val key = "invalid-lines-${UUID.randomUUID()}"
            listOf(
                "[]",
                linesJson(listOf(TestLine("   ", unitPrice = "1.00"))),
                linesJson(listOf(TestLine("Service", unitPrice = "1.005"))),
                linesJson(listOf(TestLine("Service", unitPrice = "1e3"))),
                linesJson(listOf(TestLine("Service", unitPrice = "1".repeat(50)))),
                linesJson(listOf(TestLine("Service", quantity = "3", unitPrice = "0.333"))),
                linesJson(listOf(TestLine("Service", quantity = "0", unitPrice = "1.00"))),
                linesJson(listOf(TestLine("Service", unitPrice = "1.00", currency = "EUR"), TestLine("Other", unitPrice = "1.00"))),
                linesJson(listOf(TestLine("Service", unitPrice = "1.00", currency = "XYZ"))),
                linesJson(listOf(TestLine("Credit", unitPrice = "-10.00"))),
                linesJson(List(101) { TestLine("Line $it", unitPrice = "1.00") }),
            ).forEach { lines ->
                val response =
                    application.http(
                        Request(Method.POST, "/inquiries")
                            .withSubmissionKey(key)
                            .asFionasWeb(application)
                            .header("Content-Type", "application/json")
                            .body(inquiryBody("rejected-${UUID.randomUUID()}@example.com", lines = lines)),
                    )
                response.status shouldBe Status.UNPROCESSABLE_ENTITY
                response.error().code shouldBe "validation_failed"
            }
            rows() shouldBe before
            application
                .http(
                    Request(Method.POST, "/inquiries")
                        .withSubmissionKey(key)
                        .asFionasWeb(application)
                        .header("Content-Type", "application/json")
                        .body(inquiryBody("accepted-${UUID.randomUUID()}@example.com")),
                ).status shouldBe Status.CREATED
        }

        test("a caller-supplied total is never authoritative: it is ignored and the total is derived from the lines") {
            val response =
                post(inquiryBody("forged-${UUID.randomUUID()}@example.com", extra = ""","total":"1.00","subtotal":"1.00""""))

            response.status shouldBe Status.CREATED
            val id = response.receipt().id
            CommerceJson
                .asA(
                    application.adminGet("/financial-documents/${application.initialEstimateOf(id)}").bodyString(),
                    FinancialDocumentResponse.serializer(),
                ).total shouldBe "681.25"
        }

        test("a returning customer's new request keeps its own requested service, and the earlier one is unchanged") {
            val email = "repeat-${UUID.randomUUID()}@example.com"
            val first = post(inquiryBody(email, requested = requestedServiceJson(guests = 50))).receipt()
            val firstRequest = application.adminGet("/inquiries/${first.id}").inquiry().requestedService

            val second =
                post(
                    inquiryBody(
                        email,
                        name = "Someone Else",
                        requested = requestedServiceJson(guests = 200, minutes = 90),
                        lines = linesJson(acceptanceLines(guests = 200, minutes = 90)),
                    ),
                ).receipt()

            val secondRead = application.adminGet("/inquiries/${second.id}").inquiry()
            val firstRead = application.adminGet("/inquiries/${first.id}").inquiry()
            secondRead.customerId shouldBe firstRead.customerId
            firstRead.requestedService shouldBe firstRequest
            firstRead.requestedService.guestCount shouldBe 50
            secondRead.requestedService.guestCount shouldBe 200
            secondRead.requestedService.durationMinutes shouldBe 90
        }

        test("service-priced inquiry → staff read → canonical Estimate v1 with exactly the submitted lines, authored by the service") {
            val documents = application.database.count("commerce.financial_document_snapshots")

            val receipt =
                post(inquiryBody("handoff-${UUID.randomUUID()}@example.com", extra = ""","message":"A birthday"""")).receipt()

            // The service's permission internally materializes one initial Estimate without staff financial permissions.
            application.database.count("commerce.financial_document_snapshots") shouldBe documents + 1
            val initial =
                CommerceJson.asA(
                    application.adminGet("/financial-documents/${application.initialEstimateOf(receipt.id)}").bodyString(),
                    FinancialDocumentResponse.serializer(),
                )
            initial.version shouldBe 1
            initial.stage shouldBe "ESTIMATE"
            initial.total shouldBe "681.25"
            initial.lines.map { it.description } shouldContainExactly acceptanceLines().map { it.description }
            initial.linesAuthoredBy.shouldNotBeNull().let {
                it.principalKind shouldBe "SERVICE"
                it.principalId shouldBe
                    application.web.id.value
                        .toString()
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
                        app
                            .post(
                                inquiryBody("inbox-$index@example.com", name = "Customer $index"),
                            ).receipt()
                            .id
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
