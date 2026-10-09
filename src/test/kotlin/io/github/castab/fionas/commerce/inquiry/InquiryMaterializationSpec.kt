package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.FirstSnapshotStage
import io.github.castab.fionas.commerce.financial.GetFinancialDocument
import io.github.castab.fionas.commerce.financial.GetFinancialDocumentHistory
import io.github.castab.fionas.commerce.financial.InquiryDocumentAssociation
import io.github.castab.fionas.commerce.financial.InquiryDocumentPurpose
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryProposalRepository
import io.github.castab.fionas.commerce.financial.LineAuthorship
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.adminId
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.github.castab.fionas.commerce.testing.issueProposal
import io.github.castab.fionas.commerce.testing.requestedService
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

/**
 * Atomic materialization of the public pricing authority's lines as an inquiry's canonical
 * Estimate v1: exact lines and order, authored by the service, intent only on the inquiry, all or
 * nothing across the runtime and Fiona schemas, and financial evolution that never needs a catalog.
 */
class InquiryMaterializationSpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()
        val associations = JdbiInquiryFinancialDocumentRepository()
        val authorship = JdbiFinancialDocumentAuthorshipRepository()

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun create(
            documentId: UUID = UUID.randomUUID(),
            ownerRepository: InquiryFinancialDocumentRepository = associations,
            lineIds: () -> UUID = UUID::randomUUID,
        ) = application.createInquiryOperation(
            materialize =
                MaterializeInquiryFinancialDocument(
                    application.context.financialLedger,
                    ownerRepository,
                    authorship,
                    { documentId },
                    lineIds,
                ),
        )

        val tables =
            listOf(
                "fionas.customers",
                "fionas.inquiry_submissions",
                "fionas.inquiries",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
                "fionas.financial_document_authorship",
            )

        fun counts() = tables.associateWith(application.database::count)

        test("the authority's exact lines become Estimate v1 in order, authored by the service, with intent only on the inquiry") {
            val id = UUID.randomUUID()
            val generated = mutableListOf<UUID>()
            val before = counts()
            val command = application.inquiryCommand(email = "materialized-${UUID.randomUUID()}@example.com")

            val inquiry = create(id) { UUID.randomUUID().also(generated::add) }(command)

            val restored = application.context.financialLedger.latest(id)
            (restored is FinancialDocument.Estimate) shouldBe true
            restored.version shouldBe Version.INITIAL
            restored.previousVersion.shouldBeNull()
            restored.lineItems shouldBe command.lines.zip(generated) { line, lineId -> line.withId(lineId) }
            restored.total.amount.compareTo(BigDecimal("681.25")) shouldBe 0
            restored.currency shouldBe Currency.getInstance("USD")
            application.transactor.inTransaction { transaction ->
                inquiries.findById(transaction, inquiry.id) shouldBe inquiry
                customers.findById(transaction, inquiry.customerId).shouldNotBeNull().name shouldBe CustomerName("Jane Doe")
                inquiries.findRequested(transaction, inquiry.id)?.requestedService shouldBe requestedService()
                associations.initialEstimateOf(transaction, inquiry.id) shouldBe id
                associations.documentsOf(transaction, inquiry.id) shouldBe listOf(id)
                authorship.findAll(transaction, id) shouldBe mapOf(Version.INITIAL to LineAuthorship(application.web.id, STORED_INSTANT))
            }
            val after = counts()
            tables.forEach { after.getValue(it) shouldBe before.getValue(it) + 1 }
            application.database.strings("SELECT purpose FROM fionas.inquiry_financial_documents WHERE document_id = '$id'") shouldBe
                listOf("INITIAL_ESTIMATE")
        }

        test("returning customers are reused unchanged while each inquiry gets its own initial estimate") {
            val email = "returning-${UUID.randomUUID()}@example.com"
            val first = create()(application.inquiryCommand(email = email))
            val before = application.database.count("fionas.customers")
            val second = create()(application.inquiryCommand(email = email.uppercase(), name = "Different name"))
            second.customerId shouldBe first.customerId
            application.database.count("fionas.customers") shouldBe before
            application.transactor.inTransaction { transaction ->
                customers.findById(transaction, first.customerId).shouldNotBeNull().name shouldBe CustomerName("Jane Doe")
                (associations.initialEstimateOf(transaction, first.id) != associations.initialEstimateOf(transaction, second.id)) shouldBe
                    true
            }
        }

        test("failure inside ledger snapshot persistence rolls back the entire submission across both schemas") {
            val id = UUID.randomUUID()
            val before = counts()
            // A test-only database failure after the ledger inserted the snapshot row with its lines.
            application.database.execute(
                """
                CREATE FUNCTION fionas.reject_materialized_snapshot() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN
                    IF NEW.document_id = '$id'::uuid THEN
                        RAISE EXCEPTION 'injected ledger snapshot failure';
                    END IF;
                    RETURN NEW;
                END
                ${'$'}${'$'};
                CREATE TRIGGER reject_materialized_snapshot AFTER INSERT ON commerce.financial_document_snapshots
                    FOR EACH ROW EXECUTE FUNCTION fionas.reject_materialized_snapshot();
                """.trimIndent(),
            )
            try {
                shouldThrowAny { create(id)(application.inquiryCommand()) }.message shouldContain "injected ledger snapshot failure"
                counts() shouldBe before
            } finally {
                application.database.execute(
                    "DROP TRIGGER reject_materialized_snapshot ON commerce.financial_document_snapshots; " +
                        "DROP FUNCTION fionas.reject_materialized_snapshot();",
                )
            }
        }

        listOf(false, true).forEach { afterAssociation ->
            test(
                "failure ${if (afterAssociation) "after the final association write" else "during association persistence"} rolls back all submission rows",
            ) {
                val id = UUID.randomUUID()
                val before = counts()
                val failing =
                    object : InquiryFinancialDocumentRepository by associations {
                        override fun associate(
                            transaction: Transaction,
                            association: InquiryDocumentAssociation,
                        ) {
                            application.context.financialLedger
                                .latest(transaction, id)
                                .version shouldBe Version.INITIAL
                            inquiries.findRequested(transaction, association.inquiryId).shouldNotBeNull()
                            if (afterAssociation) {
                                associations.associate(transaction, association)
                                associations.initialEstimateOf(transaction, association.inquiryId) shouldBe id
                                // An external client still sees only the pre-submission state.
                                counts() shouldBe before
                            }
                            error("injected late association failure")
                        }
                    }
                shouldThrow<IllegalStateException> { create(id, failing)(application.inquiryCommand()) }
                counts() shouldBe before
            }
        }

        test("the database permits related lineages but rejects a second canonical initial estimate") {
            val firstId = UUID.randomUUID()
            val inquiry = create(firstId)(application.inquiryCommand())
            val relatedId = UUID.randomUUID()
            val lines = listOf(CHURROS.priced())
            application.transactor.inTransaction { transaction ->
                MaterializeInquiryFinancialDocument(application.context.financialLedger, associations, authorship, { relatedId })
                    .create(transaction, inquiry.id, FirstSnapshotStage.ESTIMATE, lines, application.adminId, STORED_INSTANT)
            }
            val before = counts()
            val secondId = UUID.randomUUID()
            shouldThrow<CommerceFailure.Conflict> {
                application.transactor.inTransaction { transaction ->
                    MaterializeInquiryFinancialDocument(application.context.financialLedger, associations, authorship, { secondId })
                        .create(
                            transaction,
                            inquiry.id,
                            FirstSnapshotStage.ESTIMATE,
                            lines,
                            application.web.id,
                            STORED_INSTANT,
                            InquiryDocumentPurpose.INITIAL_ESTIMATE,
                        )
                }
            }
            counts() shouldBe before
            application.transactor.inTransaction { transaction ->
                associations.initialEstimateOf(transaction, inquiry.id) shouldBe firstId
                associations.documentsOf(transaction, inquiry.id).toSet() shouldBe setOf(firstId, relatedId)
            }
            shouldThrow<CommerceFailure.NotFound> { application.context.financialLedger.latest(secondId) }
        }

        test("bespoke lines and signed credits are recorded as committed; evolution, quote, payment and refund need no catalog") {
            val id = UUID.randomUUID()
            val inquiry = create(id)(application.inquiryCommand(lines = listOf(CHURROS, COURTESY_DISCOUNT)))
            val initial = application.context.financialLedger.latest(id)
            initial.total.amount.compareTo(BigDecimal("400.00")) shouldBe 0
            // No catalog exists anywhere: the runtime has no catalog tables, and Fiona reads none.
            application.database
                .strings("SELECT table_name FROM information_schema.tables WHERE table_name LIKE '%offering%'")
                .shouldBe(emptyList())
            val read = GetFinancialDocument(application.transactor, application.context.financialLedger, associations, authorship)(id)
            read.latest.document.lineItems shouldBe initial.lineItems
            read.latest.authorship?.author shouldBe application.web.id
            val custom =
                LineItem(
                    id = UUID.randomUUID(),
                    description = "Custom staff adjustment",
                    quantity = null,
                    price = Money(BigDecimal("12.00"), initial.currency),
                    taxAmount = Money.zero(initial.currency),
                )
            val changed =
                application.transactor.inTransaction { transaction ->
                    application.context.financialLedger.changeOrder(
                        transaction,
                        id,
                        ChangeOrder(listOf(ChangeOrder.Change.AddLineItem(custom))),
                        Version.INITIAL,
                    )
                }
            changed.lineItems shouldBe initial.lineItems + custom
            val issued = application.issueProposal(inquiry.id, version = 2)
            val quote = issued.financial
            quote.latest.document.lineItems shouldBe changed.lineItems
            val payment =
                RecordDocumentPayment(
                    application.transactor,
                    application.context.financialLedger,
                    associations,
                    authorship,
                    testClock,
                    JdbiInquiryProposalRepository(),
                )(RecordDocumentPayment.Command(id, Version.of(3), BigDecimal("50.00"), PaymentMethod.CARD, null, null, issued.proposal.id))
            payment.document.latest.document.version shouldBe Version.of(4)
            payment.document.latest.document.lineItems shouldBe changed.lineItems
            val refund =
                RecordRefund(application.transactor, application.context.financialLedger, testClock)(
                    RecordRefund.Command(
                        payment.payment.id,
                        BigDecimal("10.00"),
                        initial.currency,
                        PaymentMethod.CARD,
                        null,
                        null,
                        listOf(RecordRefund.AllocationCommand(payment.allocation.id, BigDecimal("10.00"))),
                    ),
                )
            refund.reconciliation.netReceived.amount
                .compareTo(BigDecimal("40.00")) shouldBe 0
            application.context.financialLedger
                .reconcileLatest(id)
                .netApplied.amount
                .compareTo(BigDecimal("40.00")) shouldBe 0
            GetFinancialDocumentHistory(application.transactor, application.context.financialLedger, associations, authorship)(id)
                .versions
                .let {
                    it.map { snapshot -> snapshot.document.version.number } shouldBe listOf(1, 2, 3, 4)
                    it.first().document.lineItems shouldBe initial.lineItems
                    // v2 was appended through the raw ledger (no Fiona author); the Quote and Invoice carry v2's absence forward.
                    it.map { snapshot -> snapshot.authorship?.author } shouldBe listOf(application.web.id, null, null, null)
                }
            application.transactor.inTransaction {
                authorship.find(it, FinancialDocumentReference(id, Version.INITIAL)).shouldNotBeNull()
            }
        }

        test("acceptance lines from the test authority price exactly as the old acceptance estimate did") {
            acceptanceLines()
                .map { it.priced().total.amount }
                .reduce(BigDecimal::add)
                .compareTo(BigDecimal("681.25")) shouldBe 0
        }
    })
