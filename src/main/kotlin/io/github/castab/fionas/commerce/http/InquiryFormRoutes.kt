@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingAvailability
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelectionState
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.runtime.http.AccessControl
import io.github.castab.commerce.runtime.http.CommerceErrorHandling
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.offering.OfferingDto
import io.github.castab.commerce.runtime.offering.dto
import io.github.castab.fionas.commerce.inquiry.InquiryForm
import io.github.castab.fionas.commerce.inquiry.InquiryFormControl
import io.github.castab.fionas.commerce.inquiry.InquiryFormInput
import io.github.castab.fionas.commerce.inquiry.inquiryForm
import io.github.castab.fionas.commerce.inquiry.publicInquiryOfferings
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import io.github.castab.fionas.commerce.staff.FionaPermissions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.core.with

@Serializable
data class InquiryFormResponse(
    @ApiProperty(description = "Version of Fiona's code-owned question definition, independent of catalog revisions.")
    val definitionVersion: Int,
    @ApiProperty(description = "Fiona's stable catalog identity.", format = "uuid")
    val catalogId: String,
    @ApiProperty(
        description = "The single current catalog revision resolved for this response. Submit it as pricingInputs.catalogRevision.",
    )
    val catalogRevision: Int,
    @ApiProperty(description = "Sections in display order. The service section is required, as pricingInputs is on POST /inquiries.")
    val sections: List<InquiryFormSectionResponse>,
    @ApiProperty(
        description =
            "Advisory browser estimate facts for this response's catalogRevision. Never submit calculated amounts; " +
                "estimate-preview and inquiry submission independently price facts/selections with Fiona's authoritative engine.",
    )
    val pricingPreview: InquiryPricingPreviewResponse,
)

@Serializable
data class InquiryPricingPreviewResponse(
    @ApiProperty(description = "Currency of all preview amounts and every priced public option.")
    val currency: String,
    @ApiProperty(description = "PER_QUANTITY options are guaranteed to use this dimension, whose quantity is guestCount.")
    val guestQuantityDimension: String,
    @ApiProperty(description = "Resolved contributions for every allowed service duration, in duration order.")
    val durationOptions: List<InquiryDurationPricingResponse>,
    @ApiProperty(description = "Exact decimal ice cream service amount per guest, from Fiona's pricing policy.")
    val perGuestAmount: String,
    val toppingAdjustment: InquiryToppingAdjustmentResponse,
)

@Serializable
data class InquiryDurationPricingResponse(
    val durationMinutes: Int,
    @ApiProperty(description = "Exact decimal base-service contribution at this duration; add once.")
    val baseServiceAmount: String,
    @ApiProperty(
        description =
            "Resolved flat contributions of all public PER_DURATION options at this duration. " +
                "Add only those whose offeringKey is selected. FIXED options add price.amount once; " +
                "PER_QUANTITY adds price.amount * guestCount. Unpriced options add no independent contribution. " +
                "Fractional intervals are resolved by the server, so clients need not parse intervals.",
    )
    val offeringContributions: List<InquiryDurationOfferingContributionResponse>,
)

@Serializable
data class InquiryDurationOfferingContributionResponse(
    @ApiProperty(description = "Key of a PER_DURATION offering in the response's public options.")
    val offeringKey: String,
    @ApiProperty(description = "Exact decimal flat contribution for selecting this offering at the enclosing duration; add once.")
    val amount: String,
)

@Serializable
data class InquiryToppingAdjustmentResponse(
    val category: String,
    val includedSelections: Int,
    @ApiProperty(
        description =
            "Exact decimal amount per extra topping per guest. Add max(0, selectedCount - includedSelections) * guestCount * " +
                "this amount, in addition to any catalog price on the selected toppings.",
    )
    val additionalSelectionPerGuestAmount: String,
)

@Serializable
data class InquiryFormSectionResponse(
    val key: String,
    val title: String,
    val description: String? = null,
    @ApiProperty(description = "Whether the entire section may be omitted. Field requirements apply when this section is used.")
    val optional: Boolean,
    @ApiProperty(description = "Questions in display order.")
    val fields: List<InquiryFormFieldResponse>,
)

