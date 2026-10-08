package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ManageInquiryFulfillment
import io.github.castab.fionas.commerce.inquiry.ReadInquiryLifecycle
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.inquiryProposals
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Staff Quote composition from authoritative final lines, purely and on real PostgreSQL: no
 * catalog or pricing policy anywhere. Staff replace soft serve with bespoke churros, override and
 * credit, preview write-free, and publish exactly the reviewed result atomically.
 */
class QuoteBuilderSpec :
    FunSpec({
        lateinit var app: TestApplication
        val usd = Currency.getInstance("USD")
        val owners = JdbiInquiryFinancialDocumentRepository()
        val history = JdbiInquiryProposalRepository()
        val plans = JdbiInquiryServicePlanRepository()
        val percent = DepositTerms.Percentage(BigDecimal("20"))

        fun money(amount: String) = Money(BigDecimal(amount), usd)

        fun new(
            key: String,
            line: TestLine,
        ) = ProposedLine(ProposedLineIdentity.New(LineKey(key)), line.priced())

        fun existing(
            line: LineItem,
            replacement: TestLine? = null,
        ) = ProposedLine(
            ProposedLineIdentity.Existing(line.id),
            replacement?.priced() ?: PricedLine(line.description, line.subDescription, line.quantity, line.price, line.taxAmount),
        )

        val plan =
            ProposedServicePlan(
                ServiceCommitment(
                    "Churro catering for an evening reception",
                    100,
                    120,
                    listOf("Churros with chocolate sauce", "Cinnamon sugar"),
                ),
                listOf(ProposedLineNote(ProposedLineIdentity.New(LineKey("courtesy")), "Returning customer courtesy")),
            )

        /** The example that must pass: no soft serve at all, a bespoke churro service and a separate courtesy discount. */
        val churroSwitch = LineProposal(listOf(new("churros", CHURROS), new("courtesy", COURTESY_DISCOUNT)))

        fun proposals(
            servicePlans: InquiryServicePlanRepository = plans,
            publications: InquiryProposalRepository = history,
            associations: InquiryFinancialDocumentRepository = owners,
        ) = InquiryProposals(
            app.context.financialLedger,
            associations,
            JdbiFinancialDocumentAuthorshipRepository(),
            publications,
            servicePlans,
            testClock,
        )

        fun preview(
            id: InquiryId,
            lines: LineProposal,
            servicePlan: ProposedServicePlan? = null,
            terms: DepositTerms = percent,
            version: Int = 1,
        ) = PreviewInquiryQuote(app.transactor, app.context.financialLedger, owners, history)(
            PreviewInquiryQuote.Command(id, Version.of(version), lines, servicePlan, terms),
        )

        fun issue(
            id: InquiryId,
            lines: LineProposal?,
            servicePlan: ProposedServicePlan? = null,
            token: QuoteReviewToken? = null,
            terms: DepositTerms = percent,
            version: Int = 1,
            core: InquiryProposals = proposals(),
        ) = IssueInquiryProposal(app.transactor, core)(
            IssueInquiryProposal.Command(
                id,
                Version.of(version),
                terms,
                app.adminId,
                lines?.let { ReviewedQuoteComposition(it, servicePlan, token ?: preview(id, it, servicePlan, terms, version).reviewToken) },
            ),
        )

        fun newInquiry(): InquiryId = InquiryId(UUID.fromString(app.createInquiry()))

        fun lineage(id: InquiryId) = app.transactor.inTransaction { owners.initialEstimateOf(it, id)!! }

        fun estimate(id: InquiryId) =
            app.context.financialLedger
                .history(lineage(id))
                .first() as FinancialDocument.Estimate

        val tables =
            listOf(
                "commerce.financial_document_snapshots",
                "commerce.deposit_requirement_revisions",
                "commerce.payment_records",
                "fionas.inquiry_proposals",
                "fionas.inquiry_service_plans",
                "fionas.financial_document_authorship",
                "fionas.inquiry_communications",
            )

        fun counts() = tables.map(app.database::count)

        /** Every durable fact of [id]'s lineage, compared across an attempt that must write nothing. */
        fun unchanged(
            id: InquiryId,
            action: () -> Unit,
        ) {
            val document = lineage(id)
            val before = app.context.financialLedger.history(document)
            val deposits =
                app.context.financialLedger
                    .depositRequirementHistory(document)
                    .map { it.requirement }
            val publications = app.transactor.inTransaction { history.history(it, id) }
            val requested = app.transactor.inTransaction { JdbiInquiryRepository().findRequested(it, id) }
            val totals = counts()
            action()
            app.context.financialLedger.history(document) shouldBe before
            app.context.financialLedger
                .depositRequirementHistory(document)
                .map { it.requirement } shouldBe deposits
            app.transactor.inTransaction { history.history(it, id) } shouldBe publications
            app.transactor.inTransaction { JdbiInquiryRepository().findRequested(it, id) } shouldBe requested
            counts() shouldBe totals
        }

        fun observedOwners(pid: AtomicInteger) =
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

        fun awaitAssociationWait(pid: AtomicInteger) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (pid.get() == 0 ||
                app.database.strings("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ${pid.get()}").singleOrNull() != "Lock"
            ) {
                check(System.nanoTime() < deadline) { "Contender did not wait on the canonical association" }
                Thread.yield()
            }
        }

        beforeSpec { app = TestApplication.create() }
        afterSpec { app.close() }

        // The pure core: no database, catalog or pricing policy.

        val inquiry = InquiryId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
        val pureEstimate =
            FinancialDocument.Estimate.create(
                UUID.fromString("00000000-0000-0000-0000-0000000000e1"),
                acceptanceLines().mapIndexed { index, line -> line.priced().withId(UUID(0, index.toLong() + 1)) },
            )

        test("keeping every line composes the Estimate's own Quote successor, with no change order") {
            val composed =
                QuoteComposer.compose(
                    inquiry,
                    pureEstimate,
                    LineProposal(pureEstimate.lineItems.map { existing(it) }),
                    null,
                    percent,
                )
            composed.lines.changes.shouldBeNull()
            composed.financialChange shouldBe false
            composed.quote shouldBe pureEstimate.toQuote()
            composed.requiredDeposit shouldBe money("136.25")
            composed.lines.lines
                .map { it.origin }
                .toSet() shouldBe setOf(ResolvedLineOrigin.CARRIED)
        }

        test("the churro switch removes every soft-serve line and adds bespoke lines; domain totals and the deposit follow") {
            val composed = QuoteComposer.compose(inquiry, pureEstimate, churroSwitch, plan, percent)
            val changes = composed.lines.changes.shouldNotBeNull()
            changes.changes
                .filterIsInstance<ChangeOrder.Change.RemoveLineItem>()
                .map { it.lineItemId } shouldContainExactly pureEstimate.lineItems.map { it.id }
            composed.quote.lineItems.map { it.description } shouldContainExactly listOf("Churro catering service", "Courtesy discount")
            composed.quote.version shouldBe Version.of(3)
            composed.quote.total shouldBe money("400.00")
            composed.requiredDeposit shouldBe money("80.00")
            val (churros, courtesy) = composed.quote.lineItems
            churros.id shouldBe proposedLineId(pureEstimate.id, pureEstimate.version, LineKey("churros"))
            composed.servicePlan!!.lineNotes shouldBe listOf(ServicePlanLineNote(courtesy.id, "Returning customer courtesy"))
        }

        test("a direct override keeps its line id; an independent adjustment has its own derived identity") {
            val iceCream = pureEstimate.lineItems[1]
            val proposal =
                LineProposal(
                    pureEstimate.lineItems.map {
                        if (it ==
                            iceCream
                        ) {
                            existing(it, TestLine("Ice cream service", "Negotiated", null, "250.00"))
                        } else {
                            existing(it)
                        }
                    } +
                        new("travel", TestLine("Travel surcharge", null, null, "40.00")),
                )
            val composed = QuoteComposer.compose(inquiry, pureEstimate, proposal, null, percent)
            composed.lines.lines.map { it.origin } shouldContainExactly
                listOf(ResolvedLineOrigin.CARRIED, ResolvedLineOrigin.REPLACED) + List(3) { ResolvedLineOrigin.CARRIED } +
                ResolvedLineOrigin.NEW
            composed.quote.lineItems[1].id shouldBe iceCream.id
            composed.quote.lineItems[1].price shouldBe money("250.00")
            composed.quote.lineItems[1]
                .quantity
                .shouldBeNull()
            composed.quote.lineItems
                .last()
                .id shouldNotBe iceCream.id
            composed.quote.total shouldBe money("671.25")
            // The reviewed Estimate is untouched.
            pureEstimate.lineItems[1].price shouldBe money("4.00")
        }

        test("the review token is deterministic and binds lines, order, service plan, terms and reviewed version") {
            val base = QuoteComposer.compose(inquiry, pureEstimate, churroSwitch, plan, percent)
            QuoteComposer.compose(inquiry, pureEstimate, churroSwitch, plan, percent).reviewToken shouldBe base.reviewToken
            listOf(
                QuoteComposer.compose(inquiry, pureEstimate, LineProposal(churroSwitch.lines.reversed()), plan, percent),
                QuoteComposer.compose(
                    inquiry,
                    pureEstimate,
                    LineProposal(listOf(new("churros", CHURROS.copy(unitPrice = "451.00")), new("courtesy", COURTESY_DISCOUNT))),
                    plan,
                    percent,
                ),
                QuoteComposer.compose(inquiry, pureEstimate, churroSwitch, null, percent),
                QuoteComposer.compose(
                    inquiry,
                    pureEstimate,
                    churroSwitch,
                    plan.copy(service = plan.service.copy(guestCount = 101)),
                    percent,
                ),
                QuoteComposer.compose(inquiry, pureEstimate, churroSwitch, plan, DepositTerms.Percentage(BigDecimal("25"))),
                QuoteComposer.compose(inquiry, pureEstimate, churroSwitch, plan, DepositTerms.Fixed(money("80.00"))),
                QuoteComposer.compose(
                    inquiry,
                    pureEstimate.changeOrder(
                        ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(CHURROS.priced().withId(UUID.randomUUID())))),
                    ),
                    churroSwitch,
                    plan,
                    percent,
                ),
            ).forEach { it.reviewToken shouldNotBe base.reviewToken }
        }

        test("negative, zero, unknown-line, foreign-currency and unknown-note proposals reject with stable codes") {
            fun codes(
                proposal: LineProposal,
                servicePlan: ProposedServicePlan? = null,
            ) = shouldThrow<CommerceFailure.ValidationFailed> {
                QuoteComposer.compose(inquiry, pureEstimate, proposal, servicePlan, percent)
            }.violations.map { it.code }

            codes(LineProposal(listOf(new("credit", COURTESY_DISCOUNT)))) shouldBe listOf(LineProposalViolations.NEGATIVE_DOCUMENT_TOTAL)
            codes(LineProposal(listOf(new("free", TestLine("Complimentary service", unitPrice = "0.00"))))) shouldBe
                listOf(QUOTE_TOTAL_NOT_POSITIVE)
            codes(LineProposal(listOf(ProposedLine(ProposedLineIdentity.Existing(UUID.randomUUID()), CHURROS.priced())))) shouldBe
                listOf(LineProposalViolations.LINE_NOT_IN_REVIEWED_DOCUMENT)
            codes(LineProposal(listOf(new("eur", CHURROS.copy(currency = "EUR"))))) shouldBe
                listOf(LineProposalViolations.CURRENCY_MISMATCH)
            codes(
                churroSwitch,
                ProposedServicePlan(plan.service, listOf(ProposedLineNote(ProposedLineIdentity.New(LineKey("missing")), "Why"))),
            ) shouldBe listOf(SERVICE_PLAN_LINE_NOT_FOUND)
        }

        // Real PostgreSQL publication.

        test(
            "the churro switch previews write-free and publishes exactly the reviewed Quote, deposit, plan and proposal; it books, serves and closes",
        ) {
            val id = newInquiry()
            val original = estimate(id)
            lateinit var reviewed: ComposedQuote
            unchanged(id) { reviewed = preview(id, churroSwitch, plan) }
            reviewed.quote.total shouldBe money("400.00")

            val issued = issue(id, churroSwitch, plan, reviewed.reviewToken)
            val document = lineage(id)
            val ledger = app.context.financialLedger
            // Estimate v1 → Estimate v2 (the staff lines) → Quote v3, with v1 unchanged.
            ledger.history(document).map { it.javaClass.simpleName to it.version.number } shouldContainExactly
                listOf("Estimate" to 1, "Estimate" to 2, "Quote" to 3)
            ledger.history(document).first() shouldBe original
            val quote =
                issued.financial.latest.document
                    .shouldBeInstanceOf<FinancialDocument.Quote>()
            quote shouldBe reviewed.quote
            quote.lineItems.map { it.description } shouldContainExactly listOf("Churro catering service", "Courtesy discount")
            issued.financial.latest.authorship shouldBe LineAuthorship(app.adminId, STORED_INSTANT)
            app.transactor.inTransaction {
                JdbiFinancialDocumentAuthorshipRepository().findAll(it, document).mapValues { (_, authored) -> authored.author }
            } shouldBe mapOf(Version.of(1) to app.web.id, Version.of(2) to app.adminId, Version.of(3) to app.adminId)
            val active =
                issued.deposit.depositRequirement!!
                    .requirement
                    .shouldBeInstanceOf<DepositRequirement.Active>()
            active.approvalReference shouldBe quote.reference
            active.requiredAmount shouldBe reviewed.requiredDeposit
            issued.proposal.kind shouldBe ProposalIssuanceKind.INITIAL
            issued.proposal.issuedBy shouldBe app.adminId
            val stored = app.transactor.inTransaction { plans.find(it, quote.reference) }.shouldNotBeNull()
            stored shouldBe issued.servicePlan
            stored.service shouldBe plan.service
            stored.reviewedVersion shouldBe Version.INITIAL
            stored.approvedBy shouldBe app.adminId
            stored.lineNotes.single().lineItemId shouldBe quote.lineItems[1].id

            // Accepting the exact deposit books the inquiry; serving and paying in full close it.
            val pay =
                RecordDocumentPayment(
                    app.transactor,
                    ledger,
                    owners,
                    JdbiFinancialDocumentAuthorshipRepository(),
                    testClock,
                    history,
                )
            pay(
                RecordDocumentPayment.Command(
                    document,
                    Version.of(3),
                    BigDecimal("80.00"),
                    PaymentMethod.CARD,
                    null,
                    null,
                    issued.proposal.id,
                ),
            ).document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
            val lifecycle = ReadInquiryLifecycle(ledger, owners, JdbiInquiryFulfillmentRepository())
            app.transactor.inTransaction { lifecycle.read(it, id) }.stage shouldBe InquiryStage.BOOKED
            val fulfillment =
                ManageInquiryFulfillment(
                    app.transactor,
                    JdbiInquiryRepository(),
                    owners,
                    ledger,
                    JdbiInquiryFulfillmentRepository(),
                    testClock,
                )
            fulfillment.markServed(ManageInquiryFulfillment.Command(id, app.adminId)).stage shouldBe InquiryStage.SERVED
            pay(RecordDocumentPayment.Command(document, Version.of(4), BigDecimal("320.00"), PaymentMethod.CASH, null, null))
            fulfillment.close(ManageInquiryFulfillment.Command(id, app.adminId)).stage shouldBe InquiryStage.CLOSED
            ledger.history(document).first() shouldBe original
        }

        test("unchanged lines publish Estimate v1 → Quote v2 directly, with a service plan when one is approved") {
            val id = newInquiry()
            val kept = LineProposal(estimate(id).lineItems.map { existing(it) })
            val servicePlan = ProposedServicePlan(ServiceCommitment("Soft serve as requested", 75, 120, emptyList()))
            val issued = issue(id, kept, servicePlan)
            issued.financial.latest.document.version shouldBe Version.of(2)
            issued.financial.latest.document.lineItems shouldBe estimate(id).lineItems
            issued.servicePlan!!.reviewedVersion shouldBe Version.INITIAL
            issued.financial.latest.authorship
                ?.author shouldBe app.web.id
        }

        test("approval re-composes from authoritative state: a tampered or stale review writes nothing") {
            val id = newInquiry()
            val token = preview(id, churroSwitch, plan).reviewToken
            // Different amounts, order, service plan or terms than were reviewed.
            listOf(
                { issue(id, LineProposal(churroSwitch.lines.reversed()), plan, token) },
                {
                    issue(
                        id,
                        LineProposal(listOf(new("churros", CHURROS.copy(unitPrice = "100.00")), new("courtesy", COURTESY_DISCOUNT))),
                        plan,
                        token,
                    )
                },
                { issue(id, churroSwitch, null, token) },
                { issue(id, churroSwitch, plan, token, terms = DepositTerms.Percentage(BigDecimal("50"))) },
            ).forEach { attempt ->
                unchanged(id) { shouldThrow<CommerceFailure.Conflict> { attempt() }.cause.shouldBeInstanceOf<QuoteReviewStale>() }
            }
            // The Estimate moved on after review: the reviewed version is stale, and nothing is rebased.
            val estimateNow = estimate(id)
            app.transactor.inTransaction { transaction ->
                app.context.financialLedger.changeOrder(
                    transaction,
                    estimateNow.id,
                    ChangeOrder(
                        listOf(
                            ChangeOrder.Change.AddLineItem(TestLine("Late charge", unitPrice = "1.00").priced().withId(UUID.randomUUID())),
                        ),
                    ),
                    Version.INITIAL,
                )
            }
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { issue(id, churroSwitch, plan, token) } }
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { preview(id, churroSwitch, plan) } }
        }

        test("failure at the plan insert, deposit approval or publication rolls back every fact") {
            val id = newInquiry()
            val token = preview(id, churroSwitch, plan).reviewToken
            val document = lineage(id)
            val failingPlan =
                object : InquiryServicePlanRepository by plans {
                    override fun insert(
                        transaction: Transaction,
                        plan: InquiryServicePlan,
                    ) {
                        app.context.financialLedger
                            .history(transaction, document)
                            .map { it.version.number } shouldContainExactly
                            listOf(1, 2, 3)
                        plans.insert(transaction, plan)
                        error("after plan insert")
                    }
                }
            unchanged(
                id,
            ) { shouldThrow<IllegalStateException> { issue(id, churroSwitch, plan, token, core = proposals(servicePlans = failingPlan)) } }
            val competingDeposit =
                object : InquiryServicePlanRepository by plans {
                    override fun insert(
                        transaction: Transaction,
                        plan: InquiryServicePlan,
                    ) {
                        plans.insert(transaction, plan)
                        app.context.financialLedger.activateDepositRequirement(
                            transaction,
                            document,
                            plan.quote.version,
                            DepositTerms.Fixed(money("10.00")),
                            null,
                        )
                    }
                }
            unchanged(id) {
                shouldThrow<CommerceFailure.Conflict> {
                    issue(
                        id,
                        churroSwitch,
                        plan,
                        token,
                        core = proposals(servicePlans = competingDeposit),
                    )
                }
            }
            val failingPublication =
                object : InquiryProposalRepository by history {
                    override fun append(
                        transaction: Transaction,
                        proposal: InquiryProposal,
                    ) {
                        history.append(transaction, proposal)
                        error("after publication")
                    }
                }
            unchanged(id) {
                shouldThrow<IllegalStateException> {
                    issue(
                        id,
                        churroSwitch,
                        plan,
                        token,
                        core = proposals(publications = failingPublication),
                    )
                }
            }
            issue(id, churroSwitch, plan, token)
                .financial.latest.document.version shouldBe Version.of(3)
        }

        test("concurrent approvals of one reviewed Estimate: one publishes, the waiting one conflicts without rebasing") {
            val id = newInquiry()
            val token = preview(id, churroSwitch, plan).reviewToken
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
            val first = CompletableFuture.supplyAsync { issue(id, churroSwitch, plan, token, core = proposals(servicePlans = pausing)) }
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                val pid = AtomicInteger()
                val second =
                    CompletableFuture.supplyAsync {
                        runCatching {
                            issue(
                                id,
                                churroSwitch,
                                plan,
                                token,
                                core = proposals(associations = observedOwners(pid)),
                            )
                        }.exceptionOrNull()
                    }
                awaitAssociationWait(pid)
                second.isDone shouldBe false
                resume.countDown()
                first
                    .get(30, TimeUnit.SECONDS)
                    .financial.latest.document.version shouldBe Version.of(3)
                second.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.Conflict>()
            } finally {
                resume.countDown()
            }
            app.transactor.inTransaction { history.history(it, id) }.size shouldBe 1
            app.context.financialLedger
                .history(lineage(id))
                .size shouldBe 3
        }

        test("issuance without lines keeps the Estimate and records no plan; only an unissued Estimate can be composed") {
            val id = newInquiry()
            val issued = app.issueProposal(id)
            issued.servicePlan.shouldBeNull()
            issued.financial.latest.document.version shouldBe Version.of(2)
            shouldThrow<CommerceFailure.IllegalTransition> { preview(id, churroSwitch, version = 2) }
        }

        test("a Quote revision commits staff lines with a new plan; a deposit-only revision keeps the Quote and records none") {
            val id = newInquiry()
            val first = issue(id, churroSwitch, plan)
            val quote = first.financial.latest.document
            val (churros, courtesy) = quote.lineItems
            val revision =
                ReviseInquiryQuoteProposal(app.transactor, app.inquiryProposals())(
                    ReviseInquiryQuoteProposal.Command(
                        id,
                        quote.version,
                        DepositRequirementRevision.INITIAL,
                        LineProposal(listOf(existing(churros, CHURROS.copy(unitPrice = "500.00")), existing(courtesy))),
                        percent,
                        app.adminId,
                        ProposedServicePlan(plan.service.copy(guestCount = 120)),
                    ),
                )
            val revised = revision.financial.latest.document
            revised.version shouldBe Version.of(4)
            revised.lineItems.map { it.id } shouldContainExactly listOf(churros.id, courtesy.id)
            revised.total shouldBe money("450.00")
            revision.proposal.kind shouldBe ProposalIssuanceKind.QUOTE_REVISED
            revision.servicePlan!!.reviewedVersion shouldBe Version.of(3)
            revision.servicePlan.service.guestCount shouldBe 120
            // The earlier plan stays as history of its own Quote.
            app.transactor.inTransaction { plans.find(it, quote.reference) } shouldBe first.servicePlan
            val depositOnly =
                ReviseInquiryProposalDeposit(app.transactor, app.inquiryProposals())(
                    ReviseInquiryProposalDeposit.Command(
                        id,
                        Version.of(4),
                        DepositRequirementRevision.of(2),
                        DepositTerms.Fixed(money("100.00")),
                        app.adminId,
                    ),
                )
            depositOnly.servicePlan.shouldBeNull()
            app.transactor.inTransaction { plans.find(it, FinancialDocumentReference(revised.id, Version.of(4))) }.shouldNotBeNull()
            // Revising to the same lines is no change.
            shouldThrow<CommerceFailure.ValidationFailed> {
                ReviseInquiryQuoteProposal(app.transactor, app.inquiryProposals())(
                    ReviseInquiryQuoteProposal.Command(
                        id,
                        Version.of(4),
                        DepositRequirementRevision.of(3),
                        LineProposal(revised.lineItems.map { existing(it) }),
                        percent,
                        app.adminId,
                    ),
                )
            }.violations.map { it.code } shouldBe listOf(LineProposalViolations.NO_FINANCIAL_CHANGE)
        }

        test("a staff approver that is not a real user cannot be recorded") {
            val id = newInquiry()
            val ghost = UserId(UUID.randomUUID())
            unchanged(id) {
                runCatching {
                    IssueInquiryProposal(app.transactor, proposals())(IssueInquiryProposal.Command(id, Version.INITIAL, percent, ghost))
                }.isFailure shouldBe true
            }
        }
    })
