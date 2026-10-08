package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey

/**
 * Why [FionasOfferingsEngine] generated one line: the stable logical cause of a charge, unlike
 * the line's id, which every evaluation generates anew.
 *
 * A source names the policy rule or the selected offering that caused the line, never a
 * particular offering by name, so new catalog offerings need no new source. An evaluation
 * produces at most one line per source. A selected offering without a catalog price produces no
 * line and therefore has no source; several topping selections together cause one
 * [ExtraToppings] line.
 */
sealed interface FionasChargeSource {
    /** The base event fee plus the hourly rate for the service duration. */
    data object BaseService : FionasChargeSource

    /** The per-guest ice cream service. */
    data object IceCreamService : FionasChargeSource

    /** The catalog price of one selected offering of one category. */
    data class SelectedOffering(
        val category: OfferingCategoryKey,
        val offering: OfferingKey,
    ) : FionasChargeSource

    /** Topping selections beyond those the ice cream service includes, together. */
    data object ExtraToppings : FionasChargeSource
}
