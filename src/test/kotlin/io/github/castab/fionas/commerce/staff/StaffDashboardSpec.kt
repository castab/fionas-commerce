package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.InquiryDocumentAssociation
import io.github.castab.fionas.commerce.financial.InquiryDocumentPurpose
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.http.StaffDashboardResponse
import io.github.castab.fionas.commerce.inquiry.Inquiry
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryOperationalCounts
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import io.github.castab.fionas.commerce.inquiry.RequestedInquiry
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TEST_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.customer
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.inquiry
import io.github.castab.fionas.commerce.testing.requestedPricing
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.SqlStatements
import org.jdbi.v3.core.statement.StatementContext
import java.math.BigDecimal
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StaffDashboardSpec :
    FunSpec({
        lateinit var app: TestApplication
        val inquiries = JdbiInquiryRepository()
        val customers = JdbiCustomerRepository()
        val owners = JdbiInquiryFinancialDocumentRepository()
        val facts = JdbiInquiryFulfillmentRepository()

        fun operation(
            records: InquiryRepository = inquiries,
            people: CustomerRepository = customers,
            canonical: InquiryFinancialDocumentRepository = owners,
            clock: Clock = testClock,
        ) = ReadStaffDashboard(
            app.transactor,
            ReadInquiryOperationalStates(app.transactor, records, canonical, app.context.financialLedger, facts),
            records,
            people,
            clock,
        )

        beforeTest { app = TestApplication.create() }
        afterTest { app.close() }

        test("empty population has zero counts, supported empty queues and explicit unavailable capability") {
            val result = operation()()
            result.summary shouldBe InquiryOperationalCounts(0, 0, 0, 0)
            result.asOf shouldBe STORED_INSTANT
            listOf(result.workQueue.needsQuote, result.workQueue.awaitingQuoteReply, result.workQueue.needsClosing).forEach {
                it.items shouldBe emptyList()
            }
            result.workQueue.needsReply.reason shouldBe StaffQueueUnavailability.COMMUNICATIONS_NOT_IMPLEMENTED
            result.workQueue.needsResolution.reason shouldBe StaffQueueUnavailability.RESOLUTION_POLICY_NOT_DEFINED
            app.transactor.inTransaction { inquiries.findByIds(it, emptySet()) } shouldBe emptyMap()
        }

        test("customer and event enrichment orders complete queues by oldest inquiry then lexical UUID including high-bit ties") {
            // Controlled identities straddle UUID's signed comparison boundary; lexical ordering is deliberate.
            val tied = listOf("80000000-0000-0000-0000-000000000001", "70000000-0000-0000-0000-000000000001")
            val person = customer(name = "Returning Customer")
            val oldest = inquiry(person, createdAt = STORED_INSTANT.minusSeconds(86400))
            val records = tied.map { inquiry(person).copy(id = InquiryId(UUID.fromString(it))) } + oldest
            app.transactor.inTransaction { transaction ->
                customers.insert(transaction, person)
                records.forEach { record ->
                    inquiries.insert(transaction, record, requestedPricing())
                    val usd = Currency.getInstance("USD")
                    val document =
                        FinancialDocument.Estimate.create(
                            UUID.randomUUID(),
                            listOf(
                                LineItem(
                                    UUID.randomUUID(),
                                    "Service",
                                    null,
                                    null,
                                    Money(BigDecimal("9.375"), usd),
                                    Money(BigDecimal.ZERO, usd),
                                ),
                            ),
                        )
                    app.context.financialLedger.create(transaction, document)
                    owners.associate(
                        transaction,
                        InquiryDocumentAssociation(record.id, document.id, STORED_INSTANT, InquiryDocumentPurpose.INITIAL_ESTIMATE),
                    )
                }
            }
            app.database.execute("UPDATE fionas.inquiries SET event_type = 'WEDDING' WHERE id = '${oldest.id.value}'")
            val result = operation()()
            result.workQueue.needsQuote.items
                .map { it.inquiryId.value.toString() } shouldBe
                listOf(oldest.id.value.toString(), tied[1], tied[0])
            result.workQueue.needsQuote.items
                .map { it.customerId }
                .toSet()
                .size shouldBe 1
            result.workQueue.needsQuote.items
                .forEach { it.customerName.value shouldBe "Returning Customer" }
            result.workQueue.needsQuote.items
                .first()
                .eventType.name shouldBe "WEDDING"
            result.workQueue.needsQuote.items
                .first()
                .eventDate.value
                .toString() shouldBe "2026-12-05"
            result.summary.new shouldBe 3
            val dto = CommerceJson.asA(app.adminGet("/staff/dashboard").bodyString(), StaffDashboardResponse.serializer())
            dto.workQueue.needsQuote.items
                .map { it.inquiryId } shouldBe
                result.workQueue.needsQuote.items
                    .map { it.inquiryId.value.toString() }
            dto.workQueue.needsQuote.items.forEach {
                it.total shouldBe "9.375"
                it.balance shouldBe "9.375"
            }
        }

        test("one and twenty inquiries use nine unlocked SQL statements, set enrichment and one Clock evaluation after first read") {
            app.createAcceptanceCatalog()
            listOf(1, 20).forEach { size ->
                repeat(if (size == 1) 1 else 19) { app.createInquiry("shared@example.com") }
                val sql = mutableListOf<String>()
                var observed: Transaction? = null
                var inquiryCalls = 0
                var customerCalls = 0
                var clockCalls = 0
                val records =
                    object : InquiryRepository by inquiries {
                        override fun ids(transaction: Transaction): Set<InquiryId> {
                            observed = transaction
                            transaction.handle.getConfig(SqlStatements::class.java).setSqlLogger(
                                object : SqlLogger {
                                    override fun logBeforeExecution(context: StatementContext) {
                                        context.connection.transactionIsolation shouldBe Connection.TRANSACTION_REPEATABLE_READ
                                        context.connection.autoCommit shouldBe false
                                        sql += context.rawSql
                                    }
                                },
                            )
                            return inquiries.ids(transaction)
                        }

                        override fun findByIds(
                            transaction: Transaction,
                            ids: Set<InquiryId>,
                        ): Map<InquiryId, Inquiry> {
                            transaction shouldBe observed
                            inquiryCalls++
                            ids.size shouldBe size
                            return inquiries
                                .findByIds(transaction, ids)
                                .entries
                                .reversed()
                                .associate { it.toPair() }
                        }

                        override fun findById(
                            transaction: Transaction,
                            id: InquiryId,
                        ): Inquiry? = error("No per-inquiry read")

                        override fun findRequested(
                            transaction: Transaction,
                            id: InquiryId,
                        ): RequestedInquiry? = error("No pricing history read")
                    }
                val people =
                    object : CustomerRepository by customers {
                        override fun findByIds(
                            transaction: Transaction,
                            ids: Set<CustomerId>,
                        ): Map<CustomerId, Customer> {
                            transaction shouldBe observed
                            customerCalls++
                            ids.size shouldBe 1
                            return customers.findByIds(transaction, ids)
                        }

                        override fun findById(
                            transaction: Transaction,
                            id: CustomerId,
                        ): Customer? = error("No per-customer read")
                    }
                val clock =
                    object : Clock() {
                        override fun getZone(): ZoneId = testClock.zone

                        override fun withZone(zone: ZoneId): Clock = this

                        override fun instant(): Instant {
                            sql.isNotEmpty() shouldBe true
                            observed!!.handle.connection.autoCommit shouldBe false
                            clockCalls++
                            return TEST_INSTANT
                        }
                    }
                val result = operation(records, people, clock = clock)()
                result.asOf shouldBe STORED_INSTANT
                result.summary.new shouldBe size
                result.workQueue.needsQuote.items.size shouldBe size
                listOf(inquiryCalls, customerCalls, clockCalls) shouldBe listOf(1, 1, 1)
                sql.size shouldBe 9
                sql.none { it.contains("FOR UPDATE") || it.contains("FOR NO KEY UPDATE") } shouldBe true
                sql.count { it.contains("FROM fionas.inquiries") } shouldBe 2
                sql.count { it.contains("FROM fionas.customers") } shouldBe 1
            }
        }

        listOf("inquiry", "customer").forEach { missing ->
            test("missing $missing enrichment fails the entire projection") {
                app.createAcceptanceCatalog()
                app.createInquiry()
                val records =
                    object : InquiryRepository by inquiries {
                        override fun findByIds(
                            transaction: Transaction,
                            ids: Set<InquiryId>,
                        ): Map<InquiryId, Inquiry> = if (missing == "inquiry") emptyMap() else inquiries.findByIds(transaction, ids)
                    }
                val people =
                    object : CustomerRepository by customers {
                        override fun findByIds(
                            transaction: Transaction,
                            ids: Set<CustomerId>,
                        ): Map<CustomerId, Customer> = emptyMap()
                    }
                shouldThrow<IllegalStateException> { operation(records, people)() }.message shouldBe
                    "Dashboard $missing enrichment is incomplete or corrupt"
            }
        }

        test("missing canonical financial data is internal corruption rather than a caller not-found") {
            app.createAcceptanceCatalog()
            val id = InquiryId(UUID.fromString(app.createInquiry()))
            val canonical =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun initialEstimates(transaction: Transaction): Map<InquiryId, UUID> = mapOf(id to UUID.randomUUID())
                }
            shouldThrow<IllegalStateException> { operation(canonical = canonical)() }.message shouldBe
                "Canonical dashboard financial data is missing"
        }

        test("concurrent quote and customer/event writer cannot mix operational and enrichment snapshots") {
            app.createAcceptanceCatalog()
            val id = app.createInquiry()
            val document = UUID.fromString(app.initialEstimateOf(id))
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val records =
                object : InquiryRepository by inquiries {
                    override fun findByIds(
                        transaction: Transaction,
                        ids: Set<InquiryId>,
                    ): Map<InquiryId, Inquiry> {
                        paused.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                        return inquiries.findByIds(transaction, ids)
                    }
                }
            val reader = CompletableFuture.supplyAsync { operation(records)() }
            try {
                paused.await(30, TimeUnit.SECONDS) shouldBe true
                CompletableFuture
                    .runAsync {
                        app.transactor.inTransaction { transaction ->
                            app.context.financialLedger.issueQuote(transaction, document)
                            transaction.handle.createUpdate("UPDATE fionas.customers SET name = 'Changed Customer'").execute()
                            transaction.handle
                                .createUpdate(
                                    "UPDATE fionas.inquiries SET event_date = DATE '2027-01-02', event_type = 'CORPORATE'",
                                ).execute()
                        }
                    }.get(10, TimeUnit.SECONDS)
            } finally {
                resume.countDown()
            }
            val old =
                reader
                    .get(10, TimeUnit.SECONDS)
                    .workQueue.needsQuote.items
                    .single()
            old.stage shouldBe InquiryStage.REQUESTED
            old.customerName.value shouldBe "Jane Doe"
            old.eventDate.value.toString() shouldBe "2026-12-05"
            old.eventType.name shouldBe "BIRTHDAY"
            old.latestFinancialVersion.document.version shouldBe Version.INITIAL
            val current =
                operation()()
                    .workQueue.awaitingQuoteReply.items
                    .single()
            current.stage shouldBe InquiryStage.QUOTED
            current.customerName.value shouldBe "Changed Customer"
            current.eventDate.value.toString() shouldBe "2027-01-02"
            current.eventType.name shouldBe "CORPORATE"
            current.latestFinancialVersion.document.version shouldBe Version.of(2)
        }
    })
