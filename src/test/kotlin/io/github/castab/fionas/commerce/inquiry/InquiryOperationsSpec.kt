package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.financial.MaterializeInquiryFinancialDocument
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TOPPINGS
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.addOffering
import io.github.castab.fionas.commerce.testing.createAcceptanceCatalog
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.util.UUID

class InquiryOperationsSpec :
    FunSpec({
        lateinit var application: TestApplication
        var revision = 0
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()

        beforeSpec {
            application = TestApplication.create()
            revision = application.createAcceptanceCatalog()
        }
        afterSpec { application.close() }

        fun pricing() =
            FionasPricing(FionasOfferingsEngine(FIONAS_PRICING_POLICY), application.context.offeringsSnapshotRepository::retrieveVersion)

        fun createInquiry(
            customerId: CustomerId = CustomerId(UUID.randomUUID()),
            inquiryId: InquiryId = InquiryId(UUID.randomUUID()),
            inquiryRepository: InquiryRepository = inquiries,
        ) = CreateInquiry(
            application.transactor,
            customers,
            inquiryRepository,
            JdbiInquirySubmissionRepository(),
            PublicInquiryPricing(pricing(), application.context.offeringsSnapshotRepository::retrieveLatestVersion),
            testClock,
            MaterializeInquiryFinancialDocument(application.context.financialLedger, JdbiInquiryFinancialDocumentRepository(), testClock),
            { customerId },
            { inquiryId },
        )

        fun getInquiry() = GetInquiry(application.transactor, customers, inquiries)

        fun inputs(
            catalogRevision: Int = revision,
            guests: Int = 75,
            minutes: Long = 120,
            toppings: List<String> = TOPPINGS,
        ) = FionasPricingInputs(
            catalogRevision = OfferingsRevision.of(catalogRevision),
            selections =
                OfferingSelections(
                    listOf(
                        OfferingCategorySelection(
                            OfferingCategoryKey("soft-serve-flavor"),
                            listOf("vanilla", "horchata").map(::OfferingKey),
                        ),
                        OfferingCategorySelection(OfferingCategoryKey("topping"), toppings.map(::OfferingKey)),
                        OfferingCategorySelection(OfferingCategoryKey("cone-option"), listOf(OfferingKey("waffle-cone"))),
                    ),
                ),
            context = FionasOfferingsContext(guestCount = guests, guestCountIsMinimum = true, duration = Duration.ofMinutes(minutes)),
        )

        fun command(
            email: String,
            name: String = "Jane Doe",
            message: String? = "Ice cream for a birthday party",
            pricingInputs: FionasPricingInputs = inputs(),
        ) = CreateInquiry.Command(
            CustomerName.of(name),
            Email.of(email),
            InquiryMessage.ofOptional(message),
            pricingInputs,
            ZipCode("92626"),
            EventDate.of("2026-12-05"),
            EventType.BIRTHDAY,
            InquirySubmissionKey(UUID.randomUUID().toString()),
        )

        fun rows() =
            listOf("fionas.customers", "fionas.inquiries")
                .map(application.database::count)

        test("a new email creates the customer and the inquiry together") {
            val customerId = CustomerId(UUID.randomUUID())
            val inquiryId = InquiryId(UUID.randomUUID())
            val email = "new-${UUID.randomUUID()}@example.com"

            val created = createInquiry(customerId, inquiryId)(command(email))

            created shouldBe
                Inquiry(
                    inquiryId,
                    customerId,
                    InquiryMessage("Ice cream for a birthday party"),
                    STORED_INSTANT,
                    ZipCode("92626"),
                    EventDate.of("2026-12-05"),
                    EventType.BIRTHDAY,
                )
            val read = getInquiry()(inquiryId)
            read.inquiry shouldBe created
            read.customer.id shouldBe customerId
            read.customer.name shouldBe CustomerName("Jane Doe")
            read.customer.email shouldBe Email(email)
            read.customer.createdAt shouldBe STORED_INSTANT
            read.pricingInputs shouldBe inputs()
            application.transactor.inTransaction { JdbiInquiryFinancialDocumentRepository().documentsOf(it, inquiryId) }.size shouldBe 1
        }

        test("an inquiry with a known email reuses that customer and does not overwrite its name") {
            val email = "returning-${UUID.randomUUID()}@example.com"
            val first = createInquiry()(command(email, name = "Jane Doe"))
            val customersAfterFirst = application.database.count("fionas.customers")

            val second = createInquiry()(command(email.uppercase(), name = "J. Doe", message = null))

            second.customerId shouldBe first.customerId
            second.message.shouldBeNull()
            getInquiry()(second.id).customer.name shouldBe CustomerName("Jane Doe")
            application.database.count("fionas.customers") shouldBe customersAfterFirst
        }

        test("the configured pricing inputs are recorded with the inquiry exactly as submitted") {
            val submitted = inputs(guests = 120, minutes = 180)

            val created = createInquiry()(command("configured-${UUID.randomUUID()}@example.com", pricingInputs = submitted))

            getInquiry()(created.id).pricingInputs shouldBe submitted
        }

        test("the requested catalog revision is pinned, even after the catalog moves on") {
            val pinned = inputs()
            val created = createInquiry()(command("pinned-${UUID.randomUUID()}@example.com", pricingInputs = pinned))

            val later = application.addOffering(revision, "mint", "soft-serve-flavor", "Mint")

            later shouldBe revision + 1
            getInquiry()(created.id).pricingInputs.catalogRevision shouldBe OfferingsRevision.of(revision)
            val before = rows()
            shouldThrow<CommerceFailure.Conflict> {
                createInquiry()(command("older-${UUID.randomUUID()}@example.com", pricingInputs = inputs(revision)))
            }.cause.let { (it as CatalogRevisionStale).currentRevision shouldBe OfferingsRevision.of(later) }
            rows() shouldBe before
            revision = later
        }

        test("a returning customer's new request records its own inputs and leaves earlier ones unchanged") {
            val email = "repeat-${UUID.randomUUID()}@example.com"
            val first = createInquiry()(command(email, pricingInputs = inputs(guests = 50)))
            val second = createInquiry()(command(email, name = "Someone Else", pricingInputs = inputs(guests = 200, minutes = 90)))

            second.customerId shouldBe first.customerId
            getInquiry()(first.id).pricingInputs shouldBe inputs(guests = 50)
            getInquiry()(second.id).pricingInputs shouldBe inputs(guests = 200, minutes = 90)
        }

        test("inputs the pricing rejects are a validation failure, and nothing is recorded") {
            val before = rows()

            shouldThrow<CommerceFailure.ValidationFailed> {
                createInquiry()(command("rejected-${UUID.randomUUID()}@example.com", pricingInputs = inputs(guests = 0)))
            }.message shouldContain "INVALID_GUEST_COUNT"
            shouldThrow<CommerceFailure.ValidationFailed> {
                createInquiry()(command("rejected-${UUID.randomUUID()}@example.com", pricingInputs = inputs(toppings = TOPPINGS.take(2))))
            }.message shouldContain "TOO_FEW_SELECTIONS"
            shouldThrow<CommerceFailure.NotFound> {
                createInquiry()(command("rejected-${UUID.randomUUID()}@example.com", pricingInputs = inputs(catalogRevision = 999)))
            }.message shouldBe "Offerings catalog revision r999 was not found"

            rows() shouldBe before
        }

        test("when the inquiry cannot be recorded, the new customer is not recorded either") {
            val customerId = CustomerId(UUID.randomUUID())
            val failingInquiries =
                object : InquiryRepository by inquiries {
                    override fun insert(
                        transaction: Transaction,
                        inquiry: Inquiry,
                        pricingInputs: FionasPricingInputs,
                    ): Unit = error("inquiry insert failed")
                }

            shouldThrow<IllegalStateException> {
                createInquiry(customerId, inquiryRepository = failingInquiries)(command("atomic-${UUID.randomUUID()}@example.com"))
            }

            application.transactor.inTransaction { customers.findById(it, customerId) }.shouldBeNull()
        }

        test("reading a missing inquiry is a not-found failure") {
            val missing = InquiryId(UUID.randomUUID())

            val failure = shouldThrow<CommerceFailure.NotFound> { getInquiry()(missing) }

            failure.message shouldBe "Inquiry ${missing.value} was not found"
        }
    })
