package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.OfferingsSnapshot

/** Fiona's visible menu: enabled options retain catalog order, including temporary unavailability.
 * This projection never replaces the full snapshot used by authoritative pricing.
 */
internal fun publicInquiryOfferings(
    snapshot: OfferingsSnapshot,
    category: OfferingCategoryKey,
) = snapshot.offeringsIn(category).filter { it.selectionState == OfferingSelectionState.ENABLED }
