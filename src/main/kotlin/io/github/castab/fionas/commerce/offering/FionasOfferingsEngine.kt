package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.financial.LineItem
import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingPrice
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsEngine
import io.github.castab.commerce.offering.OfferingsPolicyResult
import io.github.castab.commerce.offering.OfferingsSnapshot
import java.math.BigDecimal
import java.util.UUID

/**
 * Fiona's pricing of a selection from one exact catalog revision: the financial lines of an
 * estimate.
 *
 * commerce-domain's [OfferingsEngine] first validates the selection's structure against the
 * snapshot (known categories and offerings, selection counts, duplicates); only a
 * structurally valid selection reaches [evaluateValid]. This engine then applies Fiona's
 * [policy], which knows the event, not individual offerings:
 *
 * 1. **Base service**: the base event fee plus the hourly rate for the service duration, one
 *    flat line.
 * 2. **Ice cream service**: the per-guest rate × the guest count.
 * 3. **Priced selections**, in submitted order: each selected offering's catalog price
 *    ([OfferingPrice.Fixed] flat, [OfferingPrice.PerQuantity] per guest,
 *    [OfferingPrice.PerDuration] per interval of service). Unpriced selections add no line.
 * 4. **Extra toppings**, when more topping selections are made than the policy includes:
 *    one line, the extra selections × guests at the extra-topping rate.
 *
 * Premium flavors, cones, or any other surcharge live in the catalog as offering prices, so
 * new ones need no code. Amounts are exact; nothing is rounded, and there is no tax yet.
 *
 * Pure: no persistence, HTTP, or clock. Only [lineItemId] varies between evaluations.
 */
class FionasOfferingsEngine(
    private val policy: FionasPricingPolicy = FIONAS_PRICING_POLICY,
    private val lineItemId: () -> UUID = UUID::randomUUID,
) : OfferingsEngine<FionasOfferingsContext>() {
    override fun evaluateValid(
        snapshot: OfferingsSnapshot,
        selections: OfferingSelections,
        context: FionasOfferingsContext,
    ): OfferingsPolicyResult {
        val violations = mutableListOf<FionasOfferingsViolation>()
        if (context.guestCount < 1) violations += FionasOfferingsViolation.InvalidGuestCount(context.guestCount)
        if (context.duration !in policy.allowedDurations) {
            violations += FionasOfferingsViolation.UnsupportedDuration(context.duration, policy.allowedDurations)
        }
        val selectionCharges =
            selections.categories.flatMap { block ->
                block.offerings.mapNotNull { key ->
                    // Structural validation has already proven every selected offering exists.
                    val offering = checkNotNull(snapshot.offering(key)) { "Offering ${key.value} passed validation but is absent" }
                    offering.price?.let { price ->
                        when (val priced = charge(offering, price, context)) {
                            is Priced.Charged -> priced.charge
                            is Priced.Refused -> null.also { violations += priced.violation }
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) return OfferingsPolicyResult.Rejected(violations)

        val toppings =
            selections.categories
                .find { it.category == policy.toppingCategory }
                ?.offerings
                ?.size ?: 0
        val charges =
            listOf(baseService(context), iceCreamService(context)) + selectionCharges + listOfNotNull(extraToppings(toppings, context))
        val noTax = Money.zero(policy.currency)
        return OfferingsPolicyResult.Accepted(
            charges.map { LineItem(lineItemId(), it.description, it.subDescription, it.quantity, it.price, noTax) },
        )
    }

    private fun baseService(context: FionasOfferingsContext): Charge {
        val hours = checkNotNull(context.duration.exactMultipleOf(ONE_HOUR)) { "Allowed durations are whole-minute hour fractions" }
        val hoursText = hours.stripTrailingZeros().toPlainString() + if (hours.compareTo(BigDecimal.ONE) == 0) " hour" else " hours"
        return Charge(
            description = "Base service",
            subDescription = "$hoursText · setup, staff & local travel",
            quantity = null,
            price = policy.baseServiceAmount(context.duration),
        )
    }

    private fun iceCreamService(context: FionasOfferingsContext) =
        Charge(
            description = "Ice cream service",
            subDescription = "${context.guestCount}${if (context.guestCountIsMinimum) "+" else ""} guests",
            quantity = context.guestCount.toBigDecimal(),
            price = policy.perGuestRate,
        )

    private fun extraToppings(
        toppings: Int,
        context: FionasOfferingsContext,
    ): Charge? {
        val extra = toppings - policy.includedToppingCount
        if (extra <= 0) return null
        return Charge(
            description = "Extra toppings ($extra)",
            subDescription = "${policy.includedToppingCount} toppings included; each extra is charged per guest",
            quantity = extra.toBigDecimal() * context.guestCount.toBigDecimal(),
            price = policy.extraToppingPerGuestRate,
        )
    }

    /** The line an offering's own catalog price adds, or why Fiona's cannot price it. */
    private fun charge(
        offering: Offering,
        price: OfferingPrice,
        context: FionasOfferingsContext,
    ): Priced {
        policy.offeringPriceViolation(offering, context.duration)?.let { return Priced.Refused(it) }
        val amount =
            when (price) {
                is OfferingPrice.Fixed -> price.amount
                is OfferingPrice.PerQuantity -> price.amount
                is OfferingPrice.PerDuration -> price.amount
            }
        val quantity =
            when (price) {
                is OfferingPrice.Fixed -> null
                is OfferingPrice.PerQuantity -> context.guestCount.toBigDecimal()
                is OfferingPrice.PerDuration ->
                    checkNotNull(context.duration.exactMultipleOf(price.interval)) { "A compatible duration price has an exact multiplier" }
            }
        return Priced.Charged(Charge(offering.displayName, offering.description, quantity, amount))
    }

    private class Charge(
        val description: String,
        val subDescription: String?,
        val quantity: BigDecimal?,
        val price: Money,
    )

    private sealed interface Priced {
        class Charged(
            val charge: Charge,
        ) : Priced

        class Refused(
            val violation: FionasOfferingsViolation,
        ) : Priced
    }
}
