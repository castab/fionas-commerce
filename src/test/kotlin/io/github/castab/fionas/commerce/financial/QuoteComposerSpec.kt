package io.github.castab.fionas.commerce.financial

import io.github.castab.commerce.deposit.DepositTerms
import io.github.castab.commerce.financial.ChangeOrder
import io.github.castab.commerce.financial.FinancialDocument
import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.financial.Version
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingAvailability
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.QuantityDimension
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.fionas.commerce.inquiry.InquiryId
import io.github.castab.fionas.commerce.offering.CatalogRevisionStale
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import io.github.castab.fionas.commerce.offering.FionasChargeSource
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasOfferingsEngine
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import io.github.castab.fionas.commerce.offering.FionasPricingPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.util.Currency
import java.util.UUID

/**
 * Fiona's one quote composition core, purely: no database, no HTTP. A test policy and catalog
 * reproduce the illustrative builder case exactly ($415 Estimate: base $205, ice cream $180 for
 * 40 guests, waffle cone upgrade $30), without touching Fiona's production pricing.
 */
class QuoteComposerSpec :
    FunSpec({
        val usd = Currency.getInstance("USD")

        fun usd(amount: String) = Money(BigDecimal(amount), usd)

        val policy =
            FionasPricingPolicy(
                currency = usd,
                baseEventFee = usd("105.00"),
                hourlyRate = usd("50.00"),
                perGuestRate = usd("4.50"),
                includedToppingCount = 4,
                extraToppingPerGuestRate = usd("0.25"),
                allowedDurations = setOf(Duration.ofMinutes(120), Duration.ofMinutes(180)),
                toppingCategory = OfferingCategoryKey("topping"),
                guestDimension = QuantityDimension("guest"),
            )

        fun offering(
            key: String,
            category: String,
            name: String,
            price: OfferingPrice? = null,
            description: String? = null,
            availability: OfferingAvailability = OfferingAvailability.AVAILABLE,
        ) = Offering(
            OfferingKey(key),
            OfferingCategoryKey(category),
            name,
            description,
            price,
            OfferingSelectionState.ENABLED,
            availability,
        )

        val toppings = listOf("sprinkles", "oreos", "strawberries", "brownies")
        val catalog =
            OfferingsSnapshot.create(
                FIONA_OFFERINGS_CATALOG_ID,
                listOf(
                    OfferingCategory(OfferingCategoryKey("soft-serve-flavor"), "Soft Serve", minimumSelections = 1, maximumSelections = 2),
                    OfferingCategory(OfferingCategoryKey("topping"), "Toppings", minimumSelections = 4, maximumSelections = 6),
                    OfferingCategory(OfferingCategoryKey("cone-option"), "Cones", minimumSelections = 1, maximumSelections = 1),
                ),
                listOf(
                    offering("vanilla", "soft-serve-flavor", "Vanilla"),
                    offering("chocolate", "soft-serve-flavor", "Chocolate"),
                    offering("mango", "soft-serve-flavor", "Mango", OfferingPrice.PerQuantity(usd("0.50"), QuantityDimension("guest"))),
                ) +
                    (
                        toppings +
                            listOf(
                                "gummy-bears",
                                "cookie-dough",
                            )
                    ).map { offering(it, "topping", it.replaceFirstChar(Char::uppercase)) } +
                    listOf(
                        offering("cup", "cone-option", "Cups"),
                        offering(
                            "waffle-cone",
                            "cone-option",
                            "Waffle cone upgrade",
                            OfferingPrice.Fixed(usd("30.00")),
                            "Fresh waffle cones",
                        ),
                    ),
            )

        fun selections(
            softServe: List<String> = listOf("vanilla"),
            topping: List<String> = toppings,
            cones: List<String> = listOf("waffle-cone"),
        ) = OfferingSelections(
            listOf(
                OfferingCategorySelection(OfferingCategoryKey("soft-serve-flavor"), softServe.map(::OfferingKey)),
                OfferingCategorySelection(OfferingCategoryKey("topping"), topping.map(::OfferingKey)),
                OfferingCategorySelection(OfferingCategoryKey("cone-option"), cones.map(::OfferingKey)),
            ),
        )

        fun inputs(
            snapshot: OfferingsSnapshot = catalog,
            selected: OfferingSelections = selections(),
            guests: Int = 40,
        ) = FionasPricingInputs(snapshot.revision, selected, FionasOfferingsContext(guests, false, Duration.ofMinutes(120)))

        var counter = 0L

        fun ids(): () -> UUID = { UUID(0, ++counter) }

        val pricing = FionasPricing(FionasOfferingsEngine(policy, ids()), { _, _ -> error("the composer reads nothing") })
        val inquiryId = InquiryId(UUID.fromString("c755f7cd-1e28-4c75-a85f-d066ede7387d"))
        val documentId = UUID.fromString("5f0c6a7e-8c1d-4f63-9b2a-0d8e7f6a5b4c")
        val estimate = FinancialDocument.Estimate.create(documentId, pricing.price(catalog, inputs()).lineItems)
        val (base, iceCream, waffle) = estimate.lineItems

        fun basis(
            snapshot: OfferingsSnapshot = catalog,
            document: FinancialDocument.Estimate = estimate,
        ) = QuoteCompositionBasis(inquiryId, document, inputs(), snapshot)

        fun reason(text: String) = QuoteEditReason(text)

        fun override(
            line: LineItem,
            amount: String,
            why: String = "Negotiated package rate",
        ) = QuoteLineOverride(QuoteOverrideTarget.ExistingLine(line.id), usd(amount), reason(why))

        fun adjustment(
            key: String,
            kind: QuoteAdjustmentKind,
            amount: String,
            description: String = "Adjustment $key",
        ) = QuoteAdjustment(
            QuoteAdjustmentKey(key),
            kind,
            QuoteLineDescription(description),
            null,
            usd(amount),
            reason("Reason for $key"),
        )

        val fixed105 = DepositTerms.Fixed(usd("105.00"))

        fun compose(
            composition: QuoteComposition = QuoteComposition(QuotePricing.KeepEstimate),
            terms: DepositTerms = fixed105,
            composeBasis: QuoteCompositionBasis = basis(),
            lineIds: () -> UUID = ids(),
        ) = QuoteComposer(pricing, lineIds).compose(composeBasis, composition, terms)

        fun codes(block: () -> Unit) = shouldThrow<CommerceFailure.ValidationFailed>(block).violations.map { it.code }

        test("the fixture Estimate is the illustrative $415.00 with authoritative persisted lines") {
            estimate.lineItems.map { it.description } shouldContainExactly
                listOf("Base service", "Ice cream service", "Waffle cone upgrade")
            estimate.lineItems.map { it.total.amount } shouldContainExactly
                listOf(BigDecimal("205.00"), BigDecimal("180.00"), BigDecimal("30.00"))
            estimate.total.amount shouldBe BigDecimal("415.00")
        }

        test("KEEP_ESTIMATE without edits needs no intermediate Estimate and keeps every persisted line and id") {
            val composed = compose()
            composed.changes.shouldBeNull()
            composed.financialChange shouldBe false
            composed.quote.version shouldBe Version.of(2)
            composed.quote.lineItems shouldBe estimate.lineItems
            composed.quote.total.amount shouldBe BigDecimal("415.00")
            composed.lines.all { it.persisted && it.origin == QuoteLineOrigin.EstimateLine && it.override == null } shouldBe true
            composed.requiredDeposit shouldBe usd("105.00")
            composed.selections.map { it.displayName } shouldContainExactly listOf("Soft Serve", "Toppings", "Cones")
            composed.selections
                .last()
                .offerings
                .single() shouldBe
                ServicePlanOffering(OfferingKey("waffle-cone"), "Waffle cone upgrade", "Fresh waffle cones")
        }

        test("KEEP_ESTIMATE never reprices: changed catalog prices leave the persisted amounts and ids alone") {
            val repriced =
                catalog.replaceOffering(
                    OfferingKey("waffle-cone"),
                    catalog.offering(OfferingKey("waffle-cone"))!!.copy(price = OfferingPrice.Fixed(usd("45.00"))),
                )
            val composed = compose(composeBasis = basis(repriced))
            composed.quote.lineItems shouldBe estimate.lineItems
            composed.quote.total.amount shouldBe BigDecimal("415.00")
            composed.catalogRevision shouldBe repriced.revision
        }

        test("the illustrative composition: one $165 override, a +$25 charge and a -$20 discount make a $405 Quote") {
            val composed =
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        listOf(override(iceCream, "165.00")),
                        listOf(
                            adjustment("travel-1", QuoteAdjustmentKind.CHARGE, "25.00", "Additional travel fee"),
                            adjustment("courtesy-1", QuoteAdjustmentKind.DISCOUNT, "20.00", "Courtesy discount"),
                        ),
                    ),
                )
            composed.financialChange shouldBe true
            composed.quote.version shouldBe Version.of(3)
            composed.quote.total.amount shouldBe BigDecimal("405.00")
            composed.requiredDeposit shouldBe usd("105.00")
            val lines = composed.quote.lineItems
            lines.map { it.total.amount } shouldContainExactly
                listOf(BigDecimal("205.00"), BigDecimal("165.00"), BigDecimal("30.00"), BigDecimal("25.00"), BigDecimal("-20.00"))
            // One effective $165 line under the original id: not $180 plus an unmarked -$15 line.
            lines[0] shouldBe base
            lines[1].id shouldBe iceCream.id
            lines[1].quantity.shouldBeNull()
            lines[1].price shouldBe usd("165.00")
            lines[1].subDescription.shouldBeNull()
            lines[2] shouldBe waffle
            lines.drop(3).forEach { it.quantity.shouldBeNull() }
            val changes = composed.changes.shouldNotBeNull().changes
            changes.size shouldBe 3
            changes[0] shouldBe ChangeOrder.Change.ReplaceLineItem(iceCream.id, lines[1])
            changes.drop(1).map { it.shouldBeInstanceOf<ChangeOrder.Change.AddLineItem>().lineItem } shouldContainExactly lines.drop(3)
            composed.lines[1].override shouldBe AppliedQuoteOverride(iceCream, reason("Negotiated package rate"))
            composed.lines[3].origin shouldBe
                QuoteLineOrigin.Adjustment(QuoteAdjustmentKey("travel-1"), QuoteAdjustmentKind.CHARGE, reason("Reason for travel-1"))
            composed.lines[4]
                .origin
                .shouldBeInstanceOf<QuoteLineOrigin.Adjustment>()
                .kind shouldBe QuoteAdjustmentKind.DISCOUNT
            composed.lines.map { it.persisted } shouldContainExactly listOf(true, true, true, false, false)
        }

        test("a negotiated total that does not divide by the guest count stays exact as a flat line") {
            val composed = compose(QuoteComposition(QuotePricing.KeepEstimate, listOf(override(iceCream, "165.01"))))
            val line = composed.quote.lineItems[1]
            line.quantity.shouldBeNull()
            line.price.amount shouldBe BigDecimal("165.01")
            line.total.amount shouldBe BigDecimal("165.01")
            composed.quote.total.amount shouldBe BigDecimal("400.01")
        }

        test("a flat line keeps its secondary text; a waived charge is zero") {
            val composed = compose(QuoteComposition(QuotePricing.KeepEstimate, listOf(override(base, "0"))))
            composed.quote.lineItems[0].subDescription shouldBe base.subDescription
            composed.quote.lineItems[0]
                .total.amount
                .signum() shouldBe 0
            composed.quote.total.amount shouldBe BigDecimal("210.00")
        }

        test("credits and discounts are distinguishable intent and both negative lines; charges are positive") {
            val composed =
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        adjustments =
                            listOf(
                                adjustment("a", QuoteAdjustmentKind.CHARGE, "10.00"),
                                adjustment("b", QuoteAdjustmentKind.DISCOUNT, "10.00"),
                                adjustment("c", QuoteAdjustmentKind.CREDIT, "10.00"),
                            ),
                    ),
                )
            composed.quote.lineItems
                .drop(3)
                .map { it.price.amount } shouldContainExactly
                listOf(BigDecimal("10.00"), BigDecimal("-10.00"), BigDecimal("-10.00"))
            composed.lines.drop(3).map { (it.origin as QuoteLineOrigin.Adjustment).kind } shouldContainExactly
                listOf(QuoteAdjustmentKind.CHARGE, QuoteAdjustmentKind.DISCOUNT, QuoteAdjustmentKind.CREDIT)
        }

        test("invalid targets, duplicates, mode mismatches, unchanged overrides and currencies reject with stable codes") {
            val eur = Money(BigDecimal("1.00"), Currency.getInstance("EUR"))
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        listOf(override(base, "1.00").copy(target = QuoteOverrideTarget.ExistingLine(UUID.randomUUID()))),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.OVERRIDE_TARGET_NOT_FOUND)
            codes {
                compose(QuoteComposition(QuotePricing.KeepEstimate, listOf(override(base, "1.00"), override(base, "2.00"))))
            } shouldContainExactly listOf(QuoteCompositionViolations.DUPLICATE_OVERRIDE_TARGET)
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        listOf(
                            QuoteLineOverride(
                                QuoteOverrideTarget.GeneratedCharge(FionasChargeSource.BaseService),
                                usd("1.00"),
                                reason("x"),
                            ),
                        ),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.OVERRIDE_TARGET_NOT_ALLOWED)
            codes {
                compose(QuoteComposition(QuotePricing.KeepEstimate, listOf(override(iceCream, "180"))))
            } shouldContainExactly listOf(QuoteCompositionViolations.OVERRIDE_UNCHANGED)
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        listOf(QuoteLineOverride(QuoteOverrideTarget.ExistingLine(base.id), eur, reason("x"))),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.CURRENCY_MISMATCH)
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        adjustments =
                            listOf(
                                adjustment("a", QuoteAdjustmentKind.CHARGE, "1.00"),
                                adjustment("a", QuoteAdjustmentKind.CREDIT, "2.00"),
                            ),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.DUPLICATE_ADJUSTMENT_KEY)
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        adjustments = listOf(adjustment("a", QuoteAdjustmentKind.CHARGE, "1.00").copy(amount = eur)),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.CURRENCY_MISMATCH)
        }

        test("override and adjustment rejections are reported together") {
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        listOf(QuoteLineOverride(QuoteOverrideTarget.ExistingLine(UUID.randomUUID()), usd("1.00"), reason("x"))),
                        listOf(adjustment("a", QuoteAdjustmentKind.CHARGE, "1.00"), adjustment("a", QuoteAdjustmentKind.CHARGE, "2.00")),
                    ),
                )
            } shouldContainExactly
                listOf(QuoteCompositionViolations.OVERRIDE_TARGET_NOT_FOUND, QuoteCompositionViolations.DUPLICATE_ADJUSTMENT_KEY)
        }

        test("value objects reject negative overrides, nonpositive adjustments, excess precision and invalid text") {
            shouldThrow<IllegalArgumentException> { override(base, "-1.00") }
            shouldThrow<IllegalArgumentException> { override(base, "1.001") }
            shouldThrow<IllegalArgumentException> { adjustment("a", QuoteAdjustmentKind.CHARGE, "0") }
            shouldThrow<IllegalArgumentException> { adjustment("a", QuoteAdjustmentKind.DISCOUNT, "-5.00") }
            shouldThrow<IllegalArgumentException> { adjustment("a", QuoteAdjustmentKind.CHARGE, "0.005") }
            shouldThrow<IllegalArgumentException> { QuoteAdjustmentKey("no spaces") }
            shouldThrow<IllegalArgumentException> { QuoteAdjustmentKey("k".repeat(65)) }
            shouldThrow<IllegalArgumentException> { QuoteEditReason.of("   ") }
            shouldThrow<IllegalArgumentException> { QuoteEditReason.of("r".repeat(QuoteEditReason.MAX_LENGTH + 1)) }
            shouldThrow<IllegalArgumentException> { QuoteLineDescription.of("d".repeat(QuoteLineDescription.MAX_LENGTH + 1)) }
            QuoteEditReason.of("  trimmed  ").value shouldBe "trimmed"
            QuoteLineSubDescription.ofOptional("  ").shouldBeNull()
        }

        test("a negative resulting total and a zero Quote total reject before any write") {
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        adjustments = listOf(adjustment("c", QuoteAdjustmentKind.CREDIT, "415.01")),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.NEGATIVE_DOCUMENT_TOTAL)
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.KeepEstimate,
                        adjustments = listOf(adjustment("c", QuoteAdjustmentKind.CREDIT, "415.00")),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.QUOTE_TOTAL_NOT_POSITIVE)
        }

        test("deposits resolve against the exact final Quote with shared HALF_UP rules and bounds") {
            val composition =
                QuoteComposition(
                    QuotePricing.KeepEstimate,
                    adjustments = listOf(adjustment("c", QuoteAdjustmentKind.CREDIT, "0.03")),
                )
            // 20% of 414.97 = 82.994, HALF_UP to 82.99.
            compose(composition, DepositTerms.Percentage(BigDecimal("20"))).requiredDeposit shouldBe usd("82.99")
            // 33.335% of 415.00 = 138.34025 → 138.34.
            compose(terms = DepositTerms.Percentage(BigDecimal("33.335"))).requiredDeposit shouldBe usd("138.34")
            shouldThrow<CommerceFailure.ValidationFailed> { compose(terms = DepositTerms.Fixed(usd("415.01"))) }
            shouldThrow<CommerceFailure.ValidationFailed> { compose(terms = DepositTerms.Fixed(usd("100.001"))) }
            shouldThrow<CommerceFailure.ValidationFailed> {
                compose(terms = DepositTerms.Fixed(Money(BigDecimal("100.00"), Currency.getInstance("EUR"))))
            }
        }

        test("the review token is identical for equivalent compositions despite new line ids, and changes with any reviewed fact") {
            val composition =
                QuoteComposition(
                    QuotePricing.KeepEstimate,
                    listOf(override(iceCream, "165.00")),
                    listOf(adjustment("travel-1", QuoteAdjustmentKind.CHARGE, "25.00")),
                )
            val first = compose(composition, lineIds = { UUID.randomUUID() })
            val second = compose(composition, lineIds = { UUID.randomUUID() })
            first.quote.lineItems
                .last()
                .id shouldNotBe
                second.quote.lineItems
                    .last()
                    .id
            first.reviewToken shouldBe second.reviewToken
            val renamed =
                catalog.replaceOffering(
                    OfferingKey("waffle-cone"),
                    catalog.offering(OfferingKey("waffle-cone"))!!.copy(displayName = "Waffle cones"),
                )
            val unrelated =
                catalog.replaceOffering(
                    OfferingKey("chocolate"),
                    catalog.offering(OfferingKey("chocolate"))!!.copy(displayName = "Dark chocolate"),
                )
            listOf(
                compose(composition.copy(overrides = listOf(override(iceCream, "165.01")))),
                compose(composition.copy(overrides = listOf(override(iceCream, "165.00", "Another reason")))),
                compose(composition.copy(adjustments = listOf(adjustment("travel-2", QuoteAdjustmentKind.CHARGE, "25.00")))),
                compose(composition.copy(adjustments = listOf(adjustment("travel-1", QuoteAdjustmentKind.CREDIT, "25.00")))),
                compose(composition, DepositTerms.Percentage(BigDecimal("20"))),
                compose(composition, DepositTerms.Fixed(usd("100.00"))),
                compose(composition, composeBasis = basis(renamed)),
                // Not a reviewed label, but a newer revision the plan would be pinned to.
                compose(composition, composeBasis = basis(unrelated)),
            ).forEach { it.reviewToken shouldNotBe first.reviewToken }
            // Equal terms in another scale are the same reviewed terms.
            compose(composition, DepositTerms.Fixed(usd("105.0"))).reviewToken shouldBe first.reviewToken
        }

        test("REVISE_SERVICE_SELECTIONS keeps persisted lines for a neutral swap, and rejects unchanged or pricing changes") {
            val revised = selections(softServe = listOf("chocolate"))
            val composed = compose(QuoteComposition(QuotePricing.ReviseServiceSelections(catalog.revision, revised)))
            composed.changes.shouldBeNull()
            composed.quote.lineItems shouldBe estimate.lineItems
            composed.configuration.selections shouldBe revised
            composed.selections
                .first()
                .offerings
                .map { it.displayName } shouldContainExactly listOf("Chocolate")
            codes {
                compose(QuoteComposition(QuotePricing.ReviseServiceSelections(catalog.revision, selections())))
            } shouldContainExactly listOf(QuoteCompositionViolations.SERVICE_SELECTIONS_UNCHANGED)
            codes {
                compose(QuoteComposition(QuotePricing.ReviseServiceSelections(catalog.revision, selections(softServe = listOf("mango")))))
            } shouldContainExactly listOf(QuoteCompositionViolations.SERVICE_SELECTIONS_CHANGE_PRICING)
            codes {
                compose(QuoteComposition(QuotePricing.ReviseServiceSelections(catalog.revision, selections(cones = listOf("cup")))))
            } shouldContainExactly listOf(QuoteCompositionViolations.SERVICE_SELECTIONS_CHANGE_PRICING)
            // Overrides still target the persisted lines.
            compose(QuoteComposition(QuotePricing.ReviseServiceSelections(catalog.revision, revised), listOf(override(waffle, "25.00"))))
                .quote.total.amount shouldBe BigDecimal("410.00")
        }

        test("a neutral swap is judged by today's catalog, while the old Estimate prices remain") {
            val repriced =
                catalog.replaceOffering(
                    OfferingKey("waffle-cone"),
                    catalog.offering(OfferingKey("waffle-cone"))!!.copy(price = OfferingPrice.Fixed(usd("45.00"))),
                )
            val composed =
                compose(
                    QuoteComposition(QuotePricing.ReviseServiceSelections(repriced.revision, selections(softServe = listOf("chocolate")))),
                    composeBasis = basis(repriced),
                )
            composed.quote.total.amount shouldBe BigDecimal("415.00")
            val stale =
                shouldThrow<CommerceFailure.Conflict> {
                    compose(
                        QuoteComposition(
                            QuotePricing.ReviseServiceSelections(catalog.revision, selections(softServe = listOf("chocolate"))),
                        ),
                        composeBasis = basis(repriced),
                    )
                }
            stale.cause.shouldBeInstanceOf<CatalogRevisionStale>()
        }

        test("REPRICE_CONFIGURATION prices the reviewed revision, targets overrides by source and replaces the line set explicitly") {
            val repriced =
                catalog.replaceOffering(
                    OfferingKey("waffle-cone"),
                    catalog.offering(OfferingKey("waffle-cone"))!!.copy(price = OfferingPrice.Fixed(usd("45.00"))),
                )
            val waffleSource = FionasChargeSource.SelectedOffering(OfferingCategoryKey("cone-option"), OfferingKey("waffle-cone"))
            val composed =
                compose(
                    QuoteComposition(
                        QuotePricing.RepriceConfiguration(
                            inputs(repriced, selections(softServe = listOf("vanilla", "mango")), guests = 50),
                        ),
                        listOf(
                            QuoteLineOverride(QuoteOverrideTarget.GeneratedCharge(waffleSource), usd("40.00"), reason("Loyal customer")),
                        ),
                    ),
                    composeBasis = basis(repriced),
                )
            composed.lines.map { (it.origin as QuoteLineOrigin.Generated).source } shouldContainExactly
                listOf(
                    FionasChargeSource.BaseService,
                    FionasChargeSource.IceCreamService,
                    FionasChargeSource.SelectedOffering(OfferingCategoryKey("soft-serve-flavor"), OfferingKey("mango")),
                    waffleSource,
                )
            composed.quote.lineItems.map { it.total.amount } shouldContainExactly
                listOf(BigDecimal("205.00"), BigDecimal("225.00"), BigDecimal("25.00"), BigDecimal("40.00"))
            composed.lines[3]
                .override!!
                .original.total.amount shouldBe BigDecimal("45.00")
            composed.lines.none { it.persisted } shouldBe true
            val changes = composed.changes.shouldNotBeNull().changes
            changes.take(3) shouldContainExactly estimate.lineItems.map { ChangeOrder.Change.RemoveLineItem(it.id) }
            changes.drop(3).map { (it as ChangeOrder.Change.AddLineItem).lineItem } shouldContainExactly composed.quote.lineItems
            // Existing persisted line ids are not targets when repricing.
            codes {
                compose(QuoteComposition(QuotePricing.RepriceConfiguration(inputs()), listOf(override(base, "1.00"))))
            } shouldContainExactly listOf(QuoteCompositionViolations.OVERRIDE_TARGET_NOT_ALLOWED)
            // A source the evaluation never produced is not found.
            codes {
                compose(
                    QuoteComposition(
                        QuotePricing.RepriceConfiguration(inputs()),
                        listOf(
                            QuoteLineOverride(
                                QuoteOverrideTarget.GeneratedCharge(FionasChargeSource.ExtraToppings),
                                usd("1.00"),
                                reason("x"),
                            ),
                        ),
                    ),
                )
            } shouldContainExactly listOf(QuoteCompositionViolations.OVERRIDE_TARGET_NOT_FOUND)
        }

        test("repricing that reproduces the Estimate's charges keeps its persisted lines and needs no intermediate Estimate") {
            val composed =
                compose(QuoteComposition(QuotePricing.RepriceConfiguration(inputs(selected = selections(softServe = listOf("chocolate"))))))
            composed.changes.shouldBeNull()
            composed.quote.lineItems shouldBe estimate.lineItems
            composed.lines.all { it.persisted } shouldBe true
            composed.lines.map { (it.origin as QuoteLineOrigin.Generated).source } shouldContainExactlyInAnyOrder
                listOf(
                    FionasChargeSource.BaseService,
                    FionasChargeSource.IceCreamService,
                    FionasChargeSource.SelectedOffering(OfferingCategoryKey("cone-option"), OfferingKey("waffle-cone")),
                )
        }

        test("equal totals with different line facts are a financial change, never a no-op") {
            // $180 ice cream to $185 and waffle $30 to $25: the same $415 total, different charges.
            val composed =
                compose(QuoteComposition(QuotePricing.KeepEstimate, listOf(override(iceCream, "185.00"), override(waffle, "25.00"))))
            composed.quote.total.amount shouldBe BigDecimal("415.00")
            composed.financialChange shouldBe true
            composed.quote.version shouldBe Version.of(3)
        }

        test("an Estimate selection the current catalog no longer offers needs review instead of a fabricated name") {
            val unavailable =
                catalog.replaceOffering(
                    OfferingKey("waffle-cone"),
                    catalog.offering(OfferingKey("waffle-cone"))!!.copy(availability = OfferingAvailability.UNAVAILABLE),
                )
            shouldThrow<CommerceFailure.ValidationFailed> { compose(composeBasis = basis(unavailable)) }
                .violations
                .map { it.code } shouldContainExactly listOf("OFFERING_UNAVAILABLE")
            val retired = catalog.withoutOffering(OfferingKey("waffle-cone"))
            shouldThrow<CommerceFailure.ValidationFailed> { compose(composeBasis = basis(retired)) }
                .violations
                .map { it.code } shouldContainExactly listOf("UNKNOWN_OFFERING")
            // Reselecting from the current catalog is the reviewed path.
            compose(
                QuoteComposition(QuotePricing.RepriceConfiguration(inputs(retired, selections(cones = listOf("cup"))))),
                composeBasis = basis(retired),
            ).quote.total.amount shouldBe BigDecimal("385.00")
        }
    })
