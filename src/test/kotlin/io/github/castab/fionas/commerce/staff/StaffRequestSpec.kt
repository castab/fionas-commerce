package io.github.castab.fionas.commerce.staff

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerRepository
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.FinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.GetFinancialDocument
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.IssueQuote
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.inquiry.GetInquiry
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.InquiryStage
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.inquiry.ReadInquiryLifecycle
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.initialEstimateOf
import io.github.castab.fionas.commerce.testing.pricingBody
import io.github.castab.fionas.commerce.testing.requestedPricing
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.http4k.core.Status
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StaffRequestSpec :
    FunSpec({
        lateinit var app: TestApplication
        var revision = 0
        val customers = JdbiCustomerRepository()
        val owners = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()

        fun reader(
            associations: InquiryFinancialDocumentRepository = owners,
            people: CustomerRepository = customers,
            pricing: FinancialDocumentPricingRepository = sources,
        ) = ReadStaffRequest(
            app.transactor,
            GetInquiry(
                app.transactor,
                people,
                JdbiInquiryRepository(),
                ReadInquiryLifecycle(app.context.financialLedger, associations, JdbiInquiryFulfillmentRepository()),
            ),
            GetFinancialDocument(app.transactor, app.context.financialLedger, associations, pricing),
        )

        fun submitted(): Pair<InquiryId, UUID> {
            val id =
                app.createInquiry("jane@example.com") {
                    pricingBody(
                        it,
                        softServe = listOf("vanilla"),
                        toppings = listOf("sprinkles", "oreos", "strawberries", "brownies"),
                        cones = listOf("cup"),
                    )
                }
            return InquiryId(UUID.fromString(id)) to UUID.fromString(app.initialEstimateOf(id))
        }

        beforeSpec {
            app = TestApplication.create()
            revision = app.createAcceptanceCatalog()
        }
        afterSpec { app.close() }

        test("new request composes customer event intent lifecycle and authoritative Estimate without writes") {
            val (id, document) = submitted()
            val expected = app.transactor.inTransaction { JdbiInquiryRepository().findRequested(it, id)!! }
            val counts = listOf("fionas.inquiries", "commerce.financial_document_snapshots", "commerce.payment_records")
            val before = counts.map(app.database::count)
            val request = reader()(id)
            request.inquiry.inquiry shouldBe expected.inquiry
            request.inquiry.customer.id shouldBe expected.inquiry.customerId
            request.inquiry.customer.name.value shouldBe "Jane Doe"
            request.inquiry.customer.email.value shouldBe "jane@example.com"
            request.inquiry.inquiry.message
                ?.value shouldBe "Ice cream for a birthday."
            request.inquiry.inquiry.zipCode.value shouldBe "92626"
            request.inquiry.inquiry.eventDate.value
                .toString() shouldBe "2026-12-05"
            request.inquiry.inquiry.eventType.name shouldBe "BIRTHDAY"
            request.inquiry.pricingInputs shouldBe requestedPricing(revision)
            request.inquiry.lifecycle.stage shouldBe InquiryStage.REQUESTED
            request.inquiry.lifecycle.documentId shouldBe document
            request.financial.inquiryId shouldBe id
            request.financial.latest.document.id shouldBe document
            val estimate =
                request.financial.latest.document
                    .shouldBeInstanceOf<FinancialDocument.Estimate>()
            estimate.version shouldBe Version.INITIAL
            estimate.lineItems.map { it.description } shouldBe listOf("Base service", "Ice cream service")
            estimate.total.amount.compareTo(BigDecimal("550.00")) shouldBe 0
            request.financial.latest.pricing shouldBe null
            request.financial.latest.createdAt shouldBe
                app.context.financialLedger
                    .latestVersion(document)
                    .createdAt
            request.financial.reconciliation.documentReference shouldBe estimate.reference
            request.financial.reconciliation.grossAllocated.amount
                .signum() shouldBe 0
            request.financial.reconciliation.netApplied.amount
                .signum() shouldBe 0
            request.financial.reconciliation.balance shouldBe estimate.total
            counts.map(app.database::count) shouldBe before
        }

        test("Quote issuance moves the same canonical lineage and excludes newer RELATED financial documents") {
            val (id, document) = submitted()
            app.adminPost("/inquiries/${id.value}/estimates", pricingBody(revision)).status shouldBe Status.CREATED
            val initial = reader()(id)
            initial.inquiry.lifecycle.stage shouldBe InquiryStage.REQUESTED
            initial.financial.latest.document.id shouldBe document
            val related = app.transactor.inTransaction { owners.documentsOf(it, id).single { lineage -> lineage != document } }
            val alternate = GetFinancialDocument(app.transactor, app.context.financialLedger, owners, sources)(related)
            shouldThrow<IllegalStateException> { StaffRequest(initial.inquiry, alternate) }
            val quote = IssueQuote(app.transactor, app.context.financialLedger, owners, sources)(document, Version.INITIAL)
            val request = reader()(id)
            request.inquiry.lifecycle.stage shouldBe InquiryStage.QUOTED
            request.inquiry.lifecycle.documentId shouldBe document
            request.financial.latest.document.reference shouldBe quote.latest.document.reference
            request.financial.latest.document.total shouldBe quote.latest.document.total
            request.financial.latest.document
                .shouldBeInstanceOf<FinancialDocument.Quote>()
                .version shouldBe Version.of(2)
            app.transactor.inTransaction { owners.documentsOf(it, id).size } shouldBe 2
        }

        test("unknown inquiry is NotFound while missing canonical relationship is an internal invariant failure") {
            shouldThrow<CommerceFailure.NotFound> { reader()(InquiryId(UUID.randomUUID())) }
            val (id, _) = submitted()
            app.database.execute("UPDATE fionas.inquiry_financial_documents SET purpose = 'RELATED' WHERE inquiry_id = '${id.value}'")
            shouldThrow<IllegalStateException> { reader()(id) }
        }

        test("missing customer and canonical ownership disagreement fail internally") {
            val (id, _) = submitted()
            val missing =
                object : CustomerRepository by customers {
                    override fun findById(
                        transaction: Transaction,
                        id: CustomerId,
                    ): Customer? = null
                }
            shouldThrow<IllegalStateException> { reader(people = missing)(id) }
            val wrongOwner =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun inquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId = InquiryId(UUID.randomUUID())
                }
            shouldThrow<IllegalStateException> { reader(associations = wrongOwner)(id) }
        }

        test("missing runtime lineage and missing ownership after inquiry enrichment are internal failures") {
            val (id, _) = submitted()
            val missingLineage =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun initialEstimateOf(
                        transaction: Transaction,
                        inquiryId: InquiryId,
                    ): UUID = UUID.randomUUID()
                }
            shouldThrow<IllegalStateException> { reader(associations = missingLineage)(id) }
            val missingOwner =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun inquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = null
                }
            shouldThrow<IllegalStateException> { reader(associations = missingOwner)(id) }
        }

        test("one repeatable snapshot retains lifecycle version and reconciliation while Quote and payment commit") {
            val (id, document) = submitted()
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val seen = mutableSetOf<Transaction>()
            val observing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): FionasPricingInputs? {
                        seen += transaction
                        return sources.find(transaction, snapshot)
                    }
                }
            val pausing =
                object : CustomerRepository by customers {
                    override fun findById(
                        transaction: Transaction,
                        id: CustomerId,
                    ): Customer? {
                        seen += transaction
                        transaction.handle
                            .createQuery("SHOW transaction_isolation")
                            .mapTo(String::class.java)
                            .one() shouldBe "repeatable read"
                        val result = customers.findById(transaction, id)
                        paused.countDown()
                        check(resume.await(30, TimeUnit.SECONDS)) { "Reader was not resumed" }
                        return result
                    }
                }
            val unlocked =
                object : InquiryFinancialDocumentRepository by owners {
                    override fun lockInquiryOf(
                        transaction: Transaction,
                        documentId: UUID,
                    ): InquiryId? = error("Read must not lock ownership")
                }
            val read = CompletableFuture.supplyAsync { reader(associations = unlocked, people = pausing, pricing = observing)(id) }
            try {
                paused.await(30, TimeUnit.SECONDS) shouldBe true
                val writer =
                    CompletableFuture.supplyAsync {
                        app.adminPost("/financial-documents/$document/quote", """{"expectedVersion":1}""").status shouldBe Status.OK
                        app
                            .adminPost(
                                "/financial-documents/$document/payments",
                                """{"documentVersion":2,"amount":"50.00","method":"CASH"}""",
                            ).status
                    }
                writer.get(30, TimeUnit.SECONDS) shouldBe Status.CREATED
                read.isDone shouldBe false
            } finally {
                resume.countDown()
            }
            val before = read.get(30, TimeUnit.SECONDS)
            seen.size shouldBe 1
            before.inquiry.lifecycle.stage shouldBe InquiryStage.REQUESTED
            before.financial.latest.document.version shouldBe Version.INITIAL
            before.financial.reconciliation.netApplied.amount
                .signum() shouldBe 0
            val after = reader()(id)
            after.inquiry.lifecycle.stage shouldBe InquiryStage.QUOTED
            after.financial.latest.document.version shouldBe Version.of(2)
            after.financial.reconciliation.netApplied.amount
                .compareTo(BigDecimal("50")) shouldBe 0
        }
    })
