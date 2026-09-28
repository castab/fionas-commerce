package io.github.castab.fionas.commerce.http

import io.github.castab.commerce.financial.Money
import io.github.castab.commerce.offering.OfferingCategoryKey
import io.github.castab.commerce.offering.OfferingCategorySelection
import io.github.castab.commerce.offering.OfferingKey
import io.github.castab.commerce.offering.OfferingSelections
import io.github.castab.commerce.offering.OfferingsRevision
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.operation.validating
import io.github.castab.fionas.commerce.offering.EstimatePreview
import io.github.castab.fionas.commerce.offering.FionasOfferingsContext
import io.github.castab.fionas.commerce.offering.FionasPricingInputs
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.Tag
import org.http4k.contract.bindContract
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import java.time.Duration

/** The body of `POST /estimate-preview`. */
@Serializable
data class EstimatePreviewRequest(
    @ApiProperty(
        description =
            "The revision of Fiona's Offerings catalog the choices were made from, as `GET /offering-catalog` " +
                "returned it. The estimate uses exactly this revision, never a later one. At least 1.",
    )
    val catalogRevision: Int,
    @ApiProperty(description = "The guests to price the event for. At least 1.")
    val guestCount: Int,
    @ApiProperty(
        description =
            "Whether `guestCount` is a lower bound (\"100+ guests\"). Pricing still uses `guestCount`; the estimate " +
                "is then a starting price. False when absent.",
    )
    val guestCountIsMinimum: Boolean = false,
    @ApiProperty(description = "The service duration in minutes: one of 90, 120, 150, or 180.")
    val durationMinutes: Int,
    @ApiProperty(
        description =
            "The chosen offerings, one entry per catalog category, in the order they are shown. Every category with " +
                "a minimum number of selections must be present.",
    )
    val selections: List<EstimatePreviewSelection>,
)

/** The offerings chosen from one catalog category, in an estimate preview request. */
@Serializable
data class EstimatePreviewSelection(
    @ApiProperty(description = "The catalog category's key, for example `topping`.")
    val category: String,
    @ApiProperty(description = "The keys of the chosen offerings of that category, in the order chosen.")
    val offerings: List<String>,
)

/** An estimate preview: Fiona's priced lines for the selection and their totals. */
@Serializable
data class EstimatePreviewResponse(
    @ApiProperty(description = "The catalog revision the estimate was priced from, as requested.")
    val catalogRevision: Int,
    @ApiProperty(description = "Whether the guest count was a lower bound, so the total is a starting price.")
    val guestCountIsMinimum: Boolean,
    @ApiProperty(
        description =
            "The priced lines in a stable order: base service, ice cream service, the catalog prices of chosen " +
                "offerings in the order chosen, then extra toppings when there are any.",
    )
    val lines: List<EstimatePreviewLine>,
    @ApiProperty(description = "The sum of the lines' subtotals, an exact decimal.")
    val subtotal: String,
    @ApiProperty(description = "The sum of the lines' tax, an exact decimal. No tax is charged yet, so it is zero.")
    val taxAmount: String,
    @ApiProperty(description = "`subtotal` plus `taxAmount`, an exact decimal.")
    val total: String,
    @ApiProperty(description = "The ISO 4217 code of every amount, for example `USD`.")
    val currency: String,
)

/** One priced line of an estimate preview. */
@Serializable
data class EstimatePreviewLine(
    @ApiProperty(description = "What the line charges for, for example `Ice cream service`.")
    val description: String,
    @ApiProperty(description = "Secondary text, for example the service duration; absent when there is none.")
    val subDescription: String? = null,
    @ApiProperty(
        description =
            "How many units `unitPrice` is charged for, an exact decimal (for example guests); absent for a flat " +
                "charge, whose `unitPrice` is the whole amount.",
    )
    val quantity: String? = null,
    @ApiProperty(description = "The price of one unit, or of the flat charge, an exact decimal.")
    val unitPrice: String,
    @ApiProperty(description = "`unitPrice` × `quantity` (or `unitPrice` for a flat charge), an exact decimal.")
    val subtotal: String,
    @ApiProperty(description = "The line's tax, an exact decimal; zero for now.")
    val taxAmount: String,
    @ApiProperty(description = "`subtotal` plus `taxAmount`, an exact decimal.")
    val total: String,
    @ApiProperty(description = "The ISO 4217 code of the line's amounts.")
    val currency: String,
)

private val estimatePreviewRequest = jsonBody(EstimatePreviewRequest.serializer())
private val estimatePreviewResponse = jsonBody(EstimatePreviewResponse.serializer())

private val estimates =
    Tag(
        "Estimates",
        "Fiona's pricing of a selection from an exact catalog revision. A preview is not a quote and is never recorded.",
    )

private val exampleRequest =
    EstimatePreviewRequest(
        catalogRevision = 12,
        guestCount = 75,
        durationMinutes = 120,
        selections =
            listOf(
                EstimatePreviewSelection("soft-serve-flavor", listOf("vanilla", "horchata")),
                EstimatePreviewSelection(
                    "topping",
                    listOf("sprinkles", "oreos", "strawberries", "brownies", "gummy-bears", "cookie-dough"),
                ),
                EstimatePreviewSelection("cone-option", listOf("waffle-cone")),
            ),
    )

