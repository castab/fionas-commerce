package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.http.FinancialDocumentResponse
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.pricingBody
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.http4k.core.Response
import org.http4k.core.Status
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Multi-query financial reads under PostgreSQL READ COMMITTED: each statement may see a newer
 * commit, so a read that assembled a snapshot, its pricing source, and its settlement (or a
 * history and its pricing sources) without the lineage lock could mix two lineage states.
 *
 * Each case pauses a reader between its reads, inside its transaction, and starts a writer
 * on the same lineage. The writer must wait on the association row the reader holds, and the
 * reader must return one coherent state. A reader without the lock would let the writer
 * commit during the pause, and these cases would fail.
 */
class FinancialDocumentReadConsistencySpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0
        val associations = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()

        fun newEstimate(): UUID {
            val response = application.adminPost("/inquiries/${application.createInquiry()}/estimates", pricingBody(revision))
            response.status shouldBe Status.CREATED
            return UUID.fromString(CommerceJson.asA(response.bodyString(), FinancialDocumentResponse.serializer()).id)
        }

        /** Sessions of this spec's database waiting for a lock, seen from outside the runtime. */
        fun waitingOnLocks() =
            application.database
                .strings("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'")
                .single()
                .toInt()

        /** Waits until [writer] either finished or is waiting on a lock. */
        fun awaitBlockedOrDone(writer: Future<*>) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!writer.isDone && waitingOnLocks() == 0) {
                check(System.nanoTime() < deadline) { "The writer neither finished nor waited on a lock" }
                Thread.sleep(20)
            }
        }

        /** Pauses the reader after one of its reads until [resume] opens, having opened [paused]. */
        class Pause {
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)

            fun <T> after(read: T): T {
                paused.countDown()
                check(resume.await(30, TimeUnit.SECONDS)) { "The test never resumed the reader" }
                return read
            }
        }

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
            // Log in before any concurrent request needs the session.
            application.adminCookie
        }
        afterSpec { application.close() }

        test("a current-document read cannot return one version with the settlement of another") {
            val id = newEstimate()
            application.adminPost("/financial-documents/$id/quote", """{"expectedVersion":1}""").status shouldBe Status.OK
            // A deposit, so the settlement differs between the quote and its repriced successor.
            application
                .adminPost("/financial-documents/$id/payments", """{"documentVersion":2,"amount":"300.00","method":"CASH"}""")
                .status shouldBe Status.CREATED

            val pause = Pause()
            // Paused between the snapshot read and the reconciliation.
            val pausing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): FionasPricingInputs? = pause.after(sources.find(transaction, snapshot))
                }
            val read = GetFinancialDocument(application.transactor, application.context.financialLedger, associations, pausing)

            val reader = CompletableFuture.supplyAsync { read(id) }
            pause.paused.await(30, TimeUnit.SECONDS) shouldBe true
            val writer: CompletableFuture<Response> =
                CompletableFuture.supplyAsync {
                    val repriced = pricingBody(revision, guests = 100, expectedVersion = 2)
                    application.adminPost("/financial-documents/$id/change-orders", repriced)
                }
            awaitBlockedOrDone(writer)

            // The change order waits on the lineage lock the reader holds.
            writer.isDone shouldBe false
            pause.resume.countDown()
            val view = reader.get(30, TimeUnit.SECONDS)
            view.latest.document.version shouldBe Version.of(2)
            view.reconciliation.documentReference shouldBe view.latest.document.reference
            view.reconciliation.documentTotal shouldBe view.latest.document.total
            view.reconciliation.balance.amount
                .compareTo(view.latest.document.total.amount - "300.00".toBigDecimal()) shouldBe 0

            // The writer proceeds once the reader's transaction ends, and later reads are coherent too.
            writer.get(30, TimeUnit.SECONDS).status shouldBe Status.OK
            val after = GetFinancialDocument(application.transactor, application.context.financialLedger, associations, sources)(id)
            after.latest.document.version shouldBe Version.of(3)
            after.reconciliation.documentReference shouldBe after.latest.document.reference
        }

        test("a history read sees every version with its pricing source, never a new version without one") {
            val id = newEstimate()

            val pause = Pause()
            // Paused between the pricing-source read and the ledger history read.
            val pausing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun findAll(
                        transaction: Transaction,
                        documentId: UUID,
                    ): Map<Version, FionasPricingInputs> = pause.after(sources.findAll(transaction, documentId))
                }
            val read = GetFinancialDocumentHistory(application.transactor, application.context.financialLedger, associations, pausing)

            val reader = CompletableFuture.supplyAsync { read(id) }
            pause.paused.await(30, TimeUnit.SECONDS) shouldBe true
            val writer: CompletableFuture<Response> =
                CompletableFuture.supplyAsync { application.adminPost("/financial-documents/$id/quote", """{"expectedVersion":1}""") }
            awaitBlockedOrDone(writer)

            // The quote waits on the lineage lock the reader holds.
            writer.isDone shouldBe false
            pause.resume.countDown()
            // The complete pre-write history, without failing on a version it has no source for.
            reader.get(30, TimeUnit.SECONDS).versions.map { it.document.version } shouldContainExactly listOf(Version.INITIAL)

            writer.get(30, TimeUnit.SECONDS).status shouldBe Status.OK
            val after = GetFinancialDocumentHistory(application.transactor, application.context.financialLedger, associations, sources)(id)
            after.versions.map { it.document.version } shouldContainExactly listOf(Version.INITIAL, Version.of(2))
            after.versions
                .map { it.pricing }
                .distinct()
                .size shouldBe 1
        }

        test("reads take the lock but change nothing") {
            val id = newEstimate()

            fun state() =
                listOf(
                    "fionas.inquiry_financial_documents",
                    "fionas.financial_document_pricing",
                    "commerce.financial_document_snapshots",
                    "commerce.payment_records",
                ).map { application.database.count(it) } +
                    application.database.strings("SELECT xmin::text FROM fionas.inquiry_financial_documents WHERE document_id = '$id'")

            val before = state()
            listOf("/financial-documents/$id", "/financial-documents/$id/history").forEach {
                application.adminGet(it).status shouldBe Status.OK
            }
            state() shouldBe before
        }
    })
