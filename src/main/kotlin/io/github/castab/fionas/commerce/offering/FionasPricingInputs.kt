package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision

/**
 * Everything Fiona's prices an event from: the observed current revision of Fiona's catalog the choices
 * were made from, the choices in submitted order, and the event facts.
 *
 * The same inputs price an estimate preview, a persisted estimate, and a change order, and
 * they are recorded with every persisted financial snapshot as its pricing source, so the
 * history can say why each snapshot charged what it did. Commercial amounts are never an
 * input: the server derives them from these values with [FionasOfferingsEngine].
 *
 * @property catalogRevision A staleness token for new pricing; the observed revision remains recorded in history.
 * @property selections The chosen offerings, one block per category, in submitted order;
 *   an explicitly empty block is kept as submitted.
 * @property context The event facts the price depends on.
 */
data class FionasPricingInputs(
    val catalogRevision: OfferingsRevision,
    val selections: OfferingSelections,
    val context: FionasOfferingsContext,
)
