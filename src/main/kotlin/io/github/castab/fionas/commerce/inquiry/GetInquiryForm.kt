package io.github.castab.fionas.commerce.inquiry

import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingsCatalogId
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.fionas.commerce.customer.CustomerName
import io.github.castab.fionas.commerce.customer.Email
import io.github.castab.fionas.commerce.offering.FIONAS_PRICING_POLICY
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import io.github.castab.fionas.commerce.offering.FionasPricingPolicy

/** Reads the current catalog once through the runtime operation, then adapts it into Fiona's form. */
class GetInquiryForm(
    private val getCatalog: (OfferingsCatalogId) -> OfferingsSnapshot,
    private val policy: FionasPricingPolicy = FIONAS_PRICING_POLICY,
) {
    operator fun invoke(): InquiryForm = inquiryForm(getCatalog(FIONA_OFFERINGS_CATALOG_ID), policy)
}

/** Code-owned questions, with catalog-owned identities, option text, prices, order, and cardinality. */
internal fun inquiryForm(
    snapshot: OfferingsSnapshot,
    policy: FionasPricingPolicy = FIONAS_PRICING_POLICY,
): InquiryForm {
    val questions =
        listOf(
            OfferingQuestion(OfferingCategoryKey("soft-serve-flavor"), "Choose your soft serve flavors", InquiryFormControl.CARDS),
            OfferingQuestion(policy.toppingCategory, "Choose your toppings", InquiryFormControl.CHECKBOXES),
            OfferingQuestion(OfferingCategoryKey("cone-option"), "Choose your cones or cups", InquiryFormControl.CARDS),
        )
    val publicCategories = questions.mapNotNull { snapshot.category(it.category) }
    val offeringFields = questions.mapNotNull { question -> snapshot.category(question.category)?.let { question.field(it) } }
    val pricingPreview = inquiryPricingPreview(snapshot, publicCategories, policy)
    return InquiryForm(
        snapshot,
        listOf(
            InquiryFormSection(
                "contact",
                "Contact information",
                null,
                false,
                listOf(
                    InquiryFormField(
                        "name",
                        "Your name",
                        "Tell us who we should address your inquiry to.",
                        "/name",
                        true,
                        InquiryFormInput.Text(1, CustomerName.MAX_LENGTH),
                        InquiryFormControl.TEXT,
                    ),
                    InquiryFormField(
                        "email",
                        "Email address",
                        "We'll use this address to follow up on your inquiry.",
                        "/email",
                        true,
                        InquiryFormInput.Email(Email.MAX_LENGTH),
                        InquiryFormControl.TEXT,
                    ),
                ),
            ),
            InquiryFormSection(
                "service",
                "Build your ice cream service",
                "Choose your guest count, service duration, and ice cream options, or send us a message to discuss your event.",
                true,
                listOf(
                    InquiryFormField(
                        "guestCount",
                        "How many guests?",
                        null,
                        "/pricingInputs/guestCount",
                        true,
                        InquiryFormInput.Integer(1),
                        InquiryFormControl.NUMBER,
                    ),
                    InquiryFormField(
                        "guestCountIsMinimum",
                        "This is a minimum guest count",
                        "For example, 100 or more guests.",
                        "/pricingInputs/guestCountIsMinimum",
                        false,
                        InquiryFormInput.BooleanValue(false),
                        InquiryFormControl.CHECKBOX,
                    ),
                    InquiryFormField(
                        "durationMinutes",
                        "How long would you like service?",
                        "One service duration, in minutes.",
                        "/pricingInputs/durationMinutes",
                        true,
                        InquiryFormInput.IntegerChoice(
                            policy.allowedDurations.sorted().map {
                                val minutes = Math.toIntExact(it.toMinutes())
                                InquiryIntegerOption(minutes, "$minutes minutes")
                            },
                        ),
                        InquiryFormControl.SELECT,
                    ),
                ) + offeringFields,
            ),
            InquiryFormSection(
                "additional",
                "Additional information",
                null,
                true,
                listOf(
                    InquiryFormField(
                        "message",
                        "Tell us about your event",
                        "Share your event date, location, occasion, or anything else we should know.",
                        "/message",
                        false,
                        InquiryFormInput.Text(0, InquiryMessage.MAX_LENGTH),
                        InquiryFormControl.TEXTAREA,
                    ),
                ),
            ),
        ),
        pricingPreview,
    )
}

private data class OfferingQuestion(
    val category: OfferingCategoryKey,
    val label: String,
    val control: InquiryFormControl,
) {
    fun field(value: OfferingCategory) =
        InquiryFormField(
            "offering:${category.value}",
            label,
            value.description,
            "/pricingInputs/selections",
            value.minimumSelections > 0,
            InquiryFormInput.OfferingChoice(value),
            control,
        )
}
