package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

/**
 * The first Fiona operations that write Fiona-owned rows and commerce-runtime ledger facts
 * together: each passes its one runtime `Transaction` to the ledger's transaction-aware
 * overloads, so a failure after the runtime's write rolls the runtime's write back too.
 *
 * Each case fails a Fiona step that runs after the ledger has written. An operation that let
 * the ledger open its own transaction (a convenience overload) would leave the runtime's facts
 * committed, and these cases would fail.
 */
class FinancialDocumentAtomicitySpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0
        val inquiries = JdbiInquiryRepository()
        val associations = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()

        fun pricing() =
            FionasPricing(FionasOfferingsEngine(FIONAS_PRICING_POLICY), application.context.offeringsSnapshotRepository::retrieveVersion)

        fun inputs(guests: Int = 75) =
            FionasPricingInputs(
                OfferingsRevision.of(revision),
                OfferingSelections(
                    listOf(
                        OfferingCategorySelection(OfferingCategoryKey("soft-serve-flavor"), listOf(OfferingKey("vanilla"))),
                        OfferingCategorySelection(OfferingCategoryKey("topping"), TOPPINGS.take(4).map(::OfferingKey)),
                        OfferingCategorySelection(OfferingCategoryKey("cone-option"), listOf(OfferingKey("cup"))),
                    ),
                ),
                FionasOfferingsContext(guests, false, Duration.ofMinutes(120)),
            )

        fun createEstimate(
            pricingSources: FinancialDocumentPricingRepository = sources,
            documentId: UUID = UUID.randomUUID(),
        ) = CreateInquiryEstimate(
            application.transactor,
            inquiries,
            application.context.financialLedger,
            associations,
            pricingSources,
            pricing(),
            testClock,
            newDocumentId = { documentId },
        )(InquiryId(UUID.fromString(application.createInquiry())), inputs())

        fun issueQuote(pricingSources: FinancialDocumentPricingRepository = sources) =
            IssueQuote(application.transactor, application.context.financialLedger, associations, pricingSources)

        fun rows(
            table: String,
            column: String,
            id: UUID,
        ) = application.database
            .strings("SELECT count(*) FROM $table WHERE $column = '$id'")
            .single()
            .toInt()

        /** The snapshots, their lines, Fiona associations, and Fiona pricing sources stored for [id]. */
        fun stored(id: UUID) =
            listOf(
                rows("commerce.financial_document_snapshots", "document_id", id),
                rows("commerce.financial_document_lines", "document_id", id),
                rows("fionas.inquiry_financial_documents", "document_id", id),
                rows("fionas.financial_document_pricing", "document_id", id),
            )

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        test("a persisted estimate that fails after the ledger stored it leaves no snapshot, association, or pricing source") {
            val id = UUID.randomUUID()
            val failing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun insert(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                        inputs: FionasPricingInputs,
                    ) {
                        // The runtime's snapshot and Fiona's association are visible in this transaction...
                        application.context.financialLedger
                            .latest(transaction, snapshot.id)
                            .version shouldBe Version.INITIAL
                        associations.inquiryOf(transaction, snapshot.id).shouldNotBeNull()
                        sources.insert(transaction, snapshot, inputs)
                        error("pricing source failure after the ledger wrote")
                    }
                }

            val failure = shouldThrow<IllegalStateException> { createEstimate(failing, id) }
            failure.message shouldBe "pricing source failure after the ledger wrote"

            // ...and none of it survives the rollback.
            stored(id) shouldBe listOf(0, 0, 0, 0)
            createEstimate(documentId = id).latest.document.id shouldBe id
            stored(id) shouldBe listOf(1, 2, 1, 1)
        }

        test("direct Quote and Invoice creation roll back the first snapshot with Fiona's context") {
            listOf(CreateInquiryFinancialDocument.Stage.QUOTE, CreateInquiryFinancialDocument.Stage.INVOICE).forEach { stage ->
                val id = UUID.randomUUID()
                val failing =
                    object : FinancialDocumentPricingRepository by sources {
                        override fun insert(
                            transaction: Transaction,
                            snapshot: FinancialDocumentReference,
                            inputs: FionasPricingInputs,
                        ) {
                            application.context.financialLedger
                                .latest(transaction, snapshot.id)
                                .version shouldBe Version.INITIAL
                            associations.inquiryOf(transaction, snapshot.id).shouldNotBeNull()
                            error("pricing source failure after direct $stage creation")
                        }
                    }
                val create =
                    CreateInquiryFinancialDocument(
                        application.transactor,
                        inquiries,
                        application.context.financialLedger,
                        associations,
                        failing,
                        pricing(),
                        testClock,
                        newDocumentId = { id },
                    )
                shouldThrow<IllegalStateException> {
                    create(
                        CreateInquiryFinancialDocument.Command(
                            InquiryId(UUID.fromString(application.createInquiry())),
                            stage,
                            inputs(),
                        ),
                    )
                }
                stored(id) shouldBe listOf(0, 0, 0, 0)
            }
        }

        test("a quote that fails after the ledger appended it is rolled back with its pricing source") {
            val estimate = createEstimate().latest.document
            val failing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun copy(
                        transaction: Transaction,
                        from: FinancialDocumentReference,
                        to: FinancialDocumentReference,
                    ) {
                        application.context.financialLedger
                            .latest(transaction, from.id)
                            .version shouldBe Version.of(2)
                        error("pricing source copy failure after the quote was appended")
                    }
                }

            shouldThrow<IllegalStateException> { issueQuote(failing)(estimate.id, Version.INITIAL) }

            application.context.financialLedger
                .latest(estimate.id)
                .version shouldBe Version.INITIAL
            stored(estimate.id) shouldBe listOf(1, 2, 1, 1)
            issueQuote()(estimate.id, Version.INITIAL).latest.document.version shouldBe Version.of(2)
        }

        test("a change order that fails after the ledger appended it leaves the lineage as it was") {
            val estimate = createEstimate().latest.document
            val failing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun insert(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                        inputs: FionasPricingInputs,
                    ) {
                        snapshot.version shouldBe Version.of(2)
                        error("pricing source failure after the change order was appended")
                    }
                }

            shouldThrow<IllegalStateException> {
                CreateChangeOrder(application.transactor, application.context.financialLedger, associations, failing, pricing())(
                    estimate.id,
                    Version.INITIAL,
                    inputs(guests = 100),
                )
            }

            application.context.financialLedger
                .history(estimate.id)
                .map { it.version } shouldBe listOf(Version.INITIAL)
            stored(estimate.id) shouldBe listOf(1, 2, 1, 1)
        }

        test("a payment that fails after the ledger recorded it and its allocation leaves neither") {
            val estimate = createEstimate().latest.document
            issueQuote()(estimate.id, Version.INITIAL)
            val paymentId = UUID.randomUUID()
            val allocationId = UUID.randomUUID()
            val failing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): FionasPricingInputs? {
                        // The payment and its allocation are recorded in this transaction...
                        rowsIn(transaction, "commerce.payment_records", "payment_id", paymentId) shouldBe 1
                        rowsIn(transaction, "commerce.payment_allocations", "allocation_id", allocationId) shouldBe 1
                        error("failure after the payment was recorded")
                    }
                }
            val record =
                RecordDocumentPayment(
                    application.transactor,
                    application.context.financialLedger,
                    associations,
                    failing,
                    testClock,
                    newPaymentId = { paymentId },
                    newAllocationId = { allocationId },
                )

            shouldThrow<IllegalStateException> {
                record(RecordDocumentPayment.Command(estimate.id, Version.of(2), BigDecimal("300.00"), PaymentMethod.CARD, null, null))
            }

            // ...and neither survives the rollback.
            rows("commerce.payment_records", "payment_id", paymentId) shouldBe 0
            rows("commerce.payment_allocations", "allocation_id", allocationId) shouldBe 0
            application.context.financialLedger
                .reconcileLatest(estimate.id)
                .netApplied.amount
                .signum() shouldBe 0
        }

        test("a failed standalone allocation rolls back its ledger fact while the received payment remains") {
            val estimate = createEstimate().latest.document
            issueQuote()(estimate.id, Version.INITIAL)
            val paymentId = UUID.randomUUID()
            val allocationId = UUID.randomUUID()
            RecordPayment(application.transactor, application.context.financialLedger, testClock, newPaymentId = { paymentId })(
                RecordPayment.Command(BigDecimal("300.00"), Currency.getInstance("USD"), PaymentMethod.CARD, null, null),
            )
            val failing =
                object : FinancialDocumentPricingRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): FionasPricingInputs? {
                        rowsIn(transaction, "commerce.payment_allocations", "allocation_id", allocationId) shouldBe 1
                        error("failure after standalone allocation")
                    }
                }
            val allocate =
                AllocatePayment(
                    application.transactor,
                    application.context.financialLedger,
                    associations,
                    failing,
                    testClock,
                    newAllocationId = { allocationId },
                )
            shouldThrow<IllegalStateException> {
                allocate(AllocatePayment.Command(paymentId, estimate.id, Version.of(2), BigDecimal("150.00")))
            }
            rows("commerce.payment_records", "payment_id", paymentId) shouldBe 1
            rows("commerce.payment_allocations", "allocation_id", allocationId) shouldBe 0
            application.context.financialLedger
                .reconcileLatest(estimate.id)
                .netApplied.amount
                .signum() shouldBe 0
        }
    })

/** The rows of [table] whose [column] is [id], as [transaction] sees them. */
private fun rowsIn(
    transaction: Transaction,
    table: String,
    column: String,
    id: UUID,
): Int =
    transaction.handle
        .createQuery("SELECT count(*) FROM $table WHERE $column = :id")
        .bind("id", id)
        .mapTo(Long::class.javaObjectType)
        .one()
        .toInt()