private val exampleResponse =
    EstimatePreviewResponse(
        catalogRevision = 12,
        guestCountIsMinimum = false,
        lines =
            listOf(
                exampleLine("Base service", "2 hours · setup, staff & local travel", null, "250.00", "250.00"),
                exampleLine("Ice cream service", "75 guests", "75", "4.00", "300.00"),
                exampleLine("Horchata", "Premium soft serve", "75", "0.50", "37.50"),
                exampleLine("Waffle cones", null, "75", "0.75", "56.25"),
                exampleLine("Extra toppings (2)", "4 toppings included; each extra is charged per guest", "150", "0.25", "37.50"),
            ),
        subtotal = "681.25",
        taxAmount = "0.00",
        total = "681.25",
        currency = "USD",
    )

private fun exampleLine(
    description: String,
    subDescription: String?,
    quantity: String?,
    unitPrice: String,
    subtotal: String,
) = EstimatePreviewLine(description, subDescription, quantity, unitPrice, subtotal, "0.00", subtotal, "USD")

/**
 * `POST /estimate-preview`: prices a selection from an exact catalog revision, recording
 * nothing. The route only translates between transport and application values;
 * [previewEstimate] loads the revision and evaluates it with Fiona's pricing.
 */
fun previewEstimateRoute(previewEstimate: (FionasPricingInputs) -> EstimatePreview): ContractRoute =
    "/estimate-preview" meta {
        operationId = "previewEstimate"
        summary = "Preview an estimate"
        description =
            "Prices a selection from one exact revision of Fiona's Offerings catalog for an event's guest count and " +
            "service duration: the base service, the ice cream service, catalog prices of the chosen offerings, and " +
            "extra toppings. Nothing is recorded, and the result is not a quote. The catalog revision is never " +
            "replaced by a later one, so a selection made from an old revision is priced, or rejected, as that " +
            "revision stands."
        tags += estimates
        receiving(estimatePreviewRequest to exampleRequest)
        returning(Status.OK, estimatePreviewResponse to exampleResponse, "The estimate's lines and totals.")
        returningError(
            ErrorCategory.MALFORMED_REQUEST,
            "the body is not JSON, lacks a required property, or a property has the wrong type.",
            "Malformed request: body 'body'",
        )
        returningError(ErrorCategory.NOT_FOUND, "the catalog revision does not exist.", "Offerings catalog revision r12 was not found")
        returningError(
            ErrorCategory.VALIDATION_FAILED,
            "a value is invalid, or the selection cannot be estimated: it does not fit the catalog revision (for " +
                "example `TOO_MANY_SELECTIONS`, `UNKNOWN_OFFERING`) or Fiona's pricing (for example " +
                "`INVALID_GUEST_COUNT`, `UNSUPPORTED_DURATION`). The message names each violation's stable code.",
            "The selection cannot be estimated: TOO_MANY_SELECTIONS (category topping allows at most 6 selections, got 7)",
        )
        returningError(ErrorCategory.INTERNAL_FAILURE, "an unexpected failure; its cause is never described.", INTERNAL_FAILURE)
    } bindContract Method.POST to { request: Request ->
        val body = estimatePreviewRequest(request)
        val inputs =
            pricingInputs(
                body.catalogRevision,
                body.guestCount,
                body.guestCountIsMinimum,
                body.durationMinutes,
                body.selections.map { it.category to it.offerings },
            )
        Response(Status.OK).with(estimatePreviewResponse of previewEstimate(inputs).toResponse())
    }

private fun EstimatePreview.toResponse() =
    EstimatePreviewResponse(
        catalogRevision = catalogRevision.number,
        guestCountIsMinimum = guestCountIsMinimum,
        lines =
            evaluation.lineItems.map { line ->
                EstimatePreviewLine(
                    description = line.description,
                    subDescription = line.subDescription,
                    quantity = line.quantity?.stripTrailingZeros()?.toPlainString(),
                    unitPrice = line.price.decimal(),
                    subtotal = line.subtotal.decimal(),
                    taxAmount = line.taxAmount.decimal(),
                    total = line.total.decimal(),
                    currency = line.currency.currencyCode,
                )
            },
        subtotal = subtotal.decimal(),
        taxAmount = taxAmount.decimal(),
        total = total.decimal(),
        currency = currency.currencyCode,
    )

/**
 * Fiona's pricing inputs from their transport form, the same for every request that prices:
 * an estimate preview, a persisted estimate, and a change order. Values the domain rejects
 * (a revision below 1, a key with whitespace) fail validation.
 */
internal fun pricingInputs(
    catalogRevision: Int,
    guestCount: Int,
    guestCountIsMinimum: Boolean,
    durationMinutes: Int,
    selections: List<Pair<String, List<String>>>,
): FionasPricingInputs =
    validating {
        FionasPricingInputs(
            catalogRevision = OfferingsRevision.of(catalogRevision),
            selections =
                OfferingSelections(
                    selections.map { (category, offerings) ->
                        OfferingCategorySelection(OfferingCategoryKey(category), offerings.map(::OfferingKey))
                    },
                ),
            context =
                FionasOfferingsContext(
                    guestCount = guestCount,
                    guestCountIsMinimum = guestCountIsMinimum,
                    duration = Duration.ofMinutes(durationMinutes.toLong()),
                ),
        )
    }

/**
 * The exact amount as a decimal string, with at least the currency's minor digits
 * (`250.00`, `0.00`) and more only when the amount has them (`9.375`). Never rounded.
 */
internal fun Money.decimal(): String {
    val exact = amount.stripTrailingZeros()
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)
    return (if (exact.scale() < digits) exact.setScale(digits) else exact).toPlainString()
}
