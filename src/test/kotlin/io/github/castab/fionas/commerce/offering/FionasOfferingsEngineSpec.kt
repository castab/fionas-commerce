package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingAvailability
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.offering.OfferingsEvaluationResult
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.QuantityDimension
import io.github.castab.commerce.offering.StructuralOfferingsViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

/**
 * Fiona's pricing policy, evaluated purely: no database, no HTTP. The catalog here is a test
 * catalog built in memory; Fiona's real catalog is administrative data.
 */
class FionasOfferingsEngineSpec :
    FunSpec({
        val usd = Currency.getInstance("USD")

        fun usd(amount: String) = Money(BigDecimal(amount), usd)

        fun perGuest(amount: String) = OfferingPrice.PerQuantity(usd(amount), QuantityDimension("guest"))

        fun category(
            key: String,
            minimum: Int,
            maximum: Int?,
        ) = OfferingCategory(OfferingCategoryKey(key), key, minimumSelections = minimum, maximumSelections = maximum)

        fun offering(
            key: String,
            category: String,
            displayName: String = key,
            price: OfferingPrice? = null,
        ) = Offering(
            OfferingKey(key),
            OfferingCategoryKey(category),
            displayName,
            price = price,
            selectionState = OfferingSelectionState.ENABLED,
            availability = OfferingAvailability.AVAILABLE,
        )

        val toppings = listOf("sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough")
        val snapshot =
            OfferingsSnapshot.create(
                OfferingsCatalogId(UUID.fromString("0cde8e0b-aa9c-4129-9853-8db2cbbb909b")),
                categories =
                    listOf(
                        category("soft-serve-flavor", 1, 2),
                        category("topping", 4, 6),
                        category("cone-option", 1, 1),
                        category("extras", 0, null),
                    ),
                offerings =
                    listOf(
                        offering("vanilla", "soft-serve-flavor", "Vanilla"),
                        offering("chocolate", "soft-serve-flavor", "Chocolate"),
                        offering("horchata", "soft-serve-flavor", "Horchata", perGuest("0.50")),
                    ) + toppings.map { offering(it, "topping") } +
                        listOf(
                            offering("hot-fudge", "topping", "Hot fudge", perGuest("0.30")),
                            offering("cup", "cone-option", "Cups"),
                            offering("waffle-cone", "cone-option", "Waffle cones", perGuest("0.75")),
                            offering("photo-booth", "extras", "Photo booth", OfferingPrice.Fixed(usd("120.00"))),
                            offering(
                                "extra-attendant",
                                "extras",
                                "Extra attendant",
                                OfferingPrice.PerDuration(usd("20.00"), Duration.ofMinutes(30)),
                            ),
                            offering(
                                "quarter-attendant",
                                "extras",
                                "Attendant, per 45 minutes",
                                OfferingPrice.PerDuration(usd("10.00"), Duration.ofMinutes(45)),
                            ),
                            offering(
                                "imported-syrup",
                                "extras",
                                "Imported syrup",
                                OfferingPrice.Fixed(Money(BigDecimal("10.00"), Currency.getInstance("EUR"))),
                            ),
                            offering("per-cone", "extras", "Per cone", OfferingPrice.PerQuantity(usd("1.00"), QuantityDimension("cone"))),
                        ),
            )

        fun selections(vararg blocks: Pair<String, List<String>>) =
            OfferingSelections(
                blocks.map { (category, offerings) ->
                    OfferingCategorySelection(OfferingCategoryKey(category), offerings.map(::OfferingKey))
                },
            )

        /** The smallest structurally valid selection: nothing priced by the catalog. */
        fun basic(vararg extra: Pair<String, List<String>>) =
            selections("soft-serve-flavor" to listOf("vanilla"), "topping" to toppings.take(4), "cone-option" to listOf("cup"), *extra)

        fun context(
            guests: Int = 75,
            minutes: Long = 120,
            minimum: Boolean = false,
        ) = FionasOfferingsContext(guests, minimum, Duration.ofMinutes(minutes))

        fun sequentialIds(): () -> UUID {
            var next = 0L
            return { UUID(0, ++next) }
        }

        fun evaluate(
            selections: OfferingSelections,
            context: FionasOfferingsContext = context(),
        ) = FionasOfferingsEngine(FIONAS_PRICING_POLICY, sequentialIds()).evaluate(snapshot, selections, context)

        fun accepted(
            selections: OfferingSelections,
            context: FionasOfferingsContext = context(),
        ): OfferingsEvaluation = evaluate(selections, context).shouldBeInstanceOf<OfferingsEvaluationResult.Accepted>().evaluation

        fun rejectedCodes(
            selections: OfferingSelections,
            context: FionasOfferingsContext = context(),
        ) = evaluate(selections, context).shouldBeInstanceOf<OfferingsEvaluationResult.Rejected>().violations.map { it.code }

        /** A line as `description: quantity × price = subtotal`, with exact decimals. */
        fun LineItem.text() =
            "$description: ${quantity?.toPlainString() ?: "flat"} × ${price.amount.toPlainString()} = ${subtotal.amount.toPlainString()}"

        test("the \$681.25 estimate: 75 guests, 2 hours, horchata, waffle cones, and six toppings") {
            val evaluation =
                accepted(
                    selections(
                        "soft-serve-flavor" to listOf("vanilla", "horchata"),
                        "topping" to toppings,
                        "cone-option" to listOf("waffle-cone"),
                    ),
                )

            evaluation.lineItems.map { it.text() } shouldContainExactly
                listOf(
                    "Base service: flat × 250.00 = 250.00",
                    "Ice cream service: 75 × 4.00 = 300.00",
                    "Horchata: 75 × 0.50 = 37.50",
                    "Waffle cones: 75 × 0.75 = 56.25",
                    "Extra toppings (2): 150 × 0.25 = 37.50",
                )
            EstimatePreview(evaluation, guestCountIsMinimum = false).total shouldBe usd("681.25")
        }

        test("the base service is the event fee plus the hourly rate for the service duration, as one flat line") {
            mapOf(90L to "225.000", 120L to "250.00", 150L to "275.000", 180L to "300.00").forEach { (minutes, total) ->
                val base = accepted(basic(), context(minutes = minutes)).lineItems.first()
                base.description shouldBe "Base service"
                base.quantity.shouldBeNull()
                base.price shouldBe usd(total)
            }
            accepted(basic(), context(minutes = 90)).lineItems.first().subDescription shouldBe "1.5 hours · setup, staff & local travel"
            accepted(basic(), context(minutes = 120)).lineItems.first().subDescription shouldBe "2 hours · setup, staff & local travel"
        }

        test("the ice cream service is priced per guest, and unpriced selections add no line") {
            val lines = accepted(basic()).lineItems

            lines.map { it.text() } shouldContainExactly
                listOf("Base service: flat × 250.00 = 250.00", "Ice cream service: 75 × 4.00 = 300.00")
            lines[1].subDescription shouldBe "75 guests"
        }

        test("a FIXED catalog price is one flat line") {
            accepted(basic("extras" to listOf("photo-booth"))).lineItems.last().text() shouldBe "Photo booth: flat × 120.00 = 120.00"
        }

        test("PER_QUANTITY catalog prices per guest price premium flavors and cones without special cases") {
            accepted(
                selections(
                    "soft-serve-flavor" to listOf("horchata"),
                    "topping" to toppings.take(4),
                    "cone-option" to listOf("waffle-cone"),
                ),
                context(guests = 40),
            ).lineItems.drop(2).map { it.text() } shouldContainExactly
                listOf("Horchata: 40 × 0.50 = 20.00", "Waffle cones: 40 × 0.75 = 30.00")
        }

        test("a PER_DURATION catalog price is charged for each interval of the service duration, exactly") {
            accepted(basic("extras" to listOf("extra-attendant"))).lineItems.last().text() shouldBe
                "Extra attendant: 4 × 20.00 = 80.00"
            accepted(basic("extras" to listOf("extra-attendant")), context(minutes = 90)).lineItems.last().text() shouldBe
                "Extra attendant: 3 × 20.00 = 60.00"
            // 90 minutes is exactly two 45-minute intervals; 120 minutes is not a whole or finite multiple.
            accepted(basic("extras" to listOf("quarter-attendant")), context(minutes = 90)).lineItems.last().text() shouldBe
                "Attendant, per 45 minutes: 2 × 10.00 = 20.00"
            rejectedCodes(basic("extras" to listOf("quarter-attendant"))) shouldContainExactly listOf("INCOMPATIBLE_DURATION_PRICE")
        }

        test("four toppings are included; each one beyond is charged per guest in one aggregate line") {
            fun toppingLines(count: Int) =
                accepted(
                    selections(
                        "soft-serve-flavor" to listOf("vanilla"),
                        "topping" to toppings.take(count),
                        "cone-option" to listOf("cup"),
                    ),
                ).lineItems.filter { it.description.startsWith("Extra toppings") }.map { it.text() }

            toppingLines(4) shouldBe emptyList()
            toppingLines(5) shouldContainExactly listOf("Extra toppings (1): 75 × 0.25 = 18.75")
            toppingLines(6) shouldContainExactly listOf("Extra toppings (2): 150 × 0.25 = 37.50")
        }

        test("a premium topping's own price and the extra-topping charge both apply") {
            accepted(
                selections(
                    "soft-serve-flavor" to listOf("vanilla"),
                    "topping" to toppings.take(4) + "hot-fudge",
                    "cone-option" to listOf("cup"),
                ),
            ).lineItems.drop(2).map { it.text() } shouldContainExactly
                listOf("Hot fudge: 75 × 0.30 = 22.50", "Extra toppings (1): 75 × 0.25 = 18.75")
        }

        test("a guest count below 1 is rejected by Fiona's policy") {
            rejectedCodes(basic(), context(guests = 0)) shouldContainExactly listOf("INVALID_GUEST_COUNT")
            rejectedCodes(basic(), context(guests = -3)) shouldContainExactly listOf("INVALID_GUEST_COUNT")
        }

        test("a service duration Fiona's does not offer is rejected") {
            listOf(0L, 60L, 100L, 240L).forEach { minutes ->
                rejectedCodes(basic(), context(minutes = minutes)) shouldContainExactly listOf("UNSUPPORTED_DURATION")
            }
        }

        test("an offering priced in another currency is rejected, never converted or mixed") {
            val violation =
                evaluate(basic("extras" to listOf("imported-syrup")))
                    .shouldBeInstanceOf<OfferingsEvaluationResult.Rejected>()
                    .violations
                    .single()
            violation shouldBe
                FionasOfferingsViolation.UnsupportedCurrency(OfferingKey("imported-syrup"), Currency.getInstance("EUR"), usd)
        }

        test("an offering priced per a quantity other than guests is rejected") {
            rejectedCodes(basic("extras" to listOf("per-cone"))) shouldContainExactly listOf("UNSUPPORTED_QUANTITY_DIMENSION")
        }

        test("every policy violation is reported at once") {
            rejectedCodes(basic("extras" to listOf("per-cone", "imported-syrup")), context(guests = 0, minutes = 100)) shouldContainExactly
                listOf("INVALID_GUEST_COUNT", "UNSUPPORTED_DURATION", "UNSUPPORTED_QUANTITY_DIMENSION", "UNSUPPORTED_CURRENCY")
        }

        test("a minimum guest count changes how the estimate reads, never its arithmetic") {
            val exact = accepted(basic(), context(guests = 100))
            val minimum = accepted(basic(), context(guests = 100, minimum = true))

            minimum.lineItems.map { it.subtotal } shouldBe exact.lineItems.map { it.subtotal }
            minimum.lineItems[1].quantity shouldBe BigDecimal(100)
            minimum.lineItems[1].subDescription shouldBe "100+ guests"
            EstimatePreview(minimum, guestCountIsMinimum = true).total shouldBe EstimatePreview(exact, guestCountIsMinimum = false).total
        }

        test("lines come in a stable order with injected ids, and the same input gives the same lines") {
            val selection =
                selections(
                    "cone-option" to listOf("waffle-cone"),
                    "soft-serve-flavor" to listOf("horchata", "vanilla"),
                    "topping" to toppings,
                    "extras" to listOf("photo-booth"),
                )
            val lines = accepted(selection).lineItems

            // Base and ice cream first, then catalog prices in submitted order, then extra toppings.
            lines.map { it.description } shouldContainExactly
                listOf("Base service", "Ice cream service", "Waffle cones", "Horchata", "Photo booth", "Extra toppings (2)")
            lines.map { it.id } shouldContainExactly (1L..6L).map { UUID(0, it) }
            accepted(selection).lineItems shouldBe lines
        }

        test("there is no tax yet, and the totals are derived exactly from the lines") {
            val evaluation =
                accepted(
                    selections("soft-serve-flavor" to listOf("horchata"), "topping" to toppings, "cone-option" to listOf("waffle-cone")),
                )
            evaluation.lineItems.forEach { it.taxAmount shouldBe Money.zero(usd) }

            val preview = EstimatePreview(evaluation, guestCountIsMinimum = false)
            preview.subtotal shouldBe usd("681.25")
            preview.taxAmount.amount.compareTo(BigDecimal.ZERO) shouldBe 0
            preview.total shouldBe preview.subtotal
            preview.currency shouldBe usd
            preview.catalogRevision shouldBe snapshot.revision
        }

        test("structural validation is commerce-domain's: Fiona's policy never sees a structurally invalid selection") {
            fun structural(
                selections: OfferingSelections,
                context: FionasOfferingsContext = context(),
            ) = evaluate(selections, context)
                .shouldBeInstanceOf<OfferingsEvaluationResult.Rejected>()
                .violations
                .onEach { it.shouldBeInstanceOf<StructuralOfferingsViolation>() }
                .map { it.code }

            // Missing a required category, and too few selections in one.
            structural(selections("soft-serve-flavor" to listOf("vanilla"), "topping" to toppings.take(4))) shouldContainExactly
                listOf("TOO_FEW_SELECTIONS")
            structural(
                selections("soft-serve-flavor" to emptyList(), "topping" to toppings.take(4), "cone-option" to listOf("cup")),
            ) shouldContainExactly
                listOf("TOO_FEW_SELECTIONS")
            // Too many toppings, an unknown offering, one in the wrong category, and a duplicate.
            structural(
                selections("soft-serve-flavor" to listOf("vanilla"), "topping" to toppings + "hot-fudge", "cone-option" to listOf("cup")),
            ) shouldContainExactly listOf("TOO_MANY_SELECTIONS")
            structural(
                selections("soft-serve-flavor" to listOf("vanilla"), "topping" to toppings.take(3) + "cup", "cone-option" to listOf("cup")),
            ) shouldContainExactly listOf("OFFERING_IN_WRONG_CATEGORY")
            structural(basic("extras" to listOf("mango"))) shouldContainExactly listOf("UNKNOWN_OFFERING")
            structural(
                selections(
                    "soft-serve-flavor" to listOf("vanilla", "vanilla"),
                    "topping" to toppings.take(4),
                    "cone-option" to listOf("cup"),
                ),
            ) shouldContainExactly listOf("DUPLICATE_OFFERING")
            // Structural problems are reported alone, even when the event could not be priced either.
            structural(basic("extras" to listOf("mango")), context(guests = 0, minutes = 100)) shouldContainExactly
                listOf("UNKNOWN_OFFERING")
        }

        test("Fiona's current pricing policy") {
            with(FIONAS_PRICING_POLICY) {
                currency shouldBe usd
                baseEventFee shouldBe usd("150.00")
                hourlyRate shouldBe usd("50.00")
                perGuestRate shouldBe usd("4.00")
                includedToppingCount shouldBe 4
                extraToppingPerGuestRate shouldBe usd("0.25")
                allowedDurations shouldBe setOf(90L, 120L, 150L, 180L).map(Duration::ofMinutes).toSet()
                toppingCategory shouldBe OfferingCategoryKey("topping")
                guestDimension shouldBe QuantityDimension("guest")
            }
        }

        test("a pricing policy is one currency and charges every offered duration exactly") {
            shouldThrow<IllegalArgumentException> {
                FIONAS_PRICING_POLICY.copy(perGuestRate = Money(BigDecimal("4.00"), Currency.getInstance("EUR")))
            }
            shouldThrow<IllegalArgumentException> { FIONAS_PRICING_POLICY.copy(allowedDurations = setOf(Duration.ofMinutes(20))) }
            shouldThrow<IllegalArgumentException> { FIONAS_PRICING_POLICY.copy(allowedDurations = emptySet()) }
        }
    })
