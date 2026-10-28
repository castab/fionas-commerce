package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.CommercePermissions
import io.github.castab.commerce.staff.CommerceRoles
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.InquiryInvoiceRevisions
import io.github.castab.fionas.commerce.financial.InquiryServicePlan
import io.github.castab.fionas.commerce.financial.InquiryServicePlanRepository
import io.github.castab.fionas.commerce.financial.InvoiceRevisionIntent
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryServicePlanRepository
import io.github.castab.fionas.commerce.financial.ServicePlanChange
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.staff.FionaPermissions
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.testClock
import io.github.castab.fionas.commerce.testing.withBearer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CanonicalRevisionsSpec :
    FunSpec({
        lateinit var app: TestApplication
        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }

        fun path(id: String) = "/staff/requests/$id"

        fun read(id: String) = CommerceJson.asA(app.adminGet(path(id)).bodyString(), StaffRequestResponse.serializer())

        fun plan(description: String) =
            ServicePlanRequest(description, 100, 120, listOf(ServicePlanItemDto("Vanilla", "Flavors", "vanilla")), emptyList())

        fun line(
            amount: String,
            id: String? = null,
        ) = ProposedLineRequest(
            id,
            if (id ==
                null
            ) {
                "service"
            } else {
                null
            },
            "Catering",
            null,
            null,
            amount,
            "0.00",
            "USD",
        )

        fun issued(response: Response): IssuedInquiryProposalResponse {
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), IssuedInquiryProposalResponse.serializer())
        }

        fun initial(): String {
            val id = app.createInquiry()
            val lines = listOf(line("1200.00"))
            val preview =
                app.adminPost(
                    path(id) + "/quote-preview",
                    Json.encodeToString(
                        PreviewInquiryQuoteRequest(1, lines, plan("Original service"), DepositTermsRequest.Percentage("25")),
                    ),
                )
            preview.status shouldBe Status.OK
            val token = CommerceJson.asA(preview.bodyString(), InquiryQuotePreviewResponse.serializer()).reviewToken
            issued(
                app.adminPost(
                    path(id) + "/proposals",
                    Json.encodeToString(
                        IssueInquiryProposalRequest(1, DepositTermsRequest.Percentage("25"), lines, plan("Original service"), token),
                    ),
                ),
            )
            return id
        }

        fun pay(
            id: String,
            amount: String = "300.00",
            proposal: String? = read(id).proposal?.id,
        ): Response {
            val state = read(id)
            return app.adminPost(
                "/financial-documents/${state.financial.id}/payments",
                """{"documentVersion":${state.financial.version},"amount":"$amount","method":"CHECK","expectedProposalId":"$proposal"}""",
            )
        }

        fun book(): String = initial().also { pay(it).status shouldBe Status.CREATED }

        fun invoiceInput(
            id: String,
            amount: String = read(id).financial.total,
            action: ServicePlanActionRequest = ServicePlanActionRequest.RETAIN,
            service: ServicePlanRequest? = null,
        ) = InvoiceRevisionPreviewRequest(
            read(id).financial.version,
            listOf(
                line(
                    amount,
                    read(id)
                        .financial.lines
                        .single()
                        .id,
                ),
            ),
            action,
            service,
        )

        fun previewInvoice(
            id: String,
            input: InvoiceRevisionPreviewRequest,
        ): InvoiceRevisionPreviewResponse {
            val response = app.adminPost(path(id) + "/invoice-revision-preview", Json.encodeToString(input))
            response.status shouldBe Status.OK
            response.header("Cache-Control") shouldBe "no-store"
            return CommerceJson.asA(response.bodyString(), InvoiceRevisionPreviewResponse.serializer())
        }

        fun approveInvoice(
            id: String,
            input: InvoiceRevisionPreviewRequest,
            token: String,
        ): Response =
            app.adminPost(
                path(id) + "/invoice-revisions",
                Json.encodeToString(
                    InvoiceRevisionRequest(input.expectedDocumentVersion, input.lines, token, input.servicePlanAction, input.servicePlan),
                ),
            )

        fun reviseInvoice(
            id: String,
            input: InvoiceRevisionPreviewRequest,
        ): InvoiceRevisionPreviewResponse {
            val preview = previewInvoice(id, input)
            approveInvoice(id, input, preview.reviewToken).status shouldBe Status.OK
            return preview
        }

        fun quoteInput(
            id: String,
            amount: String = read(id).financial.total,
            action: ServicePlanActionRequest = ServicePlanActionRequest.RETAIN,
            service: ServicePlanRequest? = null,
            terms: DepositTermsRequest = DepositTermsRequest.Percentage("25"),
        ) = QuoteRevisionPreviewRequest(
            read(id).financial.version,
            read(id).proposal!!.depositRequirementRevision,
            listOf(
                line(
                    amount,
                    read(id)
                        .financial.lines
                        .single()
                        .id,
                ),
            ),
            terms,
            action,
            service,
        )

        fun quotePreview(
            id: String,
            input: QuoteRevisionPreviewRequest,
        ): QuoteRevisionPreviewResponse {
            val response = app.adminPost(path(id) + "/quote-revision-preview", Json.encodeToString(input))
            response.status shouldBe Status.OK
            return CommerceJson.asA(response.bodyString(), QuoteRevisionPreviewResponse.serializer())
        }

        fun approveQuote(
            id: String,
            input: QuoteRevisionPreviewRequest,
            token: String,
        ) = app.adminPost(
            path(id) + "/proposals/quote-revisions",
            Json.encodeToString(
                ReviseInquiryQuoteProposalRequest(
                    input.expectedDocumentVersion,
                    input.expectedDepositRequirementRevision,
                    input.lines,
                    input.terms,
                    input.servicePlan,
                    token,
                    input.servicePlanAction,
                ),
            ),
        )

        test("timeline preserves exact historical quote and invoice amounts through later revisions") {
            val id = initial()
            val input = quoteInput(id, "1600.00")
            approveQuote(id, input, quotePreview(id, input).quote.reviewToken).status shouldBe Status.OK
            pay(id, "400.00").status shouldBe Status.CREATED
            reviseInvoice(id, invoiceInput(id, "1800.00"))
            reviseInvoice(id, invoiceInput(id, "1900.00"))
            val response = app.adminGet(path(id) + "/timeline")
            response.status shouldBe Status.OK
            val timeline = CommerceJson.asA(response.bodyString(), StaffTimelineResponse.serializer())
            timeline.items.filterIsInstance<TimelineItemResponse.Quote>().map { it.financial.total } shouldBe listOf("1200.00", "1600.00")
            timeline.items.filterIsInstance<TimelineItemResponse.Invoice>().map { it.financial.total } shouldBe listOf("1800.00", "1900.00")
            timeline.items
                .filterIsInstance<TimelineItemResponse.Payment>()
                .single()
                .amount shouldBe "400.00"
            timeline.items
                .map { it.id }
                .toSet()
                .size shouldBe timeline.items.size
        }

        fun counts() =
            listOf(
                "commerce.financial_document_snapshots",
                "commerce.deposit_requirement_revisions",
                "fionas.inquiry_service_plans",
                "fionas.inquiry_proposals",
                "commerce.payment_records",
            ).map(app.database::count)

        fun invoiceCore(
            plans: InquiryServicePlanRepository = JdbiInquiryServicePlanRepository(),
            associations: InquiryFinancialDocumentRepository = JdbiInquiryFinancialDocumentRepository(),
        ) = InquiryInvoiceRevisions(
            app.transactor,
            app.context.financialLedger,
            associations,
            JdbiFinancialDocumentAuthorshipRepository(),
            JdbiInquiryProposalRepository(),
            plans,
            JdbiInquiryFulfillmentRepository(),
            testClock,
        )

        fun intent(
            id: String,
            input: InvoiceRevisionPreviewRequest,
        ) = InvoiceRevisionIntent(
            InquiryId(UUID.fromString(id)),
            Version.of(input.expectedDocumentVersion),
            input.lines.domain(input.lines.map { it.lineItemId?.let(UUID::fromString) to it.key }),
            ServicePlanChange(input.servicePlanAction.domain(), input.servicePlan?.domain(emptyList())),
        )

        test("late service approval failure rolls back Invoice snapshot and authorship") {
            val id = book()
            val before = read(id)
            val input = intent(id, invoiceInput(id, "1450.00", ServicePlanActionRequest.REPLACE, plan("Amended service")))
            val preview = invoiceCore().preview(input)
            val plans = JdbiInquiryServicePlanRepository()
            val failing =
                object : InquiryServicePlanRepository by plans {
                    override fun insert(
                        transaction: Transaction,
                        plan: InquiryServicePlan,
                    ) {
                        plans.insert(transaction, plan)
                        error("Injected late approval failure")
                    }
                }
            val priorCounts = counts()
            shouldThrow<IllegalStateException> {
                invoiceCore(
                    failing,
                ).revise(InquiryInvoiceRevisions.Command(input, preview.reviewToken, app.adminId))
            }
            counts() shouldBe priorCounts
            read(id) shouldBe before
        }

        test("service-only Invoice amendments serialize and a waiting stale approver writes nothing") {
            val id = book()
            val input = intent(id, invoiceInput(id, action = ServicePlanActionRequest.REPLACE, service = plan("Committed service")))
            val core = invoiceCore()
            val command = InquiryInvoiceRevisions.Command(input, core.preview(input).reviewToken, app.adminId)
            val plans = JdbiInquiryServicePlanRepository()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val pausing =
                object : InquiryServicePlanRepository by plans {
                    override fun insert(
                        transaction: Transaction,
                        plan: InquiryServicePlan,
                    ) {
                        plans.insert(transaction, plan)
                        entered.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                    }
                }
            val first = CompletableFuture.supplyAsync { invoiceCore(pausing).revise(command) }
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                val owners = JdbiInquiryFinancialDocumentRepository()
                val pid = AtomicInteger()
                val observing =
                    object : InquiryFinancialDocumentRepository by owners {
                        override fun lockInquiryOf(
                            transaction: Transaction,
                            documentId: UUID,
                        ): InquiryId? {
                            pid.set(
                                transaction.handle
                                    .createQuery("SELECT pg_backend_pid()")
                                    .mapTo(Int::class.java)
                                    .one(),
                            )
                            return owners.lockInquiryOf(transaction, documentId)
                        }
                    }
                val second =
                    CompletableFuture.supplyAsync {
                        runCatching {
                            invoiceCore(
                                associations = observing,
                            ).revise(command)
                        }.exceptionOrNull()
                    }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (pid.get() == 0 ||
                    app.database.strings("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ${pid.get()}").singleOrNull() != "Lock"
                ) {
                    check(System.nanoTime() < deadline)
                    Thread.yield()
                }
                second.isDone shouldBe false
                // An unlocked staff reader sees the entire old approval while the amendment is uncommitted.
                read(id).effectiveServicePlan!!.description shouldBe "Original service"
                resume.countDown()
                first.get(30, TimeUnit.SECONDS)
                second.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.Conflict>()
                read(id).servicePlanHistory.size shouldBe 2
                read(id).financial.version shouldBe input.expectedDocumentVersion.number
            } finally {
                resume.countDown()
            }
        }

        test("service-only Quote revisions have independent identity and only the latest proposal accepts a deposit") {
            val id = initial()
            val original = read(id)
            val input = quoteInput(id, action = ServicePlanActionRequest.REPLACE, service = plan("Chocolate at the same price"))
            val before = counts()
            val preview = quotePreview(id, input)
            counts() shouldBe before
            preview.quote.financialChange shouldBe false
            preview.depositChange shouldBe false
            preview.servicePlanChange shouldBe true
            preview.quote.quoteVersion shouldBe original.financial.version
            val changed = issued(approveQuote(id, input, preview.quote.reviewToken))
            changed.proposal.id shouldNotBe original.proposal!!.id
            changed.proposal.documentVersion shouldBe original.proposal.documentVersion
            changed.proposal.depositRequirementRevision shouldBe original.proposal.depositRequirementRevision
            changed.servicePlan!!.revision shouldBe 2
            read(id).servicePlanHistory.map { it.description } shouldBe listOf("Original service", "Chocolate at the same price")
            approveQuote(id, input.copy(servicePlan = plan("Another choice")), preview.quote.reviewToken).status shouldBe Status.CONFLICT
            pay(id, proposal = original.proposal.id).status shouldBe Status.CONFLICT
            pay(id).status shouldBe Status.CREATED
            read(id).acceptedServicePlan shouldBe changed.servicePlan
        }
        test("multiple Quote financial and deposit revisions preserve plans and immutable history") {
            val id = initial()
            val original = read(id).servicePlan
            listOf("1300.00", "1450.00").forEachIndexed { index, amount ->
                val input =
                    quoteInput(
                        id,
                        amount,
                        terms =
                            if (index ==
                                0
                            ) {
                                DepositTermsRequest.Percentage("30")
                            } else {
                                DepositTermsRequest.Fixed("300.00", "USD")
                            },
                    )
                val result = issued(approveQuote(id, input, quotePreview(id, input).quote.reviewToken))
                result.financial.total shouldBe amount
                result.proposal.depositRequirementRevision shouldBe index + 2
                result.servicePlan shouldBe original
            }
            read(id).proposalHistory.size shouldBe 3
            read(id).servicePlanHistory.size shouldBe 1
            pay(id).status shouldBe Status.CREATED
        }
        test("Invoice increases decreases partial payments and overpayment preserve accepted deposit") {
            val id = book()
            val accepted = read(id)
            val before = counts()
            val input = invoiceInput(id, "1450.00")
            val preview = previewInvoice(id, input)
            counts() shouldBe before
            preview.netApplied shouldBe "300.00"
            preview.balance shouldBe "1150.00"
            preview.settlement shouldBe InvoiceSettlementResponse.UNDERPAID
            approveInvoice(id, input, preview.reviewToken).status shouldBe Status.OK
            pay(id, "200.00").status shouldBe Status.CREATED
            reviseInvoice(id, invoiceInput(id, "400.00")).let {
                it.netApplied shouldBe "500.00"
                it.balance shouldBe "-100.00"
                it.settlement shouldBe InvoiceSettlementResponse.OVERPAID
            }
            val final = read(id)
            final.payments.size shouldBe 2
            final.depositRequirement shouldBe accepted.depositRequirement
            final.proposal shouldBe accepted.proposal
            final.acceptedServicePlan shouldBe accepted.acceptedServicePlan
            final.effectiveServicePlan shouldBe accepted.effectiveServicePlan
            final.inquiry.lifecycle.stage shouldBe InquiryStageResponse.BOOKED
        }
        test("Invoice financial and service amendments commit together and service-only clear and replace create history") {
            val id = book()
            val accepted = read(id).acceptedServicePlan
            reviseInvoice(id, invoiceInput(id, "1450.00", ServicePlanActionRequest.REPLACE, plan("Larger party")))
            val revised = read(id)
            revised.effectiveServicePlan!!.description shouldBe "Larger party"
            revised.acceptedServicePlan shouldBe accepted
            val version = revised.financial.version
            val clear = reviseInvoice(id, invoiceInput(id, action = ServicePlanActionRequest.CLEAR))
            clear.financialChange shouldBe false
            clear.resultingDocumentVersion shouldBe version
            read(id).effectiveServicePlan!!.cleared shouldBe true
            reviseInvoice(id, invoiceInput(id, action = ServicePlanActionRequest.REPLACE, service = plan("Final instructions")))
            read(id).financial.version shouldBe version
            read(id).servicePlanHistory.map { it.revision } shouldBe listOf(1, 2, 3, 4)
            read(id).acceptedServicePlan shouldBe accepted
        }
        test("stale payment and service reviews and duplicate approvals cannot write") {
            val id = book()
            val input = invoiceInput(id, "1450.00")
            val preview = previewInvoice(id, input)
            pay(id, "10.00").status shouldBe Status.CREATED
            val before = counts()
            val stale = approveInvoice(id, input, preview.reviewToken)
            stale.status shouldBe Status.CONFLICT
            Json
                .parseToJsonElement(stale.bodyString())
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content shouldBe "QUOTE_REVIEW_STALE"
            counts() shouldBe before
            val a = invoiceInput(id, action = ServicePlanActionRequest.REPLACE, service = plan("A"))
            val b = invoiceInput(id, action = ServicePlanActionRequest.REPLACE, service = plan("B"))
            val old = previewInvoice(id, a)
            reviseInvoice(id, b)
            approveInvoice(id, a, old.reviewToken).status shouldBe Status.CONFLICT
            val fresh = previewInvoice(id, a)
            approveInvoice(id, a, fresh.reviewToken).status shouldBe Status.OK
            val after = counts()
            approveInvoice(id, a, fresh.reviewToken).status shouldBe Status.CONFLICT
            counts() shouldBe after
        }
        test("SERVED amendments preserve fulfillment and exact-zero close remains explicit; CLOSED rejects") {
            val id = book()
            app.adminPost("/inquiries/$id/served", "{}").status shouldBe Status.OK
            val served = read(id).inquiry.lifecycle
            reviseInvoice(id, invoiceInput(id, "300.00"))
            read(id).inquiry.lifecycle.stage shouldBe InquiryStageResponse.SERVED
            read(id).inquiry.lifecycle.served shouldBe served.served
            app.adminPost("/inquiries/$id/close", "{}").status shouldBe Status.OK
            app.adminPost(path(id) + "/invoice-revision-preview", Json.encodeToString(invoiceInput(id, "400.00"))).status shouldBe
                Status.CONFLICT
            read(id).inquiry.lifecycle.stage shouldBe InquiryStageResponse.CLOSED
        }
        test("no-op numeric equivalents and invalid plans fail without writes") {
            val id = book()
            val before = counts()
            listOf(
                invoiceInput(id, "1200.000"),
                invoiceInput(id, "-1.00"),
                invoiceInput(id, action = ServicePlanActionRequest.REPLACE),
                invoiceInput(id, action = ServicePlanActionRequest.CLEAR, service = plan("Forbidden")),
            ).forEach {
                app.adminPost(path(id) + "/invoice-revision-preview", Json.encodeToString(it)).status shouldBe Status.UNPROCESSABLE_ENTITY
            }
            counts() shouldBe before
        }

        fun code(response: Response) =
            Json
                .parseToJsonElement(response.bodyString())
                .jsonObject
                .getValue("code")
                .jsonPrimitive.content

        fun violations(response: Response) =
            Json
                .parseToJsonElement(response.bodyString())
                .jsonObject["violations"]
                ?.jsonArray
                ?.map {
                    it.jsonObject
                        .getValue("code")
                        .jsonPrimitive.content
                }.orEmpty()

        test("each revision family accepts only its own canonical stage and never a declined or unknown inquiry") {
            val requested = app.createInquiry()
            val quoted = initial()
            val booked = book()
            val declined = app.createInquiry()
            app
                .adminPost("/inquiries/$declined/decline", """{"expectedDocumentVersion":1,"reason":"Unavailable"}""")
                .status shouldBe Status.OK
            val quoteFromEstimate = QuoteRevisionPreviewRequest(1, 1, listOf(line("100.00")), DepositTermsRequest.Percentage("25"))
            val invoiceFromEstimate = InvoiceRevisionPreviewRequest(1, listOf(line("100.00")))
            val before = counts()
            listOf(requested, declined).forEach { id ->
                app.adminPost(path(id) + "/quote-revision-preview", Json.encodeToString(quoteFromEstimate)).let {
                    it.status shouldBe Status.CONFLICT
                    code(it) shouldBe "illegal_transition"
                }
                app.adminPost(path(id) + "/invoice-revision-preview", Json.encodeToString(invoiceFromEstimate)).let {
                    it.status shouldBe Status.CONFLICT
                    code(it) shouldBe "illegal_transition"
                }
            }
            // The Invoice route never books a Quote; the Quote route never revises a booked Invoice.
            app
                .adminPost(
                    path(quoted) + "/invoice-revision-preview",
                    Json.encodeToString(InvoiceRevisionPreviewRequest(read(quoted).financial.version, listOf(line("100.00")))),
                ).let { code(it) shouldBe "illegal_transition" }
            app
                .adminPost(
                    path(booked) + "/quote-revision-preview",
                    Json.encodeToString(quoteFromEstimate.copy(expectedDocumentVersion = read(booked).financial.version)),
                ).let { code(it) shouldBe "illegal_transition" }
            // A stale expected version is a conflict, distinct from an ineligible stage.
            app
                .adminPost(
                    path(booked) + "/invoice-revision-preview",
                    Json.encodeToString(invoiceInput(booked, "1300.00").copy(expectedDocumentVersion = 1)),
                ).let { code(it) shouldBe "conflict" }
            val unknown = UUID.randomUUID().toString()
            app.adminPost(path(unknown) + "/quote-revision-preview", Json.encodeToString(quoteFromEstimate)).status shouldBe
                Status.NOT_FOUND
            app
                .adminPost(
                    path(unknown) + "/invoice-revisions",
                    Json.encodeToString(InvoiceRevisionRequest(1, listOf(line("100.00")), "0".repeat(64))),
                ).status shouldBe Status.NOT_FOUND
            val bookedLines = Json.encodeToString(invoiceInput(booked).lines)
            app
                .adminPost(
                    path(booked) + "/invoice-revision-preview",
                    """{"expectedDocumentVersion":3,"lines":$bookedLines,"servicePlanAction":"DISCARD"}""",
                ).status shouldBe Status.BAD_REQUEST
            app
                .adminPost(path(booked) + "/invoice-revisions", """{"expectedDocumentVersion":3,"lines":$bookedLines}""")
                .status shouldBe Status.BAD_REQUEST
            counts() shouldBe before
        }

        test("retaining a plan whose noted line is removed requires REPLACE or CLEAR on every canonical amendment path") {
            val id = book()
            val lineId =
                read(id)
                    .financial.lines
                    .single()
                    .id
            val noted = plan("Noted service").copy(lineNotes = listOf(LineNoteRequest(lineId, null, "Negotiated package rate")))
            reviseInvoice(id, invoiceInput(id, action = ServicePlanActionRequest.REPLACE, service = noted))
            read(id)
                .effectiveServicePlan!!
                .lineNotes
                .single()
                .lineItemId shouldBe lineId
            val replacement = invoiceInput(id).copy(lines = listOf(line("1200.00")))
            val before = counts()
            app.adminPost(path(id) + "/invoice-revision-preview", Json.encodeToString(replacement)).let {
                it.status shouldBe Status.UNPROCESSABLE_ENTITY
                violations(it) shouldBe listOf("SERVICE_PLAN_LINE_NOT_FOUND")
            }
            // The generic change-order route keeps the effective plan, so it cannot orphan the note either.
            app
                .adminPost(
                    "/financial-documents/${read(id).financial.id}/change-orders",
                    """{"expectedVersion":${replacement.expectedDocumentVersion},"lines":${Json.encodeToString(replacement.lines)}}""",
                ).let {
                    it.status shouldBe Status.UNPROCESSABLE_ENTITY
                    violations(it) shouldBe listOf("SERVICE_PLAN_LINE_NOT_FOUND")
                }
            counts() shouldBe before
            val cleared = reviseInvoice(id, replacement.copy(servicePlanAction = ServicePlanActionRequest.CLEAR))
            cleared.financialChange shouldBe true
            cleared.servicePlanChange shouldBe true
            read(id).let {
                it.financial.lines
                    .single()
                    .id shouldNotBe lineId
                it.effectiveServicePlan!!.cleared shouldBe true
                it.acceptedServicePlan!!.description shouldBe "Original service"
            }
        }

        test("new routes enforce staff USER and Origin; Invoice amendments do not need deposit management") {
            val id = book()
            val permissions =
                setOf(
                    CommercePermissions.FinancialDocumentCreate,
                    CommercePermissions.DepositRequirementManage,
                    FionaPermissions.FinancialTermsManage,
                )
            val service = app.serviceToken(app.provisionService("revision-service", permissions))
            val original = app.authorization.getRole(CommerceRoles.Administrator)!!.permissions
            val routes = listOf("/quote-revision-preview", "/invoice-revision-preview", "/invoice-revisions")
            try {
                routes.forEach { suffix ->
                    val url = path(id) + suffix
                    app.http(Request(Method.POST, url).body("{}")).status shouldBe Status.UNAUTHORIZED
                    app.http(Request(Method.POST, url).withBearer(service).body("{}")).status shouldBe Status.FORBIDDEN
                    app.http(Request(Method.POST, url).header("Cookie", app.adminCookie).body("{}")).status shouldBe Status.FORBIDDEN
                    (permissions - CommercePermissions.DepositRequirementManage).forEach { missing ->
                        app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original - missing)
                        app.adminPost(url, "{}").status shouldBe Status.FORBIDDEN
                    }
                    app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
                }
                app.authorization.replaceRolePermissions(
                    CommerceRoles.Administrator,
                    original - CommercePermissions.DepositRequirementManage,
                )
                reviseInvoice(id, invoiceInput(id, "1300.00"))
            } finally {
                app.authorization.replaceRolePermissions(CommerceRoles.Administrator, original)
            }
        }
    })
