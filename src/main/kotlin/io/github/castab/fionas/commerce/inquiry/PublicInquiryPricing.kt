package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingsEvaluation
import io.github.castab.commerce.runtime.operation.CommerceFailure
import io.github.castab.commerce.runtime.operation.ValidationViolation
import io.github.castab.commerce.runtime.persistence.Transaction
import io.github.castab.fionas.commerce.offering.FionasPricing
import io.github.castab.fionas.commerce.offering.FionasPricingInputs

/** Public inquiry constraints only; staff financial operations continue using [FionasPricing] directly. */
class PublicInquiryPricing(
    private val pricing: FionasPricing,
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
        // Price the full current snapshot: the runtime enforces eligibility and availability.
        return pricing.price(
            transaction,
            inputs,
            "The inquiry form changed; fetch the current form and review the selections and pricing before resubmitting",
        )
    }
}
