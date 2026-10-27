package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryRepository
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryOperationalStates
import io.github.castab.fionas.commerce.inquiry.RequestedInquiry
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.quoteLatest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.SqlStatements
import org.jdbi.v3.core.statement.StatementContext
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ListStaffRequestsSpec :
    FunSpec({
        lateinit var app: TestApplication
        val inquiries = JdbiInquiryRepository()
        val customers = JdbiCustomerRepository()

        fun operation(
            records: InquiryRepository = inquiries,
            people: CustomerRepository = customers,
        ) = ListStaffRequests(
            app.transactor,
            ReadInquiryOperationalStates(
                app.transactor,
                records,
                JdbiInquiryFinancialDocumentRepository(),
                app.context.financialLedger,
                JdbiInquiryFulfillmentRepository(),
            ),
            records,
            people,
        )
        beforeTest { app = TestApplication.create() }
        afterTest { app.close() }

        test("bulk reads use ten unlocked statements for one or many inquiries and enrich in the same repeatable snapshot") {
            listOf(1, 20).forEach { size ->
                repeat(if (size == 1) 1 else 19) { app.createInquiry() }
                val sql = mutableListOf<String>()
                var observed: Transaction? = null
                var enrichmentCalls = 0
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

                        override fun findRequestedByIds(
                            transaction: Transaction,
                            ids: Set<InquiryId>,
                        ): Map<InquiryId, RequestedInquiry> {
                            transaction shouldBe observed
                            enrichmentCalls++
                            return inquiries.findRequestedByIds(transaction, ids)
                        }
                    }
                val people =
                    object : CustomerRepository by customers {
                        override fun findByIds(
                            transaction: Transaction,
                            ids: Set<CustomerId>,
                        ): Map<CustomerId, Customer> {
                            transaction shouldBe observed
                            enrichmentCalls++
                            return customers.findByIds(transaction, ids)
                        }
                    }
                val page = operation(records, people)(ListStaffRequests.Command(limit = 1))
                page.totalCount shouldBe size
                page.items.size shouldBe 1
                enrichmentCalls shouldBe 2
                sql.size shouldBe 10
                sql.none { "FOR UPDATE" in it || "FOR NO KEY UPDATE" in it } shouldBe true
            }
        }

        test("missing enrichment fails even when search would hide every inquiry") {
            app.createInquiry()
            val missing =
                object : CustomerRepository by customers {
                    override fun findByIds(
                        transaction: Transaction,
                        ids: Set<CustomerId>,
                    ): Map<CustomerId, Customer> = emptyMap()
                }
            shouldThrow<IllegalStateException> {
                operation(people = missing)(ListStaffRequests.Command(search = StaffRequestSearch.of("absent")))
            }
            val records =
                object : InquiryRepository by inquiries {
                    override fun findRequestedByIds(
                        transaction: Transaction,
                        ids: Set<InquiryId>,
                    ): Map<InquiryId, RequestedInquiry> = emptyMap()
                }
            shouldThrow<IllegalStateException> { operation(records)(ListStaffRequests.Command()) }
        }

        test("concurrent financial and customer commits do not mix totalCount membership and page enrichment") {
            val id = app.createInquiry()
            val document = UUID.fromString(app.initialEstimateOf(id))
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val records =
                object : InquiryRepository by inquiries {
                    override fun findRequestedByIds(
                        transaction: Transaction,
                        ids: Set<InquiryId>,
                    ): Map<InquiryId, RequestedInquiry> {
                        paused.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                        return inquiries.findRequestedByIds(transaction, ids)
                    }
                }
            val query = ListStaffRequests.Command(StaffRequestFilter.NEW, StaffRequestSearch.of("Jane"))
            val pending = CompletableFuture.supplyAsync { operation(records)(query) }
            try {
                paused.await(30, TimeUnit.SECONDS) shouldBe true
                CompletableFuture
                    .runAsync {
                        app.transactor.inTransaction { transaction ->
                            app.context.financialLedger.quoteLatest(transaction, document)
                            transaction.handle.createUpdate("UPDATE fionas.customers SET name = 'Changed Customer'").execute()
                        }
                    }.get(10, TimeUnit.SECONDS)
            } finally {
                resume.countDown()
            }
            val old = pending.get(10, TimeUnit.SECONDS)
            old.totalCount shouldBe 1
            old.items
                .single()
                .customer.name.value shouldBe "Jane Doe"
            old.items
                .single()
                .operational.lifecycle.stage shouldBe InquiryStage.REQUESTED
            operation()(query).totalCount shouldBe 0
            operation()(ListStaffRequests.Command())
                .items
                .single()
                .operational.lifecycle.stage shouldBe InquiryStage.QUOTED
        }
    })
