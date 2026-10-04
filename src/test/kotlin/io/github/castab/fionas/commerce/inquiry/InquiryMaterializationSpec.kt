package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.payment.PaymentMethod
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.CreateInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.GetFinancialDocument
import io.github.castab.fionas.commerce.financial.GetFinancialDocumentHistory
import io.github.castab.fionas.commerce.financial.InquiryDocumentAssociation
import io.github.castab.fionas.commerce.financial.InquiryDocumentPurpose
import io.github.castab.fionas.commerce.financial.InquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.IssueQuote
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentPricingRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.financial.RecordDocumentPayment
import io.github.castab.fionas.commerce.financial.RecordRefund
import io.github.castab.fionas.commerce.financial.SetDepositRequirement
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.perGuest
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Status
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

class InquiryMaterializationSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()
        val associations = JdbiInquiryFinancialDocumentRepository()
        val sources = JdbiFinancialDocumentPricingRepository()

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        fun inputs() =
            FionasPricingInputs(
                OfferingsRevision.of(revision),
                OfferingSelections(
                    listOf(
                        OfferingCategorySelection(
                            OfferingCategoryKey("soft-serve-flavor"),
                            listOf("vanilla", "horchata").map(::OfferingKey),
                        ),
                        OfferingCategorySelection(OfferingCategoryKey("topping"), TOPPINGS.map(::OfferingKey)),
                        OfferingCategorySelection(OfferingCategoryKey("cone-option"), listOf(OfferingKey("waffle-cone"))),
                    ),
                ),
                FionasOfferingsContext(75, true, Duration.ofMinutes(120)),
            )

        fun pricing(
            latest: (Transaction, OfferingsCatalogId) -> OfferingsSnapshot? =
                application.context.offeringsSnapshotRepository::retrieveLatestVersion,
        ) = FionasPricing(FionasOfferingsEngine(FIONAS_PRICING_POLICY), latest)

        fun command(
            input: FionasPricingInputs = inputs(),
            email: String = "materialized-${UUID.randomUUID()}@example.com",
        ) = CreateInquiry.Command(
            CustomerName("Jane Doe"),
            Email.of(email),
            InquiryMessage("Birthday"),
            input,
            ZipCode("01234"),
            EventDate.of("2026-12-05"),
            EventType.BIRTHDAY,
            InquirySubmissionKey(UUID.randomUUID().toString()),
        )

        fun create(
            documentId: UUID = UUID.randomUUID(),
            ownerRepository: InquiryFinancialDocumentRepository = associations,
            latest: (Transaction, OfferingsCatalogId) -> OfferingsSnapshot? =
                application.context.offeringsSnapshotRepository::retrieveLatestVersion,
            price: FionasPricing = pricing(latest),
        ) = CreateInquiry(
            application.transactor,
            customers,
            inquiries,
            JdbiInquirySubmissionRepository(),
            PublicInquiryPricing(price),
            testClock,
            MaterializeInquiryFinancialDocument(application.context.financialLedger, ownerRepository, testClock) { documentId },
        )

        val tables =
            listOf(
                "fionas.customers",
                "fionas.inquiry_submissions",
                "fionas.inquiries",
                "commerce.financial_document_snapshots",
                "fionas.inquiry_financial_documents",
                "fionas.financial_document_pricing",
            )

        fun counts() = tables.associateWith(application.database::count)

        test("a priced inquiry persists exactly the single pricing result as Estimate v1, with intent only on the inquiry") {
            val submitted = inputs()
            val id = UUID.randomUUID()
            val before = counts()
            val generatedIds = mutableListOf<UUID>()
            var catalogReads = 0
            val singlePricing =
                FionasPricing(
                    FionasOfferingsEngine(FIONAS_PRICING_POLICY) { UUID.randomUUID().also(generatedIds::add) },
                ) { transaction, catalogId ->
                    catalogReads++
                    application.context.offeringsSnapshotRepository.retrieveLatestVersion(transaction, catalogId)
                }
            val inquiry = create(id, price = singlePricing)(command(submitted))
            catalogReads shouldBe 1
            val restored = application.context.financialLedger.latest(id)
            (restored is FinancialDocument.Estimate) shouldBe true
            restored.version shouldBe Version.INITIAL
            restored.previousVersion.shouldBeNull()
            restored.lineItems.map { it.id } shouldBe generatedIds
            // Re-evaluate separately with the captured identities to compare every persisted line fact.
            val ids = generatedIds.iterator()
            val expected =
                application.transactor.inTransaction { transaction ->
                    FionasPricing(
                        FionasOfferingsEngine(FIONAS_PRICING_POLICY) { ids.next() },
                        application.context.offeringsSnapshotRepository::retrieveLatestVersion,
                    ).price(transaction, submitted).lineItems
                }
            restored.lineItems shouldBe expected
            restored.total.amount.compareTo(BigDecimal("681.25")) shouldBe 0
            restored.currency shouldBe Currency.getInstance("USD")
            application.transactor.inTransaction { transaction ->
                inquiries.findById(transaction, inquiry.id) shouldBe inquiry
                customers.findById(transaction, inquiry.customerId).shouldNotBeNull().name shouldBe CustomerName("Jane Doe")
                inquiries.findRequested(transaction, inquiry.id)?.pricingInputs shouldBe submitted
                associations.initialEstimateOf(transaction, inquiry.id) shouldBe id
                associations.documentsOf(transaction, inquiry.id) shouldBe listOf(id)
                sources.findAll(transaction, id) shouldBe emptyMap()
            }
            val after = counts()
            val additions =
                mapOf(
                    "fionas.customers" to 1,
                    "fionas.inquiry_submissions" to 1,
                    "fionas.inquiries" to 1,
                    "commerce.financial_document_snapshots" to 1,
                    "fionas.inquiry_financial_documents" to 1,
                )
            tables.forEach { after.getValue(it) shouldBe before.getValue(it) + additions.getOrDefault(it, 0) }
            application.database.strings("SELECT purpose FROM fionas.inquiry_financial_documents WHERE document_id = '$id'") shouldBe
                listOf("INITIAL_ESTIMATE")
        }

        test("priced returning customers are reused unchanged while each inquiry gets its own initial estimate") {
            val email = "returning-${UUID.randomUUID()}@example.com"
            val first = create()(command(email = email))
            val before = application.database.count("fionas.customers")
            val second = create()(command(email = email.uppercase()).copy(name = CustomerName("Different name")))
            second.customerId shouldBe first.customerId
            application.database.count("fionas.customers") shouldBe before
            application.transactor.inTransaction { transaction ->
                customers.findById(transaction, first.customerId).shouldNotBeNull().name shouldBe CustomerName("Jane Doe")
                (associations.initialEstimateOf(transaction, first.id) != associations.initialEstimateOf(transaction, second.id)) shouldBe
                    true
            }
        }

        test("publication after observing current does not invalidate the accepted immutable snapshot") {
            val accepted = revision
            val id = UUID.randomUUID()
            val submitted = inputs()
            val inquiry =
                create(id, latest = { transaction, catalogId ->
                    val snapshot = application.context.offeringsSnapshotRepository.retrieveLatestVersion(transaction, catalogId)
                    revision = application.addOffering(accepted, "mint", "soft-serve-flavor", "Mint")
                    snapshot
                })(command(submitted))
            revision shouldBe accepted + 1
            application.transactor.inTransaction { inquiries.findRequested(it, inquiry.id) }?.pricingInputs shouldBe submitted
            application.context.financialLedger
                .latest(id)
                .total.amount
                .compareTo(BigDecimal("681.25")) shouldBe 0
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
                shouldThrowAny { create(id)(command()) }.message shouldContain "injected ledger snapshot failure"
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
                shouldThrow<IllegalStateException> { create(id, failing)(command()) }
                counts() shouldBe before
            }
        }

        test("the database permits related lineages but rejects a second canonical initial estimate") {
            val firstId = UUID.randomUUID()
            val inquiry = create(firstId)(command())
            val relatedId = UUID.randomUUID()
            val lines =
                application.context.financialLedger
                    .latest(firstId)
                    .lineItems
            val materialize =
                MaterializeInquiryFinancialDocument(application.context.financialLedger, associations, testClock) { relatedId }
            application.transactor.inTransaction { transaction ->
                materialize.create(transaction, inquiry.id, CreateInquiryFinancialDocument.Stage.ESTIMATE, lines)
            }
            val before = counts()
            val secondId = UUID.randomUUID()
            shouldThrow<CommerceFailure.Conflict> {
                application.transactor.inTransaction { transaction ->
                    MaterializeInquiryFinancialDocument(application.context.financialLedger, associations, testClock) { secondId }.create(
                        transaction,
                        inquiry.id,
                        CreateInquiryFinancialDocument.Stage.ESTIMATE,
                        lines,
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

        test("catalog changes cannot reinterpret the estimate; reads, custom evolution, quote and invoice need no pricing source") {
            val id = UUID.randomUUID()
            create(id)(command())
            val initial = application.context.financialLedger.latest(id)
            val updated =
                application.adminRequest(
                    Method.PUT,
                    "/offering-catalog/offerings",
                    """{"expectedRevision":$revision,"offerings":[{"key":"horchata",""" +
                        """"selectionState":"ENABLED","availability":"AVAILABLE",""" +
                        """"category":"soft-serve-flavor","displayName":"Renamed premium flavor",""" +
                        """"price":${perGuest("9.00")}}]}""",
                )
            updated.status shouldBe Status.OK
            // Test-only destruction of catalog data proves the financial lineage has no read or FK
            // dependency on it. Production current catalogs are runtime-owned; captured snapshots are immutable.
            application.database.execute("DELETE FROM commerce.offerings_catalogs")
            // No pricing or catalog collaborator participates in these financial reads or transitions.
            application.context.financialLedger
                .latest(id)
                .lineItems shouldBe initial.lineItems
            val read = GetFinancialDocument(application.transactor, application.context.financialLedger, associations, sources)(id)
            read.latest.pricing.shouldBeNull()
            read.latest.document.lineItems shouldBe initial.lineItems
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
                    )
                }
            changed.lineItems shouldBe initial.lineItems + custom
            val quote = IssueQuote(application.transactor, application.context.financialLedger, associations, sources)(id, Version.of(2))
            quote.latest.document.lineItems shouldBe changed.lineItems
            quote.latest.pricing.shouldBeNull()
            SetDepositRequirement(application.transactor, application.context.financialLedger, associations, sources)(
                SetDepositRequirement.Command(id, Version.of(3), null, DepositTerms.Fixed(Money(BigDecimal("50.00"), initial.currency))),
            )
            val payment =
                RecordDocumentPayment(
                    application.transactor,
                    application.context.financialLedger,
                    associations,
                    sources,
                    testClock,
                )(RecordDocumentPayment.Command(id, Version.of(3), BigDecimal("50.00"), PaymentMethod.CARD, null, null))
            payment.document.latest.document.version shouldBe Version.of(4)
            payment.document.latest.document.lineItems shouldBe changed.lineItems
            payment.document.latest.pricing
                .shouldBeNull()
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
            GetFinancialDocumentHistory(
                application.transactor,
                application.context.financialLedger,
                associations,
                sources,
            )(id).versions.let {
                it.map { snapshot -> snapshot.document.version.number } shouldBe listOf(1, 2, 3, 4)
                it.map { snapshot -> snapshot.pricing } shouldBe listOf(null, null, null, null)
                it.first().document.lineItems shouldBe initial.lineItems
            }
        }
    })
