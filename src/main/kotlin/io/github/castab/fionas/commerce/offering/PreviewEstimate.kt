package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import java.util.Currency

/**
 * An accepted estimate of a selection, before any quote exists: the [evaluation]'s lines
 * and totals derived from them, so the totals can never disagree with the lines.
 *
 * [guestCountIsMinimum] says the estimate is a lower bound ("from $X"); the lines were
 * priced for the stated guest count either way. There is no tax yet, so [total] equals
 * [subtotal].
 */
class EstimatePreview(
    val evaluation: OfferingsEvaluation,
    val guestCountIsMinimum: Boolean,
) {
    val catalogRevision: OfferingsRevision get() = evaluation.snapshot.revision

    /** The one currency of every line; commerce-domain's evaluation guarantees there is one. */
    val currency: Currency = evaluation.lineItems.first().currency

    val subtotal: Money = evaluation.lineItems.fold(Money.zero(currency)) { sum, line -> sum + line.subtotal }
    val taxAmount: Money = evaluation.lineItems.fold(Money.zero(currency)) { sum, line -> sum + line.taxAmount }
    val total: Money = subtotal + taxAmount
}

/**
 * Prices the current catalog without recording anything. [getCatalog] is the runtime's
 * GetOfferingsCatalog read in its own transaction; the caller's revision must still match.
 * A publication after observation does not change this captured immutable value.
 */
class PreviewEstimate(
    private val getCatalog: (OfferingsCatalogId) -> OfferingsSnapshot,
    private val pricing: FionasPricing,
) {
    operator fun invoke(inputs: FionasPricingInputs): EstimatePreview {
        val snapshot = getCatalog(FIONA_OFFERINGS_CATALOG_ID)
        requireCurrentCatalogRevision(inputs.catalogRevision, snapshot)
        return EstimatePreview(pricing.price(snapshot, inputs), inputs.context.guestCountIsMinimum)
    }
}
