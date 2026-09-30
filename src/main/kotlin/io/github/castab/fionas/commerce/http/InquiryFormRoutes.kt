@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.offering.Offering
import io.github.castab.commerce.offering.OfferingCategory
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingsSnapshot
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.offering.OfferingDto
import io.github.castab.commerce.runtime.offering.dto
import io.github.castab.fionas.commerce.inquiry.InquiryForm
import io.github.castab.fionas.commerce.inquiry.InquiryFormControl
import io.github.castab.fionas.commerce.inquiry.InquiryFormInput
import io.github.castab.fionas.commerce.inquiry.inquiryForm
import io.github.castab.fionas.commerce.offering.FIONA_OFFERINGS_CATALOG_ID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with

@Serializable
data class InquiryFormResponse(
    @ApiProperty(description = "Version of Fiona's code-owned question definition, independent of catalog revisions. Currently 1.")
    val definitionVersion: Int,
    @ApiProperty(description = "Fiona's stable catalog identity.", format = "uuid")
    val catalogId: String,
    @ApiProperty(
        description = "The single current catalog revision resolved for this response. Submit it as pricingInputs.catalogRevision.",
    )
    val catalogRevision: Int,
    @ApiProperty(description = "Sections in display order. The service section is optional, as pricingInputs is on POST /inquiries.")
    val sections: List<InquiryFormSectionResponse>,
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
    @SerialName("TEXT")
    data class Text(
        @ApiProperty(description = "Minimum length after trimming. Required names must not be blank; optional blank notes count as absent.")
        val minLength: Int,
        @ApiProperty(description = "Maximum length after trimming.")
        val maxLength: Int,
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
                "Active offerings in catalog order, using commerce-runtime's existing offering and price representation. " +
                    "Submit selected keys as offerings. Prices are descriptive; estimate-preview calculates the service total.",
        )
        val options: List<OfferingDto>,
    ) : InquiryFormInputResponse
}

@Serializable
data class InquiryFormIntegerOption(
    val value: Int,
    val label: String,
)

private val inquiryFormBody = jsonBody(InquiryFormResponse.serializer())

// Rendering-only example; it initializes no catalog and creates no production offerings.
private val exampleInquiryForm =
    inquiryForm(
        OfferingsSnapshot.create(
            FIONA_OFFERINGS_CATALOG_ID,
            listOf(OfferingCategory(OfferingCategoryKey("soft-serve-flavor"), "Soft serve", minimumSelections = 1, maximumSelections = 2)),
            listOf(Offering(OfferingKey("vanilla"), OfferingCategoryKey("soft-serve-flavor"), "Vanilla")),
        ),
    ).toResponse()

fun getInquiryFormRoute(getInquiryForm: () -> InquiryForm): ContractRoute =
    "/inquiry-form" meta {
        operationId = "getInquiryForm"
        summary = "Read the customer inquiry form"
        description = "Public ordered questions for POST /inquiries. Input semantics and presentation hints are separate. " +
            "Service configuration is optional; when used, required fields and category limits apply. Copy catalogRevision " +
            "to pricingInputs.catalogRevision for both estimate-preview and inquiry submission. Later catalog changes " +
            "do not reprice those submitted choices. All active categories are represented; retired categories and offerings " +
            "are absent. Name, email, and pricing are validated by the existing submission operation, independently of this metadata."
        tags += inquiries
        returning(
            Status.OK,
            inquiryFormBody to exampleInquiryForm,
            "The question definition with choices from one current catalog revision.",
        )
        returningError(ErrorCategory.NOT_FOUND, "Fiona's catalog has not been initialized.", "Offerings catalog was not found")
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
    } bindContract Method.GET to { _: Request ->
        Response(Status.OK).with(inquiryFormBody of getInquiryForm().toResponse())
    }

private fun InquiryForm.toResponse(): InquiryFormResponse {
    // Reuse the runtime's conversion, including every price form, without re-modeling its DTOs.
    val categories = catalog.dto().categories.associateBy { it.key }
    return InquiryFormResponse(
        definitionVersion = 1,
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
                                is InquiryFormInput.Text -> InquiryFormInputResponse.Text(value.minLength, value.maxLength)
                                is InquiryFormInput.Email -> InquiryFormInputResponse.Email(value.maxLength)
                                is InquiryFormInput.Integer -> InquiryFormInputResponse.Integer(value.minimum)
                                is InquiryFormInput.BooleanValue -> InquiryFormInputResponse.BooleanValue(value.defaultValue)
                                is InquiryFormInput.IntegerChoice ->
                                    InquiryFormInputResponse.IntegerChoice(
                                        value.options.map {
                                            InquiryFormIntegerOption(it.value, it.label)
                                        },
                                    )
                                is InquiryFormInput.OfferingChoice -> {
                                    val category = categories.getValue(value.category.key.value)
                                    InquiryFormInputResponse.OfferingChoice(
                                        category.key,
                                        category.minimumSelections,
                                        category.maximumSelections,
                                        category.offerings,
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
    )
}
