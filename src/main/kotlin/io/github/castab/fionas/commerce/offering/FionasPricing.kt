package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.offering.OfferingsEvaluationResult
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsViolation
import io.github.castab.commerce.offering.StructuralOfferingsViolation
import io.github.castab.commerce.runtime.offering.offeringsValidationFailed
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.persistence.Transaction

/**
 * Fiona's server-authoritative pricing of [FionasPricingInputs], shared by estimate previews,
 * persisted estimates, and change orders, so every one of them prices the same inputs the
 * same way.
 *
 * [retrieveLatest] is commerce-runtime's transaction-bound latest catalog read, handed over
 * by the composition root. The submitted revision is a staleness token: only the observed
 * current snapshot can be priced. The read shares the transaction that writes its result.
 *
 * A selection the [engine] rejects, structurally or by Fiona's policy, fails with
 * [CommerceFailure.ValidationFailed] naming each violation's stable code.
 */
class FionasPricing(
    private val engine: FionasOfferingsEngine,
    private val retrieveLatest: (Transaction, OfferingsCatalogId) -> OfferingsSnapshot?,
) {
    /** Reads current once in [transaction], rejects stale inputs, then prices that observed value. */
    fun price(
        transaction: Transaction,
        inputs: FionasPricingInputs,
        conflictMessage: String = STAFF_CATALOG_REVISION_STALE_MESSAGE,
    ): OfferingsEvaluation {
        val snapshot =
            retrieveLatest(transaction, FIONA_OFFERINGS_CATALOG_ID)
                ?: throw CommerceFailure.NotFound("Fiona's Offerings catalog was not found")
        requireCurrentCatalogRevision(inputs.catalogRevision, snapshot, conflictMessage)
        return price(snapshot, inputs)
    }

    /** Prices [inputs] from [snapshot], which must be the exact revision they name. */
    fun price(
        snapshot: OfferingsSnapshot,
        inputs: FionasPricingInputs,
    ): OfferingsEvaluation {
        check(snapshot.catalogId == FIONA_OFFERINGS_CATALOG_ID && snapshot.revision == inputs.catalogRevision) {
            "Pricing inputs for ${inputs.catalogRevision} were given catalog snapshot ${snapshot.reference}"
        }
        return when (val result = engine.evaluate(snapshot, inputs.selections, inputs.context)) {
            is OfferingsEvaluationResult.Accepted -> result.evaluation
            is OfferingsEvaluationResult.Rejected ->
                throw offeringsValidationFailed(
                    message =
                        "The selection cannot be estimated: " + result.violations.joinToString("; ") { "${it.code} (${it.explanation()})" },
                    violations = result.violations,
                )
        }
    }
}

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
