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
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.inquiryProposals
import io.github.castab.fionas.commerce.testing.issueProposal
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
        val sources = JdbiFinancialDocumentAuthorshipRepository()

        fun money(value: String) = Money(BigDecimal(value), usd)

        fun line(
            value: String,
            description: String = "Catering",
        ) = LineItem(UUID.randomUUID(), description, quantity = null, price = money(value), taxAmount = money("0.00"))

        fun add(item: LineItem) = ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(item)))

        fun invoice() = app.context.financialLedger.create(FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(line("600.00"))))

        beforeSpec {
            app = TestApplication.create()
        }
        afterSpec { app.close() }

        test("signed flat credit round trips exactly and appends an immutable database-timestamped successor") {
            val ledger = app.context.financialLedger
            val before = Instant.now()
            val original = invoice()
            val originalVersion = ledger.version(original.reference)
            val credit = line("-40.00", "Desired flavor unavailable — service credit")
            val revised = ledger.changeOrder(original.id, add(credit), original.version)
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
            val mixed = ledger.changeOrder(original.id, changes, original.version)
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
                    mixed.version,
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
                    revised.version,
                )
            }
            // A version the caller did not review is stale, even when the change itself is valid.
            shouldThrow<CommerceFailure.Conflict> { ledger.changeOrder(original.id, add(line("1.00")), original.version) }
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
                    revised.version,
                ).total shouldBe money("600.00")
        }

        test("generic signs and exact tax arithmetic remain supported while mixed currencies reject") {
            val ledger = app.context.financialLedger
            val original = invoice()
            val adjustment = line("2.125").copy(quantity = BigDecimal("-2.00"), taxAmount = money("-0.12500"))
            val revised = ledger.changeOrder(original.id, add(adjustment), original.version)
            revised.subtotal.amount.toPlainString() shouldBe "595.75000"
            revised.taxAmount.amount.toPlainString() shouldBe "-0.12500"
            revised.total.amount.toPlainString() shouldBe "595.62500"
            ledger.get(revised.reference) shouldBe revised
            shouldThrow<CommerceFailure.ValidationFailed> {
                val eur = Currency.getInstance("EUR")
                ledger.changeOrder(
                    original.id,
                    add(line("1").copy(price = Money(BigDecimal.ONE, eur), taxAmount = Money.zero(eur))),
                    revised.version,
                )
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
                    val revised = ledger.changeOrder(original.id, changes, original.version)
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

        /** A staff proposal replacing every reviewed line with one flat line charging [total]. */
        fun flat(total: String) =
            LineProposal(
                listOf(
                    ProposedLine(
                        ProposedLineIdentity.New(LineKey("revised")),
                        PricedLine("Revised service", null, null, money(total), money("0.00")),
                    ),
                ),
            )

        fun changeOrder() =
            CreateChangeOrder(app.transactor, app.context.financialLedger, owners, sources, JdbiInquiryFulfillmentRepository(), testClock)

        fun keep(
            line: LineItem,
            scale: Int? = null,
        ) = ProposedLine(
            ProposedLineIdentity.Existing(line.id),
            PricedLine(
                line.description,
                line.subDescription,
                line.quantity,
                scale?.let { Money(line.price.amount.setScale(it), usd) } ?: line.price,
                scale?.let { Money(line.taxAmount.amount.setScale(it), usd) } ?: line.taxAmount,
            ),
        )

        test(
            "financially identical staff lines persist their identity: reorder and remove-and-add append versions, a true no-op does not",
        ) {
            val id = InquiryId(UUID.fromString(app.createInquiry()))
            val a = line("100.00")
            val b = line("100.00")
            val original = app.context.financialLedger.create(FinancialDocument.Invoice.create(UUID.randomUUID(), listOf(a, b)))
            app.transactor.inTransaction { owners.associate(it, InquiryDocumentAssociation(id, original.id, STORED_INSTANT)) }
            val ledger = app.context.financialLedger

            // Reordering equal lines is a real edit: a new immutable version holding [B, A].
            val reordered =
                changeOrder()(CreateChangeOrder.Command(original.id, original.version, LineProposal(listOf(keep(b), keep(a))), app.adminId))
            val v2 = reordered.latest.document
            v2.version shouldBe Version.of(2)
            v2.lineItems.map { it.id } shouldBe listOf(b.id, a.id)
            v2.lineItems shouldBe listOf(b, a)
            v2.total shouldBe original.total
            reordered.latest.authorship?.author shouldBe app.adminId
            ledger.get(original.reference).lineItems shouldBe listOf(a, b)

            // Omitting A and adding an identical new line is a remove-and-add under a new id.
            val replacement =
                ProposedLine(
                    ProposedLineIdentity.New(LineKey("replacement")),
                    PricedLine("Catering", null, null, money("100.00"), money("0.00")),
                )
            val v3 =
                changeOrder()(
                    CreateChangeOrder.Command(original.id, v2.version, LineProposal(listOf(keep(b), replacement)), app.adminId),
                ).latest.document
            v3.version shouldBe Version.of(3)
            v3.lineItems.map { it.id }.first() shouldBe b.id
            val added = v3.lineItems.last().id
            (added == a.id) shouldBe false
            added shouldBe proposedLineId(original.id, v2.version, LineKey("replacement"))
            v3.total shouldBe original.total
            ledger.get(v2.reference).lineItems shouldBe listOf(b, a)

            // The same ordered ids with numerically equal amounts at another scale: no change, no version.
            val stored = v3.lineItems
            shouldThrow<CommerceFailure.ValidationFailed> {
                changeOrder()(
                    CreateChangeOrder.Command(original.id, v3.version, LineProposal(stored.map { keep(it, scale = 4) }), app.adminId),
                )
            }.violations.map { it.code } shouldBe listOf(LineProposalViolations.NO_FINANCIAL_CHANGE)
            ledger.versionHistory(original.id).size shouldBe 3

            // A stale reviewed version still conflicts, even for an identity-only edit, and appends nothing.
            shouldThrow<CommerceFailure.Conflict> {
                changeOrder()(CreateChangeOrder.Command(original.id, v2.version, LineProposal(listOf(keep(a), keep(b))), app.adminId))
            }
            ledger.versionHistory(original.id).map { it.document } shouldBe listOf(original, v2, v3)
        }

        test("a negative staff change order rejects even after full payment; a zero Invoice persists with a negative balance") {
            val id = InquiryId(UUID.fromString(app.createInquiry()))
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
            listOf("-0.01", "-120.00").forEach { total ->
                shouldThrow<CommerceFailure.ValidationFailed> {
                    changeOrder()(CreateChangeOrder.Command(document.id, document.version, flat(total), app.adminId))
                }.violations.map { it.code } shouldBe listOf(LineProposalViolations.NEGATIVE_DOCUMENT_TOTAL)
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
                    documents.commitLines(
                        transaction,
                        current,
                        flat("-120.00").resolveAgainst(current.document),
                        app.adminId,
                        STORED_INSTANT,
                    )
                }
                app.context.financialLedger.history(transaction, document.id) shouldBe listOf(document)
                sources.findAll(transaction, document.id) shouldBe emptyMap()
            }
            val zero = changeOrder()(CreateChangeOrder.Command(document.id, document.version, flat("0.000"), app.adminId))
            zero.latest.document.total.amount
                .signum() shouldBe 0
            zero.latest.document.shouldBeInstanceOf<FinancialDocument.Invoice>()
            zero.latest.document.version shouldBe document.version.next()
            zero.latest.authorship?.author shouldBe app.adminId
            zero.reconciliation.balance.amount
                .compareTo(BigDecimal("-600")) shouldBe 0
            val history = app.context.financialLedger.paymentHistory(payment.id)
            history.payment shouldBe payment
            history.allocations shouldBe listOf(allocation)
            history.refunds shouldBe emptyList()
            history.refundAllocations shouldBe emptyList()
        }

        test("concurrent staff edits of one reviewed version commit one immediate successor and conflict the stale writer") {
            val id = InquiryId(UUID.fromString(app.createInquiry()))
            val document = invoice()
            app.transactor.inTransaction { owners.associate(it, InquiryDocumentAssociation(id, document.id, STORED_INSTANT)) }
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val attempts =
                listOf("700.00", "800.00").map { total ->
                    CompletableFuture.supplyAsync {
                        ready.countDown()
                        check(start.await(30, TimeUnit.SECONDS))
                        runCatching { changeOrder()(CreateChangeOrder.Command(document.id, document.version, flat(total), app.adminId)) }
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

        test("a negative Quote revision and an unpublishable zero Quote preserve the entire approved proposal") {
            val id = InquiryId(UUID.fromString(app.createInquiry()))
            val published = app.issueProposal(id)
            val document = published.proposal.documentReference.id
            val ledger = app.context.financialLedger
            val history = ledger.versionHistory(document).map { it.document to it.createdAt }
            val deposits = ledger.depositRequirementHistory(document).map { it.requirement to it.createdAt }
            val repository = JdbiInquiryProposalRepository()
            val publications = app.transactor.inTransaction { repository.history(it, id) }
            val authored = app.transactor.inTransaction { sources.findAll(it, document) }
            val before = app.adminGet("/staff/requests/${id.value}").bodyString()
            val cases = listOf("-20.00" to LineProposalViolations.NEGATIVE_DOCUMENT_TOTAL, "0.000" to QUOTE_TOTAL_NOT_POSITIVE)
            cases.forEach { (total, code) ->
                shouldThrow<CommerceFailure.ValidationFailed> {
                    ReviseInquiryQuoteProposal(app.transactor, app.inquiryProposals())(
                        ReviseInquiryQuoteProposal.Command(
                            id,
                            published.proposal.documentReference.version,
                            published.proposal.depositRequirementRevision,
                            flat(total),
                            DepositTerms.Percentage(BigDecimal("20")),
                            app.adminId,
                        ),
                    )
                }.violations.map { it.code } shouldBe listOf(code)
                ledger.versionHistory(document).map { it.document to it.createdAt } shouldBe history
                ledger.depositRequirementHistory(document).map { it.requirement to it.createdAt } shouldBe deposits
                app.transactor.inTransaction { sources.findAll(it, document) } shouldBe authored
                app.transactor.inTransaction { repository.history(it, id) } shouldBe publications
                app.adminGet("/staff/requests/${id.value}").bodyString() shouldBe before
            }
        }
    })
