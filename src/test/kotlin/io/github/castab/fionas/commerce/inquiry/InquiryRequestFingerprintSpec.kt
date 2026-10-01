package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Duration

class InquiryRequestFingerprintSpec :
    FunSpec({
        val inputs =
            FionasPricingInputs(
                OfferingsRevision.of(20),
                OfferingSelections(
                    listOf(
                        OfferingCategorySelection(
                            OfferingCategoryKey("soft-serve-flavor"),
                            listOf(OfferingKey("vanilla"), OfferingKey("chocolate")),
                        ),
                        OfferingCategorySelection(OfferingCategoryKey("cone-option"), listOf(OfferingKey("cup"))),
                    ),
                ),
                FionasOfferingsContext(75, false, Duration.ofMinutes(120)),
            )
        val original =
            CreateInquiry.Command(
                CustomerName.of("Jane"),
                Email.of("jane@example.com"),
                InquiryMessage.ofOptional("Birthday"),
                inputs,
                ZipCode.of("02108"),
                EventDate.of("2026-12-05"),
                EventType.BIRTHDAY,
                InquirySubmissionKey("A"),
            )

        fun context(value: FionasOfferingsContext) = original.copy(pricingInputs = inputs.copy(context = value))

        fun selections(value: List<OfferingCategorySelection>) =
            original.copy(pricingInputs = inputs.copy(selections = OfferingSelections(value)))
        val differences =
            listOf(
                "name" to original.copy(name = CustomerName.of("Janet")),
                "email" to original.copy(email = Email.of("janet@example.com")),
                "message" to original.copy(message = InquiryMessage.ofOptional("Wedding")),
                "absent message" to original.copy(message = null),
                "zip" to original.copy(zipCode = ZipCode.of("92626")),
                "date" to original.copy(eventDate = EventDate.of("2026-12-06")),
                "type" to original.copy(eventType = EventType.WEDDING),
                "absent pricing" to original.copy(pricingInputs = null),
                "revision" to original.copy(pricingInputs = inputs.copy(catalogRevision = OfferingsRevision.of(21))),
                "guests" to context(inputs.context.copy(guestCount = 76)),
                "minimum guests" to context(inputs.context.copy(guestCountIsMinimum = true)),
                "duration" to context(inputs.context.copy(duration = Duration.ofMinutes(90))),
                "fractional duration" to context(inputs.context.copy(duration = inputs.context.duration.plusNanos(1))),
                "category order" to selections(inputs.selections.categories.reversed()),
                "offering order" to
                    selections(inputs.selections.categories.map { OfferingCategorySelection(it.category, it.offerings.reversed()) }),
                "offering identity" to
                    selections(inputs.selections.categories.map { OfferingCategorySelection(it.category, listOf(OfferingKey("other"))) }),
                "category identity" to
                    selections(listOf(OfferingCategorySelection(OfferingCategoryKey("other"), listOf(OfferingKey("cup"))))),
                "empty block versus omission" to
                    selections(inputs.selections.categories + OfferingCategorySelection(OfferingCategoryKey("topping"), emptyList())),
            )
        differences.forEach { (field, changed) ->
            test("fingerprint includes $field") { changed.fingerprint() shouldNotBe original.fingerprint() }
        }
        test("canonical normalization and command key do not affect fingerprints") {
            original
                .copy(
                    name = CustomerName.of(" Jane "),
                    email = Email.of(" JANE@EXAMPLE.COM "),
                    message = InquiryMessage.ofOptional(" Birthday "),
                    zipCode = ZipCode.of(" 02108 "),
                    submissionKey = InquirySubmissionKey("different"),
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
