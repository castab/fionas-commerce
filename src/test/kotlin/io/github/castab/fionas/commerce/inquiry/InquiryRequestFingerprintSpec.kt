package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.staff.ServiceId
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.financial.PricedLine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigDecimal
import java.util.Currency
import java.util.UUID

class InquiryRequestFingerprintSpec :
    FunSpec({
        val usd = Currency.getInstance("USD")

        fun money(amount: String) = Money(BigDecimal(amount), usd)

        val service = PricedLine("Churro catering service", "Prepared on site", BigDecimal("100"), money("4.50"), money("0.00"))
        val discount = PricedLine("Courtesy discount", null, null, money("-50.00"), money("0.00"))
        val requested =
            RequestedService(
                75,
                false,
                120,
                listOf(RequestedServiceItem("Churros", "Desserts", "churros"), RequestedServiceItem("Chocolate sauce", null, null)),
                "fionas-web-pricing@2026-10-01",
            )
        val original =
            CreateInquiry.Command(
                CustomerName.of("Jane"),
                Email.of("jane@example.com"),
                InquiryMessage.ofOptional("Birthday"),
                requested,
                listOf(service, discount),
                ZipCode.of("02108"),
                EventDate.of("2026-12-05"),
                EventType.BIRTHDAY,
                InquirySubmissionKey("A"),
                ServiceId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            )

        fun requested(value: RequestedService) = original.copy(requestedService = value)

        fun lines(vararg value: PricedLine) = original.copy(lines = value.toList())

        val differences =
            listOf(
                "name" to original.copy(name = CustomerName.of("Janet")),
                "email" to original.copy(email = Email.of("janet@example.com")),
                "message" to original.copy(message = InquiryMessage.ofOptional("Wedding")),
                "absent message" to original.copy(message = null),
                "zip" to original.copy(zipCode = ZipCode.of("92626")),
                "date" to original.copy(eventDate = EventDate.of("2026-12-06")),
                "type" to original.copy(eventType = EventType.WEDDING),
                "guests" to requested(requested.copy(guestCount = 76)),
                "minimum guests" to requested(requested.copy(guestCountIsMinimum = true)),
                "duration" to requested(requested.copy(durationMinutes = 90)),
                "absent duration" to requested(requested.copy(durationMinutes = null)),
                "item order" to requested(requested.copy(items = requested.items.reversed())),
                "item label" to requested(requested.copy(items = listOf(RequestedServiceItem("Churro", "Desserts", "churros")))),
                "item group" to requested(requested.copy(items = listOf(RequestedServiceItem("Churros", null, "churros")))),
                "item key" to requested(requested.copy(items = listOf(RequestedServiceItem("Churros", "Desserts", null)))),
                "pricing reference" to requested(requested.copy(pricingReference = "fionas-web-pricing@2026-10-02")),
                "absent pricing reference" to requested(requested.copy(pricingReference = null)),
                "line order" to lines(discount, service),
                "missing line" to lines(service),
                "extra line" to lines(service, discount, discount.copy(description = "Second discount")),
                "line description" to lines(service.copy(description = "Churro service"), discount),
                "line sub-description" to lines(service.copy(subDescription = null), discount),
                "line quantity" to lines(service.copy(quantity = BigDecimal("101")), discount),
                "flat versus quantity" to lines(service, discount.copy(quantity = BigDecimal.ONE)),
                "unit price" to lines(service.copy(unitPrice = money("4.51")), discount),
                "tax amount" to lines(service.copy(taxAmount = money("0.01")), discount),
                "currency" to
                    lines(
                        service.copy(
                            unitPrice = Money(BigDecimal("4.50"), Currency.getInstance("EUR")),
                            taxAmount = Money.zero(Currency.getInstance("EUR")),
                        ),
                    ),
            )
        differences.forEach { (field, changed) ->
            test("fingerprint includes $field") { changed.fingerprint() shouldNotBe original.fingerprint() }
        }
        test("the v2 encoding is pinned, so committed submissions keep replaying") {
            original.fingerprint() shouldBe PINNED_V2
        }
        test("canonical normalization, numeric scale, the submitting principal and the command key do not affect fingerprints") {
            original
                .copy(
                    name = CustomerName.of(" Jane "),
                    email = Email.of(" JANE@EXAMPLE.COM "),
                    message = InquiryMessage.ofOptional(" Birthday "),
                    zipCode = ZipCode.of(" 02108 "),
                    submissionKey = InquirySubmissionKey("different"),
                    submittedBy = ServiceId(UUID.randomUUID()),
                    lines =
                        listOf(
                            service.copy(quantity = BigDecimal("100.000"), unitPrice = money("4.5"), taxAmount = money("0")),
                            discount.copy(unitPrice = money("-50")),
                        ),
                ).fingerprint() shouldBe original.fingerprint()
            original.copy(message = InquiryMessage.ofOptional(" ")).fingerprint() shouldBe original.copy(message = null).fingerprint()
            original.fingerprint().matches(Regex("[0-9a-f]{64}")) shouldBe true
        }
        test("string encoding is lossless and field boundaries cannot collide") {
            original.copy(name = CustomerName("\uD800")).fingerprint() shouldNotBe
                original.copy(name = CustomerName("?")).fingerprint()
            original.copy(name = CustomerName("😀")).fingerprint() shouldNotBe
                original.copy(name = CustomerName("😁")).fingerprint()
            original.copy(name = CustomerName("ab"), message = InquiryMessage("c")).fingerprint() shouldNotBe
                original.copy(name = CustomerName("a"), message = InquiryMessage("bc")).fingerprint()
            lines(service.copy(description = "ab", subDescription = "c")).fingerprint() shouldNotBe
                lines(service.copy(description = "a", subDescription = "bc")).fingerprint()
        }

        test("key spelling is opaque, bounded, UUID compatible and never normalized") {
            listOf("A", "abc_123-XYZ", "123e4567-e89b-12d3-a456-426614174000", "a".repeat(128)).forEach {
                InquirySubmissionKey(it).value shouldBe
                    it
            }
            listOf("", " ", " A", "A ", "a".repeat(129), "a.b", "a/b", "é", "a\nb", "a,b").forEach {
                shouldThrow<IllegalArgumentException> { InquirySubmissionKey(it) }
            }
        }
    })

/** The v2 fingerprint of the spec's original command; changing it breaks replay of committed submissions. */
private const val PINNED_V2 = "f99475e6f019d7154c66c634510872a304b9d96c18193411edbc640f7778c226"
