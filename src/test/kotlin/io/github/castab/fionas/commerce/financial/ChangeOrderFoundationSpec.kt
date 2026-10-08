package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.payment.PaymentRecord
import io.github.castab.commerce.runtime.financial.RefundAllocationPortion
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Consumer tests of the resolved commerce artifacts through the application-composed ledger. */
class ChangeOrderFoundationSpec :
    FunSpec({
        lateinit var app: TestApplication
        val usd = Currency.getInstance("USD")
        val owners = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()

        fun money(value: String) = Money(BigDecimal(value), usd)

        fun line(
            value: String,
            description: String = "Catering",
        ) = LineItem(UUID.randomUUID(), description, quantity = null, price = money(value), taxAmount = money("0.00"))

        fun add(item: LineItem) = ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(item)))

        fun invoice() = app.context.financialLedger.create(FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line("600.00"))))

        beforeSpec {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }

        test("signed flat credit round trips exactly and appends an immutable database-timestamped successor") {
            val ledger = app.context.financialLedger
            val before = Instant.now()
            val original = invoice()
            val originalVersion = ledger.version(original.reference)
            val credit = line("-40.00", "Desired flavor unavailable — service credit")
            val revised = ledger.changeOrder(original.id, add(credit))
            val persisted = ledger.version(revised.reference)

            revised.shouldBeInstanceOf<FinancialDocument.Invoice>()
            revised.id shouldBe original.id
            revised.version shouldBe original.version.next()
            revised.previousVersion shouldBe original.version
            revised.lineItems shouldBe original.lineItems + credit
            revised.subtotal shouldBe money("560.00")
            revised.taxAmount shouldBe money("0.00")
            revised.total shouldBe money("560.00")
            persisted.document shouldBe revised
            persisted.document.lineItems
                .last()
                .price.amount
                .toPlainString() shouldBe "-40.00"
            persisted.document.lineItems
                .last()
                .quantity shouldBe null
            ledger.version(original.reference).document shouldBe originalVersion.document
            ledger.version(original.reference).createdAt shouldBe originalVersion.createdAt
            ledger.get(original.reference) shouldBe original
            ledger.versionHistory(original.id).map { it.document } shouldBe listOf(original, revised)
            (persisted.createdAt >= originalVersion.createdAt) shouldBe true
            (originalVersion.createdAt >= before && persisted.createdAt <= Instant.now()) shouldBe true
            (persisted.createdAt != STORED_INSTANT) shouldBe true
        }

        test("ordered granular changes retain untouched identities and reject invalid changes atomically") {
            val ledger = app.context.financialLedger
            val original = invoice()
            val charge = line("75.00", "Extra service")
            val credit = line("-40.00", "Service credit")
            val changes = ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(charge), ChangeOrder.Change.AddLineItem(credit)))
            val mixed = ledger.changeOrder(original.id, changes)
            mixed.total shouldBe money("635.00")
            val replacement = charge.copy(price = money("25.00"))
            val revised =
                ledger.changeOrder(
                    original.id,
                    ChangeOrder(
                        listOf(
                            ChangeOrder.Change.ReplaceLineItem(charge.id, replacement),
                            ChangeOrder.Change.RemoveLineItem(credit.id),
                        ),
                    ),
                )
            revised.lineItems shouldBe original.lineItems + replacement
            revised.total shouldBe money("625.00")
            ledger.get(mixed.reference).lineItems shouldBe original.lineItems + charge + credit
            val history = ledger.versionHistory(original.id).map { it.document to it.createdAt }
            shouldThrow<CommerceFailure.ValidationFailed> {
                ledger.changeOrder(
                    original.id,
                    ChangeOrder(
                        listOf(
                            ChangeOrder.Change.AddLineItem(credit),
                            ChangeOrder.Change.RemoveLineItem(UUID.randomUUID()),
                        ),
                    ),
                )
            }
            ledger.versionHistory(original.id).map { it.document to it.createdAt } shouldBe history
            // Later changes can target a line added earlier in the same order.
            ledger
                .changeOrder(
                    original.id,
                    ChangeOrder(
                        listOf(
                            ChangeOrder.Change.AddLineItem(credit),
                            ChangeOrder.Change.ReplaceLineItem(credit.id, credit.copy(price = money("-25.00"))),
                        ),
                    ),
                ).total shouldBe money("600.00")
        }

        test("generic signs and exact tax arithmetic remain supported while mixed currencies reject") {
            val ledger = app.context.financialLedger
            val original = invoice()
            val adjustment = line("2.125").copy(quantity = BigDecimal("-2.00"), taxAmount = money("-0.12500"))
            val revised = ledger.changeOrder(original.id, add(adjustment))
            revised.subtotal.amount.toPlainString() shouldBe "595.75000"
            revised.taxAmount.amount.toPlainString() shouldBe "-0.12500"
            revised.total.amount.toPlainString() shouldBe "595.62500"
            ledger.get(revised.reference) shouldBe revised
            shouldThrow<CommerceFailure.ValidationFailed> {
                val eur = Currency.getInstance("EUR")
                ledger.changeOrder(original.id, add(line("1").copy(price = Money(BigDecimal.ONE, eur), taxAmount = Money.zero(eur))))
            }
            ledger.latest(original.id) shouldBe revised
        }

        test("Fiona validates totals numerically without restricting signed lines or negative balances") {
            listOf("0", "0.0", "0.00", "0.0000", "40.000").forEach { remaining ->
                val original = FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line("100.00")))
                val credit = line(BigDecimal(remaining).subtract(BigDecimal("100.00")).toPlainString())
                validateChangeOrder(original, add(credit))
            }
            listOf("-100.001", "-120.00", "-120.0000").forEach { credit ->
                val original = FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line("100.00")))
                shouldThrow<CommerceFailure.ValidationFailed> { validateChangeOrder(original, add(line(credit))) }
            }
            // The shared domain deliberately permits a negative total for other consumers.
            val original = invoice()
            original.changeOrder(add(line("-700.00"))).total shouldBe money("-100.00")
        }

        listOf(Triple("200.00", "75.00", "475.00"), Triple("200.00", "-40.00", "360.00"), Triple("600.00", "-40.00", "-40.00"))
            .forEach { (paid, adjustment, balance) ->
                test("payment $paid remains on its original snapshot after adjustment $adjustment with balance $balance") {
                    val ledger = app.context.financialLedger
                    val original = invoice()
                    val payment = PaymentRecord(UUID.randomUUID(), money(paid), PaymentMethod.CARD, STORED_INSTANT)
                    val allocation =
                        ledger.recordPaymentAgainstDocument(
                            payment,
                            UUID.randomUUID(),
                            original.reference,
                            money(paid),
                            STORED_INSTANT,
                        )
                    val before = ledger.paymentHistory(payment.id)
                    ledger.reconcileLatest(original.id).balance shouldBe money(if (paid == "200.00") "400.00" else "0.00")
                    val changes = add(line(adjustment))
                    validateChangeOrder(original, changes)
                    val revised = ledger.changeOrder(original.id, changes)
                    val after = ledger.paymentHistory(payment.id)
                    after.payment shouldBe before.payment
                    after.allocations shouldBe before.allocations
                    after.allocations.single().financialDocumentReference shouldBe original.reference
                    after.refunds shouldBe emptyList()
                    after.refundAllocations shouldBe emptyList()
                    val view = ledger.financialLineages(listOf(original.id)).single()
                    view.latestVersion.document shouldBe revised
                    view.reconciliation.netApplied shouldBe money(paid)
                    view.reconciliation.balance shouldBe money(balance)
                    if (balance == "-40.00") {
                        ledger.recordRefund(
                            payment.id,
                            UUID.randomUUID(),
                            money("40.00"),
                            PaymentMethod.CARD,
                            STORED_INSTANT,
                            allocations = listOf(RefundAllocationPortion(UUID.randomUUID(), allocation.id, money("40.00"), STORED_INSTANT)),
                        )
                        val refunded = ledger.paymentHistory(payment.id)
                        refunded.payment shouldBe before.payment
                        refunded.allocations shouldBe before.allocations
                        refunded.refunds.size shouldBe 1
                        refunded.refundAllocations.single().paymentAllocationReference shouldBe allocation.id
                        ledger.reconcileLatest(original.id).netApplied shouldBe money("560.00")
                        ledger.reconcileLatest(original.id).balance shouldBe money("0.00")
                    }
                }
            }

        fun pricing(total: String) =
            FionasPricing(
                FionasOfferingsEngine(
                    FIONAS_PRICING_POLICY.copy(baseEventFee = money(total), hourlyRate = money("0"), perGuestRate = money("0")),
                ),
                app.context.offeringsSnapshotRepository::retrieveLatestVersion,
            )

        test("negative repricing rejects even after full payment and zero Invoice repricing persists with a negative balance") {
            val id =
                InquiryId(
                    UUID.fromString(
                        app.createInquiry(pricing = {
                            pricingBody(it, softServe = listOf("vanilla"), toppings = TOPPINGS.take(4), cones = listOf("cup"))
                        }),
                    ),
                )
            val inputs = app.transactor.inTransaction { JdbiInquiryRepository().findRequested(it, id)!!.pricingInputs }
            val document = invoice()
            app.transactor.inTransaction { owners.associate(it, InquiryDocumentAssociation(id, document.id, STORED_INSTANT)) }
            val payment = PaymentRecord(UUID.randomUUID(), money("600.00"), PaymentMethod.CARD, STORED_INSTANT)
            val allocation =
                app.context.financialLedger.recordPaymentAgainstDocument(
                    payment,
                    UUID.randomUUID(),
                    document.reference,
                    payment.amount,
                    STORED_INSTANT,
                )
            val before =
                app.context.financialLedger
                    .versionHistory(document.id)
                    .map { it.document to it.createdAt }
            listOf("-0.001", "-120.00").forEach { total ->
                shouldThrow<CommerceFailure.ValidationFailed> {
                    CreateChangeOrder(
                        app.transactor,
                        app.context.financialLedger,
                        owners,
                        sources,
                        pricing(total),
                        JdbiInquiryFulfillmentRepository(),
                    )(
                        document.id,
                        document.version,
                        inputs,
                    )
                }
                app.context.financialLedger
                    .versionHistory(document.id)
                    .map { it.document to it.createdAt } shouldBe before
                app.transactor.inTransaction { sources.findAll(it, document.id) } shouldBe emptyMap()
            }
            // Catch inside the caller's transaction: absence of a successor must not rely on rollback.
            app.transactor.inTransaction { transaction ->
                val documents = FionaFinancialDocuments(app.context.financialLedger, owners, sources)
                val current = documents.expectLatest(transaction, document.id, document.version)
                shouldThrow<CommerceFailure.ValidationFailed> {
                    documents.reprice(transaction, current, inputs, pricing("-120.00"))
                }
                app.context.financialLedger.history(transaction, document.id) shouldBe listOf(document)
                sources.findAll(transaction, document.id) shouldBe emptyMap()
            }
            val zero =
                CreateChangeOrder(
                    app.transactor,
                    app.context.financialLedger,
                    owners,
                    sources,
                    pricing("0.000"),
                    JdbiInquiryFulfillmentRepository(),
                )(
                    document.id,
                    document.version,
                    inputs,
                )
            zero.latest.document.total.amount
                .signum() shouldBe 0
            zero.latest.document.shouldBeInstanceOf<FinancialDocument.Invoice>()
            zero.latest.document.version shouldBe document.version.next()
            zero.reconciliation.balance.amount
                .compareTo(BigDecimal("-600")) shouldBe 0
            val history = app.context.financialLedger.paymentHistory(payment.id)
            history.payment shouldBe payment
            history.allocations shouldBe listOf(allocation)
            history.refunds shouldBe emptyList()
            history.refundAllocations shouldBe emptyList()
        }

        test("concurrent repricing of one reviewed version commits one immediate successor and conflicts the stale writer") {
            val id =
                InquiryId(
                    UUID.fromString(
                        app.createInquiry(pricing = {
                            pricingBody(it, softServe = listOf("vanilla"), toppings = TOPPINGS.take(4), cones = listOf("cup"))
                        }),
                    ),
                )
            val inputs = app.transactor.inTransaction { JdbiInquiryRepository().findRequested(it, id)!!.pricingInputs }
            val document = invoice()
            app.transactor.inTransaction { owners.associate(it, InquiryDocumentAssociation(id, document.id, STORED_INSTANT)) }
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val attempts =
                listOf("700.00", "800.00").map { total ->
                    CompletableFuture.supplyAsync {
                        ready.countDown()
                        check(start.await(30, TimeUnit.SECONDS))
                        runCatching {
                            CreateChangeOrder(
                                app.transactor,
                                app.context.financialLedger,
                                owners,
                                sources,
                                pricing(total),
                                JdbiInquiryFulfillmentRepository(),
                            )(
                                document.id,
                                document.version,
                                inputs,
                            )
                        }
                    }
                }
            try {
                ready.await(30, TimeUnit.SECONDS) shouldBe true
                start.countDown()
                val results = attempts.map { it.get(30, TimeUnit.SECONDS) }
                results.count { it.isSuccess } shouldBe 1
                results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<CommerceFailure.Conflict>()
                app.context.financialLedger
                    .history(document.id)
                    .map { it.version } shouldBe listOf(Version.INITIAL, Version.INITIAL.next())
                app.transactor.inTransaction { sources.findAll(it, document.id).size } shouldBe 1
            } finally {
                start.countDown()
            }
        }

        test("negative Quote repricing and unpublishable zero Quote preserve the entire approved proposal") {
            val id =
                InquiryId(
                    UUID.fromString(
                        app.createInquiry(pricing = {
                            pricingBody(it, softServe = listOf("vanilla"), toppings = TOPPINGS.take(4), cones = listOf("cup"))
                        }),
                    ),
                )
            val published = app.issueProposal(id)
            val document = published.proposal.documentReference.id
            val inputs = app.transactor.inTransaction { JdbiInquiryRepository().findRequested(it, id)!!.pricingInputs }
            val ledger = app.context.financialLedger
            val history = ledger.versionHistory(document).map { it.document to it.createdAt }
            val deposits = ledger.depositRequirementHistory(document).map { it.requirement to it.createdAt }
            val repository = JdbiInquiryProposalRepository()
            val publications = app.transactor.inTransaction { repository.history(it, id) }
            val before = app.adminGet("/staff/requests/${id.value}").bodyString()
            listOf("-20.00", "0.000").forEach { total ->
                val operation =
                    ReviseInquiryQuoteProposal(
                        app.transactor,
                        InquiryProposals(ledger, owners, sources, repository, pricing(total), testClock),
                    )
                shouldThrow<CommerceFailure.ValidationFailed> {
                    operation(
                        ReviseInquiryQuoteProposal.Command(
                            id,
                            published.proposal.documentReference.version,
                            published.proposal.depositRequirementRevision,
                            inputs,
                            DepositTerms.Percentage(BigDecimal("20")),
                            UserId(UUID.randomUUID()),
                        ),
                    )
                }
                ledger.versionHistory(document).map { it.document to it.createdAt } shouldBe history
                ledger.depositRequirementHistory(document).map { it.requirement to it.createdAt } shouldBe deposits
                app.transactor.inTransaction { sources.findAll(it, document) } shouldBe emptyMap()
                app.transactor.inTransaction { repository.history(it, id) } shouldBe publications
                app.adminGet("/staff/requests/${id.value}").bodyString() shouldBe before
            }
        }
    })