@Serializable
data class InquiryFormFieldResponse(
    @ApiProperty(description = "Stable question identity, independent of its label; offering fields use offering:<category key>.")
    val key: String,
    val label: String,
    val description: String? = null,
    @ApiProperty(
        description =
            "JSON Pointer into CreateInquiryRequest. OFFERING_CHOICE contributes {category, offerings} at selections; " +
                "all other inputs set the pointed-to property. Never submit options, prices, or presentation metadata.",
    )
    val submissionPointer: String,
    @ApiProperty(description = "An answer is required when the section is used. Offering requirements derive from minimumSelections > 0.")
    val required: Boolean,
    val input: InquiryFormInputResponse,
    val presentation: InquiryFormPresentation,
)

@Serializable
data class InquiryFormPresentation(
    @ApiProperty(description = "Preferred control only. Clients may choose any accessible rendering that respects the input semantics.")
    val control: InquiryFormControl,
)

/** The type discriminator is written by kotlinx.serialization, never maintained as an independent string field. */
@Serializable
@JsonClassDiscriminator("type")
sealed interface InquiryFormInputResponse {
    @Serializable
    @SerialName("DATE")
    data class Date(
        @ApiProperty(description = "Calendar date submitted as YYYY-MM-DD, without a time or time zone.")
        val format: String,
    ) : InquiryFormInputResponse

    @Serializable
    @SerialName("STRING_CHOICE")
    data class StringChoice(
        @ApiProperty(description = "Choose one string value from these labeled options.")
        val options: List<InquiryFormStringOption>,
    ) : InquiryFormInputResponse

    @Serializable
    @SerialName("TEXT")
    data class Text(
        @ApiProperty(
            description = "Minimum length of trimmed nonblank text. Required names cannot be blank; optional blank text is absent.",
        )
        val minLength: Int,
        @ApiProperty(description = "Maximum length after trimming.")
        val maxLength: Int,
        @ApiProperty(description = "Optional pattern for trimmed nonblank text. ZIP codes are strings, preserving leading zeroes.")
        val pattern: String? = null,
    ) : InquiryFormInputResponse

    @Serializable
    @SerialName("EMAIL")
    data class Email(
        @ApiProperty(
            description = "Maximum length after trimming and lowercasing. The server applies its existing shallow email validation.",
        )
        val maxLength: Int,
    ) : InquiryFormInputResponse

    @Serializable
    @SerialName("INTEGER")
    data class Integer(
        @ApiProperty(description = "Minimum accepted integer. Answers must fit a signed 32-bit integer.")
        val minimum: Int,
    ) : InquiryFormInputResponse

    @Serializable
    @SerialName("BOOLEAN")
    data class BooleanValue(
        val defaultValue: Boolean,
    ) : InquiryFormInputResponse

    @Serializable
    @SerialName("INTEGER_CHOICE")
    data class IntegerChoice(
        @ApiProperty(description = "Choose exactly one integer value from these options, derived from Fiona's allowed service durations.")
        val options: List<InquiryFormIntegerOption>,
    ) : InquiryFormInputResponse

    @Serializable
    @SerialName("OFFERING_CHOICE")
    data class OfferingChoice(
        @ApiProperty(description = "Stable category key for the submitted selection's category.")
        val category: String,
        @ApiProperty(description = "Minimum selected options, projected from the resolved catalog category.")
        val minSelections: Int,
        @ApiProperty(description = "Maximum selected options, projected from the catalog category; absent means unbounded.")
        val maxSelections: Int? = null,
        @ApiProperty(
            description =
                "Enabled offerings in catalog order; disabled and retired offerings are omitted. " +
                    "UNAVAILABLE options remain visible but clients must prevent customer selection (check back later). " +
                    "Availability is distinct from disabled/retired. Uses commerce-runtime's offering and price representation. " +
                    "Submit selected keys as offerings. Prices are descriptive; estimate-preview calculates the service total.",
        )
        val options: List<OfferingDto>,
    ) : InquiryFormInputResponse
}

