package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryFulfillmentRepository
import io.github.castab.fionas.commerce.inquiry.JdbiInquiryRepository
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiry
import io.github.castab.fionas.commerce.testing.newLinesProposal
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
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
        val inquiries = JdbiInquiryRepository()
        val associations = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentAuthorshipRepository()

        fun lines(guests: Int = 75) = acceptanceLines(guests = guests, horchata = false, toppings = 4, waffleCones = false)

        fun create(
            stage: FirstSnapshotStage,
            authorship: FinancialDocumentAuthorshipRepository = sources,
            documentId: UUID = UUID.randomUUID(),
        ) = CreateInquiryFinancialDocument(
            application.transactor,
            inquiries,
            application.context.financialLedger,
            associations,
            authorship,
            MaterializeInquiryFinancialDocument(application.context.financialLedger, associations, authorship, { documentId }),
            testClock,
        )(
            CreateInquiryFinancialDocument.Command(
                InquiryId(UUID.fromString(application.createInquiry())),
                stage,
                lines().map { it.priced() },
                application.adminId,
            ),
        )

        fun createEstimate(
            authorship: FinancialDocumentAuthorshipRepository = sources,
            documentId: UUID = UUID.randomUUID(),
        ) = create(FirstSnapshotStage.ESTIMATE, authorship, documentId)

        fun issueQuote(pricingSources: FinancialDocumentAuthorshipRepository = sources) =
            IssueQuote(application.transactor, application.context.financialLedger, associations, pricingSources)

        fun rows(
            table: String,
            column: String,
            id: UUID,
        ) = application.database
            .strings("SELECT count(*) FROM $table WHERE $column = '$id'")
            .single()
            .toInt()

        /** The snapshots (each holding its lines), Fiona associations, and Fiona line authorship stored for [id]. */
        fun stored(id: UUID) =
            listOf(
                rows("commerce.financial_document_snapshots", "document_id", id),
                rows("fionas.inquiry_financial_documents", "document_id", id),
                rows("fionas.financial_document_authorship", "document_id", id),
            )

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        test("a persisted estimate that fails after the ledger stored it leaves no snapshot, association, or authorship") {
            val id = UUID.randomUUID()
            val failing =
                object : FinancialDocumentAuthorshipRepository by sources {
                    override fun insert(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                        authorship: LineAuthorship,
                    ) {
                        // The runtime's snapshot and Fiona's association are visible in this transaction...
                        application.context.financialLedger
                            .latest(transaction, snapshot.id)
                            .version shouldBe Version.INITIAL
                        associations.inquiryOf(transaction, snapshot.id).shouldNotBeNull()
                        sources.insert(transaction, snapshot, authorship)
                        error("authorship failure after the ledger wrote")
                    }
                }

            val failure = shouldThrow<IllegalStateException> { createEstimate(failing, id) }
            failure.message shouldBe "authorship failure after the ledger wrote"

            // ...and none of it survives the rollback.
            stored(id) shouldBe listOf(0, 0, 0)
            createEstimate(documentId = id).latest.document.id shouldBe id
            stored(id) shouldBe listOf(1, 1, 1)
        }

        test("direct Quote and Invoice creation roll back the first snapshot with Fiona's context") {
            listOf(FirstSnapshotStage.QUOTE, FirstSnapshotStage.INVOICE).forEach { stage ->
                val id = UUID.randomUUID()
                val failing =
                    object : FinancialDocumentAuthorshipRepository by sources {
                        override fun insert(
                            transaction: Transaction,
                            snapshot: FinancialDocumentReference,
                            authorship: LineAuthorship,
                        ) {
                            application.context.financialLedger
                                .latest(transaction, snapshot.id)
                                .version shouldBe Version.INITIAL
                            associations.inquiryOf(transaction, snapshot.id).shouldNotBeNull()
                            error("authorship failure after direct $stage creation")
                        }
                    }
                shouldThrow<IllegalStateException> { create(stage, failing, id) }
                stored(id) shouldBe listOf(0, 0, 0)
            }
        }

        test("a quote that fails after the ledger appended it is rolled back with its authorship") {
            val estimate = createEstimate().latest.document
            val failing =
                object : FinancialDocumentAuthorshipRepository by sources {
                    override fun copy(
                        transaction: Transaction,
                        from: FinancialDocumentReference,
                        to: FinancialDocumentReference,
                    ) {
                        application.context.financialLedger
                            .latest(transaction, from.id)
                            .version shouldBe Version.of(2)
                        error("authorship copy failure after the quote was appended")
                    }
                }

            shouldThrow<IllegalStateException> { issueQuote(failing)(estimate.id, Version.INITIAL) }

            application.context.financialLedger
                .latest(estimate.id)
                .version shouldBe Version.INITIAL
            stored(estimate.id) shouldBe listOf(1, 1, 1)
            issueQuote()(estimate.id, Version.INITIAL).latest.document.version shouldBe Version.of(2)
        }

        test("a change order that fails after the ledger appended it leaves the lineage as it was") {
            val estimate = createEstimate().latest.document
            val failing =
                object : FinancialDocumentAuthorshipRepository by sources {
                    override fun insert(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                        authorship: LineAuthorship,
                    ) {
                        snapshot.version shouldBe Version.of(2)
                        error("authorship failure after the change order was appended")
                    }
                }

            shouldThrow<IllegalStateException> {
                CreateChangeOrder(
                    application.transactor,
                    application.context.financialLedger,
                    associations,
                    failing,
                    JdbiInquiryFulfillmentRepository(),
                    testClock,
                )(CreateChangeOrder.Command(estimate.id, Version.INITIAL, newLinesProposal(lines(guests = 100)), application.adminId))
            }

            application.context.financialLedger
                .history(estimate.id)
                .map { it.version } shouldBe listOf(Version.INITIAL)
            stored(estimate.id) shouldBe listOf(1, 1, 1)
        }

        test("a payment that fails after the ledger recorded it and its allocation leaves neither") {
            val estimate = createEstimate().latest.document
            issueQuote()(estimate.id, Version.INITIAL)
            val paymentId = UUID.randomUUID()
            val allocationId = UUID.randomUUID()
            val failing =
                object : FinancialDocumentAuthorshipRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): LineAuthorship? {
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
                    JdbiInquiryProposalRepository(),
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
                object : FinancialDocumentAuthorshipRepository by sources {
                    override fun find(
                        transaction: Transaction,
                        snapshot: FinancialDocumentReference,
                    ): LineAuthorship? {
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
