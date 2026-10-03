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

/** Hours with a fraction symbol (90 minutes is "1½ hours"); other remainders fall back to minutes. */
private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val fraction =
        when (minutes % 60) {
            0 -> ""
            15 -> "¼"
            30 -> "½"
            45 -> "¾"
            else -> return "$minutes minutes"
        }
    return when {
        hours == 0 -> "$fraction hour"
        hours == 1 && fraction.isEmpty() -> "1 hour"
        else -> "$hours$fraction hours"
    }
}

/** Code-owned questions, with catalog-owned identities, option text, prices, order, and cardinality. */
internal fun inquiryForm(
    snapshot: OfferingsSnapshot,
    policy: FionasPricingPolicy = FIONAS_PRICING_POLICY,
): InquiryForm {
    val questions = publicOfferingQuestions(policy)
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
                    InquiryFormField(
                        "zipCode",
                        "ZIP code",
                        "Your event's five-digit ZIP code helps us review the service area and any travel surcharge.",
                        "/zipCode",
                        true,
                        InquiryFormInput.Text(ZipCode.LENGTH, ZipCode.LENGTH, ZipCode.PATTERN),
                        InquiryFormControl.TEXT,
                    ),
                ),
            ),
            InquiryFormSection(
                "event",
                "Event details",
                null,
                false,
                listOf(
                    InquiryFormField(
                        "eventDate",
                        "Event date",
                        "What date is your event?",
                        "/eventDate",
                        true,
                        InquiryFormInput.Date,
                        InquiryFormControl.DATE,
                    ),
                    InquiryFormField(
                        "eventType",
                        "Event type",
                        "What kind of event are you planning?",
                        "/eventType",
                        true,
                        InquiryFormInput.StringChoice(EventType.entries.map { InquiryStringOption(it.name, it.label) }),
                        InquiryFormControl.SELECT,
                    ),
                ),
            ),
            InquiryFormSection(
                "service",
                "Build your ice cream service",
                "Choose your guest count, service duration, and ice cream options.",
                false,
                listOf(
                    InquiryFormField(
                        "guestCount",
                        "How many guests?",
                        "An estimate is totally okay - we can hash out the finer details during quoting.",
                        "/pricingInputs/guestCount",
                        true,
                        InquiryFormInput.Integer(1),
                        InquiryFormControl.NUMBER,
                    ),
                    InquiryFormField(
                        "durationMinutes",
                        "How long are we scoopin'?",
                        null,
                        "/pricingInputs/durationMinutes",
                        true,
                        InquiryFormInput.IntegerChoice(
                            policy.allowedDurations.sorted().map {
                                val minutes = Math.toIntExact(it.toMinutes())
                                InquiryIntegerOption(minutes, durationLabel(minutes))
                            },
                        ),
                        InquiryFormControl.CHIPS,
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
                        "Other notes & questions",
                        "Anything the form can't capture: off-menu requests, special accommodations, or questions for us.",
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

/** The single Fiona-owned public category definition, shared by form rendering and submission. */
internal fun publicOfferingQuestions(policy: FionasPricingPolicy = FIONAS_PRICING_POLICY) =
    listOf(
        OfferingQuestion(OfferingCategoryKey("soft-serve-flavor"), "Choose your soft serve flavors", InquiryFormControl.CHIPS),
        OfferingQuestion(policy.toppingCategory, "Choose your toppings", InquiryFormControl.CHIPS),
        OfferingQuestion(OfferingCategoryKey("cone-option"), "Choose your cones or cups", InquiryFormControl.CHIPS),
    )

internal data class OfferingQuestion(
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
