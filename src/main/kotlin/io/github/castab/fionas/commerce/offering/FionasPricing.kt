package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.offering.OfferingsEvaluationResult
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.offering.OfferingsViolation
import io.github.castab.commerce.offering.StructuralOfferingsViolation
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction

/**
 * Fiona's server-authoritative pricing of [FionasPricingInputs], shared by estimate previews,
 * persisted estimates, and change orders, so every one of them prices the same inputs the
 * same way.
 *
 * [retrieveRevision] is commerce-runtime's `OfferingsSnapshotRepository.retrieveVersion`,
 * handed over by the composition root: it reads an exact catalog revision inside the
 * caller's transaction, so an operation that persists what it priced reads the catalog in
 * the same transaction as its writes. Fiona never reads the catalog tables itself.
 *
 * A selection the [engine] rejects, structurally or by Fiona's policy, fails with
 * [CommerceFailure.ValidationFailed] naming each violation's stable code.
 */
class FionasPricing(
    private val engine: FionasOfferingsEngine,
    private val retrieveRevision: (Transaction, OfferingsSnapshotReference) -> OfferingsSnapshot?,
) {
    /**
     * Prices [inputs] from the catalog revision they name, read in [transaction]. Fails with
     * [CommerceFailure.NotFound] when Fiona's catalog has no such revision, exactly as
     * commerce-runtime's `GetOfferingsCatalogRevision` does for a preview.
     */
    fun price(
        transaction: Transaction,
        inputs: FionasPricingInputs,
    ): OfferingsEvaluation {
        val reference = fionaCatalogRevision(inputs.catalogRevision)
        val snapshot =
            retrieveRevision(transaction, reference)
                ?: throw CommerceFailure.NotFound("Offerings catalog revision ${reference.revision} was not found")
        return price(snapshot, inputs)
    }

    /** Prices [inputs] from [snapshot], which must be the exact revision they name. */
    fun price(
        snapshot: OfferingsSnapshot,
        inputs: FionasPricingInputs,
    ): OfferingsEvaluation {
        check(snapshot.reference == fionaCatalogRevision(inputs.catalogRevision)) {
            "Pricing inputs for ${inputs.catalogRevision} were given catalog snapshot ${snapshot.reference}"
        }
        return when (val result = engine.evaluate(snapshot, inputs.selections, inputs.context)) {
            is OfferingsEvaluationResult.Accepted -> result.evaluation
            is OfferingsEvaluationResult.Rejected ->
                throw CommerceFailure.ValidationFailed(
                    "The selection cannot be estimated: " + result.violations.joinToString("; ") { "${it.code} (${it.explanation()})" },
                )
        }
    }
}

/** The reference of [revision] of Fiona's catalog. */
fun fionaCatalogRevision(revision: OfferingsRevision) = OfferingsSnapshotReference(FIONA_OFFERINGS_CATALOG_ID, revision)

private fun OfferingsViolation.explanation(): String =
    when (this) {
        is FionasOfferingsViolation -> message
        is StructuralOfferingsViolation.UnknownCategory -> "this catalog revision has no category ${category.value}"
        is StructuralOfferingsViolation.UnknownOffering -> "this catalog revision has no offering ${offering.value}"
        is StructuralOfferingsViolation.OfferingInWrongCategory ->
            "offering ${offering.value} does not belong to category ${category.value}"
        is StructuralOfferingsViolation.TooFewSelections ->
            "category ${category.value} needs at least $minimum selections, got $actual"
        is StructuralOfferingsViolation.TooManySelections ->
            "category ${category.value} allows at most $maximum selections, got $actual"
        is StructuralOfferingsViolation.DuplicateCategory -> "category ${category.value} is selected from more than once"
        is StructuralOfferingsViolation.DuplicateOffering ->
            "offering ${offering.value} is selected more than once in category ${category.value}"
        else -> "the selection is not allowed"
    }
