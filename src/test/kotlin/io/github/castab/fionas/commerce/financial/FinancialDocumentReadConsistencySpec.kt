package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.http.FinancialDocumentResponse
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
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
import java.util.concurrent.TimeUnit

/**
 * Each reader pauses after its first database query has established a REPEATABLE READ snapshot.
 * A writer commits while the reader transaction remains open; the reader finishes with its old
 * snapshot, and a fresh reader sees the new state. The writer's progress also proves that query
 * paths do not lock the lineage association row.
 */
class FinancialDocumentReadConsistencySpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0
        val associations = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()

        fun newEstimate(): Pair<InquiryId, UUID> {
            val inquiryId = InquiryId(UUID.fromString(application.createInquiry()))
            val response = application.adminPost("/inquiries/${inquiryId.value}/estimates", pricingBody(revision))
            response.status shouldBe Status.CREATED
            val documentId = UUID.fromString(CommerceJson.asA(response.bodyString(), FinancialDocumentResponse.serializer()).id)
            return inquiryId to documentId
        }

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
            application.adminCookie
        }
        afterSpec { application.close() }

        test("a current-document read keeps pre-payment settlement while a payment commits") {
            val (_, id) = newEstimate()
            application.adminPost("/financial-documents/$id/quote", """{"expectedVersion":1}""").status shouldBe Status.OK

            val pause = Pause()
            // The document and pricing have been read; reconciliation has not.
            val pausing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): FionasPricingInputs? = pause.after(sources.find(transaction, snapshot))
                }
            val nonLockingAssociations =
                object : InquiryFinancialDocumentRepository by associations {
                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = error("A current-document read must not lock a financial lineage")
                }
            val read = GetFinancialDocument(application.transactor, application.context.financialLedger, nonLockingAssociations, pausing)
            val reader = CompletableFuture.supplyAsync { read(id) }
            try {
                pause.paused.await(30, TimeUnit.SECONDS) shouldBe true
                val writer: CompletableFuture<Response> =
                    CompletableFuture.supplyAsync {
                        application.adminPost(
                            "/financial-documents/$id/payments",
                            """{"documentVersion":2,"amount":"300.00","method":"CASH"}""",
                        )
                    }
                writer.get(30, TimeUnit.SECONDS).status shouldBe Status.CREATED
                reader.isDone shouldBe false
            } finally {
                pause.resume.countDown()
            }

            val view = reader.get(30, TimeUnit.SECONDS)
            view.latest.document.version shouldBe Version.of(2)
            view.reconciliation.documentReference shouldBe view.latest.document.reference
            view.reconciliation.balance.amount
                .compareTo(view.latest.document.total.amount) shouldBe 0

            val after = GetFinancialDocument(application.transactor, application.context.financialLedger, associations, sources)(id)
            after.latest.document.version shouldBe Version.of(2)
            after.reconciliation.documentReference shouldBe after.latest.document.reference
            after.reconciliation.balance.amount
                .compareTo(after.latest.document.total.amount - "300.00".toBigDecimal()) shouldBe 0
        }

        test("a history read keeps pre-quote versions and pricing while a quote commits") {
            val (_, id) = newEstimate()
            val pause = Pause()
            // Pricing sources establish the snapshot before the ledger history query.
            val pausing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun findAll(
                        transaction: Transaction,
                        documentId: UUID,
                    ): Map<Version, FionasPricingInputs> = pause.after(sources.findAll(transaction, documentId))
                }
            val nonLockingAssociations =
                object : InquiryFinancialDocumentRepository by associations {
                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = error("A history read must not lock a financial lineage")
                }
            val read =
                GetFinancialDocumentHistory(application.transactor, application.context.financialLedger, nonLockingAssociations, pausing)
            val reader = CompletableFuture.supplyAsync { read(id) }
            try {
                pause.paused.await(30, TimeUnit.SECONDS) shouldBe true
                val writer =
                    CompletableFuture.supplyAsync {
                        application.adminPost("/financial-documents/$id/quote", """{"expectedVersion":1}""")
                    }
                writer.get(30, TimeUnit.SECONDS).status shouldBe Status.OK
                reader.isDone shouldBe false
            } finally {
                pause.resume.countDown()
            }

            reader.get(30, TimeUnit.SECONDS).versions.map { it.document.version } shouldContainExactly listOf(Version.INITIAL)
            val after = GetFinancialDocumentHistory(application.transactor, application.context.financialLedger, associations, sources)(id)
            after.versions.map { it.document.version } shouldContainExactly listOf(Version.INITIAL, Version.of(2))
            after.versions
                .map { it.pricing }
                .distinct()
                .size shouldBe 1
        }

        test("an inquiry list keeps pre-quote documents while a quote commits") {
            val (inquiryId, id) = newEstimate()
            val pause = Pause()
            // The inquiry and its lineage ids have been read; each document is read afterwards.
            val pausing =
                object : InquiryFinancialDocumentRepository by associations {
                    override fun documentsOf(
                        transaction: Transaction,
                        inquiryId: InquiryId,
                    ): List<UUID> = pause.after(associations.documentsOf(transaction, inquiryId))

                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = error("An inquiry list must not lock a financial lineage")
                }
            val read =
                ListInquiryFinancialDocuments(
                    application.transactor,
                    JdbiInquiryRepository(),
                    application.context.financialLedger,
                    pausing,
                    sources,
                )
            val reader = CompletableFuture.supplyAsync { read(inquiryId) }
            try {
                pause.paused.await(30, TimeUnit.SECONDS) shouldBe true
                val writer =
                    CompletableFuture.supplyAsync {
                        application.adminPost("/financial-documents/$id/quote", """{"expectedVersion":1}""")
                    }
                writer.get(30, TimeUnit.SECONDS).status shouldBe Status.OK
                reader.isDone shouldBe false
            } finally {
                pause.resume.countDown()
            }

            val before = reader.get(30, TimeUnit.SECONDS)
            before.map { it.latest.document.version } shouldContainExactly listOf(Version.INITIAL)
            before.single().reconciliation.documentReference shouldBe
                before
                    .single()
                    .latest.document.reference

            val after =
                ListInquiryFinancialDocuments(
                    application.transactor,
                    JdbiInquiryRepository(),
                    application.context.financialLedger,
                    associations,
                    sources,
                )(inquiryId)
            after.map { it.latest.document.version } shouldContainExactly listOf(Version.of(2))
            after.single().reconciliation.documentReference shouldBe
                after
                    .single()
                    .latest.document.reference
        }

        test("reads change no database rows") {
            val (inquiryId, id) = newEstimate()

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
            ListInquiryFinancialDocuments(
                application.transactor,
                JdbiInquiryRepository(),
                application.context.financialLedger,
                associations,
                sources,
            )(inquiryId).size shouldBe 1
            state() shouldBe before
        }
    })
