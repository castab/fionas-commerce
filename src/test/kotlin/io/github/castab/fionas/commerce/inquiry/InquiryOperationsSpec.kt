package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocumentReference
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.financial.JdbiFinancialDocumentAuthorshipRepository
import io.github.castab.fionas.commerce.financial.JdbiInquiryFinancialDocumentRepository
import io.github.castab.fionas.commerce.testing.CHURROS
import io.github.castab.fionas.commerce.testing.COURTESY_DISCOUNT
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.TestLine
import io.github.castab.fionas.commerce.testing.acceptanceLines
import io.github.castab.fionas.commerce.testing.createInquiryOperation
import io.github.castab.fionas.commerce.testing.inquiryCommand
import io.github.castab.fionas.commerce.testing.requestedService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.util.UUID

class InquiryOperationsSpec :
    FunSpec({
        lateinit var application: TestApplication
        val customers = JdbiCustomerRepository()
        val inquiries = JdbiInquiryRepository()

        beforeSpec { application = TestApplication.create() }
        afterSpec { application.close() }

        fun createInquiry(
            customerId: CustomerId = CustomerId(UUID.randomUUID()),
            inquiryId: InquiryId = InquiryId(UUID.randomUUID()),
            inquiryRepository: InquiryRepository = inquiries,
        ) = application.createInquiryOperation(
            customers = customers,
            inquiries = inquiryRepository,
            newCustomerId = { customerId },
            newInquiryId = { inquiryId },
        )

        fun getInquiry() =
            GetInquiry(
                application.transactor,
                customers,
                inquiries,
                ReadInquiryLifecycle(
                    application.context.financialLedger,
                    JdbiInquiryFinancialDocumentRepository(),
                    JdbiInquiryFulfillmentRepository(),
                ),
            )

        fun command(
            email: String,
            name: String = "Jane Doe",
            message: String? = "Ice cream for a birthday party",
            requested: RequestedService = requestedService(guestCountIsMinimum = true),
            lines: List<TestLine> = acceptanceLines(),
        ) = application.inquiryCommand(email, name, message, lines, requested)

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
            read.requestedService shouldBe requestedService(guestCountIsMinimum = true)
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

        test("the requested service is recorded with the inquiry exactly as submitted") {
            val submitted = requestedService(guests = 120, minutes = 180)

            val created = createInquiry()(command("configured-${UUID.randomUUID()}@example.com", requested = submitted))

            getInquiry()(created.id).requestedService shouldBe submitted
        }

        test("the authority's lines become Estimate v1 exactly, in order, with the submitting service as their author") {
            val created =
                createInquiry()(command("bespoke-${UUID.randomUUID()}@example.com", lines = listOf(CHURROS, COURTESY_DISCOUNT)))

            val estimate =
                application.transactor.inTransaction {
                    val id = checkNotNull(JdbiInquiryFinancialDocumentRepository().initialEstimateOf(it, created.id))
                    application.context.financialLedger.latest(it, id) to
                        JdbiFinancialDocumentAuthorshipRepository().find(it, FinancialDocumentReference(id, Version.INITIAL))
                }
            estimate.first.lineItems.map { it.description to it.price.amount } shouldBe
                listOf("Churro catering service" to BigDecimal("450.00"), "Courtesy discount" to BigDecimal("-50.00"))
            estimate.first.total.amount shouldBe BigDecimal("400.00")
            estimate.second?.author shouldBe application.web.id
        }

        test("a returning customer's new request records its own requested service and leaves earlier ones unchanged") {
            val email = "repeat-${UUID.randomUUID()}@example.com"
            val first = createInquiry()(command(email, requested = requestedService(guests = 50)))
            val second = createInquiry()(command(email, name = "Someone Else", requested = requestedService(guests = 200, minutes = 90)))

            second.customerId shouldBe first.customerId
            getInquiry()(first.id).requestedService shouldBe requestedService(guests = 50)
            getInquiry()(second.id).requestedService shouldBe requestedService(guests = 200, minutes = 90)
        }

        test("lines that do not form a valid nonnegative document are rejected before anything is recorded") {
            val before = rows()

            shouldThrow<IllegalArgumentException> { command("empty@example.com", lines = emptyList()) }
            shouldThrow<IllegalArgumentException> { command("negative@example.com", lines = listOf(COURTESY_DISCOUNT)) }
            shouldThrow<IllegalArgumentException> {
                command("mixed@example.com", lines = listOf(CHURROS, CHURROS.copy(currency = "EUR")))
            }

            rows() shouldBe before
        }

        test("when the inquiry cannot be recorded, the new customer is not recorded either") {
            val customerId = CustomerId(UUID.randomUUID())
            val failingInquiries =
                object : InquiryRepository by inquiries {
                    override fun insert(
                        transaction: Transaction,
                        inquiry: Inquiry,
                        requestedService: RequestedService,
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
