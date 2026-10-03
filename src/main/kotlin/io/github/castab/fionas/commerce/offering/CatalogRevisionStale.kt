package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.runtime.operation.CommerceFailure

/** Typed conflict context; HTTP translates it using the runtime's error envelope. */
class CatalogRevisionStale(
    val submittedRevision: OfferingsRevision,
    val currentRevision: OfferingsRevision,
) : RuntimeException()

const val STAFF_CATALOG_REVISION_STALE_MESSAGE =
    "The offerings catalog changed; reload it and review the selections before pricing again"

/** Validates the caller's staleness token against the one snapshot observed for pricing. */
fun requireCurrentCatalogRevision(
    requested: OfferingsRevision,
    snapshot: OfferingsSnapshot,
    conflictMessage: String = STAFF_CATALOG_REVISION_STALE_MESSAGE,
) {
    if (requested.number > snapshot.revision.number) {
        throw CommerceFailure.NotFound("Offerings catalog revision $requested was not found")
    }
    if (requested != snapshot.revision) {
        throw CommerceFailure.Conflict(conflictMessage, CatalogRevisionStale(requested, snapshot.revision))
    }
}
