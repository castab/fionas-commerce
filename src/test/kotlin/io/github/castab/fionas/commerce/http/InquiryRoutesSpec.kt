package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.util.UUID

/** The inquiry API through the complete fionas-commerce HTTP handler, runtime error handling included. */
class InquiryRoutesSpec :
    FunSpec({
        lateinit var application: TestApplication

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun post(body: String) = application.http(Request(Method.POST, "/inquiries").header("Content-Type", "application/json").body(body))

        fun get(path: String) = application.http(Request(Method.GET, path))

        fun Response.inquiry() = CommerceJson.asA(bodyString(), InquiryResponse.serializer())

        fun Response.error() = CommerceJson.asA(bodyString(), ErrorResponse.serializer())

        fun rows() = application.database.count("fionas.customers") to application.database.count("fionas.inquiries")

        test("POST /inquiries records the inquiry and returns it, with its location") {
            val email = "jane-${UUID.randomUUID()}@example.com"

            val response =
                post(
                    """{"name":" Jane Doe ","email":"${email.uppercase()}","message":"I'm interested in ice cream service for a birthday."}""",
                )

            response.status shouldBe Status.CREATED
            val created = response.inquiry()
            created.name shouldBe "Jane Doe"
            created.email shouldBe email
            created.message shouldBe "I'm interested in ice cream service for a birthday."
            created.createdAt shouldBe STORED_INSTANT.toString()
            UUID.fromString(created.id) shouldNotBe UUID.fromString(created.customerId)
            response.header("Location") shouldBe "/inquiries/${created.id}"
        }

        test("GET /inquiries/{id} returns the persisted inquiry") {
            val created = post("""{"name":"Ada","email":"ada-${UUID.randomUUID()}@example.com"}""").inquiry()

            val response = get("/inquiries/${created.id}")

            response.status shouldBe Status.OK
            response.inquiry() shouldBe created
            created.message.shouldBeNull()
            response.bodyString().contains("\"message\"") shouldBe false
        }

        test("a second inquiry with the same email belongs to the same customer") {
            val email = "twice-${UUID.randomUUID()}@example.com"
            val first = post("""{"name":"Jane Doe","email":"$email"}""").inquiry()

            val second = post("""{"name":"Janet","email":"$email","message":"Also a wedding"}""").inquiry()

            second.customerId shouldBe first.customerId
            second.name shouldBe "Jane Doe"
            second.id shouldNotBe first.id
        }

        test("invalid values are a validation failure, and nothing is recorded") {
            val before = rows()

            post("""{"name":"Jane","email":"not-an-email"}""").let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error() shouldBe ErrorResponse("validation_failed", "Email must contain exactly one @ after a non-empty local part")
            }
            post("""{"name":"   ","email":"jane@example.com"}""").let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                it.error() shouldBe ErrorResponse("validation_failed", "Name must not be blank")
            }
            post("""{"name":"Jane","email":"jane@example.com","message":"${"x".repeat(4001)}"}""").error().code shouldBe
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

            rows() shouldBe before
        }

        test("an inquiry id that is not a UUID is a malformed request") {
            get("/inquiries/not-a-uuid").let {
                it.status shouldBe Status.BAD_REQUEST
                it.error().code shouldBe "malformed_request"
            }
        }

        test("an unknown inquiry is not found") {
            val missing = UUID.randomUUID()

            get("/inquiries/$missing").let {
                it.status shouldBe Status.NOT_FOUND
                it.error() shouldBe ErrorResponse("not_found", "Inquiry $missing was not found")
            }
        }

        // The routes are http4k contract routes, which would otherwise validate inputs and
        // render failures themselves; commerce-runtime still owns every error response.
        test("inputs the contract cannot read keep commerce-runtime's error body") {
            listOf(post(""), post("""{not json"""), post("""{"name":1,"email":"jane@example.com"}""")).forEach {
                it.status shouldBe Status.BAD_REQUEST
                it.header("Content-Type") shouldBe "application/json; charset=utf-8"
                it.error() shouldBe ErrorResponse("malformed_request", "Malformed request: body 'body'")
            }
            get("/inquiries/not-a-uuid").error() shouldBe ErrorResponse("malformed_request", "Malformed request: path 'inquiryId'")
            get("/inquiries/${UUID.randomUUID()}/more").error().code shouldBe "not_found"
        }

        test("a method an inquiry path does not declare is not allowed, OPTIONS included") {
            val id = UUID.randomUUID()
            listOf(
                Request(Method.GET, "/inquiries"),
                Request(Method.PUT, "/inquiries"),
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
