package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.customer.JdbiCustomerRepository
import io.github.castab.fionas.commerce.testing.STORED_INSTANT
import io.github.castab.fionas.commerce.testing.TestApplication
import io.github.castab.fionas.commerce.testing.testClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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
        ) = CreateInquiry(application.transactor, customers, inquiryRepository, testClock, { customerId }, { inquiryId })

        fun getInquiry() = GetInquiry(application.transactor, customers, inquiries)

        fun command(
            email: String,
            name: String = "Jane Doe",
            message: String? = "Ice cream for a birthday party",
        ) = CreateInquiry.Command(CustomerName.of(name), Email.of(email), InquiryMessage.ofOptional(message))

        test("a new email creates the customer and the inquiry together") {
            val customerId = CustomerId(UUID.randomUUID())
            val inquiryId = InquiryId(UUID.randomUUID())
            val email = "new-${UUID.randomUUID()}@example.com"

            val created = createInquiry(customerId, inquiryId)(command(email))

            created.customer.id shouldBe customerId
            created.customer.name shouldBe CustomerName("Jane Doe")
            created.customer.email shouldBe Email(email)
            created.customer.createdAt shouldBe STORED_INSTANT
            created.inquiry shouldBe Inquiry(inquiryId, customerId, InquiryMessage("Ice cream for a birthday party"), STORED_INSTANT)
            getInquiry()(inquiryId) shouldBe created
        }

        test("an inquiry with a known email reuses that customer and does not overwrite its name") {
            val email = "returning-${UUID.randomUUID()}@example.com"
            val first = createInquiry()(command(email, name = "Jane Doe"))
            val customersAfterFirst = application.database.count("public.customers")

            val second = createInquiry()(command(email.uppercase(), name = "J. Doe", message = null))

            second.customer shouldBe first.customer
            second.customer.name shouldBe CustomerName("Jane Doe")
            second.inquiry.customerId shouldBe first.customer.id
            second.inquiry.message.shouldBeNull()
            getInquiry()(second.inquiry.id) shouldBe second
            application.database.count("public.customers") shouldBe customersAfterFirst
        }

        test("when the inquiry cannot be recorded, the new customer is not recorded either") {
            val customerId = CustomerId(UUID.randomUUID())
            val failingInquiries =
                object : InquiryRepository by inquiries {
                    override fun insert(
                        transaction: Transaction,
                        inquiry: Inquiry,
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
