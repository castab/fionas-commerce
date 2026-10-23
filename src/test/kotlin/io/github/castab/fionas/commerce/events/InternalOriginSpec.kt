package io.github.castab.fionas.commerce.events

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.InternalComponentId
import io.github.castab.commerce.runtime.operation.OperationOrigin
import io.github.castab.fionas.commerce.http.FinancialDocumentResponse
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.proposalJson
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Status
import java.util.UUID

class InternalOriginSpec :
    FunSpec({
        val origin = OperationOrigin.Internal(InternalComponentId("fionas.inquiry-intake"))
        val tables =
            listOf(
                "fionas.customers",
                "fionas.inquiries",
                "fionas.inquiry_submissions",
                "fionas.inquiry_financial_documents",
                "fionas.financial_document_authorship",
                "commerce.financial_document_snapshots",
                "commerce.outbox_events",
            )

        test("intake ignores forged attribution, creates no SERVICE, exposes INTERNAL and preserves staff revision history") {
            TestApplication.create().use { app ->
                val input =
                    submittedFixture().replace(
                        "\"source\":\"fionas-web\"",
                        "\"source\":\"fionas-web\",\"originKind\":\"PRINCIPAL\",\"component\":\"forged\",\"submittedBy\":\"${UUID.randomUUID()}\"",
                    )
                app.database.count("commerce.service_identities") shouldBe 0
                app.database.count("commerce.service_credentials") shouldBe 0
                inquiryIntakeHandler(app.context, testClock)(input.toByteArray())
                app.database.count("commerce.service_identities") shouldBe 0
                app.database.count("commerce.service_credentials") shouldBe 0
                val documentId = app.database.strings("SELECT document_id FROM fionas.financial_document_authorship").single()
                val read = app.adminRequest(Method.GET, "/financial-documents/$documentId")
                read.status shouldBe Status.OK
                val initial = CommerceJson.asA(read.bodyString(), FinancialDocumentResponse.serializer())
                initial.linesAuthoredBy?.originKind shouldBe "INTERNAL"
                initial.linesAuthoredBy?.component shouldBe "fionas.inquiry-intake"
                initial.linesAuthoredBy?.principalKind shouldBe null
                initial.linesAuthoredBy?.principalId shouldBe null
                val revised =
                    app.adminPost(
                        "/financial-documents/$documentId/change-orders",
                        """{"expectedVersion":1,"lines":${proposalJson(CHURROS.new("staff-line"))}}""",
                    )
                revised.status shouldBe Status.OK
                val staff = CommerceJson.asA(revised.bodyString(), FinancialDocumentResponse.serializer())
                staff.linesAuthoredBy?.originKind shouldBe "PRINCIPAL"
                staff.linesAuthoredBy?.principalKind shouldBe "USER"
                staff.linesAuthoredBy?.principalId shouldBe app.adminId.value.toString()
                staff.linesAuthoredBy?.component shouldBe null
                app.database.strings("SELECT author_kind FROM fionas.financial_document_authorship ORDER BY document_version") shouldBe
                    listOf("INTERNAL", "USER")
            }
        }

        test("cross-origin replay preserves first authorship, counts and fingerprint; changed intent still conflicts") {
            TestApplication.create().use { app ->
                val principal = OperationOrigin.Principal(app.web.id)
                listOf(origin to principal, principal to origin).forEach { (first, replay) ->
                    val command = submittedCommand(submittedFixture().toByteArray(), first)
                    val operation = app.createInquiryOperation()
                    val inquiry = operation(command)
                    val before = tables.associateWith(app.database::count)
                    val authors =
                        app.database.strings(
                            "SELECT author_kind || ':' || coalesce(author_id::text, author_component) FROM fionas.financial_document_authorship ORDER BY document_id",
                        )
                    operation(command.copy(origin = replay)) shouldBe inquiry
                    tables.associateWith(app.database::count) shouldBe before
                    app.database.strings(
                        "SELECT author_kind || ':' || coalesce(author_id::text, author_component) FROM fionas.financial_document_authorship ORDER BY document_id",
                    ) shouldBe
                        authors
                    shouldThrow<CommerceFailure.Conflict> { operation(command.copy(origin = replay, lines = command.lines.reversed())) }
                    tables.associateWith(app.database::count) shouldBe before
                }
            }
        }

        test("an outbox database failure rolls back INTERNAL authorship and submission claim and allows retry") {
            TestApplication.create().use { app ->
                val command = submittedCommand(submittedFixture().toByteArray(), origin)
                val eventId = UUID.randomUUID()
                val operation = app.createInquiryOperation(newEventId = { eventId })
                operation(command)
                val next = submittedCommand(submittedFixture().toByteArray(), origin)
                val before = tables.associateWith(app.database::count)
                shouldThrow<Exception> { operation(next) }
                tables.associateWith(app.database::count) shouldBe before
                app.createInquiryOperation()(next)
                app.database.count("fionas.inquiries") shouldBe before.getValue("fionas.inquiries") + 1
            }
        }
    })
