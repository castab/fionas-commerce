package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.offering.QuantityDimension

/** Fiona's customer questions resolved from one immutable catalog, without storing another catalog. */
data class InquiryForm(
    val catalog: OfferingsSnapshot,
    val sections: List<InquiryFormSection>,
    val pricingPreview: InquiryPricingPreview,
)

/** Advisory arithmetic facts only. The existing pricing engine still calculates authoritative amounts. */
data class InquiryPricingPreview(
    val durationOptions: List<InquiryDurationPricing>,
    val perGuestAmount: Money,
    val guestQuantityDimension: QuantityDimension,
    val toppingAdjustment: InquiryToppingAdjustment,
)

data class InquiryDurationPricing(
    val durationMinutes: Int,
    val baseServiceAmount: Money,
    /** Flat contributions of PER_DURATION offerings at this duration; fixed/per-guest prices stay on the offering. */
    val offeringContributions: List<InquiryDurationOfferingContribution>,
)

data class InquiryDurationOfferingContribution(
    val offering: OfferingKey,
    val amount: Money,
)

data class InquiryToppingAdjustment(
    val category: OfferingCategoryKey,
    val includedSelections: Int,
    val additionalSelectionPerGuestAmount: Money,
)

data class InquiryFormSection(
    val key: String,
    val title: String,
    val description: String?,
    val optional: Boolean,
    val fields: List<InquiryFormField>,
)

data class InquiryFormField(
    val key: String,
    val label: String,
    val description: String?,
    /** JSON Pointer into the existing inquiry request; choices append a category selection at this pointer. */
    val submissionPointer: String,
    /** Required when this field's section is used. */
    val required: Boolean,
    val input: InquiryFormInput,
    val control: InquiryFormControl,
)

/** Answer semantics, independent of any preferred visual control. */
sealed interface InquiryFormInput {
    data object Date : InquiryFormInput

    data class StringChoice(
        val options: List<InquiryStringOption>,
    ) : InquiryFormInput

    data class Text(
        val minLength: Int,
        val maxLength: Int,
        val pattern: String? = null,
    ) : InquiryFormInput

    data class Email(
        val maxLength: Int,
    ) : InquiryFormInput

    data class Integer(
        val minimum: Int,
    ) : InquiryFormInput

    data class BooleanValue(
        val defaultValue: Boolean,
    ) : InquiryFormInput

    data class IntegerChoice(
        val options: List<InquiryIntegerOption>,
    ) : InquiryFormInput

    /** The upstream category owns selection limits; the form owns only the question. */
    data class OfferingChoice(
        val category: OfferingCategory,
    ) : InquiryFormInput
}

data class InquiryIntegerOption(
    val value: Int,
    val label: String,
    /** A short label shown with the option at all times; absent or nonblank. */
    val badge: String? = null,
    /** Current situation only; never overrides availability. Absent or nonblank. */
    val statusNote: String? = null,
    /** A lasting fact shown on demand; absent or nonblank. */
    val infoNote: String? = null,
) {
    init {
        require(badge == null || badge.isNotBlank()) { "Option badge must be absent or nonblank" }
        require(statusNote == null || statusNote.isNotBlank()) { "Option status note must be absent or nonblank" }
        require(infoNote == null || infoNote.isNotBlank()) { "Option info note must be absent or nonblank" }
    }
}

data class InquiryStringOption(
    val value: String,
    val label: String,
    /** A short label shown with the option at all times; absent or nonblank. */
    val badge: String? = null,
    /** Current situation only; never overrides availability. Absent or nonblank. */
    val statusNote: String? = null,
    /** A lasting fact shown on demand; absent or nonblank. */
    val infoNote: String? = null,
) {
    init {
        require(badge == null || badge.isNotBlank()) { "Option badge must be absent or nonblank" }
        require(statusNote == null || statusNote.isNotBlank()) { "Option status note must be absent or nonblank" }
        require(infoNote == null || infoNote.isNotBlank()) { "Option info note must be absent or nonblank" }
    }
}

/** Hints only: clients choose their own components, accessibility, layout, and styling. */
enum class InquiryFormControl { TEXT, TEXTAREA, NUMBER, CHECKBOX, SELECT, CARDS, CHECKBOXES, DATE, CHIPS }
