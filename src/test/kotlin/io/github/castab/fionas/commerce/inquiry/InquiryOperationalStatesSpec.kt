package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.commerce.staff.UserId
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.InquiryDocumentAssociation
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
import io.github.castab.fionas.commerce.http.StaffDashboardResponse
import io.github.castab.fionas.commerce.http.StaffDashboardSummaryResponse
import io.github.castab.fionas.commerce.staff.DashboardAttentionPolicy
import io.github.castab.fionas.commerce.staff.ReadStaffDashboard
import io.github.castab.fionas.commerce.staff.StaffAttentionReason
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.changeLatest
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.proposalId
import io.github.castab.fionas.commerce.testing.replacementLines
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Status
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.SqlStatements
import org.jdbi.v3.core.statement.StatementContext
import java.math.BigDecimal
import java.sql.Connection
import java.time.ZoneId
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InquiryOperationalStatesSpec :
    FunSpec({
        lateinit var app: TestApplication
        val inquiries = JdbiInquiryRepository()
        val owners = JdbiInquiryFinancialDocumentRepository()
        val facts = JdbiInquiryFulfillmentRepository()
        val pricing = JdbiFinancialDocumentAuthorshipRepository()
        val user = UserId(UUID.randomUUID())
        val service = ServiceId(UUID.randomUUID())

        fun money(amount: String) = Money(BigDecimal(amount), Currency.getInstance("USD"))

        fun read() = ReadInquiryOperationalStates(app.transactor, inquiries, owners, app.context.financialLedger, facts)()

        fun dashboard() =
            ReadStaffDashboard(
                app.transactor,
                ReadInquiryOperationalStates(app.transactor, inquiries, owners, app.context.financialLedger, facts),
                inquiries,
                JdbiCustomerRepository(),
                testClock,
                JdbiInquiryCommunicationRepository(),
                ZoneId.of("America/Los_Angeles"),
            )()

        fun requested(): Pair<InquiryId, UUID> {
            val id = app.createInquiry()
            return InquiryId(UUID.fromString(id)) to UUID.fromString(app.initialEstimateOf(id))
        }

        fun quote(): Pair<InquiryId, UUID> =
            requested().also {
                app.issueProposal(it.first)
            }

        fun pay(
            id: UUID,
            amount: BigDecimal,
        ) = RecordDocumentPayment(app.transactor, app.context.financialLedger, owners, pricing, testClock, JdbiInquiryProposalRepository())(
            RecordDocumentPayment.Command(
                id,
                app.context.financialLedger
                    .latest(id)
                    .version,
                amount,
                PaymentMethod.CARD,
                null,
                null,
                app.proposalId(id),
            ),
        )

        fun book(pair: Pair<InquiryId, UUID>): Pair<InquiryId, UUID> {
            pay(pair.second, BigDecimal("50.00"))
            return pair
        }

        fun booked() = book(quote())

        fun manage() = ManageInquiryFulfillment(app.transactor, inquiries, owners, app.context.financialLedger, facts, testClock)

        fun serve(pair: Pair<InquiryId, UUID>) {
            manage().markServed(ManageInquiryFulfillment.Command(pair.first, user))
        }

        fun balance(id: UUID) =
            app.context.financialLedger
                .financialLineages(listOf(id))
                .single()
                .reconciliation.balance.amount

        fun addCharge(id: UUID) {
            app.transactor.inTransaction { transaction ->
                app.context.financialLedger.changeLatest(
                    transaction,
                    id,
                    ChangeOrder(
                        listOf(
                            ChangeOrder.Change.AddLineItem(LineItem(UUID.randomUUID(), "Extra", null, null, money("10.00"), money("0.00"))),
                        ),
                    ),
                )
            }
        }

        beforeTest {
            app = TestApplication.create()
        }
        afterTest { app.close() }

        test("empty system and empty fulfillment input return empty states and zero counts without empty SQL lists") {
            read().states shouldBe emptyList()
            read().counts shouldBe InquiryOperationalCounts(0, 0, 0, 0)
            InquiryOperationalCounts.project(emptyList()) shouldBe InquiryOperationalCounts(0, 0, 0, 0)
            app.transactor.inTransaction { transaction ->
                owners.initialEstimates(transaction) shouldBe emptyMap()
                facts.findAll(transaction, emptyList()) shouldBe emptyMap()
            }
        }

        test("mixed population and pure counts obey all seven cases with overlapping served and needs-closing counts") {
            val requested = requested()
            val quoted = quote()
            val booked = booked()
            val positive = booked().also(::serve)
            val zero = booked().also(::serve).also { pay(it.second, balance(it.second)) }
            val negative = booked().also(::serve).also { pay(it.second, balance(it.second).add(BigDecimal.ONE)) }
            val closed =
                booked().also(::serve).also {
                    pay(it.second, balance(it.second))
                    manage().close(ManageInquiryFulfillment.Command(it.first, service))
                }
            val snapshot = read()
            snapshot.states.size shouldBe 7
            snapshot.counts shouldBe InquiryOperationalCounts(1, 1, 4, 1)
            val dashboard = dashboard()
            dashboard.summary shouldBe snapshot.counts
            dashboard.workQueue.needsQuote.items
                .map { it.inquiryId } shouldBe listOf(requested.first)
            dashboard.workQueue.needsResolution.items
                .filter { StaffAttentionReason.READY_TO_CLOSE in it.reasons }
                .map { it.inquiryId } shouldBe listOf(zero.first)
            dashboard.workQueue.needsResolution.items
                .single { StaffAttentionReason.READY_TO_CLOSE in it.reasons }
                .servedAt shouldBe STORED_INSTANT
            val response = app.adminGet("/staff/dashboard")
            response.status.code shouldBe 200
            val dto = CommerceJson.asA(response.bodyString(), StaffDashboardResponse.serializer())
            dto.summary shouldBe StaffDashboardSummaryResponse(1, 1, 4, 1)
            dto.workQueue.needsResolution.items
                .filter { it.reasons.any { reason -> reason.name == "READY_TO_CLOSE" } }
                .map { it.inquiryId } shouldBe listOf(zero.first.value.toString())
            dto.workQueue.needsResolution.items
                .single { it.reasons.any { reason -> reason.name == "READY_TO_CLOSE" } }
                .balance shouldBe "0.00"
            val states = snapshot.states.associateBy { it.inquiryId }
            listOf(
                requested to InquiryOperationalCounts(1, 0, 0, 0),
                quoted to InquiryOperationalCounts(0, 1, 0, 0),
                booked to InquiryOperationalCounts(0, 0, 1, 0),
                positive to InquiryOperationalCounts(0, 0, 1, 0),
                zero to InquiryOperationalCounts(0, 0, 1, 1),
                negative to InquiryOperationalCounts(0, 0, 1, 0),
                closed to InquiryOperationalCounts(0, 0, 0, 0),
            ).forEach { (pair, expected) ->
                val state = states.getValue(pair.first)
                state.lifecycle.documentId shouldBe pair.second
                state.financial.latestVersion.document.id shouldBe pair.second
                InquiryOperationalCounts.project(listOf(state)) shouldBe expected
            }
            states
                .getValue(zero.first)
                .financial.reconciliation.balance.amount
                .signum() shouldBe 0
            // Zero decimals with different scales compare equally to the authoritative balance.
            listOf("0", "0.0", "0.0000").forEach {
                states
                    .getValue(zero.first)
                    .financial.reconciliation.balance.amount
                    .compareTo(BigDecimal(it)) shouldBe 0
            }
            app.database.execute(
                "UPDATE fionas.inquiries SET event_date = CASE WHEN event_type = 'BIRTHDAY' THEN DATE '0001-01-01' ELSE DATE '9999-12-31' END",
            )
            read().counts shouldBe snapshot.counts
            app.database.execute("UPDATE fionas.inquiries SET event_date = DATE '9999-12-31'")
            read().counts shouldBe snapshot.counts
        }

        test("RELATED invoices and money do not affect canonical lifecycle or counts") {
            val (inquiry, canonical) = requested()
            val related =
                FinancialDocument.Invoice.create(
                    UUID.randomUUID(),
                    listOf(LineItem(UUID.randomUUID(), "Related service", null, null, money("20.00"), money("0.00"))),
                )
            app.transactor.inTransaction { transaction ->
                app.context.financialLedger.create(transaction, related)
                owners.associate(transaction, InquiryDocumentAssociation(inquiry, related.id, STORED_INSTANT))
            }
            pay(related.id, BigDecimal("20.00"))
            val snapshot = read()
            snapshot.states
                .single()
                .financial.latestVersion.document.id shouldBe canonical
            snapshot.states
                .single()
                .lifecycle.stage shouldBe InquiryStage.REQUESTED
            snapshot.counts shouldBe InquiryOperationalCounts(1, 0, 0, 0)
            val item =
                dashboard()
                    .workQueue.needsQuote.items
                    .single()
            item.latestFinancialVersion.document.id shouldBe canonical
            item.latestFinancialVersion.document.total shouldBe
                snapshot.states
                    .single()
                    .financial.latestVersion.document.total
            item.balance shouldBe
                snapshot.states
                    .single()
                    .financial.reconciliation.balance
            app.transactor.inTransaction { owners.initialEstimates(it) } shouldBe mapOf(inquiry to canonical)
        }

        test("pure counts treat zero balances at different decimal scales equally and snapshots retain their own state collection") {
            val views =
                app.transactor.inTransaction { transaction ->
                    val documents =
                        listOf("0", "0.0", "0.0000").map { zero ->
                            FinancialDocument.Invoice
                                .create(
                                    UUID.randomUUID(),
                                    listOf(LineItem(UUID.randomUUID(), "Zero service", null, null, money(zero), money(zero))),
                                ).also { app.context.financialLedger.create(transaction, it) }
                        }
                    app.context.financialLedger.financialLineages(transaction, documents.map { it.id })
                }
            views
                .map {
                    it.reconciliation.balance.amount
                        .scale()
                }.toSet() shouldBe setOf(0, 1, 4)
            val states =
                views.map {
                    InquiryOperationalState(InquiryId(UUID.randomUUID()), it, InquiryFulfillment(InquiryMilestone(STORED_INSTANT, user)))
                }
            // Projection below has only in-memory input and needs no transaction or database collaborator.
            states.forEach { InquiryOperationalCounts.project(listOf(it)) shouldBe InquiryOperationalCounts(0, 0, 1, 1) }
            states.forEach {
                it.needsClosing shouldBe true
                val resolution =
                    DashboardAttentionPolicy().needsResolution(
                        it,
                        EventDate.of("2020-01-01"),
                        InquiryCommunicationAttention(null, null),
                        STORED_INSTANT,
                        testClock.zone,
                    )!!
                resolution.reasons shouldBe setOf(StaffAttentionReason.READY_TO_CLOSE)
                resolution.attentionSince shouldBe STORED_INSTANT
            }
            InquiryOperationalCounts.project(states) shouldBe InquiryOperationalCounts(0, 0, 3, 3)
            val source = states.toMutableList()
            val snapshot = InquiryOperationalSnapshot(source)
            source.clear()
            snapshot.states.size shouldBe 3
            snapshot.counts shouldBe InquiryOperationalCounts(0, 0, 3, 3)
        }

        test("quote and Invoice revisions preserve lifecycle; refunds do not demote durable booking") {
            val pair = quote()
            app
                .adminPost(
                    "/staff/requests/${pair.first.value}/proposals/quote-revisions",
                    """{"expectedDocumentVersion":2,"expectedDepositRequirementRevision":1,
                    "lines":${replacementLines(acceptanceLines(guests = 100))},
                    "terms":{"type":"FIXED","amount":"50","currency":"USD"}}""",
                ).status shouldBe Status.OK
            read()
                .states
                .single()
                .lifecycle.stage shouldBe InquiryStage.QUOTED
            read().counts shouldBe InquiryOperationalCounts(0, 1, 0, 0)
            val payment = pay(pair.second, BigDecimal("50.00"))
            addCharge(pair.second)
            read()
                .states
                .single()
                .lifecycle.stage shouldBe InquiryStage.BOOKED
            RecordRefund(app.transactor, app.context.financialLedger, testClock)(
                RecordRefund.Command(
                    payment.payment.id,
                    BigDecimal("1.00"),
                    Currency.getInstance("USD"),
                    PaymentMethod.CARD,
                    null,
                    null,
                    listOf(RecordRefund.AllocationCommand(payment.allocation.id, BigDecimal("1.00"))),
                ),
            )
            val refunded = read()
            refunded.states
                .single()
                .financial.depositSatisfied shouldBe false
            refunded.counts shouldBe InquiryOperationalCounts(0, 0, 1, 0)
            serve(pair)
            pay(pair.second, balance(pair.second))
            read().counts shouldBe InquiryOperationalCounts(0, 0, 1, 1)
            addCharge(pair.second)
            read()
                .states
                .single()
                .lifecycle.stage shouldBe InquiryStage.SERVED
            read().counts shouldBe InquiryOperationalCounts(0, 0, 1, 0)
            pay(pair.second, balance(pair.second))
            manage().close(ManageInquiryFulfillment.Command(pair.first, service))
            addCharge(pair.second)
            read()
                .states
                .single()
                .lifecycle.stage shouldBe InquiryStage.CLOSED
            read().counts shouldBe InquiryOperationalCounts(0, 0, 0, 0)
        }

        test("bulk fulfillment reconstructs both USER and SERVICE provenance, omits unserved and excludes unrequested ids") {
            val first = booked()
            val second = booked()
            val third = booked()
            val unserved = requested()
            app.transactor.inTransaction { transaction ->
                facts.serve(transaction, first.first, InquiryMilestone(STORED_INSTANT, user))
                facts.close(transaction, first.first, InquiryMilestone(STORED_INSTANT.plusSeconds(1), service))
                facts.serve(transaction, second.first, InquiryMilestone(STORED_INSTANT, service))
                facts.close(transaction, second.first, InquiryMilestone(STORED_INSTANT.plusSeconds(2), user))
                facts.serve(transaction, third.first, InquiryMilestone(STORED_INSTANT, user))
                facts.findAll(
                    transaction,
                    listOf(first.first, second.first, unserved.first, InquiryId(UUID.randomUUID()), first.first),
                ) shouldBe
                    mapOf(
                        first.first to
                            InquiryFulfillment(
                                InquiryMilestone(STORED_INSTANT, user),
                                InquiryMilestone(STORED_INSTANT.plusSeconds(1), service),
                            ),
                        second.first to
                            InquiryFulfillment(
                                InquiryMilestone(STORED_INSTANT, service),
                                InquiryMilestone(STORED_INSTANT.plusSeconds(2), user),
                            ),
                    )
                facts.findAll(transaction, listOf(third.first)).getValue(third.first) shouldBe facts.find(transaction, third.first)
            }
        }

        test("missing canonical association fails rather than dropping an inquiry or using a RELATED lineage") {
            val pair = requested()
            app.database.execute("UPDATE fionas.inquiry_financial_documents SET purpose = 'RELATED' WHERE document_id = '${pair.second}'")
            shouldThrow<IllegalStateException> { read() }.message shouldBe
                "Inquiry population does not match canonical initial Estimate relationships"
        }

        test("corrupt canonical document identity fails through the runtime semantic read") {
            val pair = requested()
            val corrupt =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun initialEstimates(transaction: Transaction): Map<InquiryId, UUID> = mapOf(pair.first to UUID.randomUUID())
                }
            shouldThrow<CommerceFailure.NotFound> {
                ReadInquiryOperationalStates(app.transactor, inquiries, corrupt, app.context.financialLedger, facts)()
            }
        }

        test("canonical relationships with a different inquiry population fail even when population sizes match") {
            val pair = requested()
            val corrupt =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun initialEstimates(transaction: Transaction): Map<InquiryId, UUID> =
                        mapOf(InquiryId(UUID.randomUUID()) to pair.second)
                }
            shouldThrow<IllegalStateException> {
                ReadInquiryOperationalStates(app.transactor, inquiries, corrupt, app.context.financialLedger, facts)()
            }.message shouldBe "Inquiry population does not match canonical initial Estimate relationships"
        }

        test("multiple inquiries resolving to one canonical lineage fail rather than sharing financial state") {
            val first = requested()
            val second = requested()
            val corrupt =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun initialEstimates(transaction: Transaction): Map<InquiryId, UUID> =
                        mapOf(first.first to first.second, second.first to first.second)
                }
            shouldThrow<IllegalStateException> {
                ReadInquiryOperationalStates(app.transactor, inquiries, corrupt, app.context.financialLedger, facts)()
            }.message shouldBe "Canonical financial lineage belongs to multiple inquiries"
        }

        listOf("incomplete", "duplicate", "unexpected").forEach { corruption ->
            test("$corruption runtime bulk results fail instead of returning a partial or ambiguous operational snapshot") {
                requested()
                requested()
                val unrelated =
                    FinancialDocument.Invoice.create(
                        UUID.randomUUID(),
                        listOf(LineItem(UUID.randomUUID(), "Unrelated service", null, null, money("20.00"), money("0.00"))),
                    )
                app.transactor.inTransaction { app.context.financialLedger.create(it, unrelated) }
                shouldThrow<IllegalStateException> {
                    ReadInquiryOperationalStates(
                        app.transactor,
                        inquiries,
                        owners,
                        app.context.financialLedger,
                        facts,
                        readLineages = { transaction, ids ->
                            val views = app.context.financialLedger.financialLineages(transaction, ids)
                            when (corruption) {
                                "incomplete" -> views.take(1)
                                "duplicate" -> listOf(views.first(), views.first())
                                else ->
                                    views.take(1) +
                                        app.context.financialLedger.financialLineages(transaction, listOf(unrelated.id))
                            }
                        },
                    )()
                }.message shouldBe "Canonical financial lineages are incomplete or corrupt"
                // The fault is confined to the read seam; authoritative data still yields the complete population.
                read().counts shouldBe InquiryOperationalCounts(2, 0, 0, 0)
            }
        }

        test("fulfillment outside the inquiry population fails instead of being silently discarded") {
            requested()
            val corrupt =
                object : InquiryFulfillmentRepository by facts {
                    override fun findAll(
                        transaction: Transaction,
                        inquiryIds: Collection<InquiryId>,
                    ): Map<InquiryId, InquiryFulfillment> =
                        mapOf(InquiryId(UUID.randomUUID()) to InquiryFulfillment(InquiryMilestone(STORED_INSTANT, user)))
                }
            shouldThrow<IllegalStateException> {
                ReadInquiryOperationalStates(app.transactor, inquiries, owners, app.context.financialLedger, corrupt)()
            }.message shouldBe "Fulfillment returned an inquiry outside the operational population"
        }

        test("fulfillment before canonical Invoice still fails in the authoritative projector") {
            val pair = requested()
            app.transactor.inTransaction { facts.serve(it, pair.first, InquiryMilestone(STORED_INSTANT, user)) }
            shouldThrow<IllegalStateException> { read() }.message shouldBe "Inquiry fulfillment requires a canonical Invoice"
        }

        test("one and twenty inquiries use seven SQL statements, one bulk runtime call and the same unlocked transaction") {
            listOf(1, 20).forEach { size ->
                repeat(if (size == 1) 1 else 19) { requested() }
                val sql = mutableListOf<String>()
                var observed: Transaction? = null
                var populationCalls = 0
                var associationCalls = 0
                var financialCalls = 0
                var fulfillmentCalls = 0
                val population =
                    object : InquiryRepository by inquiries {
                        override fun ids(transaction: Transaction): Set<InquiryId> {
                            populationCalls++
                            observed = transaction
                            val connection = transaction.handle.connection
                            connection.transactionIsolation shouldBe Connection.TRANSACTION_REPEATABLE_READ
                            transaction.handle.getConfig(SqlStatements::class.java).setSqlLogger(
                                object : SqlLogger {
                                    override fun logBeforeExecution(context: StatementContext) {
                                        (context.connection === connection) shouldBe true
                                        context.connection.autoCommit shouldBe false
                                        context.connection.transactionIsolation shouldBe Connection.TRANSACTION_REPEATABLE_READ
                                        sql += context.rawSql
                                    }
                                },
                            )
                            return inquiries.ids(transaction)
                        }

                        override fun findById(
                            transaction: Transaction,
                            id: InquiryId,
                        ): Inquiry? = error("No single inquiry reads")
                    }
                val canonical =
                    object : InquiryFinancialDocumentRepository by owners {
                        override fun initialEstimates(transaction: Transaction): Map<InquiryId, UUID> {
                            associationCalls++
                            transaction shouldBe observed
                            return owners.initialEstimates(transaction)
                        }

                        override fun initialEstimateOf(
                            transaction: Transaction,
                            inquiryId: InquiryId,
                        ): UUID? = error("No single canonical reads")

                        override fun lockInquiryOf(
                            transaction: Transaction,
                            documentId: UUID,
                        ): InquiryId? = error("No read locks")
                    }
                val fulfillment =
                    object : InquiryFulfillmentRepository by facts {
                        override fun findAll(
                            transaction: Transaction,
                            inquiryIds: Collection<InquiryId>,
                        ): Map<InquiryId, InquiryFulfillment> {
                            fulfillmentCalls++
                            transaction shouldBe observed
                            inquiryIds.size shouldBe size
                            return facts.findAll(transaction, inquiryIds)
                        }

                        override fun find(
                            transaction: Transaction,
                            inquiryId: InquiryId,
                        ): InquiryFulfillment? = error("No single fulfillment reads")
                    }
                val snapshot =
                    ReadInquiryOperationalStates(
                        app.transactor,
                        population,
                        canonical,
                        app.context.financialLedger,
                        fulfillment,
                        readLineages = { transaction, ids ->
                            financialCalls++
                            transaction shouldBe observed
                            ids.size shouldBe size
                            // Reverse the runtime order to prove identity pairing does not depend on parallel list order.
                            app.context.financialLedger
                                .financialLineages(transaction, ids)
                                .reversed()
                        },
                    )()
                snapshot.counts shouldBe InquiryOperationalCounts(size, 0, 0, 0)
                snapshot.states.associate { it.inquiryId to it.financial.latestVersion.document.id } shouldBe
                    app.transactor.inTransaction { owners.initialEstimates(it) }
                listOf(populationCalls, associationCalls, financialCalls, fulfillmentCalls) shouldBe listOf(1, 1, 1, 1)
                sql.size shouldBe 7
                sql.count { it.contains("FROM fionas.inquiries") } shouldBe 1
                sql.count { it.contains("FROM fionas.inquiry_financial_documents") } shouldBe 1
                sql.count { it.contains("FROM fionas.inquiry_fulfillment") } shouldBe 1
                sql.none { it.contains("FOR UPDATE") || it.contains("FOR NO KEY UPDATE") } shouldBe true
            }
        }

        listOf(false, true).forEach { afterFinancial ->
            test(
                "REPEATABLE_READ prevents hybrid booking/service state with writer committing ${if (afterFinancial) "after financial read" else "before financial read"}",
            ) {
                val pair = quote()
                val paused = CountDownLatch(1)
                val resume = CountDownLatch(1)

                fun pause() {
                    paused.countDown()
                    check(resume.await(30, TimeUnit.SECONDS))
                }
                val canonical =
                    object : InquiryFinancialDocumentRepository by owners {
                        override fun initialEstimates(transaction: Transaction): Map<InquiryId, UUID> =
                            owners.initialEstimates(transaction).also {
                                if (!afterFinancial) pause()
                            }
                    }
                val operation =
                    ReadInquiryOperationalStates(
                        app.transactor,
                        inquiries,
                        canonical,
                        app.context.financialLedger,
                        facts,
                        readLineages = { transaction, ids ->
                            app.context.financialLedger
                                .financialLineages(transaction, ids)
                                .also { if (afterFinancial) pause() }
                        },
                    )
                val reader = CompletableFuture.supplyAsync { operation() }
                try {
                    paused.await(30, TimeUnit.SECONDS) shouldBe true
                    CompletableFuture
                        .runAsync {
                            book(pair)
                            serve(pair)
                        }.get(10, TimeUnit.SECONDS)
                    reader.isDone shouldBe false
                } finally {
                    resume.countDown()
                }
                val old = reader.get(10, TimeUnit.SECONDS)
                old.states
                    .single()
                    .lifecycle.stage shouldBe InquiryStage.QUOTED
                old.states
                    .single()
                    .lifecycle.fulfillment shouldBe null
                old.states
                    .single()
                    .financial.latestVersion.document.version shouldBe Version.of(2)
                old.counts shouldBe InquiryOperationalCounts(0, 1, 0, 0)
                read()
                    .states
                    .single()
                    .lifecycle.stage shouldBe InquiryStage.SERVED
                read().counts shouldBe InquiryOperationalCounts(0, 0, 1, 0)
            }
        }
    })
