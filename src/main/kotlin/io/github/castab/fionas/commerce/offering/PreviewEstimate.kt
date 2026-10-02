package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.runtime.operation.CommerceFailure
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
 * Prices a selection from one exact revision of Fiona's catalog, without recording anything.
 *
 * The revision is the one the caller rendered its choices from, never the latest: a
 * selection made from revision 12 is evaluated against revision 12 even after the catalog
 * has moved on, and one that names an offering revision 12 lacks is rejected.
 *
 * [getRevision] is commerce-runtime's `GetOfferingsCatalogRevision`, which reads the
 * snapshot in its own transaction and fails with [CommerceFailure.NotFound] for a revision
 * that does not exist. [pricing] is the same Fiona pricing that persisted financial
 * documents use; a selection it rejects fails with [CommerceFailure.ValidationFailed].
 */
class PreviewEstimate(
    private val getRevision: (OfferingsSnapshotReference) -> OfferingsSnapshot,
    private val pricing: FionasPricing,
) {
    operator fun invoke(inputs: FionasPricingInputs): EstimatePreview {
        val snapshot = getRevision(fionaCatalogRevision(inputs.catalogRevision))
        return EstimatePreview(pricing.price(snapshot, inputs), inputs.context.guestCountIsMinimum)
    }
}