@Serializable
data class InquiryFormIntegerOption(
    val value: Int,
    val label: String,
    @ApiProperty(description = "Short label shown with the option at all times; absent or nonblank, under any control.")
    val badge: String? = null,
    @ApiProperty(description = "Current situation; never implies or overrides availability. Absent or nonblank, under any control.")
    val statusNote: String? = null,
    @ApiProperty(description = "Lasting fact shown on demand; absent or nonblank, under any control.")
    val infoNote: String? = null,
)

@Serializable
data class InquiryFormStringOption(
    val value: String,
    val label: String,
    @ApiProperty(description = "Short label shown with the option at all times; absent or nonblank, under any control.")
    val badge: String? = null,
    @ApiProperty(description = "Current situation; never implies or overrides availability. Absent or nonblank, under any control.")
    val statusNote: String? = null,
    @ApiProperty(description = "Lasting fact shown on demand; absent or nonblank, under any control.")
    val infoNote: String? = null,
)

private val inquiryFormBody = jsonBody(InquiryFormResponse.serializer())

// Rendering-only example; it initializes no catalog and creates no production offerings.
private val exampleInquiryForm =
    inquiryForm(
        OfferingsSnapshot.create(
            FIONA_OFFERINGS_CATALOG_ID,
            listOf(OfferingCategory(OfferingCategoryKey("soft-serve-flavor"), "Soft serve", minimumSelections = 1, maximumSelections = 2)),
            listOf(
                Offering(
                    OfferingKey("vanilla"),
                    OfferingCategoryKey("soft-serve-flavor"),
                    "Vanilla",
                    selectionState = OfferingSelectionState.ENABLED,
                    availability = OfferingAvailability.AVAILABLE,
                ),
            ),
        ),
    ).toResponse()

fun getInquiryFormRoute(
    getInquiryForm: () -> InquiryForm,
    access: AccessControl,
): ContractRoute =
    "/inquiry-form" meta {
        operationId = "getInquiryForm"
        summary = "Read the customer inquiry form"
        description = "Ordered customer-facing questions for POST /inquiries. Input semantics and presentation hints are separate. " +
            "Duration, soft serve flavors, toppings, and cones/cups use CHIPS; offering limits determine single or multiple selection. " +
            "Every option, including catalog OfferingDto options, may carry badge, statusNote, and infoNote under any control. " +
            "statusNote never overrides availability; UNAVAILABLE options remain unselectable. " +
            "Service configuration is required: every inquiry is a request for configured ice cream service, priced on " +
            "submission into an initial Estimate. Required fields and category limits apply. Copy catalogRevision " +
            "to pricingInputs.catalogRevision for both estimate-preview and inquiry submission. Later catalog changes " +
            "reject stale previews and inquiry submissions with 409 CATALOG_REVISION_STALE; fetch a fresh form and ask the customer " +
            "to review before resubmitting. Choices are never silently repriced. Only configured Fiona categories appear; " +
            "disabled offerings and retired categories/offerings are absent. Returned options have selectionState=ENABLED; " +
            "availability=UNAVAILABLE stays visible but must be rendered unselectable (check back later). " +
            "Temporary unavailability is distinct from disabled/retired and does not invalidate the form. " +
            "pricingPreview resolves policy/catalog facts for visible options for instant advisory browser arithmetic. " +
            "Contact details, event date/type, and pricing are validated on submission, independently of this metadata. " +
            "Requires `${FionaPermissions.InquiryFormRead.value}`, held by any authenticated principal: normally the server-side " +
            "web frontend's SERVICE principal (an access token from POST $SERVICE_TOKEN_PATH), or a staff user granted it. " +
            "Successful responses have Cache-Control: " +
            "private, max-age=60, must-revalidate; failures have no-store."
        tags += inquiries
        returning(
            Status.OK,
            inquiryFormBody to exampleInquiryForm,
            "The question definition with choices from one current catalog revision.",
        )
        returningError(ErrorCategory.NOT_FOUND, "Fiona's catalog has not been initialized.", "Offerings catalog was not found")
        principalAccess(FionaPermissions.InquiryFormRead)
        returningError(
            ErrorCategory.INTERNAL_FAILURE,
            "the public catalog cannot be represented/priced across every allowed duration " +
                "(unsupported currency/dimension/interval, insufficient options, or required hidden category), or an unexpected failure. " +
                "Configuration diagnostics stay in server logs; its cause is never described to customers.",
            INTERNAL_FAILURE,
        )
    } bindContract Method.GET to
        inquiryFormCaching
            .then(access.requirePermission(FionaPermissions.InquiryFormRead))
            .then(CommerceErrorHandling)
            .then { _: Request ->
                Response(Status.OK).with(inquiryFormBody of getInquiryForm().toResponse())
            }

