package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.http.StaffDashboardResponse
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.communicationHistory
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.SqlStatements
import org.jdbi.v3.core.statement.StatementContext
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InquiryCommunicationRepositorySpec :
    FunSpec({
        lateinit var app: TestApplication
        val repository = JdbiInquiryCommunicationRepository()
        beforeTest {
            app = TestApplication.create()
            app.createAcceptanceCatalog()
        }
        afterTest { app.close() }

        fun fact(
            id: InquiryId,
            kind: InquiryCommunicationKind,
            seconds: Long = 0,
        ) = InquiryCommunication(
            InquiryCommunicationId(UUID.randomUUID()),
            id,
            kind,
            STORED_INSTANT.plusSeconds(seconds),
            if (kind == InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED) null else app.authorization.findUserByUsername("admin")!!.id,
        )

        test("communication append uses only the authoritative locking lookup and insert; missing inquiry stops at the lookup") {
            val statements = mutableListOf<String>()
            val observed =
                object : InquiryCommunicationRepository by repository {
                    override fun append(
                        transaction: Transaction,
                        activity: InquiryCommunication,
                    ): RecordedInquiryCommunication {
                        transaction.handle.getConfig(SqlStatements::class.java).setSqlLogger(
                            object : SqlLogger {
                                override fun logBeforeExecution(context: StatementContext) {
                                    statements.add(context.rawSql)
                                }
                            },
                        )
                        return repository.append(transaction, activity)
                    }
                }
            val recorder = RecordInquiryCommunication(app.transactor, observed, testClock)
            val id = InquiryId(UUID.fromString(app.createInquiry()))
            recorder.customerEmailReceived(id, STORED_INSTANT)
            statements.size shouldBe 2
            statements[0] shouldBe "SELECT id FROM fionas.inquiries WHERE id = :inquiry FOR UPDATE"
            statements[1].startsWith("INSERT INTO fionas.inquiry_communications ") shouldBe true
            statements[1].endsWith("RETURNING recorded_order") shouldBe true
            statements.clear()
            shouldThrow<CommerceFailure.NotFound> {
                recorder.customerEmailReceived(InquiryId(UUID.randomUUID()), STORED_INSTANT)
            }
            statements shouldBe listOf("SELECT id FROM fionas.inquiries WHERE id = :inquiry FOR UPDATE")
            app.database.count("fionas.inquiry_communications") shouldBe 1
        }

        test("the application operation rolls back an append when its repository fails after insertion") {
            val id = InquiryId(UUID.fromString(app.createInquiry()))
            val failing =
                object : InquiryCommunicationRepository by repository {
                    override fun append(
                        transaction: Transaction,
                        activity: InquiryCommunication,
                    ): RecordedInquiryCommunication {
                        repository.append(transaction, activity)
                        error("Failure after append")
                    }
                }
            shouldThrow<IllegalStateException> {
                RecordInquiryCommunication(app.transactor, failing, testClock).customerEmailReceived(id, STORED_INSTANT)
            }
            app.database.count("fionas.inquiry_communications") shouldBe 0
            app.transactor.inTransaction { repository.attentionFor(it, listOf(id)) } shouldBe emptyMap()
        }

        test("SQL aggregate agrees with pure durable-order semantics for backdated, equal-time and shuffled histories") {
            val histories =
                listOf(
                    emptyList(),
                    listOf(InquiryCommunicationKind.STAFF_ACKNOWLEDGED to 0L),
                    listOf(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED to 100L, InquiryCommunicationKind.STAFF_ACKNOWLEDGED to 0L),
                    listOf(InquiryCommunicationKind.STAFF_ACKNOWLEDGED to 100L, InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED to 0L),
                    listOf(InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED to 0L, InquiryCommunicationKind.STAFF_EMAIL_SENT to 0L),
                    listOf(InquiryCommunicationKind.STAFF_EMAIL_SENT to 100L, InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED to 0L),
                    listOf(
                        InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED to 100L,
                        InquiryCommunicationKind.STAFF_EMAIL_SENT to 200L,
                        InquiryCommunicationKind.STAFF_ACKNOWLEDGED to 300L,
                        InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED to 50L,
                        InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED to 25L,
                    ),
                )
            val expected =
                histories.associate { history ->
                    val id = InquiryId(UUID.fromString(app.createInquiry()))
                    val recorded =
                        app.transactor.inTransaction { tx ->
                            history.map { (kind, seconds) -> repository.append(tx, fact(id, kind, seconds)) }
                        }
                    id to InquiryCommunicationAttention.project(recorded.reversed())
                }
            val actual = app.transactor.inTransaction { repository.attentionFor(it, expected.keys) }
            actual.keys.size shouldBe histories.size - 1
            expected.forEach { (id, attention) -> (actual[id] ?: InquiryCommunicationAttention(null, null)) shouldBe attention }
            app.transactor.inTransaction { repository.attentionFor(it, emptySet()) } shouldBe emptyMap()
        }

        listOf(true, false).forEach { inboundFirst ->
            test("same-inquiry appends wait for commit: inbound first = $inboundFirst; unrelated inquiry remains independent") {
                val id = InquiryId(UUID.fromString(app.createInquiry()))
                val unrelated = InquiryId(UUID.fromString(app.createInquiry()))
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val firstKind =
                    if (inboundFirst) InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED else InquiryCommunicationKind.STAFF_ACKNOWLEDGED
                val secondKind =
                    if (inboundFirst) InquiryCommunicationKind.STAFF_ACKNOWLEDGED else InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED
                val first =
                    CompletableFuture.supplyAsync {
                        app.transactor.inTransaction { tx ->
                            repository.append(tx, fact(id, firstKind, 100)).also {
                                entered.countDown()
                                check(release.await(30, TimeUnit.SECONDS))
                            }
                        }
                    }
                val pid = CompletableFuture<Int>()
                var second: CompletableFuture<RecordedInquiryCommunication>? = null
                try {
                    entered.await(20, TimeUnit.SECONDS) shouldBe true
                    second =
                        CompletableFuture.supplyAsync {
                            app.transactor.inTransaction { tx ->
                                pid.complete(
                                    tx.handle
                                        .createQuery("SELECT pg_backend_pid()")
                                        .mapTo(Int::class.javaObjectType)
                                        .one(),
                                )
                                repository.append(tx, fact(id, secondKind, 0))
                            }
                        }
                    val waiter = pid.get(10, TimeUnit.SECONDS)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    var blocked = false
                    while (System.nanoTime() < deadline && !blocked) {
                        blocked =
                            app.database
                                .strings(
                                    "SELECT pid FROM pg_stat_activity WHERE pid = $waiter AND wait_event_type = 'Lock' " +
                                        "AND cardinality(pg_blocking_pids(pid)) > 0",
                                ).isNotEmpty()
                        if (!blocked) Thread.sleep(10)
                    }
                    blocked shouldBe true
                    second.isDone shouldBe false
                    CompletableFuture
                        .runAsync {
                            app.transactor.inTransaction {
                                repository.append(
                                    it,
                                    fact(unrelated, InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED),
                                )
                            }
                        }.get(10, TimeUnit.SECONDS)
                    // Unlocked dashboard reads finish while the writer is held, and see no uncommitted same-inquiry facts.
                    app.transactor.inTransaction { repository.attentionFor(it, listOf(id)) } shouldBe emptyMap()
                } finally {
                    release.countDown()
                    first.get(10, TimeUnit.SECONDS)
                    second?.get(10, TimeUnit.SECONDS)
                }
                val rows = app.transactor.inTransaction { communicationHistory(it, id) }
                rows.size shouldBe 2
                (rows[0].recordedOrder < rows[1].recordedOrder) shouldBe true
                val attention = app.transactor.inTransaction { repository.attentionFor(it, listOf(id)).getValue(id) }
                attention shouldBe InquiryCommunicationAttention.project(rows)
                attention.unacknowledgedSince shouldBe if (inboundFirst) null else STORED_INSTANT
                attention.latestEmailAt shouldBe if (inboundFirst) STORED_INSTANT.plusSeconds(100) else STORED_INSTANT
            }
        }

        test("hundreds of facts per inquiry return one aggregate each through one SQL statement and at most one reply card each") {
            val ids = (1..6).map { InquiryId(UUID.fromString(app.createInquiry())) }
            val expected =
                app.transactor.inTransaction { tx ->
                    ids.associateWith { id ->
                        val rows =
                            (1..300).map { n ->
                                val kind =
                                    when (n % 3) {
                                        0 -> InquiryCommunicationKind.CUSTOMER_EMAIL_RECEIVED
                                        1 -> InquiryCommunicationKind.STAFF_ACKNOWLEDGED
                                        else -> InquiryCommunicationKind.STAFF_EMAIL_SENT
                                    }
                                repository.append(tx, fact(id, kind, -n.toLong()))
                            }
                        InquiryCommunicationAttention.project(rows)
                    }
                }
            listOf(ids.take(1), ids).forEach { population ->
                var statements = 0
                val result =
                    app.transactor.inTransaction { tx ->
                        tx.handle.getConfig(SqlStatements::class.java).setSqlLogger(
                            object : SqlLogger {
                                override fun logBeforeExecution(context: StatementContext) {
                                    statements++
                                }
                            },
                        )
                        repository.attentionFor(tx, population)
                    }
                statements shouldBe 1
                result.size shouldBe population.size
                result shouldBe expected.filterKeys { it in population }
            }
            app.database.count("fionas.inquiry_communications") shouldBe 1800
            val dashboard = CommerceJson.asA(app.adminGet("/staff/dashboard").bodyString(), StaffDashboardResponse.serializer())
            dashboard.workQueue.needsReply.items.size shouldBe ids.size
            dashboard.workQueue.needsReply.items
                .map { it.inquiryId }
                .toSet()
                .size shouldBe ids.size
        }
    })
