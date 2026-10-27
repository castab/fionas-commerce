package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class StaffRequestListRoutesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val path = "/staff/requests"

        fun escape(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

        fun read(query: String = ""): StaffRequestListResponse {
            val response = app.adminGet(path + query)
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            return CommerceJson.asA(response.bodyString(), StaffRequestListResponse.serializer())
        }
        beforeTest { app = TestApplication.create() }
        afterTest { app.close() }

        test("literal normalized customer search keeps returning customer inquiries separate and combines with filters") {
            val first = app.createInquiry("maria@example.com")
            val second = app.createInquiry("maria@example.com")
            app.createInquiry("someone@example.com")
            app.database.execute("UPDATE fionas.customers SET name = 'Maria %_ Gonzalez' WHERE email = 'maria@example.com'")
            listOf("mArIa", "  GONZA  ", "RIA@EXAM", "%_", first).forEach { q ->
                val page = read("?q=${escape(q)}")
                page.items.map { it.inquiryId }.toSet() shouldBe if (q == first) setOf(first) else setOf(first, second)
                page.totalCount shouldBe if (q == first) 1 else 2
            }
            read("?q=maria&filter=quoted").totalCount shouldBe 0
            read("?q=maria&filter=new").totalCount shouldBe 2
            read("?q=unmatched").items shouldBe emptyList()
            read("?q=unmatched").nextCursor shouldBe null
            read("?q=%20%20").totalCount shouldBe 3
            val item = read("?q=$first").items.single()
            item.customerEmail shouldBe "maria@example.com"
            item.total shouldBe "681.25"
            item.balance shouldBe "681.25"
            item.stage shouldBe InquiryStageResponse.REQUESTED
            item.totalQualifier shouldBe DashboardTotalQualifierResponse.EXACT
            item.needsClosing shouldBe false
            app.database.execute(
                "UPDATE fionas.inquiries SET requested_service = jsonb_set(requested_service, '{guestCountIsMinimum}', 'true')",
            )
            read("?q=$first").items.single().totalQualifier shouldBe DashboardTotalQualifierResponse.FROM
        }

        test("empty singleton exact page and consecutive tied timestamp pages terminate with totalCount unchanged") {
            read().items shouldBe emptyList()
            read().totalCount shouldBe 0
            read().nextCursor shouldBe null
            val ids = mutableListOf(app.createInquiry())
            read("?limit=1").nextCursor shouldBe null
            repeat(4) { ids += app.createInquiry() }
            read("?limit=5").nextCursor shouldBe null
            val found = mutableListOf<String>()
            var cursor: String? = null
            do {
                val page = read("?limit=2" + (cursor?.let { "&cursor=$it" } ?: ""))
                page.totalCount shouldBe 5
                found += page.items.map { it.inquiryId }
                cursor = page.nextCursor
            } while (cursor != null)
            found shouldBe ids.sortedDescending()
            found.toSet().size shouldBe 5
        }

        test("strict safe query validation and cursor binding happen after authorization") {
            repeat(2) { app.createInquiry() }
            val cursor = read("?q=%20JANE%20&limit=1").nextCursor!!
            read("?q=jane&limit=2&cursor=$cursor").items.size shouldBe 1
            val bad =
                listOf(
                    "filter=unknown",
                    "filter=NEW",
                    "filter=all&filter=new",
                    "limit=0",
                    "limit=101",
                    "limit=-1",
                    "limit=1.5",
                    "limit=abc",
                    "limit=999999999999",
                    "limit=1&limit=2",
                    "cursor=broken",
                    "cursor=",
                    "cursor=$cursor",
                    "q=jane&filter=quoted&cursor=$cursor",
                    "q=other&cursor=$cursor",
                    "q=a&q=b",
                    "q=${"a".repeat(201)}",
                    "q=%00",
                )
            bad.forEach { query ->
                app.adminGet("$path?$query").status shouldBe Status.BAD_REQUEST
                app.http(Request(Method.GET, "$path?$query")).status shouldBe Status.UNAUTHORIZED
            }
        }

        test("USER and SERVICE need both live permissions with session precedence and safe internal failure") {
            app.createInquiry()
            val permissions = setOf(FionaPermissions.InquiriesRead, CommercePermissions.FinancialDocumentRead)
            val service = app.provisionService("requests", permissions)
            val token = app.serviceToken(service)
            val cookie = app.adminCookie

            fun user() = app.http(Request(Method.GET, path).header("Cookie", cookie))

            fun bearer() = app.http(Request(Method.GET, path).header("Authorization", "Bearer $token"))
            listOf(emptySet(), setOf(FionaPermissions.InquiriesRead), setOf(CommercePermissions.FinancialDocumentRead)).forEach { grants ->
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, grants)
                app.authorization.replaceRolePermissions(service.role, grants)
                user().status shouldBe Status.FORBIDDEN
                bearer().status shouldBe Status.FORBIDDEN
            }
            app.authorization.replaceRolePermissions(service.role, permissions)
            bearer().status shouldBe Status.OK
            app.http(Request(Method.GET, path).header("Cookie", cookie).header("Authorization", "Bearer $token")).status shouldBe
                Status.FORBIDDEN
            app.authorization.replaceRolePermissions(CommerceRoles.Administrator, permissions)
            user().status shouldBe Status.OK
            app.database.execute("UPDATE fionas.inquiry_financial_documents SET purpose = 'RELATED'")
            listOf(user(), bearer()).forEach {
                it.status shouldBe Status.INTERNAL_SERVER_ERROR
                it.bodyString() shouldBe """{"code":"internal_failure","message":"The request could not be completed"}"""
            }
        }
    })
