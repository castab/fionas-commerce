package io.github.castab.fionas.commerce.inquiry

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
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.AllocatePayment
import io.github.castab.fionas.commerce.financial.FinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.InquiryDocumentAssociation
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.IssueInvoice
import io.github.castab.fionas.commerce.financial.IssueQuote
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
import io.github.castab.fionas.commerce.financial.SetDepositRequirement
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.proposalId
import io.github.castab.fionas.commerce.testing.requestedPricing
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InquiryLifecycleSpec :
    FunSpec({
        lateinit var app: TestApplication
        val owners = JdbiInquiryFinancialDocumentRepository()
        val pricing = JdbiFinancialDocumentPricingRepository()
        val facts = JdbiInquiryFulfillmentRepository()
        val actor = UserId(UUID.randomUUID())
        val usd = Currency.getInstance("USD")

        fun money(amount: String) = Money(BigDecimal(amount), usd)

        fun read(id: InquiryId) =
            app.transactor.inTransaction { ReadInquiryLifecycle(app.context.financialLedger, owners, facts).read(it, id) }

        fun commands() =
            ManageInquiryFulfillment(app.transactor, JdbiInquiryRepository(), owners, app.context.financialLedger, facts, testClock)

        fun requested(): Pair<InquiryId, UUID> {
            val id = app.createInquiry()
            return InquiryId(UUID.fromString(id)) to UUID.fromString(app.initialEstimateOf(id))
        }

        fun quote(): Pair<InquiryId, UUID> {
            val result = requested()
            app.issueProposal(result.first)
            return result
        }

        fun pay(
            id: UUID,
            amount: String,
            sources: FinancialDocumentPricingRepository = pricing,
            associations: InquiryFinancialDocumentRepository = owners,
        ) = RecordDocumentPayment(
            app.transactor,
            app.context.financialLedger,
            associations,
            sources,
            testClock,
            JdbiInquiryProposalRepository(),
        )(
            RecordDocumentPayment.Command(
                id,
                app.context.financialLedger
                    .latest(id)
                    .version,
                BigDecimal(amount),
                PaymentMethod.CARD,
                null,
                null,
                app.proposalId(id),
            ),
        )

        fun standalone(amount: String) =
            RecordPayment(app.transactor, app.context.financialLedger, testClock)(
                RecordPayment.Command(BigDecimal(amount), usd, PaymentMethod.CASH, null, null),
            )

        fun refund(
            payment: UUID,
            allocation: UUID,
            amount: String,
        ) = RecordRefund(app.transactor, app.context.financialLedger, testClock)(
            RecordRefund.Command(
                payment,
                BigDecimal(amount),
                usd,
                PaymentMethod.CARD,
                null,
                null,
                listOf(RecordRefund.AllocationCommand(allocation, BigDecimal(amount))),
            ),
        )
        beforeSpec {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }

        test("canonical lineage projects every lifecycle stage; dates, related invoices and refunds do not manufacture or undo booking") {
            val (inquiry, document) = requested()
            read(inquiry).stage shouldBe InquiryStage.REQUESTED
            val related =
                FinancialDocument.Invoice.create(
                    UUID.randomUUID(),
                    listOf(
                        LineItem(UUID.randomUUID(), "Unrelated", null, null, money("20"), money("0")),
                    ),
                )
            app.transactor.inTransaction {
                app.context.financialLedger.create(it, related)
                owners.associate(it, InquiryDocumentAssociation(inquiry, related.id, STORED_INSTANT))
            }
            app.database.execute("UPDATE fionas.inquiries SET event_date = '2000-01-01' WHERE id = '${inquiry.value}'")
            read(inquiry).stage shouldBe InquiryStage.REQUESTED
            shouldThrow<CommerceFailure.IllegalTransition> { commands().markServed(ManageInquiryFulfillment.Command(inquiry, actor)) }
            app.issueProposal(inquiry)
            read(inquiry).stage shouldBe InquiryStage.QUOTED
            shouldThrow<CommerceFailure.IllegalTransition> { commands().markServed(ManageInquiryFulfillment.Command(inquiry, actor)) }
            shouldThrow<CommerceFailure.IllegalTransition> {
                IssueInvoice(app.transactor, app.context.financialLedger, owners, pricing)(document, Version.of(2))
            }
            listOf("10", "39.99", "0.01", "51").forEach { amount ->
                shouldThrow<CommerceFailure.ValidationFailed> { pay(document, amount) }
                read(inquiry).stage shouldBe InquiryStage.QUOTED
            }
            val final = pay(document, "50")
            final.document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
            final.allocation.financialDocumentReference.version shouldBe Version.of(2)
            read(inquiry).stage shouldBe InquiryStage.BOOKED
            shouldThrow<CommerceFailure.Conflict> {
                IssueQuote(
                    app.transactor,
                    app.context.financialLedger,
                    owners,
                    pricing,
                )(document, Version.of(2))
            }
            refund(final.payment.id, final.allocation.id, "0.01")
            app.context.financialLedger
                .financialLineages(listOf(document))
                .single()
                .depositSatisfied shouldBe false
            read(inquiry).stage shouldBe InquiryStage.BOOKED
            shouldThrow<CommerceFailure.IllegalTransition> { commands().close(ManageInquiryFulfillment.Command(inquiry, actor)) }
            val served = commands().markServed(ManageInquiryFulfillment.Command(inquiry, actor))
            served.stage shouldBe InquiryStage.SERVED
            served.fulfillment!!.served shouldBe InquiryMilestone(STORED_INSTANT, actor)
            shouldThrow<CommerceFailure.IllegalTransition> { commands().markServed(ManageInquiryFulfillment.Command(inquiry, actor)) }
            shouldThrow<CommerceFailure.IllegalTransition> { commands().close(ManageInquiryFulfillment.Command(inquiry, actor)) }
            val balance =
                app.context.financialLedger
                    .reconcileLatest(document)
                    .balance.amount
            val overpaid = pay(document, balance.add(BigDecimal("1")).toPlainString())
            shouldThrow<CommerceFailure.IllegalTransition> { commands().close(ManageInquiryFulfillment.Command(inquiry, actor)) }
            refund(overpaid.payment.id, overpaid.allocation.id, "1")
            // Invoice change orders keep service, but change close eligibility.
            app.transactor.inTransaction {
                app.context.financialLedger.changeOrder(
                    it,
                    document,
                    ChangeOrder(
                        listOf(
                            ChangeOrder.Change.AddLineItem(
                                LineItem(UUID.randomUUID(), "Additional service", null, null, money("10"), money("0")),
                            ),
                        ),
                    ),
                )
            }
            read(inquiry).stage shouldBe InquiryStage.SERVED
            shouldThrow<CommerceFailure.IllegalTransition> { commands().close(ManageInquiryFulfillment.Command(inquiry, actor)) }
            pay(document, "10")
            commands().close(ManageInquiryFulfillment.Command(inquiry, actor)).stage shouldBe InquiryStage.CLOSED
            read(inquiry).fulfillment!!.closed shouldBe InquiryMilestone(STORED_INSTANT, actor)
            shouldThrow<CommerceFailure.IllegalTransition> { commands().close(ManageInquiryFulfillment.Command(inquiry, actor)) }
            shouldThrow<CommerceFailure.IllegalTransition> { commands().markServed(ManageInquiryFulfillment.Command(inquiry, actor)) }
            read(inquiry).fulfillment!!.served shouldBe served.fulfillment.served
        }

        test("standalone allocations reject canonical deposits; exact receipt books and Invoice allocations preserve metadata") {
            val (inquiry, id) = quote()
            val inputs = requestedPricing()
            app.transactor.inTransaction {
                pricing.insert(
                    it,
                    app.context.financialLedger
                        .latest(it, id)
                        .reference,
                    inputs,
                )
            }
            val payment = standalone("80")
            val allocate = AllocatePayment(app.transactor, app.context.financialLedger, owners, pricing, testClock)
            shouldThrow<CommerceFailure.IllegalTransition> {
                allocate(AllocatePayment.Command(payment.id, id, Version.of(2), BigDecimal("50")))
            }
            read(inquiry).stage shouldBe InquiryStage.QUOTED
            val result = pay(id, "50")
            result.document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
            result.document.latest.pricing shouldBe inputs
            read(inquiry).stage shouldBe InquiryStage.BOOKED
            allocate(AllocatePayment.Command(payment.id, id, Version.of(3), BigDecimal("20")))
            allocate(AllocatePayment.Command(payment.id, id, Version.of(3), BigDecimal("60")))
        }

        test("standalone canonical deposit changes reject before acceptance; RELATED manual invoicing remains financial only") {
            listOf(false, true).forEach { replacing ->
                val (inquiry, id) = requested()
                app.issueProposal(inquiry, "100")
                shouldThrow<CommerceFailure.ValidationFailed> { pay(id, "75") }
                shouldThrow<CommerceFailure.IllegalTransition> {
                    SetDepositRequirement(app.transactor, app.context.financialLedger, owners, pricing, JdbiInquiryProposalRepository())(
                        SetDepositRequirement.Command(
                            id,
                            Version.of(2),
                            if (replacing) io.github.castab.commerce.deposit.DepositRequirementRevision.INITIAL else null,
                            DepositTerms.Fixed(money("50")),
                        ),
                    )
                }
                read(inquiry).stage shouldBe InquiryStage.QUOTED
            }
            val (inquiry, _) = requested()
            val related =
                FinancialDocument.Quote.create(
                    UUID.randomUUID(),
                    listOf(LineItem(UUID.randomUUID(), "Related", null, null, money("100"), money("0"))),
                )
            app.transactor.inTransaction {
                app.context.financialLedger.create(it, related)
                owners.associate(it, InquiryDocumentAssociation(inquiry, related.id, STORED_INSTANT))
            }
            pay(related.id, "50")
                .document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Quote>()
            SetDepositRequirement(app.transactor, app.context.financialLedger, owners, pricing, JdbiInquiryProposalRepository())(
                SetDepositRequirement.Command(related.id, Version.INITIAL, null, DepositTerms.Fixed(money("50"))),
            ).latestVersion.document.shouldBeInstanceOf<FinancialDocument.Quote>()
            pay(related.id, "1")
                .document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Quote>()
            AllocatePayment(app.transactor, app.context.financialLedger, owners, pricing, testClock)(
                AllocatePayment.Command(standalone("1").id, related.id, Version.INITIAL, BigDecimal("1")),
            ).document.latest.document.shouldBeInstanceOf<FinancialDocument.Quote>()
            read(inquiry).stage shouldBe InquiryStage.REQUESTED
            IssueInvoice(
                app.transactor,
                app.context.financialLedger,
                owners,
                pricing,
            )(related.id, Version.INITIAL).latest.document.shouldBeInstanceOf<FinancialDocument.Invoice>()
            read(inquiry).stage shouldBe InquiryStage.REQUESTED
        }

        test("Invoice promotion failure rolls back exact payment and its cross-schema writes") {
            val (inquiry, id) = quote()
            val failing =
                object : FinancialDocumentPricingRepository by pricing {
                    override fun copy(
                        transaction: Transaction,
                        from: FinancialDocumentReference,
                        to: FinancialDocumentReference,
                    ) {
                        app.context.financialLedger
                            .latest(transaction, id)
                            .shouldBeInstanceOf<FinancialDocument.Invoice>()
                        error("Failure after Invoice insert")
                    }
                }
            val before =
                app.database.strings(
                    "SELECT count(*) FROM commerce.payment_records UNION ALL SELECT count(*) FROM commerce.payment_allocations UNION ALL SELECT count(*) FROM commerce.deposit_requirement_revisions",
                )
            shouldThrow<IllegalStateException> {
                pay(id, "50", failing)
            }
            app.database.strings(
                "SELECT count(*) FROM commerce.payment_records UNION ALL SELECT count(*) FROM commerce.payment_allocations UNION ALL SELECT count(*) FROM commerce.deposit_requirement_revisions",
            ) shouldBe
                before
            app.context.financialLedger
                .history(id)
                .size shouldBe 2
            read(inquiry).stage shouldBe InquiryStage.QUOTED
        }

        test("staff detail uses one snapshot when booking and service commit between its financial and fulfillment reads") {
            val (inquiry, id) = requested()
            val observed = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val pausedFacts =
                object : InquiryFulfillmentRepository by facts {
                    override fun find(
                        transaction: Transaction,
                        inquiryId: InquiryId,
                    ): InquiryFulfillment? {
                        observed.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                        return facts.find(transaction, inquiryId)
                    }
                }
            val detail =
                GetInquiry(
                    app.transactor,
                    JdbiCustomerRepository(),
                    JdbiInquiryRepository(),
                    ReadInquiryLifecycle(app.context.financialLedger, owners, pausedFacts),
                )
            val pending = CompletableFuture.supplyAsync { detail(inquiry) }
            try {
                check(observed.await(30, TimeUnit.SECONDS))
                app.issueProposal(inquiry)
                pay(id, "50")
                commands().markServed(ManageInquiryFulfillment.Command(inquiry, actor))
            } finally {
                resume.countDown()
            }
            pending.get(15, TimeUnit.SECONDS).lifecycle.stage shouldBe InquiryStage.REQUESTED
            read(inquiry).stage shouldBe InquiryStage.SERVED
        }

        test("impossible fulfillment before Invoice fails internally and closed facts require complete served provenance in the schema") {
            val (inquiry, id) = requested()
            shouldThrow<IllegalStateException> {
                InquiryLifecycle.project(
                    app.context.financialLedger.latest(id),
                    InquiryFulfillment(InquiryMilestone(STORED_INSTANT, actor)),
                )
            }
            shouldThrow<Exception> {
                app.database.execute(
                    "INSERT INTO fionas.inquiry_fulfillment (inquiry_id, closed_at, closed_by_kind, closed_by_id) VALUES ('${inquiry.value}', now(), 'USER', '${actor.value}')",
                )
            }
            shouldThrow<Exception> {
                app.database.execute(
                    "INSERT INTO fionas.inquiry_fulfillment (inquiry_id, served_at, served_by_kind, served_by_id, closed_at) VALUES ('${inquiry.value}', now(), 'USER', '${actor.value}', now())",
                )
            }
            shouldThrow<CommerceFailure.NotFound> {
                commands().markServed(
                    ManageInquiryFulfillment.Command(InquiryId(UUID.randomUUID()), actor),
                )
            }
        }
    })
