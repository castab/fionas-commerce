package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.fionas.commerce.customer.Customer
import io.github.castab.fionas.commerce.customer.CustomerId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.testing.TEST_INSTANT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

class InquiryValuesSpec :
    FunSpec({
        test("event dates are real calendar dates without a time zone and round-trip as ISO text") {
            EventDate.of("2028-02-29").value.toString() shouldBe "2028-02-29"
            listOf("2026-02-29", "2026-04-31", "0000-01-01", "10000-01-01", "2026-1-1", "2026-01-01T00:00:00Z").forEach {
                shouldThrow<IllegalArgumentException> { EventDate.of(it) }
            }
        }
        test("required ZIP codes are trimmed text, preserve leading zeroes and reject blank or malformed values") {
            ZipCode.of(" 02108 ").value shouldBe "02108"
            listOf("", " \n ", "1234", "123456", "12a45", "12345-6789", "１２３４５").forEach { invalid ->
                shouldThrow<IllegalArgumentException> { ZipCode.of(invalid) }.message shouldBe "ZIP code must contain exactly five digits"
            }
            listOf("", "1234", "123456", "12a45", "12345-6789", "１２３４５", " 12345 ").forEach { invalid ->
                shouldThrow<IllegalArgumentException> { ZipCode(invalid) }.message shouldBe "ZIP code must contain exactly five digits"
            }
        }

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
            val requested =
                FionasPricingInputs(
                    OfferingsRevision.of(1),
                    OfferingSelections(listOf(OfferingCategorySelection(OfferingCategoryKey("cone-option"), listOf(OfferingKey("cup"))))),
                    FionasOfferingsContext(75, false, Duration.ofMinutes(120)),
                )
            val inquiry =
                Inquiry(
                    InquiryId(UUID.randomUUID()),
                    customer.id,
                    null,
                    TEST_INSTANT,
                    ZipCode("92626"),
                    EventDate.of("2026-12-05"),
                    EventType.BIRTHDAY,
                )

            val currency = Currency.getInstance("USD")
            val lifecycle =
                InquiryLifecycle.project(
                    FinancialDocument.Estimate.create(
                        UUID.randomUUID(),
                        listOf(
                            LineItem(UUID.randomUUID(), "Service", null, null, Money(BigDecimal("100"), currency), Money.zero(currency)),
                        ),
                    ),
                    null,
                )
            InquiryDetails(inquiry, customer, requested, lifecycle).customer shouldBe customer
            InquirySummary(inquiry, customer).customer shouldBe customer
            shouldThrow<IllegalArgumentException> {
                InquiryDetails(inquiry.copy(customerId = CustomerId(UUID.randomUUID())), customer, requested, lifecycle)
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
