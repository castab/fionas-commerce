package io.github.castab.fionas.commerce.offering

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.offering.OfferingsEvaluationResult
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.OfferingsSnapshotReference
import io.github.castab.commerce.offering.OfferingsViolation
import io.github.castab.commerce.offering.StructuralOfferingsViolation
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
 * that does not exist. A selection the [engine] rejects, structurally or by Fiona's policy,
 * fails with [CommerceFailure.ValidationFailed] naming each violation's stable code.
 */
class PreviewEstimate(
    private val getRevision: (OfferingsSnapshotReference) -> OfferingsSnapshot,
    private val engine: FionasOfferingsEngine,
) {
    /** A request to estimate [selections] from [catalogRevision] of Fiona's catalog for the event in [context]. */
    data class Command(
        val catalogRevision: OfferingsRevision,
        val selections: OfferingSelections,
        val context: FionasOfferingsContext,
    )

    operator fun invoke(command: Command): EstimatePreview {
        val snapshot = getRevision(OfferingsSnapshotReference(FIONA_OFFERINGS_CATALOG_ID, command.catalogRevision))
        return when (val result = engine.evaluate(snapshot, command.selections, command.context)) {
            is OfferingsEvaluationResult.Accepted -> EstimatePreview(result.evaluation, command.context.guestCountIsMinimum)
            is OfferingsEvaluationResult.Rejected ->
                throw CommerceFailure.ValidationFailed(
                    "The selection cannot be estimated: " + result.violations.joinToString("; ") { "${it.code} (${it.explanation()})" },
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
