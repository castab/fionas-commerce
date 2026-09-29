package io.github.castab.fionas.commerce.inquiry

import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.testing.TEST_INSTANT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class InquiryValuesSpec :
    FunSpec({
        test("a message is trimmed, and a missing or blank one is absent") {
            InquiryMessage.ofOptional("  A birthday party.  ")?.value shouldBe "A birthday party."
            InquiryMessage.ofOptional(null).shouldBeNull()
            InquiryMessage.ofOptional(" \n ").shouldBeNull()
        }

        test("a message may be at most 4000 characters") {
            InquiryMessage.ofOptional("x".repeat(InquiryMessage.MAX_LENGTH))?.value?.length shouldBe InquiryMessage.MAX_LENGTH
            shouldThrow<IllegalArgumentException> { InquiryMessage.ofOptional("x".repeat(InquiryMessage.MAX_LENGTH + 1)) }
        }

        test("a message is constructed only in canonical form") {
            shouldThrow<IllegalArgumentException> { InquiryMessage("") }
            shouldThrow<IllegalArgumentException> { InquiryMessage("hello ") }
        }

        test("inquiry details pair an inquiry only with its own customer") {
            val customer = Customer(CustomerId(UUID.randomUUID()), CustomerName("Jane Doe"), Email("jane@example.com"), TEST_INSTANT)
            val inquiry = Inquiry(InquiryId(UUID.randomUUID()), customer.id, null, TEST_INSTANT)

            InquiryDetails(inquiry, customer, null).customer shouldBe customer
            InquirySummary(inquiry, customer).customer shouldBe customer
            shouldThrow<IllegalArgumentException> {
                InquiryDetails(inquiry.copy(customerId = CustomerId(UUID.randomUUID())), customer, null)
            }
            shouldThrow<IllegalArgumentException> {
                InquirySummary(inquiry.copy(customerId = CustomerId(UUID.randomUUID())), customer)
            }
        }

        test("a list page holds 1 to 100 inquiries, 25 unless asked otherwise") {
            ListInquiries.Command(after = null).limit shouldBe 25
            ListInquiries.Command(after = null, limit = 1).limit shouldBe 1
            ListInquiries.Command(after = null, limit = 100).limit shouldBe 100
            listOf(0, -1, 101).forEach { limit ->
                shouldThrow<IllegalArgumentException> { ListInquiries.Command(after = null, limit = limit) }.message shouldBe
                    "limit must be between 1 and 100"
            }
        }
    })
