package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.offering.OfferingPriceDto
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalId
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.http.InquiryStageResponse
import io.github.castab.fionas.commerce.http.StaffRequestResponse
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.offering.CatalogRevisionStale
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.currentCatalogRevision
import io.github.castab.fionas.commerce.testing.fionasPricing
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.retireOfferings
import io.github.castab.fionas.commerce.testing.testClock
import io.github.castab.fionas.commerce.testing.updateOffering
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The quote builder's initial composition through the real application and PostgreSQL: a
 * write-free preview, and one atomic issuance of Estimate → [Estimate] → Quote, service plan,
 * deposit approval and publication. Amounts derive from Fiona's actual engine and acceptance
 * catalog: 40 guests for two hours with vanilla, four included toppings and waffle cones price
 * base $250.00 + ice cream $160.00 + waffle cones $30.00 = $440.00.
 */
class QuoteBuilderSpec :
    FunSpec({
        lateinit var app: TestApplication
        val owners = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()
        val history = JdbiInquiryProposalRepository()
        val plans = JdbiInquiryServicePlanRepository()
        val actor = UserId(UUID.randomUUID())
        val usd = Currency.getInstance("USD")

        fun usd(amount: String) = Money(BigDecimal(amount), usd)

        fun fixed(amount: String) = DepositTerms.Fixed(usd(amount))

        fun composition() = InquiryQuoteComposition(JdbiInquiryRepository(), sources, app.fionasPricing())

        fun proposals(
            servicePlans: InquiryServicePlanRepository = plans,
            publications: InquiryProposalRepository = history,
            associations: InquiryFinancialDocumentRepository = owners,
        ) = InquiryProposals(
            app.context.financialLedger,
            associations,
            sources,
            publications,
            servicePlans,
            composition(),
            app.fionasPricing(),
            testClock,
        )

        fun preview(
            id: InquiryId,
            composition: QuoteComposition,
            terms: DepositTerms = fixed("105.00"),
            version: Int = 1,
        ) = PreviewInquiryQuote(app.transactor, app.context.financialLedger, owners, history, composition())(
            PreviewInquiryQuote.Command(id, Version.of(version), composition, terms),
        )

        fun issue(
            id: InquiryId,
            composition: QuoteComposition?,
            token: QuoteReviewToken? = null,
            terms: DepositTerms = fixed("105.00"),
            version: Int = 1,
            core: InquiryProposals = proposals(),
            principal: PrincipalId = actor,
        ) = IssueInquiryProposal(app.transactor, core)(
            IssueInquiryProposal.Command(
                id,
                Version.of(version),
                terms,
                principal,
                composition?.let { ReviewedQuoteComposition(it, token ?: preview(id, it, terms, version).reviewToken) },
            ),
        )

        fun newInquiry(): InquiryId =
            InquiryId(
                UUID.fromString(
                    app.createInquiry {
                        pricingBody(
                            it,
                            guests = 40,
                            softServe = listOf("vanilla"),
                            toppings = TOPPINGS.take(4),
                            cones = listOf("waffle-cone"),
                        )
                    },
                ),
            )

        fun lineage(id: InquiryId) = app.transactor.inTransaction { owners.initialEstimateOf(it, id)!! }

        fun estimateLines(id: InquiryId) =
            app.context.financialLedger
                .history(lineage(id))
                .first()
                .lineItems

        fun requested(id: InquiryId) = app.transactor.inTransaction { JdbiInquiryRepository().findRequested(it, id)!!.pricingInputs }

        fun reason(text: String) = QuoteEditReason(text)

        fun staffRequest(id: InquiryId) =
            CommerceJson.asA(app.adminGet("/staff/requests/${id.value}").bodyString(), StaffRequestResponse.serializer())

        val tables =
            listOf(
                "commerce.financial_document_snapshots",
                "commerce.deposit_requirement_revisions",
                "commerce.payment_records",
                "fionas.inquiry_proposals",
                "fionas.inquiry_service_plans",
                "fionas.financial_document_pricing",
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
            val requestedInputs = requested(id)
            val totals = counts()
            action()
            app.context.financialLedger.history(document) shouldBe before
            app.context.financialLedger
                .depositRequirementHistory(document)
                .map { it.requirement } shouldBe deposits
            app.transactor.inTransaction { history.history(it, id) } shouldBe publications
            requested(id) shouldBe requestedInputs
            counts() shouldBe totals
        }

        fun keep(
            overrides: List<QuoteLineOverride> = emptyList(),
            adjustments: List<QuoteAdjustment> = emptyList(),
        ) = QuoteComposition(QuotePricing.KeepEstimate, overrides, adjustments)

        fun adjustment(
            key: String,
            kind: QuoteAdjustmentKind,
            amount: String,
            description: String,
            why: String,
        ) = QuoteAdjustment(QuoteAdjustmentKey(key), kind, QuoteLineDescription(description), null, usd(amount), reason(why))

        fun selections(
            softServe: List<String> = listOf("vanilla"),
            cones: List<String> = listOf("waffle-cone"),
        ) = OfferingSelections(
            listOf(
                OfferingCategorySelection(OfferingCategoryKey("soft-serve-flavor"), softServe.map(::OfferingKey)),
                OfferingCategorySelection(OfferingCategoryKey("topping"), TOPPINGS.take(4).map(::OfferingKey)),
                OfferingCategorySelection(OfferingCategoryKey("cone-option"), cones.map(::OfferingKey)),
            ),
        )

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

        beforeSpec {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }

        test("preview of an untouched Estimate is its persisted result, writes nothing, and fails write-free") {
            val id = newInquiry()
            val lines = estimateLines(id)
            lines.map {
                it.total.amount
                    .stripTrailingZeros()
                    .toPlainString()
            } shouldContainExactly listOf("250", "160", "30")
            unchanged(id) {
                val composed = preview(id, keep())
                composed.financialChange shouldBe false
                composed.quote.version shouldBe Version.of(2)
                composed.quote.lineItems shouldBe lines
                composed.quote.total.amount shouldBe BigDecimal("440.00")
                composed.requiredDeposit shouldBe usd("105.00")
                composed.selections.flatMap { it.offerings }.map { it.displayName } shouldContainExactly
                    listOf("Vanilla", "Sprinkles", "Oreos", "Strawberries", "Brownies", "Waffle cones")
                preview(id, keep()).reviewToken shouldBe composed.reviewToken
                shouldThrow<CommerceFailure.Conflict> { preview(id, keep(), version = 2) }
                shouldThrow<CommerceFailure.NotFound> { preview(InquiryId(UUID.randomUUID()), keep()) }
                shouldThrow<CommerceFailure.ValidationFailed> { preview(id, keep(), fixed("440.01")) }
                shouldThrow<CommerceFailure.ValidationFailed> {
                    preview(id, keep(adjustments = listOf(adjustment("c", QuoteAdjustmentKind.CREDIT, "440.00", "Credit", "Free event"))))
                }.violations.single().code shouldBe QuoteCompositionViolations.QUOTE_TOTAL_NOT_POSITIVE
                shouldThrow<CommerceFailure.ValidationFailed> {
                    preview(id, keep(adjustments = listOf(adjustment("c", QuoteAdjustmentKind.CREDIT, "500.00", "Credit", "Too much"))))
                }.violations.single().code shouldBe QuoteCompositionViolations.NEGATIVE_DOCUMENT_TOTAL
            }
        }

        test("the unmodified Estimate issues as Quote v2 with an approved plan; Estimate and requested inputs stay intact") {
            val id = newInquiry()
            val lines = estimateLines(id)
            val inputs = requested(id)
            val issued = issue(id, keep())
            val quote =
                issued.financial.latest.document
                    .shouldBeInstanceOf<FinancialDocument.Quote>()
            quote.version shouldBe Version.of(2)
            quote.lineItems shouldBe lines
            app.context.financialLedger
                .history(quote.id)
                .map { it.version.number } shouldContainExactly listOf(1, 2)
            app.context.financialLedger
                .history(quote.id)
                .first()
                .lineItems shouldBe lines
            requested(id) shouldBe inputs
            val plan = issued.servicePlan.shouldNotBeNull()
            plan.quote shouldBe quote.reference
            plan.reviewedEstimate shouldBe FinancialDocumentReference(quote.id, Version.INITIAL)
            plan.basis shouldBe QuotePricingBasis.KEEP_ESTIMATE
            plan.lines.map { it.lineItemId } shouldContainExactly lines.map { it.id }
            plan.lines.all { it.origin == ServicePlanLineOrigin.EstimateLine && it.overrideReason == null } shouldBe true
            plan.context shouldBe inputs.context
            app.transactor.inTransaction { plans.find(it, quote.reference) } shouldBe plan
            // Composed Quotes record their provenance in the plan, not legacy pricing metadata.
            issued.financial.latest.pricing
                .shouldBeNull()
            val active =
                issued.deposit.depositRequirement!!
                    .requirement
                    .shouldBeInstanceOf<DepositRequirement.Active>()
            active.approvalReference shouldBe quote.reference
            active.requiredAmount shouldBe usd("105.00")
            issued.proposal.kind shouldBe ProposalIssuanceKind.INITIAL
            issued.proposal.documentReference shouldBe quote.reference
        }

        test("an override, a charge and a discount publish Estimate v1 → Estimate v2 → Quote v3 with exact provenance, then book once") {
            val id = newInquiry()
            val (base, iceCream, waffle) = estimateLines(id)
            val composition =
                keep(
                    listOf(
                        QuoteLineOverride(QuoteOverrideTarget.ExistingLine(iceCream.id), usd("145.00"), reason("Negotiated package rate")),
                    ),
                    listOf(
                        adjustment("travel-1", QuoteAdjustmentKind.CHARGE, "25.00", "Additional travel fee", "Outside normal service area"),
                        adjustment("courtesy-1", QuoteAdjustmentKind.DISCOUNT, "20.00", "Courtesy discount", "Customer accommodation"),
                    ),
                )
            val reviewed = preview(id, composition)
            reviewed.quote.total.amount shouldBe BigDecimal("430.00")
            val issued = issue(id, composition, reviewed.reviewToken)
            val versions = app.context.financialLedger.history(lineage(id))
            versions.map { it::class } shouldContainExactly
                listOf(FinancialDocument.Estimate::class, FinancialDocument.Estimate::class, FinancialDocument.Quote::class)
            versions.map { it.total.amount } shouldContainExactly listOf(BigDecimal("440.00"), BigDecimal("430.00"), BigDecimal("430.00"))
            versions[0].lineItems shouldContainExactly listOf(base, iceCream, waffle)
            val quote =
                issued.financial.latest.document
                    .shouldBeInstanceOf<FinancialDocument.Quote>()
            quote.version shouldBe Version.of(3)
            quote.lineItems[0] shouldBe base
            quote.lineItems[1].id shouldBe iceCream.id
            quote.lineItems[1].quantity.shouldBeNull()
            quote.lineItems[1].price.amount shouldBe BigDecimal("145.00")
            quote.lineItems[2] shouldBe waffle
            quote.lineItems.drop(3).map { it.price.amount } shouldContainExactly listOf(BigDecimal("25.00"), BigDecimal("-20.00"))
            // The published lines are the reviewed lines, persisted ids included.
            quote.lineItems.map { it.description } shouldContainExactly reviewed.quote.lineItems.map { it.description }
            val plan = issued.servicePlan.shouldNotBeNull()
            plan.reviewedEstimate.version shouldBe Version.INITIAL
            plan.approvedAt shouldBe STORED_INSTANT
            plan.principalId shouldBe actor
            plan.lines shouldContainExactly
                listOf(
                    ServicePlanLine(base.id, ServicePlanLineOrigin.EstimateLine, null),
                    ServicePlanLine(iceCream.id, ServicePlanLineOrigin.EstimateLine, reason("Negotiated package rate")),
                    ServicePlanLine(waffle.id, ServicePlanLineOrigin.EstimateLine, null),
                    ServicePlanLine(
                        quote.lineItems[3].id,
                        ServicePlanLineOrigin.Adjustment(QuoteAdjustmentKind.CHARGE, reason("Outside normal service area")),
                        null,
                    ),
                    ServicePlanLine(
                        quote.lineItems[4].id,
                        ServicePlanLineOrigin.Adjustment(QuoteAdjustmentKind.DISCOUNT, reason("Customer accommodation")),
                        null,
                    ),
                )
            plan.selections.flatMap { it.offerings }.map { it.offering.value } shouldContainExactly
                listOf("vanilla") + TOPPINGS.take(4) + "waffle-cone"
            val active =
                issued.deposit.depositRequirement!!
                    .requirement
                    .shouldBeInstanceOf<DepositRequirement.Active>()
            active.approvalReference shouldBe quote.reference
            active.requiredAmount shouldBe usd("105.00")
            // Issued, not sent: no communication, payment or booking follows approval.
            val quoted = staffRequest(id)
            quoted.inquiry.lifecycle.stage shouldBe InquiryStageResponse.QUOTED
            quoted.servicePlan.shouldNotBeNull().documentVersion shouldBe 3
            quoted.payments shouldBe emptyList()
            app.transactor
                .inTransaction { JdbiInquiryRepository().findRequested(it, id)!! }
                .pricingInputs.context.guestCount shouldBe 40
            app.database.count("fionas.inquiry_communications") shouldBe 0
            // The existing exact full-deposit acceptance books the same lineage.
            val paid =
                RecordDocumentPayment(app.transactor, app.context.financialLedger, owners, sources, testClock, history)(
                    RecordDocumentPayment.Command(
                        quote.id,
                        quote.version,
                        BigDecimal("105.00"),
                        PaymentMethod.CASH,
                        null,
                        null,
                        issued.proposal.id,
                    ),
                )
            paid.document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
                .lineItems shouldBe quote.lineItems
            val booked = staffRequest(id)
            booked.inquiry.lifecycle.stage shouldBe InquiryStageResponse.BOOKED
            booked.servicePlan shouldBe quoted.servicePlan
        }

        test("changed unpriced selections are pinned as a service-only plan without an intermediate Estimate") {
            val id = newInquiry()
            val lines = estimateLines(id)
            val revision = OfferingsRevision.of(app.currentCatalogRevision())
            val issued =
                issue(id, QuoteComposition(QuotePricing.ReviseServiceSelections(revision, selections(softServe = listOf("chocolate")))))
            issued.financial.latest.document.version shouldBe Version.of(2)
            issued.financial.latest.document.lineItems shouldBe lines
            val plan = issued.servicePlan.shouldNotBeNull()
            plan.basis shouldBe QuotePricingBasis.REVISE_SERVICE_SELECTIONS
            plan.selections
                .first()
                .offerings
                .map { it.displayName } shouldContainExactly listOf("Chocolate")
            requested(id)
                .selections.categories
                .first()
                .offerings shouldContainExactly listOf(OfferingKey("vanilla"))
            // A priced change is never smuggled through: Horchata and Cups change the charges.
            val other = newInquiry()
            unchanged(other) {
                shouldThrow<CommerceFailure.ValidationFailed> {
                    issue(
                        other,
                        QuoteComposition(QuotePricing.ReviseServiceSelections(revision, selections(softServe = listOf("horchata")))),
                    )
                }.violations.single().code shouldBe QuoteCompositionViolations.SERVICE_SELECTIONS_CHANGE_PRICING
            }
        }

        test("repricing a reviewed configuration targets generated sources and requires the current catalog revision") {
            val id = newInquiry()
            val revision = app.currentCatalogRevision()
            val inputs =
                FionasPricingInputs(
                    OfferingsRevision.of(revision),
                    selections(softServe = listOf("vanilla", "horchata")),
                    FionasOfferingsContext(50, false, Duration.ofMinutes(150)),
                )
            val waffleSource = FionasChargeSource.SelectedOffering(OfferingCategoryKey("cone-option"), OfferingKey("waffle-cone"))
            val composition =
                QuoteComposition(
                    QuotePricing.RepriceConfiguration(inputs),
                    listOf(QuoteLineOverride(QuoteOverrideTarget.GeneratedCharge(waffleSource), usd("30.00"), reason("Cone promotion"))),
                )
            unchanged(id) {
                shouldThrow<CommerceFailure.Conflict> {
                    issue(
                        id,
                        composition.copy(
                            pricing =
                                QuotePricing.RepriceConfiguration(
                                    inputs.copy(
                                        catalogRevision =
                                            OfferingsRevision.of(
                                                revision - 1,
                                            ),
                                    ),
                                ),
                        ),
                    )
                }.cause.shouldBeInstanceOf<CatalogRevisionStale>()
            }
            val estimate = estimateLines(id)
            val issued = issue(id, composition)
            val quote = issued.financial.latest.document
            quote.version shouldBe Version.of(3)
            // base 150 + 2.5 h × 50 = 275; ice cream 50 × 4 = 200; horchata 50 × 0.50 = 25; waffle cones overridden 37.50 → 30.
            quote.lineItems.map {
                it.total.amount
                    .stripTrailingZeros()
                    .toPlainString()
            } shouldContainExactly
                listOf("275", "200", "25", "30")
            quote.lineItems.none { line -> estimate.any { it.id == line.id } } shouldBe true
            val plan = issued.servicePlan.shouldNotBeNull()
            plan.lines.map { (it.origin as ServicePlanLineOrigin.Generated).source } shouldContainExactly
                listOf(
                    FionasChargeSource.BaseService,
                    FionasChargeSource.IceCreamService,
                    FionasChargeSource.SelectedOffering(OfferingCategoryKey("soft-serve-flavor"), OfferingKey("horchata")),
                    waffleSource,
                )
            plan.lines.last().overrideReason shouldBe reason("Cone promotion")
            plan.context shouldBe inputs.context
            issued.financial.latest.pricing
                .shouldBeNull()
            app.transactor.inTransaction { sources.findAll(it, quote.id) } shouldBe emptyMap()
        }

        test("approval re-evaluates the composition: a changed result after review is stale and writes nothing") {
            val id = newInquiry()
            val composition = keep()
            val token = preview(id, composition).reviewToken
            unchanged(id) {
                shouldThrow<CommerceFailure.Conflict> {
                    issue(id, composition, QuoteReviewToken("0".repeat(64)))
                }.cause.shouldBeInstanceOf<QuoteReviewStale>()
                shouldThrow<CommerceFailure.Conflict> { issue(id, composition, token, terms = fixed("100.00")) }
                    .cause
                    .shouldBeInstanceOf<QuoteReviewStale>()
            }
            // A catalog rename after review changes the names staff would approve.
            app.updateOffering(app.currentCatalogRevision(), "vanilla") { it.copy(displayName = "Vanilla bean") }
            unchanged(id) {
                shouldThrow<CommerceFailure.Conflict> { issue(id, composition, token) }.cause.shouldBeInstanceOf<QuoteReviewStale>()
            }
            val reviewed = preview(id, composition)
            reviewed.selections
                .first()
                .offerings
                .single()
                .displayName shouldBe "Vanilla bean"
            issue(id, composition, reviewed.reviewToken)
                .servicePlan!!
                .selections
                .first()
                .offerings
                .single()
                .displayName shouldBe "Vanilla bean"
            app.updateOffering(app.currentCatalogRevision(), "vanilla") { it.copy(displayName = "Vanilla") }
        }

        test("failure at the change-order write, plan insert, deposit approval or publication rolls back every fact") {
            val id = newInquiry()
            val (_, iceCream) = estimateLines(id)
            val composition =
                keep(listOf(QuoteLineOverride(QuoteOverrideTarget.ExistingLine(iceCream.id), usd("150.00"), reason("Negotiated"))))
            val token = preview(id, composition).reviewToken
            val document = lineage(id)
            // Change order: another runtime writer holds the lineage's NOWAIT mutation lock.
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                CompletableFuture.runAsync {
                    runCatching {
                        app.transactor.inTransaction { transaction ->
                            app.context.financialLedger.activateDepositRequirement(
                                transaction,
                                document,
                                Version.INITIAL,
                                fixed("10.00"),
                                null,
                            )
                            holding.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                            error("roll back the holder")
                        }
                    }
                }
            try {
                holding.await(30, TimeUnit.SECONDS) shouldBe true
                unchanged(id) { shouldThrow<CommerceFailure.Conflict> { issue(id, composition, token) } }
            } finally {
                release.countDown()
                holder.get(30, TimeUnit.SECONDS)
            }
            // Plan insert, after both ledger successors exist in the transaction.
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
            ) { shouldThrow<IllegalStateException> { issue(id, composition, token, core = proposals(servicePlans = failingPlan)) } }
            // Deposit approval: a competing requirement makes the approval's expected empty history stale.
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
                            fixed("10.00"),
                            null,
                        )
                    }
                }
            unchanged(id) {
                shouldThrow<CommerceFailure.Conflict> { issue(id, composition, token, core = proposals(servicePlans = competingDeposit)) }
            }
            // Publication, after the deposit was approved against the final Quote.
            val failingPublication =
                object : InquiryProposalRepository by history {
                    override fun append(
                        transaction: Transaction,
                        proposal: InquiryProposal,
                    ) {
                        app.context.financialLedger
                            .latestDepositRequirement(transaction, document)!!
                            .requirement
                            .shouldBeInstanceOf<DepositRequirement.Active>()
                            .approvalReference shouldBe proposal.documentReference
                        history.append(transaction, proposal)
                        error("after publication")
                    }
                }
            unchanged(id) {
                shouldThrow<IllegalStateException> { issue(id, composition, token, core = proposals(publications = failingPublication)) }
            }
            issue(id, composition, token)
                .financial.latest.document.version shouldBe Version.of(3)
        }

        test("concurrent composed approvals of one Estimate: one publishes, the waiting one conflicts") {
            val id = newInquiry()
            val (_, iceCream) = estimateLines(id)
            val composition =
                keep(listOf(QuoteLineOverride(QuoteOverrideTarget.ExistingLine(iceCream.id), usd("150.00"), reason("Negotiated"))))
            val token = preview(id, composition).reviewToken
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
            val first = CompletableFuture.supplyAsync { issue(id, composition, token, core = proposals(servicePlans = pausing)) }
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                val pid = AtomicInteger()
                val second =
                    CompletableFuture.supplyAsync {
                        runCatching {
                            issue(
                                id,
                                composition,
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
            app.database.count("fionas.inquiry_service_plans WHERE document_id = '${lineage(id)}'") shouldBe 1
            app.context.financialLedger
                .history(lineage(id))
                .size shouldBe 3
        }

        test("deposit-only issuance remains compatible and records no plan; SERVICE provenance is retained on plans") {
            val legacy = newInquiry()
            val issued = app.issueProposal(legacy)
            issued.servicePlan.shouldBeNull()
            issued.financial.latest.document.version shouldBe Version.of(2)
            staffRequest(legacy).servicePlan.shouldBeNull()
            val service = ServiceId(UUID.randomUUID())
            val composed = issue(newInquiry(), keep(), principal = service)
            composed.servicePlan!!.principalId shouldBe service
            app.transactor.inTransaction { plans.find(it, composed.proposal.documentReference) }!!.principalId shouldBe service
            // Only an unissued Estimate can be composed.
            shouldThrow<CommerceFailure.IllegalTransition> {
                preview(legacy, keep(), version = 2)
            }
        }

        test("a later Quote revision keeps the approved plan as history and reports no plan for the new Quote") {
            val id = newInquiry()
            val (_, iceCream) = estimateLines(id)
            val issued =
                issue(
                    id,
                    keep(listOf(QuoteLineOverride(QuoteOverrideTarget.ExistingLine(iceCream.id), usd("150.00"), reason("Negotiated")))),
                )
            val plan = issued.servicePlan.shouldNotBeNull()
            val revised =
                ReviseInquiryQuoteProposal(app.transactor, proposals())(
                    ReviseInquiryQuoteProposal.Command(
                        id,
                        Version.of(3),
                        issued.proposal.depositRequirementRevision,
                        requested(id).copy(
                            catalogRevision = OfferingsRevision.of(app.currentCatalogRevision()),
                            context = FionasOfferingsContext(60, false, Duration.ofMinutes(120)),
                        ),
                        fixed("105.00"),
                        actor,
                    ),
                )
            revised.financial.latest.document.version shouldBe Version.of(4)
            revised.servicePlan.shouldBeNull()
            staffRequest(id).servicePlan.shouldBeNull()
            app.transactor.inTransaction { plans.find(it, plan.quote) } shouldBe plan
        }

        test("changed catalog prices never reprice a kept Estimate; retired selections require review instead of fabricated names") {
            val id = newInquiry()
            val lines = estimateLines(id)
            var revision =
                app.updateOffering(app.currentCatalogRevision(), "waffle-cone") {
                    it.copy(
                        price =
                            CommerceJson.asA(
                                """{"kind":"PER_QUANTITY","amount":"1.25","currency":"USD","dimension":"guest"}""",
                                OfferingPriceDto.serializer(),
                            ),
                    )
                }
            val kept = preview(id, keep())
            kept.quote.lineItems shouldBe lines
            kept.quote.total.amount shouldBe BigDecimal("440.00")
            kept.catalogRevision shouldBe OfferingsRevision.of(revision)
            revision = app.retireOfferings(revision, "waffle-cone")
            val inputs = requested(id)
            unchanged(id) {
                shouldThrow<CommerceFailure.ValidationFailed> { issue(id, keep(), QuoteReviewToken("0".repeat(64))) }
                    .violations
                    .map { it.code } shouldContainExactly listOf("UNKNOWN_OFFERING")
            }
            val reselected =
                issue(
                    id,
                    QuoteComposition(
                        QuotePricing.RepriceConfiguration(
                            FionasPricingInputs(OfferingsRevision.of(revision), selections(cones = listOf("cup")), inputs.context),
                        ),
                    ),
                )
            reselected.financial.latest.document.total.amount shouldBe BigDecimal("410.00")
            reselected.servicePlan!!
                .selections
                .last()
                .offerings
                .map { it.displayName } shouldContainExactly listOf("Cups")
            requested(id) shouldBe inputs
            app.context.financialLedger
                .history(lineage(id))
                .first()
                .lineItems shouldBe lines
        }
    })