private val inquiryFormCaching =
    Filter { next ->
        { request ->
            val response = next(request)
            response.header(
                "Cache-Control",
                if (response.status == Status.OK) "private, max-age=60, must-revalidate" else "no-store",
            )
        }
    }

private fun InquiryForm.toResponse(): InquiryFormResponse {
    // Reuse the runtime's conversion, including every price form, without re-modeling its DTOs.
    val categories = catalog.dto().categories.associateBy { it.key }
    return InquiryFormResponse(
        definitionVersion = 10,
        catalogId = catalog.catalogId.value.toString(),
        catalogRevision = catalog.revision.number,
        sections =
            sections.map { section ->
                InquiryFormSectionResponse(
                    section.key,
                    section.title,
                    section.description,
                    section.optional,
                    section.fields.map { field ->
                        val input =
                            when (val value = field.input) {
                                InquiryFormInput.Date -> InquiryFormInputResponse.Date("date")
                                is InquiryFormInput.StringChoice ->
                                    InquiryFormInputResponse.StringChoice(
                                        value.options.map {
                                            InquiryFormStringOption(
                                                it.value,
                                                it.label,
                                                it.badge,
                                                it.statusNote,
                                                it.infoNote,
                                            )
                                        },
                                    )
                                is InquiryFormInput.Text -> InquiryFormInputResponse.Text(value.minLength, value.maxLength, value.pattern)
                                is InquiryFormInput.Email -> InquiryFormInputResponse.Email(value.maxLength)
                                is InquiryFormInput.Integer -> InquiryFormInputResponse.Integer(value.minimum)
                                is InquiryFormInput.BooleanValue -> InquiryFormInputResponse.BooleanValue(value.defaultValue)
                                is InquiryFormInput.IntegerChoice ->
                                    InquiryFormInputResponse.IntegerChoice(
                                        value.options.map {
                                            InquiryFormIntegerOption(it.value, it.label, it.badge, it.statusNote, it.infoNote)
                                        },
                                    )
                                is InquiryFormInput.OfferingChoice -> {
                                    val category = categories.getValue(value.category.key.value)
                                    val visibleKeys = publicInquiryOfferings(catalog, value.category.key).map { it.key.value }.toSet()
                                    InquiryFormInputResponse.OfferingChoice(
                                        category.key,
                                        category.minimumSelections,
                                        category.maximumSelections,
                                        category.offerings.filter { it.key in visibleKeys },
                                    )
                                }
                            }
                        InquiryFormFieldResponse(
                            field.key,
                            field.label,
                            field.description,
                            field.submissionPointer,
                            field.required,
                            input,
                            InquiryFormPresentation(field.control),
                        )
                    },
                )
            },
        pricingPreview =
            InquiryPricingPreviewResponse(
                currency = pricingPreview.perGuestAmount.currency.currencyCode,
                guestQuantityDimension = pricingPreview.guestQuantityDimension.value,
                durationOptions =
                    pricingPreview.durationOptions.map { duration ->
                        InquiryDurationPricingResponse(
                            duration.durationMinutes,
                            duration.baseServiceAmount.amount.toPlainString(),
                            duration.offeringContributions.map { contribution ->
                                InquiryDurationOfferingContributionResponse(
                                    contribution.offering.value,
                                    contribution.amount.amount.toPlainString(),
                                )
                            },
                        )
                    },
                perGuestAmount = pricingPreview.perGuestAmount.amount.toPlainString(),
                toppingAdjustment =
                    InquiryToppingAdjustmentResponse(
                        pricingPreview.toppingAdjustment.category.value,
                        pricingPreview.toppingAdjustment.includedSelections,
                        pricingPreview.toppingAdjustment.additionalSelectionPerGuestAmount.amount
                            .toPlainString(),
                    ),
            ),
    )
}
