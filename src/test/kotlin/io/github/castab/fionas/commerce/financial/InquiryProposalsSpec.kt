package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirement
import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.PrincipalStatus
import io.github.castab.commerce.staff.User
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.newLinesProposal
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InquiryProposalsSpec :
    FunSpec({
        lateinit var app: TestApplication
        val owners = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentAuthorshipRepository()
        val history = JdbiInquiryProposalRepository()
        // A real staff user: proposals reference the runtime's published users.
        val actor = UserId(UUID.randomUUID())
        val percent = DepositTerms.Percentage(BigDecimal("20"))

        fun fixed(amount: String) = DepositTerms.Fixed(Money(BigDecimal(amount), Currency.getInstance("USD")))

        fun core(
            repository: InquiryProposalRepository = history,
            metadata: FinancialDocumentAuthorshipRepository = sources,
            associations: InquiryFinancialDocumentRepository = owners,
        ) = InquiryProposals(
            app.context.financialLedger,
            associations,
            metadata,
            repository,
            JdbiInquiryServicePlanRepository(),
            testClock,
        )

        fun newInquiry() = InquiryId(UUID.fromString(app.createInquiry()))

        fun issue(
            id: InquiryId,
            terms: DepositTerms = percent,
            repository: InquiryProposalRepository = history,
            metadata: FinancialDocumentAuthorshipRepository = sources,
        ) = IssueInquiryProposal(
            app.transactor,
            core(repository, metadata),
        )(IssueInquiryProposal.Command(id, Version.INITIAL, terms, actor))

        fun deposit(
            id: InquiryId,
            version: Int,
            rev: Int,
            terms: DepositTerms,
        ) = ReviseInquiryProposalDeposit(app.transactor, core())(
            ReviseInquiryProposalDeposit.Command(id, Version.of(version), DepositRequirementRevision.of(rev), terms, actor),
        )

        fun quote(
            id: InquiryId,
            version: Int = 2,
            rev: Int = 1,
            guests: Int = 100,
        ) = ReviseInquiryQuoteProposal(app.transactor, core())(
            ReviseInquiryQuoteProposal.Command(
                id,
                Version.of(version),
                DepositRequirementRevision.of(rev),
                newLinesProposal(acceptanceLines(guests = guests)),
                percent,
                actor,
            ),
        )

        fun persisted(id: InquiryId) = app.transactor.inTransaction { history.history(it, id) }

        fun current(id: InquiryProposalId) =
            IsCurrentPayableInquiryProposal(app.transactor, app.context.financialLedger, owners, history)(id)

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

        fun unchanged(
            id: InquiryId,
            action: () -> Unit,
        ) {
            val lineage = app.transactor.inTransaction { owners.initialEstimateOf(it, id)!! }
            val before = app.context.financialLedger.history(lineage)
            val deposits =
                app.context.financialLedger
                    .depositRequirementHistory(lineage)
                    .map { it.requirement to it.createdAt }
            val publications = persisted(id)
            val payments = app.context.financialLedger.paymentHistoriesForLineage(lineage)
            val counts = listOf("commerce.payment_records", "commerce.payment_allocations").map(app.database::count)
            action()
            app.context.financialLedger.paymentHistoriesForLineage(lineage) shouldBe payments
            listOf("commerce.payment_records", "commerce.payment_allocations").map(app.database::count) shouldBe counts
            app.context.financialLedger.history(lineage) shouldBe before
            app.context.financialLedger
                .depositRequirementHistory(lineage)
                .map { it.requirement to it.createdAt } shouldBe deposits
            persisted(id) shouldBe publications
        }

        fun payProposal(
            accepted: InquiryProposal,
            amount: String,
            associations: InquiryFinancialDocumentRepository = owners,
            repository: InquiryProposalRepository = history,
        ) = RecordDocumentPayment(app.transactor, app.context.financialLedger, associations, sources, testClock, repository)(
            RecordDocumentPayment.Command(
                accepted.documentReference.id,
                accepted.documentReference.version,
                BigDecimal(amount),
                PaymentMethod.CASH,
                null,
                null,
                accepted.id,
            ),
        )

        beforeSpec {
            app = TestApplication.create()
            app.authorization.createUser(User(actor, "proposal-actor", null, null, "Proposal actor", PrincipalStatus.ACTIVE, emptySet()))
        }
        afterSpec { app.close() }

        test("initial issuance atomically freezes explicit percentage or fixed terms against Quote v2 and preserves provenance") {
            listOf(percent, DepositTerms.Percentage(BigDecimal("25")), fixed("150.00")).forEach { terms ->
                val id = newInquiry()
                val result = issue(id, terms)
                val quote =
                    result.financial.latest.document
                        .shouldBeInstanceOf<FinancialDocument.Quote>()
                quote.version shouldBe Version.of(2)
                val active =
                    result.deposit.depositRequirement!!
                        .requirement
                        .shouldBeInstanceOf<DepositRequirement.Active>()
                active.approvalReference shouldBe quote.reference
                active.revision shouldBe DepositRequirementRevision.INITIAL
                active.terms shouldBe terms
                active.requiredAmount shouldBe terms.resolve(quote)
                result.proposal.documentReference shouldBe quote.reference
                result.proposal.depositRequirementRevision shouldBe active.revision
                result.proposal.issuedAt shouldBe STORED_INSTANT
                result.proposal.issuedBy shouldBe actor
                result.proposal.kind shouldBe ProposalIssuanceKind.INITIAL
                persisted(id) shouldBe listOf(result.proposal)
                current(result.proposal.id) shouldBe true
                app.adminGet("/staff/requests/${id.value}").status shouldBe Status.OK
                app.database.count("fionas.inquiry_communications") shouldBe 0
            }
        }
        test("stale issue and invalid resolved deposits roll back every write") {
            val id = newInquiry()
            unchanged(id) {
                shouldThrow<CommerceFailure.Conflict> {
                    IssueInquiryProposal(app.transactor, core())(IssueInquiryProposal.Command(id, Version.of(2), percent, actor))
                }
            }
            unchanged(id) { shouldThrow<CommerceFailure.ValidationFailed> { issue(id, fixed("999999")) } }
            unchanged(id) { shouldThrow<CommerceFailure.ValidationFailed> { issue(id, fixed("1.001")) } }
        }
        test("failure after Quote or after deposit rolls back both schemas and the publication") {
            val id = newInquiry()
            val failingMetadata =
                object : FinancialDocumentAuthorshipRepository by sources {
                    override fun copy(
                        transaction: Transaction,
                        from: FinancialDocumentReference,
                        to: FinancialDocumentReference,
                    ) {
                        app.context.financialLedger
                            .latest(transaction, to.id)
                            .shouldBeInstanceOf<FinancialDocument.Quote>()
                        error("after Quote")
                    }
                }
            unchanged(id) { shouldThrow<IllegalStateException> { issue(id, metadata = failingMetadata) } }
            val failingPublication =
                object : InquiryProposalRepository by history {
                    override fun append(
                        transaction: Transaction,
                        proposal: InquiryProposal,
                    ) {
                        app.context.financialLedger
                            .latestDepositRequirement(
                                transaction,
                                proposal.documentReference.id,
                            )!!
                            .requirement.revision shouldBe
                            DepositRequirementRevision.INITIAL
                        history.append(transaction, proposal)
                        error("after publication insert")
                    }
                }
            unchanged(id) { shouldThrow<IllegalStateException> { issue(id, repository = failingPublication) } }
            issue(id).proposal.kind shouldBe ProposalIssuanceKind.INITIAL
        }
        test("Quote and deposit revisions append exact pairs and supersede all older immutable publication identities") {
            val id = newInquiry()
            val a = issue(id)
            val b = quote(id)
            b.financial.latest.document.version shouldBe Version.of(3)
            val requirement = b.deposit.depositRequirement!!.requirement as DepositRequirement.Active
            requirement.approvalReference shouldBe b.financial.latest.document.reference
            requirement.revision shouldBe DepositRequirementRevision.of(2)
            requirement.terms shouldBe percent
            requirement.requiredAmount shouldBe percent.resolve(b.financial.latest.document)
            b.proposal.kind shouldBe ProposalIssuanceKind.QUOTE_REVISED
            current(a.proposal.id) shouldBe false
            current(b.proposal.id) shouldBe true
            val c = deposit(id, 3, 2, fixed("300.00"))
            c.proposal.kind shouldBe ProposalIssuanceKind.DEPOSIT_REVISED
            c.proposal.documentReference shouldBe b.proposal.documentReference
            c.proposal.depositRequirementRevision shouldBe DepositRequirementRevision.of(3)
            app.context.financialLedger
                .history(c.proposal.documentReference.id)
                .size shouldBe 3
            persisted(id) shouldBe listOf(a.proposal, b.proposal, c.proposal)
            current(a.proposal.id) shouldBe false
            current(b.proposal.id) shouldBe false
            current(c.proposal.id) shouldBe true
            val revisions = app.context.financialLedger.depositRequirementHistory(c.proposal.documentReference.id)
            revisions[0].requirement shouldBe a.deposit.depositRequirement!!.requirement
            revisions[0].createdAt shouldBe a.deposit.depositRequirement!!.createdAt
            revisions[1].requirement shouldBe b.deposit.depositRequirement!!.requirement
            revisions[1].createdAt shouldBe b.deposit.depositRequirement!!.createdAt
        }
        test("both reviewed tokens and numerical no-ops reject without writes; changing term form is meaningful at equal money") {
            val id = newInquiry()
            val a = issue(id)
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { quote(id, version = 1) } }
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { quote(id, rev = 2) } }
            unchanged(id) { shouldThrow<CommerceFailure.ValidationFailed> { quote(id, guests = 75) } }
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { deposit(id, 1, 1, fixed("200")) } }
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { deposit(id, 2, 2, fixed("200")) } }
            unchanged(
                id,
            ) { shouldThrow<CommerceFailure.ValidationFailed> { deposit(id, 2, 1, DepositTerms.Percentage(BigDecimal("20.000"))) } }
            val amount = (a.deposit.depositRequirement!!.requirement as DepositRequirement.Active).requiredAmount.amount.toPlainString()
            val b = deposit(id, 2, 1, fixed(amount))
            b.proposal.documentReference shouldBe a.proposal.documentReference
            unchanged(id) { shouldThrow<CommerceFailure.ValidationFailed> { deposit(id, 2, 2, fixed(amount + "0")) } }
        }
        test("revision publication failure rolls back repriced Quote, replacement deposit and metadata") {
            val id = newInquiry()
            issue(id)
            val failing =
                object : InquiryProposalRepository by history {
                    override fun append(
                        transaction: Transaction,
                        proposal: InquiryProposal,
                    ) {
                        history.append(transaction, proposal)
                        error("publication failed")
                    }
                }
            unchanged(id) {
                shouldThrow<IllegalStateException> {
                    ReviseInquiryQuoteProposal(app.transactor, core(failing))(
                        ReviseInquiryQuoteProposal.Command(
                            id,
                            Version.of(2),
                            DepositRequirementRevision.INITIAL,
                            newLinesProposal(acceptanceLines(guests = 100)),
                            percent,
                            actor,
                        ),
                    )
                }
            }
            unchanged(id) {
                shouldThrow<IllegalStateException> {
                    ReviseInquiryProposalDeposit(
                        app.transactor,
                        core(failing),
                    )(ReviseInquiryProposalDeposit.Command(id, Version.of(2), DepositRequirementRevision.INITIAL, fixed("300"), actor))
                }
            }
        }
        test("any historical allocation blocks both revisions even after its complete refund unwind") {
            val id = newInquiry()
            val a = issue(id)
            val document = a.proposal.documentReference.id
            val paid =
                app.transactor.inTransaction { transaction ->
                    val payment =
                        io.github.castab.commerce.payment.PaymentRecord(
                            UUID.randomUUID(),
                            Money(BigDecimal("1.00"), Currency.getInstance("USD")),
                            PaymentMethod.CASH,
                            STORED_INSTANT,
                            null,
                        )
                    val allocation =
                        app.context.financialLedger.recordPaymentAgainstDocument(
                            transaction,
                            payment,
                            UUID.randomUUID(),
                            a.proposal.documentReference,
                            payment.amount,
                            STORED_INSTANT,
                        )
                    payment to allocation
                }

            fun blocked() {
                unchanged(id) {
                    shouldThrow<CommerceFailure.IllegalTransition> {
                        payProposal(
                            a.proposal,
                            (a.deposit.depositRequirement!!.requirement as DepositRequirement.Active).requiredAmount.amount.toPlainString(),
                        )
                    }
                    shouldThrow<CommerceFailure.IllegalTransition> { quote(id) }
                    shouldThrow<CommerceFailure.IllegalTransition> { deposit(id, 2, 1, fixed("300")) }
                }
            }
            blocked()
            RecordRefund(app.transactor, app.context.financialLedger, testClock)(
                RecordRefund.Command(
                    paid.first.id,
                    BigDecimal("1.00"),
                    Currency.getInstance("USD"),
                    PaymentMethod.CASH,
                    null,
                    null,
                    listOf(RecordRefund.AllocationCommand(paid.second.id, BigDecimal("1.00"))),
                ),
            )
            app.context.financialLedger
                .reconcileLatest(document)
                .netApplied.amount
                .signum() shouldBe 0
            blocked()
        }
        test("a deposit is one exact receipt; partial and excessive attempts cannot accumulate; Invoice payments remain partial") {
            val id = newInquiry()
            val a = issue(id, fixed("300"))
            val document = a.proposal.documentReference.id
            listOf("100", "200", "299.99", "300.01", "400").forEach { amount ->
                unchanged(id) { shouldThrow<CommerceFailure.ValidationFailed> { payProposal(a.proposal, amount) } }
            }
            unchanged(id) {
                shouldThrow<CommerceFailure.ValidationFailed> {
                    RecordDocumentPayment(app.transactor, app.context.financialLedger, owners, sources, testClock, history)(
                        RecordDocumentPayment.Command(document, Version.of(2), BigDecimal("300"), PaymentMethod.CASH, null, null),
                    )
                }
            }
            val paid = payProposal(a.proposal, "300.00")
            paid.document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
            paid.allocation.financialDocumentReference shouldBe a.proposal.documentReference
            paid.allocation.amount.amount
                .compareTo(BigDecimal("300")) shouldBe 0
            paid.payment.amount shouldBe paid.allocation.amount
            persisted(id) shouldBe listOf(a.proposal)
            app.context.financialLedger
                .financialLineages(listOf(document))
                .single()
                .depositSatisfied shouldBe true
            val invoice = paid.document.latest.document
            listOf("100", "50").forEach { amount ->
                RecordDocumentPayment(app.transactor, app.context.financialLedger, owners, sources, testClock, history)(
                    RecordDocumentPayment.Command(document, invoice.version, BigDecimal(amount), PaymentMethod.CASH, null, null),
                )
            }
            val payments = app.context.financialLedger.paymentHistoriesForLineage(document)
            payments.size shouldBe 3
            payments.map { it.payment.id }.toSet().size shouldBe 3
            payments
                .single { it.payment.id == paid.payment.id }
                .allocations
                .single()
                .financialDocumentReference shouldBe a.proposal.documentReference
            app.context.financialLedger
                .reconcileLatest(document)
                .netApplied.amount
                .compareTo(BigDecimal("450")) shouldBe 0
        }

        test("deposit-only republication invalidates the old id even when the resolved amount and Quote version are identical") {
            val id = newInquiry()
            val a = issue(id)
            val amount = (a.deposit.depositRequirement!!.requirement as DepositRequirement.Active).requiredAmount.amount.toPlainString()
            val b = deposit(id, 2, 1, fixed(amount))
            b.proposal.documentReference shouldBe a.proposal.documentReference
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { payProposal(a.proposal, amount) } }
            payProposal(b.proposal, amount)
                .document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
        }

        test("a negotiated smaller deposit and a revised Quote each require their newly published proposal") {
            val id = newInquiry()
            val a = issue(id, fixed("300"))
            unchanged(id) { shouldThrow<CommerceFailure.ValidationFailed> { payProposal(a.proposal, "200") } }
            val b = deposit(id, 2, 1, fixed("200"))
            unchanged(id) { shouldThrow<CommerceFailure.Conflict> { payProposal(a.proposal, "300") } }
            payProposal(b.proposal, "200")
                .document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
            val other = newInquiry()
            val old = issue(other, fixed("300"))
            val revised = quote(other)
            unchanged(other) { shouldThrow<CommerceFailure.Conflict> { payProposal(old.proposal, "300") } }
            val amount =
                (revised.deposit.depositRequirement!!.requirement as DepositRequirement.Active)
                    .requiredAmount.amount
                    .toPlainString()
            payProposal(revised.proposal, amount)
                .document.latest.document
                .shouldBeInstanceOf<FinancialDocument.Invoice>()
        }

        test("proposal republication wins the association lock before a waiting exact payment; stale money never commits") {
            val id = newInquiry()
            val a = issue(id)
            val amount = (a.deposit.depositRequirement!!.requirement as DepositRequirement.Active).requiredAmount.amount.toPlainString()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val pausing =
                object : InquiryProposalRepository by history {
                    override fun append(
                        transaction: Transaction,
                        proposal: InquiryProposal,
                    ) {
                        history.append(transaction, proposal)
                        entered.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                    }
                }
            val first =
                CompletableFuture.supplyAsync {
                    ReviseInquiryProposalDeposit(app.transactor, core(pausing))(
                        ReviseInquiryProposalDeposit.Command(id, Version.of(2), DepositRequirementRevision.INITIAL, fixed(amount), actor),
                    )
                }
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                val pid = AtomicInteger()
                val second =
                    CompletableFuture.supplyAsync {
                        runCatching { payProposal(a.proposal, amount, observedOwners(pid)) }.exceptionOrNull()
                    }
                awaitAssociationWait(pid)
                second.isDone shouldBe false
                resume.countDown()
                val b = first.get(30, TimeUnit.SECONDS)
                second.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.Conflict>()
                app.context.financialLedger.paymentHistoriesForLineage(a.proposal.documentReference.id) shouldBe emptyList()
                payProposal(b.proposal, amount)
                    .document.latest.document
                    .shouldBeInstanceOf<FinancialDocument.Invoice>()
            } finally {
                resume.countDown()
            }
        }

        test("exact payment wins the association lock; a waiting proposal revision sees the booked Invoice") {
            val id = newInquiry()
            val a = issue(id, fixed("300"))
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val observing =
                object : InquiryProposalRepository by history {
                    override fun latest(
                        transaction: Transaction,
                        inquiryId: InquiryId,
                    ): InquiryProposal? {
                        transaction.handle
                            .createQuery("SHOW transaction_isolation")
                            .mapTo(String::class.java)
                            .one() shouldBe "read committed"
                        entered.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                        return history.latest(transaction, inquiryId)
                    }
                }
            val first = CompletableFuture.supplyAsync { payProposal(a.proposal, "300", repository = observing) }
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                val pid = AtomicInteger()
                val second =
                    CompletableFuture.supplyAsync {
                        runCatching {
                            ReviseInquiryProposalDeposit(app.transactor, core(associations = observedOwners(pid)))(
                                ReviseInquiryProposalDeposit.Command(
                                    id,
                                    Version.of(2),
                                    DepositRequirementRevision.INITIAL,
                                    fixed("200"),
                                    actor,
                                ),
                            )
                        }.exceptionOrNull()
                    }
                awaitAssociationWait(pid)
                second.isDone shouldBe false
                resume.countDown()
                first
                    .get(30, TimeUnit.SECONDS)
                    .document.latest.document
                    .shouldBeInstanceOf<FinancialDocument.Invoice>()
                second.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.Conflict>()
                persisted(id) shouldBe listOf(a.proposal)
                app.context.financialLedger
                    .paymentHistoriesForLineage(a.proposal.documentReference.id)
                    .size shouldBe 1
            } finally {
                resume.countDown()
            }
        }

        test("deposit satisfaction books atomically while publication remains historical and non-payable") {
            val id = newInquiry()
            val a = issue(id)
            val amount = (a.deposit.depositRequirement!!.requirement as DepositRequirement.Active).requiredAmount.amount
            RecordDocumentPayment(app.transactor, app.context.financialLedger, owners, sources, testClock, JdbiInquiryProposalRepository())(
                RecordDocumentPayment.Command(
                    a.proposal.documentReference.id,
                    Version.of(2),
                    amount,
                    PaymentMethod.CHECK,
                    null,
                    null,
                    a.proposal.id,
                ),
            ).document.latest.document.shouldBeInstanceOf<FinancialDocument.Invoice>()
            current(a.proposal.id) shouldBe false
            persisted(id) shouldBe listOf(a.proposal)
            app.adminGet("/staff/requests/${id.value}").status shouldBe Status.OK
        }
        test("booked acceptance rejects standalone approval and withdrawal across later Invoice change orders") {
            val id = newInquiry()
            issue(id)
            quote(id)
            val accepted = deposit(id, 3, 2, fixed("300"))
            val document = accepted.proposal.documentReference.id
            RecordDocumentPayment(app.transactor, app.context.financialLedger, owners, sources, testClock, JdbiInquiryProposalRepository())(
                RecordDocumentPayment.Command(
                    document,
                    Version.of(3),
                    BigDecimal("300"),
                    PaymentMethod.CHECK,
                    null,
                    null,
                    accepted.proposal.id,
                ),
            ).document.latest.document.shouldBeInstanceOf<FinancialDocument.Invoice>()

            var lockedTransaction: Transaction? = null
            val observingOwners =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = owners.lockInquiryOf(transaction, documentId).also { lockedTransaction = transaction }
                }
            var historyReads = 0
            val observingHistory =
                object : InquiryProposalRepository by history {
                    override fun latest(
                        transaction: Transaction,
                        inquiryId: InquiryId,
                    ): InquiryProposal? {
                        transaction shouldBe lockedTransaction
                        transaction.handle
                            .createQuery("SHOW transaction_isolation")
                            .mapTo(String::class.java)
                            .one() shouldBe "read committed"
                        historyReads++
                        return history.latest(transaction, inquiryId)
                    }
                }

            fun forbidden(version: Int) {
                val staff = app.adminGet("/staff/requests/${id.value}").bodyString()
                unchanged(id) {
                    listOf(null, accepted.proposal.depositRequirementRevision).forEach { reviewed ->
                        shouldThrow<CommerceFailure.IllegalTransition> {
                            SetDepositRequirement(app.transactor, app.context.financialLedger, observingOwners, sources, observingHistory)(
                                SetDepositRequirement.Command(document, Version.of(version), reviewed, fixed("50")),
                            )
                        }
                    }
                    shouldThrow<CommerceFailure.IllegalTransition> {
                        WithdrawDepositRequirement(app.transactor, app.context.financialLedger, observingOwners, observingHistory)(
                            WithdrawDepositRequirement.Command(document, accepted.proposal.depositRequirementRevision),
                        )
                    }
                }
                app.adminGet("/staff/requests/${id.value}").bodyString() shouldBe staff
                current(accepted.proposal.id) shouldBe false
            }
            forbidden(4)
            CreateChangeOrder(
                app.transactor,
                app.context.financialLedger,
                owners,
                sources,
                JdbiInquiryFulfillmentRepository(),
                testClock,
            )(
                CreateChangeOrder.Command(document, Version.of(4), newLinesProposal(acceptanceLines(guests = 125)), actor),
            ).latest.document.shouldBeInstanceOf<FinancialDocument.Invoice>()
            forbidden(5)
            historyReads shouldBe 6
            val requirement =
                app.context.financialLedger
                    .latestDepositRequirement(document)!!
                    .requirement as DepositRequirement.Active
            requirement.approvalReference shouldBe accepted.proposal.documentReference
            requirement.revision shouldBe accepted.proposal.depositRequirementRevision
        }
        test("booked staff reads fail internally if authoritative acceptance is replaced or withdrawn outside Fiona") {
            listOf(false, true).forEach { withdraw ->
                val id = newInquiry()
                val accepted = issue(id)
                val document = accepted.proposal.documentReference.id
                val amount = (accepted.deposit.depositRequirement!!.requirement as DepositRequirement.Active).requiredAmount.amount
                RecordDocumentPayment(
                    app.transactor,
                    app.context.financialLedger,
                    owners,
                    sources,
                    testClock,
                    JdbiInquiryProposalRepository(),
                )(
                    RecordDocumentPayment.Command(document, Version.of(2), amount, PaymentMethod.CHECK, null, null, accepted.proposal.id),
                )
                app.transactor.inTransaction { transaction ->
                    if (withdraw) {
                        app.context.financialLedger.withdrawDepositRequirement(transaction, document, DepositRequirementRevision.INITIAL)
                    } else {
                        app.context.financialLedger.activateDepositRequirement(
                            transaction,
                            document,
                            Version.of(3),
                            fixed("50"),
                            DepositRequirementRevision.INITIAL,
                        )
                    }
                }
                app.adminGet("/staff/requests/${id.value}").status shouldBe Status.INTERNAL_SERVER_ERROR
                shouldThrow<IllegalStateException> { current(accepted.proposal.id) }
            }
        }
        test("same-lineage contention observes preceding commit and cannot issue two initial proposals") {
            val id = newInquiry()
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val pausing =
                object : InquiryProposalRepository by history {
                    override fun append(
                        transaction: Transaction,
                        proposal: InquiryProposal,
                    ) {
                        history.append(transaction, proposal)
                        entered.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                    }
                }
            val first = CompletableFuture.supplyAsync { issue(id, repository = pausing) }
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                val pid = AtomicInteger()
                val second =
                    CompletableFuture.supplyAsync {
                        runCatching {
                            IssueInquiryProposal(
                                app.transactor,
                                core(associations = observedOwners(pid)),
                            )(IssueInquiryProposal.Command(id, Version.INITIAL, percent, actor))
                        }.exceptionOrNull()
                    }
                awaitAssociationWait(pid)
                second.isDone shouldBe false
                resume.countDown()
                first.get(30, TimeUnit.SECONDS)
                second.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.Conflict>()
                persisted(id).size shouldBe 1
            } finally {
                resume.countDown()
            }
        }
        test("repository preserves staff provenance, uniqueness, caller rollback and history ordered independently of timestamps") {
            val id = newInquiry()
            val a =
                IssueInquiryProposal(
                    app.transactor,
                    core(),
                )(IssueInquiryProposal.Command(id, Version.INITIAL, percent, actor))
            persisted(id).single().issuedBy shouldBe actor
            unchanged(id) {
                shouldThrow<CommerceFailure.Conflict> {
                    app.transactor.inTransaction {
                        history.append(
                            it,
                            a.proposal.copy(id = InquiryProposalId(UUID.randomUUID()), kind = ProposalIssuanceKind.DEPOSIT_REVISED),
                        )
                    }
                }
            }
            val b = deposit(id, 2, 1, fixed("200"))
            a.proposal.issuedAt shouldBe b.proposal.issuedAt
            app.transactor.inTransaction { history.latest(it, id) } shouldBe b.proposal
            current(InquiryProposalId(UUID.randomUUID())) shouldBe false
        }

        listOf("QUOTE", "DEPOSIT").forEach { change ->
            test("a waiting staff revision cannot publish from the proposal already superseded by a $change revision") {
                val id = newInquiry()
                val a = issue(id)
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val pausing =
                    object : InquiryProposalRepository by history {
                        override fun append(
                            transaction: Transaction,
                            proposal: InquiryProposal,
                        ) {
                            history.append(transaction, proposal)
                            entered.countDown()
                            check(resume.await(30, TimeUnit.SECONDS))
                        }
                    }
                val inputs = newLinesProposal(acceptanceLines(guests = 100))
                val first =
                    CompletableFuture.supplyAsync {
                        if (change == "QUOTE") {
                            ReviseInquiryQuoteProposal(app.transactor, core(pausing))(
                                ReviseInquiryQuoteProposal.Command(
                                    id,
                                    Version.of(2),
                                    DepositRequirementRevision.INITIAL,
                                    inputs,
                                    percent,
                                    actor,
                                ),
                            )
                        } else {
                            ReviseInquiryProposalDeposit(app.transactor, core(pausing))(
                                ReviseInquiryProposalDeposit.Command(
                                    id,
                                    Version.of(2),
                                    DepositRequirementRevision.INITIAL,
                                    fixed("300"),
                                    actor,
                                ),
                            )
                        }
                    }
                try {
                    entered.await(30, TimeUnit.SECONDS) shouldBe true
                    val pid = AtomicInteger()
                    val second =
                        CompletableFuture.supplyAsync {
                            runCatching {
                                ReviseInquiryProposalDeposit(app.transactor, core(associations = observedOwners(pid)))(
                                    ReviseInquiryProposalDeposit.Command(
                                        id,
                                        Version.of(2),
                                        DepositRequirementRevision.INITIAL,
                                        fixed("200"),
                                        actor,
                                    ),
                                )
                            }.exceptionOrNull()
                        }
                    awaitAssociationWait(pid)
                    resume.countDown()
                    val b = first.get(30, TimeUnit.SECONDS)
                    second.get(30, TimeUnit.SECONDS).shouldBeInstanceOf<CommerceFailure.Conflict>()
                    persisted(id) shouldBe listOf(a.proposal, b.proposal)
                    current(a.proposal.id) shouldBe false
                    current(b.proposal.id) shouldBe true
                } finally {
                    resume.countDown()
                }
            }
        }

        test("currentness retains one unlocked repeatable snapshot while a deposit reissuance commits") {
            val id = newInquiry()
            val a = issue(id)
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val pausing =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun initialEstimateOf(
                        transaction: Transaction,
                        inquiryId: InquiryId,
                    ): UUID? {
                        val canonical = owners.initialEstimateOf(transaction, inquiryId)
                        entered.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                        return canonical
                    }
                }
            val reader =
                CompletableFuture.supplyAsync {
                    IsCurrentPayableInquiryProposal(app.transactor, app.context.financialLedger, pausing, history)(a.proposal.id)
                }
            try {
                entered.await(30, TimeUnit.SECONDS) shouldBe true
                val b = CompletableFuture.supplyAsync { deposit(id, 2, 1, fixed("300")) }.get(30, TimeUnit.SECONDS)
                current(b.proposal.id) shouldBe true
                reader.isDone shouldBe false
            } finally {
                resume.countDown()
            }
            reader.get(30, TimeUnit.SECONDS) shouldBe true
            current(a.proposal.id) shouldBe false
        }
    })
