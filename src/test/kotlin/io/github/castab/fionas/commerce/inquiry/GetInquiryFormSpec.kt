package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingAvailability
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.QuantityDimension
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency

class GetInquiryFormSpec :
    FunSpec({
        val publicCategory = OfferingCategoryKey("soft-serve-flavor")

        fun money(
            amount: String,
            currency: String = "USD",
        ) = Money(BigDecimal(amount), Currency.getInstance(currency))

        fun catalog(price: OfferingPrice? = null) =
            OfferingsSnapshot.create(
                FIONA_OFFERINGS_CATALOG_ID,
                listOf(OfferingCategory(publicCategory, "Flavors")),
                listOf(Offering(OfferingKey("flavor"), publicCategory, "Flavor", price = price)),
            )

        test("public projection preserves the exact snapshot and ignores disabled prices while retaining unavailable pricing facts") {
            val snapshot =
                OfferingsSnapshot.create(
                    FIONA_OFFERINGS_CATALOG_ID,
                    listOf(OfferingCategory(publicCategory, "Flavors", minimumSelections = 2)),
                    listOf(
                        Offering(OfferingKey("available"), publicCategory, "Available"),
                        Offering(
                            OfferingKey("disabled"),
                            publicCategory,
                            "Disabled",
                            price = OfferingPrice.Fixed(money("1.00", "EUR")),
                            selectionState = OfferingSelectionState.DISABLED,
                        ),
                        Offering(
                            OfferingKey("temporary"),
                            publicCategory,
                            "Temporary",
                            price = OfferingPrice.PerDuration(money("3.00"), Duration.ofHours(1)),
                            availability = OfferingAvailability.UNAVAILABLE,
                        ),
                        Offering(
                            OfferingKey("both"),
                            publicCategory,
                            "Both",
                            selectionState = OfferingSelectionState.DISABLED,
                            availability = OfferingAvailability.UNAVAILABLE,
                        ),
                    ),
                )
            val form = GetInquiryForm({ snapshot })()
            form.catalog shouldBeSameInstanceAs snapshot
            form.catalog.offerings shouldBe snapshot.offerings
            publicInquiryOfferings(snapshot, publicCategory).map { it.key.value } shouldBe listOf("available", "temporary")
            form.pricingPreview.durationOptions.forEach {
                it.offeringContributions.map { contribution -> contribution.offering.value } shouldBe listOf("temporary")
            }
        }

        test("one read supplies question options, revision and resolved duration contributions together") {
            val first = catalog(OfferingPrice.PerDuration(money("3.00"), Duration.ofHours(1)))
            val later =
                first.replaceOffering(
                    OfferingKey("flavor"),
                    Offering(OfferingKey("flavor"), publicCategory, "Updated", price = OfferingPrice.Fixed(money("50.00"))),
                )
            var reads = 0
            val form = GetInquiryForm({ if (reads++ == 0) first else later })()
            reads shouldBe 1
            form.catalog shouldBe first
            form.pricingPreview.durationOptions.map { it.durationMinutes } shouldContainExactly listOf(90, 120, 150, 180)
            form.pricingPreview.durationOptions.map {
                it.offeringContributions
                    .single()
                    .amount.amount
                    .stripTrailingZeros()
            } shouldContainExactly
                listOf("4.50", "6.00", "7.50", "9.00").map { BigDecimal(it).stripTrailingZeros() }
        }

        test("policy edits automatically change every preview fact and the offered durations and topping question") {
            val policy =
                FIONAS_PRICING_POLICY.copy(
                    currency = Currency.getInstance("CAD"),
                    baseEventFee = money("200.00", "CAD"),
                    hourlyRate = money("80.00", "CAD"),
                    perGuestRate = money("6.00", "CAD"),
                    includedToppingCount = 2,
                    extraToppingPerGuestRate = money("0.40", "CAD"),
                    allowedDurations = setOf(Duration.ofMinutes(90), Duration.ofMinutes(180)),
                    toppingCategory = OfferingCategoryKey("crunch"),
                    guestDimension = QuantityDimension("attendee"),
                )
            val snapshot =
                OfferingsSnapshot.create(
                    FIONA_OFFERINGS_CATALOG_ID,
                    listOf(OfferingCategory(policy.toppingCategory, "Crunch")),
                    listOf(
                        Offering(
                            OfferingKey("premium"),
                            policy.toppingCategory,
                            "Premium",
                            price = OfferingPrice.PerQuantity(money("0.50", "CAD"), policy.guestDimension),
                        ),
                    ),
                )
            val form = GetInquiryForm({ snapshot }, policy)()
            val preview = form.pricingPreview
            preview.perGuestAmount shouldBe policy.perGuestRate
            preview.guestQuantityDimension shouldBe policy.guestDimension
            preview.toppingAdjustment shouldBe InquiryToppingAdjustment(policy.toppingCategory, 2, money("0.40", "CAD"))
            preview.durationOptions.map { it.baseServiceAmount.amount.stripTrailingZeros() } shouldContainExactly
                listOf(BigDecimal("320"), BigDecimal("440")).map { it.stripTrailingZeros() }
            val fields = form.sections.flatMap { it.fields }
            (
                fields
                    .single {
                        it.key == "durationMinutes"
                    }.input as InquiryFormInput.IntegerChoice
            ).options.map { it.value } shouldContainExactly
                preview.durationOptions.map { it.durationMinutes }
            fields.single { it.key == "offering:crunch" }.label shouldBe "Choose your toppings"
        }

        listOf(
            "UNSUPPORTED_CURRENCY" to OfferingPrice.Fixed(money("1.00", "EUR")),
            "UNSUPPORTED_QUANTITY_DIMENSION" to OfferingPrice.PerQuantity(money("1.00"), QuantityDimension("vehicle")),
            "INCOMPATIBLE_DURATION_PRICE" to OfferingPrice.PerDuration(money("1.00"), Duration.ofMinutes(7)),
            // 120 / 45 has a repeating decimal, even though the 90-minute duration works.
            "INCOMPATIBLE_DURATION_PRICE" to OfferingPrice.PerDuration(money("1.00"), Duration.ofMinutes(45)),
        ).forEachIndexed { index, (code, price) ->
            test("a public price incompatible with any offered context fails the whole form with diagnostic $index") {
                val failure = shouldThrow<IllegalStateException> { GetInquiryForm({ catalog(price) })() }
                failure.message shouldContain code
                failure.message shouldContain "flavor"
            }
        }

        test("an optional hidden category may contain prices Fiona cannot evaluate without exposing or validating them") {
            val snapshot = catalog()
            val hiddenKey = OfferingCategoryKey("staff-equipment")
            val withHidden =
                snapshot.revise(
                    snapshot.categories + OfferingCategory(hiddenKey, "Equipment"),
                    snapshot.offerings +
                        Offering(
                            OfferingKey("equipment"),
                            hiddenKey,
                            "Equipment",
                            price = OfferingPrice.PerQuantity(money("2.00", "EUR"), QuantityDimension("vehicle")),
                        ),
                )
            val form = GetInquiryForm({ withHidden })()
            form.sections shouldBe GetInquiryForm({ snapshot })().sections
            form.pricingPreview shouldBe GetInquiryForm({ snapshot })().pricingPreview
        }

        test("a required hidden category and an impossible public selection minimum fail configuration rather than exposing answers") {
            val initial = catalog()
            val hidden =
                initial.revise(
                    initial.categories +
                        OfferingCategory(OfferingCategoryKey("internal-adjustments"), "Internal", minimumSelections = 1),
                    initial.offerings,
                )
            shouldThrow<IllegalStateException> { GetInquiryForm({ hidden })() }.message shouldContain "hidden category"
            val impossible = initial.replaceCategory(publicCategory, OfferingCategory(publicCategory, "Flavors", minimumSelections = 2))
            shouldThrow<IllegalStateException> { GetInquiryForm({ impossible })() }.message shouldContain "fewer options"
        }
    })
