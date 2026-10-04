package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositRequirementRevision
import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.sqlState
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.http4k.core.Method
import org.http4k.core.Status
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.SqlStatements
import org.jdbi.v3.core.statement.StatementContext
import java.math.BigDecimal
import java.sql.Connection
import java.util.Currency
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DepositRequirementOperationsSpec :
    FunSpec({
        lateinit var app: TestApplication
        val owners = JdbiInquiryFinancialDocumentRepository()
        val pricing = JdbiFinancialDocumentPricingRepository()

        fun money(amount: String) = Money(BigDecimal(amount), Currency.getInstance("USD"))

        fun documents(count: Int = 1): List<UUID> {
            val inquiry = InquiryId(UUID.fromString(app.createInquiry()))
            return app.transactor.inTransaction { transaction ->
                (1..count).map {
                    val id = UUID.randomUUID()
                    app.context.financialLedger.create(
                        transaction,
                        FinancialDocument.Quote.create(
                            id,
                            listOf(LineItem(UUID.randomUUID(), "Service", null, null, money("200.00"), money("0.00"))),
                        ),
                    )
                    owners.associate(transaction, InquiryDocumentAssociation(inquiry, id, testClock.instant()))
                    id
                }
            }
        }

        fun command(id: UUID) = SetDepositRequirement.Command(id, Version.INITIAL, null, DepositTerms.Fixed(money("50.00")))
        beforeSpec {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
            app.adminCookie
        }
        afterSpec { app.close() }

        test("approval checks, mutation and all projection queries share the ownership READ_COMMITTED transaction") {
            val id = documents().single()
            var ownershipCalls = 0
            val sql = mutableListOf<String>()
            val observing =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? {
                        ownershipCalls++
                        val connection = transaction.handle.connection
                        connection.transactionIsolation shouldBe Connection.TRANSACTION_READ_COMMITTED
                        // Observe the real runtime ledger through the caller's handle, including its final bulk read.
                        transaction.handle.getConfig(SqlStatements::class.java).setSqlLogger(
                            object : SqlLogger {
                                override fun logBeforeExecution(context: StatementContext) {
                                    (context.connection === connection) shouldBe true
                                    context.connection.autoCommit shouldBe false
                                    context.connection.transactionIsolation shouldBe Connection.TRANSACTION_READ_COMMITTED
                                    sql += context.rawSql
                                }
                            },
                        )
                        return owners.lockInquiryOf(transaction, documentId)
                    }
                }
            val result = SetDepositRequirement(app.transactor, app.context.financialLedger, observing, pricing)(command(id))
            ownershipCalls shouldBe 1
            sql.first().contains("fionas.inquiry_financial_documents") shouldBe true
            sql.any { it.contains("commerce.financial_document_snapshots") && it.contains("LIMIT 1") } shouldBe true
            val writes = sql.withIndex().filter { it.value.startsWith("INSERT INTO commerce.deposit_requirement_revisions") }
            writes.size shouldBe 1
            // Canonical identity and all four runtime projection queries use the observed handle after its own write.
            val projection = sql.drop(writes.single().index + 1)
            projection.size shouldBe 5
            projection.first().contains("purpose = 'INITIAL_ESTIMATE'") shouldBe true
            projection[1].contains("SELECT DISTINCT ON (document_id)") shouldBe true
            projection.last().contains("WHERE r.document_id IN") shouldBe true
            result.depositRequirement!!.requirement.revision shouldBe DepositRequirementRevision.INITIAL
            result.depositRequirement!!.createdAt shouldBe result.activity.latestDepositRequirementAt
        }

        test("twenty lineages use one actual ownership SQL query and one runtime bulk call in the same REPEATABLE_READ transaction") {
            val ids = documents(20).reversed()
            var ownershipCalls = 0
            var financialCalls = 0
            var ownershipTransaction: Transaction? = null
            val sql = mutableListOf<String>()
            val probe =
                object : SqlLogger {
                    override fun logBeforeExecution(context: StatementContext) {
                        sql += context.rawSql
                    }
                }
            val instrumented =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun inquiriesOf(
                        transaction: Transaction,
                        documentIds: Collection<UUID>,
                    ): Map<UUID, InquiryId> {
                        ownershipCalls++
                        ownershipTransaction = transaction
                        documentIds.toList() shouldBe ids
                        transaction.handle.connection.transactionIsolation shouldBe Connection.TRANSACTION_REPEATABLE_READ
                        val config = transaction.handle.getConfig(SqlStatements::class.java)
                        val previous = config.sqlLogger
                        config.setSqlLogger(probe)
                        return try {
                            owners.inquiriesOf(transaction, documentIds)
                        } finally {
                            config.setSqlLogger(previous)
                        }
                    }

                    override fun inquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = error("No per-id ownership queries")

                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = error("No read locks")
                }
            val query =
                QueryFinancialLineages(
                    app.transactor,
                    app.context.financialLedger,
                    instrumented,
                    readLineages = { transaction, documentIds ->
                        financialCalls++
                        transaction shouldBe ownershipTransaction
                        documentIds.toList() shouldBe ids
                        app.context.financialLedger.financialLineages(transaction, documentIds)
                    },
                )
            query(QueryFinancialLineages.Command(ids)).map { it.financial.latestVersion.document.id } shouldContainExactly ids
            ownershipCalls shouldBe 1
            financialCalls shouldBe 1
            sql.size shouldBe 1
            sql.single().contains("WHERE document_id IN") shouldBe true
            sql.single().contains("FOR UPDATE") shouldBe false
        }

        test("ownership failure prevents every runtime bulk read") {
            var called = false
            val query =
                QueryFinancialLineages(
                    app.transactor,
                    app.context.financialLedger,
                    owners,
                    readLineages = { _, _ ->
                        called = true
                        error("Unowned ids must never reach the ledger")
                    },
                )
            shouldThrowAny { query(QueryFinancialLineages.Command(listOf(documents().single(), UUID.randomUUID()))) }
                .shouldBeInstanceOf<CommerceFailure.NotFound>()
            called shouldBe false
        }

        test("current deposit reader holds its snapshot without locking while approval commits") {
            val id = documents().single()
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val pausing =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun inquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? {
                        val owner = owners.inquiryOf(transaction, documentId)
                        transaction.handle.connection.transactionIsolation shouldBe Connection.TRANSACTION_REPEATABLE_READ
                        paused.countDown()
                        check(resume.await(30, TimeUnit.SECONDS))
                        return owner
                    }

                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = error("Reads never lock")
                }
            val reader = CompletableFuture.supplyAsync { GetDepositRequirement(app.transactor, app.context.financialLedger, pausing)(id) }
            try {
                paused.await(30, TimeUnit.SECONDS) shouldBe true
                CompletableFuture
                    .supplyAsync {
                        SetDepositRequirement(app.transactor, app.context.financialLedger, owners, pricing)(command(id))
                    }.get(10, TimeUnit.SECONDS)
                    .depositRequirement!!
                    .requirement.revision shouldBe DepositRequirementRevision.INITIAL
                reader.isDone shouldBe false
            } finally {
                resume.countDown()
            }
            reader.get(10, TimeUnit.SECONDS).depositRequirement shouldBe null
            GetDepositRequirement(
                app.transactor,
                app.context.financialLedger,
                owners,
            )(id).depositRequirement!!.requirement.revision.number shouldBe
                1
        }

        test("Fiona locks its association before runtime NOWAIT conflicts and the complete transaction rolls back") {
            val id = documents().single()
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer =
                CompletableFuture.runAsync {
                    app.transactor.inTransaction { transaction ->
                        app.context.financialLedger.activateDepositRequirement(
                            transaction,
                            id,
                            Version.INITIAL,
                            DepositTerms.Fixed(money("25.00")),
                            null,
                        )
                        held.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                    }
                }
            var locked = false
            val observing =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? {
                        val owner = owners.lockInquiryOf(transaction, documentId)
                        // Another connection cannot acquire this row: Fiona's lock precedes the runtime call.
                        shouldThrowAny {
                            app.database.execute(
                                "SELECT document_id FROM fionas.inquiry_financial_documents WHERE document_id = '$id' FOR UPDATE NOWAIT",
                            )
                        }.sqlState() shouldBe
                            "55P03"
                        locked = true
                        transaction.handle
                            .createUpdate(
                                "UPDATE fionas.inquiries SET message = 'must roll back' WHERE id = :id",
                            ).bind("id", owner!!.value)
                            .execute()
                        return owner
                    }
                }
            val inquiry = app.transactor.inTransaction { owners.inquiryOf(it, id)!! }
            val original = app.database.strings("SELECT coalesce(message, '') FROM fionas.inquiries WHERE id = '${inquiry.value}'")
            try {
                held.await(30, TimeUnit.SECONDS) shouldBe true
                // Bounded completion while the runtime owner remains paused proves non-waiting behavior.
                CompletableFuture
                    .supplyAsync {
                        runCatching {
                            SetDepositRequirement(
                                app.transactor,
                                app.context.financialLedger,
                                observing,
                                pricing,
                            )(command(id))
                        }.exceptionOrNull()
                    }.get(5, TimeUnit.SECONDS)
                    .shouldBeInstanceOf<CommerceFailure.Conflict>()
                locked shouldBe true
                app.database.strings("SELECT coalesce(message, '') FROM fionas.inquiries WHERE id = '${inquiry.value}'") shouldBe original
                app.database
                    .strings(
                        "SELECT count(*) FROM commerce.deposit_requirement_revisions WHERE document_id = '$id'",
                    ).single() shouldBe
                    "0"
                CompletableFuture
                    .supplyAsync {
                        app.adminRequest(
                            Method.PUT,
                            "/financial-documents/$id/deposit-requirement",
                            """{"expectedDocumentVersion":1,"expectedRequirementRevision":null,
                            "terms":{"type":"FIXED","amount":"50.00","currency":"USD"}}""",
                        )
                    }.get(5, TimeUnit.SECONDS)
                    .status shouldBe Status.CONFLICT
            } finally {
                release.countDown()
            }
            writer.get(10, TimeUnit.SECONDS)
            val history = GetDepositRequirementHistory(app.transactor, app.context.financialLedger, owners)(id)
            history.size shouldBe 1
            history
                .single()
                .requirement.revision.number shouldBe 1
            // Retraction has the same lock ordering, without an expected document version.
            locked = false
            WithdrawDepositRequirement(
                app.transactor,
                app.context.financialLedger,
                observing,
            )(WithdrawDepositRequirement.Command(id, DepositRequirementRevision.INITIAL)).requirement.revision.number shouldBe
                2
            locked shouldBe true
        }
    })
