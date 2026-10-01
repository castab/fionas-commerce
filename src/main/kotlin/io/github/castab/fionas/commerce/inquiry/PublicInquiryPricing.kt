package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.ValidationViolation
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs

/** Public inquiry constraints only; staff financial operations continue using [FionasPricing] directly. */
class PublicInquiryPricing(
    private val pricing: FionasPricing,
    private val retrieveLatest: (Transaction, OfferingsCatalogId) -> OfferingsSnapshot?,
) {
    fun price(
        transaction: Transaction,
        inputs: FionasPricingInputs,
    ): OfferingsEvaluation {
        val publicKeys = publicOfferingQuestions().map { it.category }.toSet()
        if (inputs.selections.categories.any { it.category !in publicKeys }) {
            throw CommerceFailure.ValidationFailed(
                "Selections must use categories exposed by Fiona's public inquiry form",
                listOf(ValidationViolation("PUBLIC_INQUIRY_CATEGORY_NOT_ALLOWED")),
            )
        }
        // Observe latest once inside the caller's READ COMMITTED transaction. Immutable revisions
        // let a publication after this observation coexist with this already accepted submission.
        val snapshot =
            retrieveLatest(transaction, FIONA_OFFERINGS_CATALOG_ID)
                ?: throw CommerceFailure.NotFound("Fiona's Offerings catalog was not found")
        if (inputs.catalogRevision.number > snapshot.revision.number) {
            throw CommerceFailure.NotFound("Offerings catalog revision ${inputs.catalogRevision} was not found")
        }
        if (inputs.catalogRevision != snapshot.revision) {
            throw CommerceFailure.Conflict(
                "The inquiry form changed; fetch the current form and review the selections and pricing before resubmitting",
                CatalogRevisionStale(inputs.catalogRevision, snapshot.revision),
            )
        }
        // Enabled offerings in public categories are advertised, including unavailable options.
        // Keep disabled offerings in this full snapshot so the runtime reports OFFERING_DISABLED,
        // and let it enforce availability, membership, retirement and cardinality before pricing.
        return pricing.price(snapshot, inputs)
    }
}

/** Typed conflict context, carried by the runtime failure; HTTP retains the runtime error envelope. */
class CatalogRevisionStale(
    val submittedRevision: OfferingsRevision,
    val currentRevision: OfferingsRevision,
) : RuntimeException()
