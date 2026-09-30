package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingsSnapshot

/** Fiona's customer questions resolved from one immutable catalog, without storing another catalog. */
data class InquiryForm(
    val catalog: OfferingsSnapshot,
    val sections: List<InquiryFormSection>,
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
    data class Text(
        val minLength: Int,
        val maxLength: Int,
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
)

/** Hints only: clients choose their own components, accessibility, layout, and styling. */
enum class InquiryFormControl { TEXT, TEXTAREA, NUMBER, CHECKBOX, SELECT, CARDS, CHECKBOXES }
